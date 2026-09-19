package com.example.musicplayerapp

import android.content.Context
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayerapp.data.lastfm.LastfmApi
import com.example.musicplayerapp.data.lastfm.LastfmBackend
import com.example.musicplayerapp.data.lastfm.LastfmRequest
import com.example.musicplayerapp.data.lastfm.LastfmRequestFactory
import com.example.musicplayerapp.data.lastfm.LastfmTransportResult
import com.example.musicplayerapp.data.lastfm.SharedSecretSigner
import org.junit.Assume.assumeTrue

/**
 * The opt-in between `connectedDebugAndroidTest` and the real Last.fm - the
 * counterpart of [LiveSupabase], and as strong.
 *
 * ```
 * # normal, and incapable of reaching Last.fm
 * ./gradlew connectedDebugAndroidTest
 *
 * # deliberate live validation, with real credentials in lastfm.properties
 * ./gradlew connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.liveLastfm=true
 * ```
 *
 * `MyataTestRunner` reads the flag before `MyataApplication.onCreate` and, without
 * it, installs [OfflineLastfmApi] as the transport - then refuses to start the app if
 * that has not happened. The gate sits at the transport and never reads
 * `LastfmConfig`, so `configuredOverrideForTest = true` cannot open it.
 */
object LiveLastfm {

    /** `-Pandroid.testInstrumentationRunnerArguments.liveLastfm=true` */
    const val ARG = "liveLastfm"

    private const val HOW =
        "live Last.fm tests are opt-in. Re-run with " +
            "-Pandroid.testInstrumentationRunnerArguments.liveLastfm=true"

    /** Reads the flag out of a runner argument bundle, before any test exists. */
    fun optedIn(arguments: Bundle?): Boolean =
        arguments?.getString(ARG)?.trim().equals("true", ignoreCase = true)

    /** Reads the flag from the running instrumentation. */
    val isOptedIn: Boolean
        get() = optedIn(InstrumentationRegistry.getArguments())

    /** Skips the calling test unless this run explicitly asked for live Last.fm. */
    fun assumeOptedIn() = assumeTrue(HOW, isOptedIn)

    /**
     * Puts the offline transport back - what every test that installed a fake must
     * call in `@After`. Deliberately not `null`: `null` would hand every later test
     * in the run the real transport.
     *
     * A live run is the exception: there the real transport is the one to restore.
     */
    fun restoreOffline() {
        LastfmBackend.overrideForInstrumentation(if (isOptedIn) null else { _: Context -> OfflineLastfmApi })
        LastfmBackend.overrideRequestsForInstrumentation(null)
        // G6b P6b: a test that opened the write gate for a scripted fake must not
        // leave it open for the next one.
        LastfmBackend.overrideWritesForTest(null)
    }

    /**
     * A request factory with fabricated credentials, for exercising the signed auth
     * flow against a fake transport. Every build in existence has no credentials, so
     * without this no request could be built at all. The values are keyboard patterns;
     * the transport gate guarantees nothing they sign reaches Last.fm.
     */
    fun fakeRequests(): LastfmRequestFactory =
        LastfmRequestFactory(FAKE_API_KEY, SharedSecretSigner(FAKE_SECRET))

    const val FAKE_API_KEY = "0123456789abcdef0123456789abcdef"
    private const val FAKE_SECRET = "fedcba9876543210fedcba9876543210"
}

/**
 * A transport that is not a transport: every request is unreachable.
 *
 * The answer the auth rules treat as nobody's fault - a pending token is kept, no
 * new one is requested, a linked account stays linked - so anything the app does on
 * its own during a test finds its state exactly as it left it.
 */
object OfflineLastfmApi : LastfmApi {
    override suspend fun send(request: LastfmRequest): LastfmTransportResult =
        LastfmTransportResult.Unreachable("Offline")
}

/**
 * For a test in which **no** Last.fm request of any kind may happen (G6b P6b).
 * Reaching it fails the test outright - a write that got this far in a test is a
 * bug, and the offline transport would only have hidden it. Names the method,
 * nothing else about the request.
 */
object FailingLastfmApi : LastfmApi {
    override suspend fun send(request: LastfmRequest): LastfmTransportResult =
        throw AssertionError("no Last.fm request was expected, but ${request.method} was sent")
}
