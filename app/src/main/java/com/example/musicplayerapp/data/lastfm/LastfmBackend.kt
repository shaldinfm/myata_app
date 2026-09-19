package com.example.musicplayerapp.data.lastfm

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.example.musicplayerapp.BuildConfig
import com.example.musicplayerapp.SecureNetModule

/**
 * Where this feature gets its transport and its request factory - and the seam
 * that decides whether either can reach the real Last.fm.
 *
 * The same shape as `EmailAuthBackend` and `ReactionSyncBackend`, and for the same
 * reason. In a shipped app neither override is ever set and this returns the real
 * transport and the build's own request factory. Under instrumentation,
 * `MyataTestRunner` installs `OfflineLastfmApi` **before `MyataApplication.onCreate`**
 * unless the run opts in with `liveLastfm=true`, and refuses to start the app if it
 * has not - so an ordinary `connectedDebugAndroidTest` cannot reach Last.fm, from a
 * test or from anything the app does on its own.
 *
 * ## Two independent locks
 *
 *  1. **The override.** In instrumentation, [api] returns the offline transport,
 *     not [HttpLastfmApi]. Tests that need particular answers install their own
 *     fake and must restore the *offline* one afterwards, never `null` - `null`
 *     would hand every later test the real transport.
 *  2. **Arming.** [HttpLastfmApi] refuses to send until [armForProduction] has
 *     run, and only `MyataApplication.onCreate` calls it. JVM tests never run the
 *     Application, so a JVM test that somehow built the real transport would fail
 *     on its first send instead of reaching the network.
 *
 * Neither lock reads `LastfmConfig`, so `configuredOverrideForTest = true` - which
 * makes the Settings row appear - cannot open either of them. Having credentials is
 * not permission to send.
 *
 * ## The request factory
 *
 * [requests] is overridable separately because instrumentation needs to *build*
 * signed requests to exercise the auth flow, and every build in existence has no
 * credentials. A test factory carries fabricated values; the requests it signs go to
 * a fake transport and nowhere else, which is exactly what the two locks guarantee.
 */
object LastfmBackend {

    @Volatile
    private var apiOverride: ((Context) -> LastfmApi)? = null

    @Volatile
    private var requestsOverride: (() -> LastfmRequestFactory?)? = null

    @Volatile
    private var armed = false

    /**
     * Allows the real transport to send. Called once, first thing in
     * `MyataApplication.onCreate`, and from nowhere else.
     */
    fun armForProduction() {
        armed = true
    }

    /** Read by [HttpLastfmApi] before every send. */
    internal val isArmed: Boolean
        get() = armed

    /** The transport to use right now. Resolved per call, so a test's fake applies at once. */
    fun api(context: Context): LastfmApi =
        apiOverride?.invoke(context)
            ?: HttpLastfmApi(SecureNetModule.getOkHttpClient(context.applicationContext))

    /** The request factory to use right now, or null when this build has no credentials. */
    fun requests(): LastfmRequestFactory? {
        val override = requestsOverride
        return if (override != null) override() else LastfmRequestFactory.forThisBuild()
    }

    /**
     * Instrumentation only: replace the transport. `MyataTestRunner` installs the
     * offline one before the Application starts; a test may install a fake, and
     * must put the offline one back afterwards - never `null`.
     */
    fun overrideForInstrumentation(api: ((Context) -> LastfmApi)?) {
        apiOverride = api
    }

    /** Instrumentation only: replace the request factory. `null` restores the build's own. */
    fun overrideRequestsForInstrumentation(requests: (() -> LastfmRequestFactory?)?) {
        requestsOverride = requests
    }

    /** Whether the transport is currently replaced. `MyataTestRunner` asserts this. */
    val isOverridden: Boolean
        get() = apiOverride != null

    // ---- the write gate (G6b P6b) -------------------------------------------------

    /** The Last.fm methods that write to a listener's profile. */
    val WRITE_METHODS: Set<String> = setOf("track.scrobble", "track.updateNowPlaying")

    @Volatile
    private var writesOverride: Boolean? = null

    /**
     * Whether Last.fm writes may be sent at all: [BuildConfig.LASTFM_LIVE_WRITES] -
     * or a test's override. Release builds: always on. Debug builds: off, unless
     * built with exactly `-PlastfmLiveWrites=true` on the Gradle command line; no
     * other property source (environment, `-D`, `gradle.properties`) can turn it on.
     *
     * Checked three times over: `ScrobbleSender` and `NowPlayingSender` before
     * building a request, and [HttpLastfmApi] refuses a write method regardless.
     * Reads and authentication never consult it.
     */
    val writesEnabled: Boolean
        get() = writesOverride ?: BuildConfig.LASTFM_LIVE_WRITES

    /**
     * Tests only: let scripted fake transports exercise the whole write path while
     * the build itself stays write-disabled; `null` restores the build's value.
     * Nothing in `src/main` calls this.
     */
    @VisibleForTesting
    fun overrideWritesForTest(enabled: Boolean?) {
        writesOverride = enabled
    }

    /** Tests only: undo [armForProduction] so a JVM test cannot leave the transport armed. */
    @VisibleForTesting
    fun disarmForTest() {
        armed = false
    }
}
