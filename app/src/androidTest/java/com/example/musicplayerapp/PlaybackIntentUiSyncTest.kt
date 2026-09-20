package com.example.musicplayerapp

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.example.musicplayerapp.data.PlaybackIntentStore
import com.example.musicplayerapp.data.Streams
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app and the speaker agree on which station is on.
 *
 * Before this slice the ViewModel opened on `myata` unconditionally, and nothing
 * anywhere taught it otherwise. That was invisible while a process death left the
 * radio silent; the moment a restart resumes GOLD by itself, the app draws MYATA
 * over GOLD audio, which is a worse thing to ship than the bug being fixed.
 *
 * ## What is asserted here, and where the rest lives
 *
 * The hierarchy itself - session, then this UI's own state, then the durable
 * record, then the default - is pinned exhaustively and without a device in
 * `PlaybackIntentPolicyTest.uiStream`. What needs a device is the **wiring**:
 * that a real `StreamsViewModel`, built by a real Activity, actually consults the
 * record and opens on what it says.
 *
 * The top of the hierarchy, a loaded session, is deliberately not exercised here.
 * Getting a media item into the session means starting a stream, which reaches a
 * real network host and leaves a live session behind for whatever runs next - the
 * decision `ThemeRecreationPlaybackTest` records and `MiniPlayerContractTest`
 * asserts against. It is covered by the manual kill-and-reopen pass instead, and
 * by `StreamMediaItemTest`, which pins the media id the session answers with.
 *
 * ## Mobile and TV
 *
 * The ViewModel is the same class on both, but the Activity that builds it is
 * not, and `MainActivity` does not resume on a TV device - so the file picks the
 * front door the device actually uses. Running it on both is what makes "the TV
 * app opens on the restored station too" an assertion rather than an inference.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackIntentUiSyncTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    private var scenario: ActivityScenario<*>? = null

    @Before
    fun setUp() {
        if (Build.VERSION.SDK_INT >= 33) {
            ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(
                    "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS"
                ),
            ).use { it.readBytes() }
        }
        PlaybackIntentStore.clearForTest(context)
    }

    @After
    fun tidy() {
        scenario?.close()
        scenario = null
        PlaybackIntentStore.clearForTest(context)
    }

    @Test
    fun a_restored_gold_opens_the_app_on_gold() {
        assertOpensOn(recorded = Streams.GOLD, wantsPlayback = true, expected = Streams.GOLD)
    }

    @Test
    fun a_restored_xtra_opens_the_app_on_xtra() {
        assertOpensOn(recorded = Streams.XTRA, wantsPlayback = true, expected = Streams.XTRA)
    }

    @Test
    fun a_restored_myata_opens_the_app_on_myata() {
        assertOpensOn(recorded = Streams.MYATA, wantsPlayback = true, expected = Streams.MYATA)
    }

    /**
     * The paused case the review calls out: no audio comes back, and the UI still
     * says GOLD rather than claiming some other station is the active one.
     *
     * The record keeps the station across a pause precisely so this stays true -
     * the service has adopted GOLD in memory but has no media item, so the session
     * cannot answer and the record is the only thing that can.
     */
    @Test
    fun a_paused_gold_opens_the_app_on_gold_and_plays_nothing() {
        val vm = assertOpensOn(recorded = Streams.GOLD, wantsPlayback = false, expected = Streams.GOLD)
        assertFalse(
            "a paused record must not make the UI think something is playing",
            onMainThread { vm.isPlaying.value == true },
        )
    }

    /**
     * A record naming something that is not a stream cannot be acted on, and the
     * UI must not be left with it either. The default is the safe answer, and it
     * is the answer the app had before any of this existed.
     */
    @Test
    fun an_unusable_persisted_stream_opens_the_app_on_the_default() {
        PlaybackIntentStore.writeRawForTest(context, "android.intent.action.PLAY", wantsPlayback = true)
        assertEquals(Streams.MYATA, Streams.DEFAULT)
        assertEquals(Streams.DEFAULT, launchAndReadStream())
    }

    @Test
    fun a_fresh_install_opens_the_app_on_the_default() {
        assertEquals(Streams.DEFAULT, launchAndReadStream())
    }

    private fun assertOpensOn(
        recorded: String,
        wantsPlayback: Boolean,
        expected: String,
    ): StreamsViewModel {
        PlaybackIntentStore.writeRawForTest(context, recorded, wantsPlayback)
        val vm = launchAndReadViewModel()
        assertEquals(
            "the app opened on ${onMainThread { vm.currentStreamLive.value }} " +
                "with $recorded on the durable record",
            expected,
            onMainThread { vm.currentStreamLive.value },
        )
        return vm
    }

    private fun launchAndReadStream(): String? {
        // The launch must happen off the main thread; only the read goes on it.
        val vm = launchAndReadViewModel()
        return onMainThread { vm.currentStreamLive.value }
    }

    /** The app's front door on this device, and the ViewModel it built. */
    private fun launchAndReadViewModel(): StreamsViewModel =
        if (isTv()) {
            scenario = ActivityScenario.launch(TvMainActivity::class.java)
            awaitViewModel("TvMainActivity") { resumedOf<TvMainActivity>()?.vm }
        } else {
            scenario = ActivityScenario.launch(MainActivity::class.java)
            awaitViewModel("MainActivity") { resumedOf<MainActivity>()?.viewModel }
        }

    private fun awaitViewModel(what: String, read: () -> StreamsViewModel?): StreamsViewModel {
        val deadline = SystemClock.uptimeMillis() + LAUNCH_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val vm = runCatching { onMainThread(read) }.getOrNull()
            if (vm != null) return vm
            Thread.sleep(100)
        }
        fail("timed out on API ${Build.VERSION.SDK_INT} waiting for a resumed $what")
        error("unreachable")
    }

    private inline fun <reified T : Activity> resumedOf(): T? =
        ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(Stage.RESUMED)
            .filterIsInstance<T>()
            .firstOrNull()

    /** The same predicate `MediaPlayerService` uses to decide it is on a television. */
    private fun isTv(): Boolean {
        val uiMode = context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        if (uiMode.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
        return !context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }

    private fun <T> onMainThread(block: () -> T): T {
        var out: T? = null
        instrumentation.runOnMainSync { out = block() }
        @Suppress("UNCHECKED_CAST")
        return out as T
    }

    private companion object {
        const val LAUNCH_TIMEOUT_MS = 20_000L
    }
}
