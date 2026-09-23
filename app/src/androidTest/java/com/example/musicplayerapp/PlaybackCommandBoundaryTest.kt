package com.example.musicplayerapp

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayerapp.data.BootIdentity
import com.example.musicplayerapp.data.SleepTimerStore
import com.example.musicplayerapp.service.MediaPlayerService
import com.example.musicplayerapp.service.SleepTimerContract
import com.example.musicplayerapp.ui.sleeptimer.SleepTimerState
import com.example.musicplayerapp.utils.ServiceUtils
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The exported endpoint against the app's own protocol, on a device.
 *
 * ## What was wrong
 *
 * `MediaPlayerService` is a `MediaSessionService` and it is exported - which it has
 * to be for the session - and its `onStartCommand` used to read the app's private
 * protocol out of whatever intent started it: `ACTION`, `STREAM`, `ARTIST`, `SONG`,
 * `force_play`, `MINUTES`, `IS_CUSTOM`. Those extras are an ordinary start intent for
 * an exported component, so any app on the device could send them.
 *
 * ## What is asserted here
 *
 * Two starts with identical extras are sent - one the way another app would send it,
 * one through the app's own channel - and they must have opposite outcomes. The
 * legacy start is the exact shape the service used to obey, the private one is the
 * exact shape every screen sends now, and the pair is what makes "nothing happened"
 * a fact about the path rather than about a command that never works.
 *
 * The commands used are the sleep timer and `play`, deliberately: the timer is
 * observable through its store without a note of audio being played, and `play` on
 * an empty player would load a media item before it reached the network - so
 * "nothing happened" is visible without a live stream, which this suite does not
 * open (`PlaybackIntentServiceTest` records why).
 *
 * ## Mobile and TV
 *
 * It runs on both, like the rest of the playback suites: one service serves both, and
 * the front door is the only difference.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackCommandBoundaryTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val boot: Int get() = BootIdentity.read(context) ?: 0

    private var scenario: ActivityScenario<MainActivity>? = null
    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null

    @Before
    fun setUp() {
        if (Build.VERSION.SDK_INT >= 33) {
            ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(
                    "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS"
                ),
            ).use { it.readBytes() }
        }
        // A plain startService is only accepted on API 26+ while the app is in front,
        // which is the condition every real caller meets.
        scenario = ActivityScenario.launch(MainActivity::class.java)

        // A timer left behind by another class would make the negative test pass for
        // the wrong reason, so leave through the app's own path and wait for it.
        ServiceUtils.sendSleepTimerCommand(context, SleepTimerContract.ACTION_CANCEL)
        awaitNoRecord()
        SleepTimerStore.clearForTest(context)
    }

    @After
    fun tidy() {
        ServiceUtils.sendSleepTimerCommand(context, SleepTimerContract.ACTION_CANCEL)
        runCatching { awaitStoppedAndEmpty() }
        releaseController()
        SleepTimerStore.clearForTest(context)
        scenario?.close()
        scenario = null
    }

    // ==================== the exported path cannot instruct anything ====================

    /**
     * The protocol as an external app sends it: an explicit start intent for the
     * exported component, with the action and the duration in extras - the shape this
     * service used to act on.
     */
    @Test
    fun an_exported_start_with_the_old_extras_cannot_arm_a_timer() {
        val legacy = Intent(context, MediaPlayerService::class.java).apply {
            putExtra("ACTION", SleepTimerContract.ACTION_SET)
            putExtra("MINUTES", 30)
            putExtra("IS_CUSTOM", false)
        }
        context.startService(legacy)

        // The service really did take that start: this command goes through the private
        // channel, its answer is the next start command's work, and a start command
        // runs the commands it drained in order - so the answer is proof that the
        // legacy start before it was dealt with.
        drainService()

        assertFalse(
            "an exported start carrying the private protocol armed a sleep timer",
            SleepTimerStore.hasRecordForTest(context),
        )
    }

    /**
     * The same thing with the playback protocol: `ACTION=play` with a station was how
     * another app could make this app start the radio.
     */
    @Test
    fun an_exported_start_with_the_old_play_protocol_does_not_start_playback() {
        awaitStoppedAndEmpty()

        val legacy = Intent(context, MediaPlayerService::class.java).apply {
            putExtra("ACTION", "play")
            putExtra("STREAM", "myata")
            putExtra("FOREGROUND_START", true)
        }
        context.startService(legacy)
        drainService()

        // A Play installs its media item before it prepares anything, so an empty
        // timeline after a moment of quiet is the honest witness - the same one
        // PlaybackIntentServiceTest uses for a restart that must not make a sound.
        Thread.sleep(QUIET_MS)
        val controller = awaitController()
        assertEquals(
            "an exported start carrying the playback protocol loaded a station",
            0,
            onMainThread { controller.mediaItemCount },
        )
        assertFalse(
            "an exported start carrying the playback protocol started playing",
            onMainThread { controller.isPlaying },
        )
    }

    // ==================== the private channel still works ====================

    /**
     * The positive control for both tests above, and the answer to "did the app's own
     * commands survive the change": the same arming, through the channel every screen
     * uses.
     */
    @Test
    fun the_same_command_through_the_private_channel_is_honoured() {
        assumeNotNull(BootIdentity.read(context))

        ServiceUtils.sendSleepTimerCommand(
            context,
            SleepTimerContract.ACTION_SET,
            minutes = 30,
            isCustom = false,
        )

        val armed = awaitArmed()
        assertEquals(30, armed.durationMinutes)
        assertTrue(SleepTimerStore.hasRecordForTest(context))
    }

    // ==================== the endpoint it is exported for ====================

    /**
     * The session, from outside the app's own code: a `MediaController` built from the
     * component name, exactly what an external media controller would hold. The
     * endpoint has to keep answering it - that is what the export is for - and it has
     * to answer it without being handed the app's protocol.
     */
    @Test
    fun the_session_endpoint_still_serves_a_media_controller() {
        val controller = awaitController()
        assertTrue(
            "the exported session answered a controller with no commands at all",
            onMainThread { controller.availableCommands.size() } > 0,
        )
    }

    // ============================ plumbing ============================

    private fun restored(): SleepTimerState.Armed? =
        (SleepTimerStore.restore(context, boot, SystemClock.elapsedRealtime())
            as? SleepTimerStore.Restored.Armed)?.timer

    private fun awaitArmed(timeoutMs: Long = COMMAND_TIMEOUT_MS): SleepTimerState.Armed {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            restored()?.let { return it }
            Thread.sleep(POLL_MS)
        }
        fail("no armed timer within ${timeoutMs}ms")
        error("unreachable")
    }

    private fun awaitNoRecord(timeoutMs: Long = COMMAND_TIMEOUT_MS) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (!SleepTimerStore.hasRecordForTest(context)) return
            Thread.sleep(POLL_MS)
        }
        fail("a sleep-timer record was still on disk after ${timeoutMs}ms")
    }

    /**
     * Blocks until the service has drained past everything sent before this.
     *
     * `sleep_timer_sync` always answers with exactly one broadcast, and the commands a
     * start command drains are run in order - so the answer is proof that whatever was
     * sent before it has been handled too.
     */
    private fun drainService() {
        val latch = CountDownLatch(1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = latch.countDown()
        }
        val manager = LocalBroadcastManager.getInstance(context)
        instrumentation.runOnMainSync {
            manager.registerReceiver(receiver, IntentFilter(SleepTimerContract.BROADCAST_STATE))
        }
        try {
            ServiceUtils.sendSleepTimerCommand(context, SleepTimerContract.ACTION_SYNC)
            if (!latch.await(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                fail("the service did not answer a sleep_timer_sync within ${COMMAND_TIMEOUT_MS}ms")
            }
        } finally {
            instrumentation.runOnMainSync { manager.unregisterReceiver(receiver) }
        }
    }

    private fun awaitStoppedAndEmpty() {
        ServiceUtils.sendUiCommand(context, "stop")
        val controller = awaitController()
        val deadline = SystemClock.uptimeMillis() + COMMAND_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            if (onMainThread { controller.mediaItemCount } == 0 && !onMainThread { controller.isPlaying }) return
            Thread.sleep(POLL_MS)
        }
        fail("the shared service never reached a stopped, empty player")
    }

    private fun awaitController(): MediaController {
        controllerFuture?.let { existing ->
            val ready = runCatching { existing.get(0, TimeUnit.MILLISECONDS) }.getOrNull()
            if (ready != null && onMainThread { ready.isConnected }) return ready
        }
        // Media3 builds a controller on the application thread and answers on it.
        val token = SessionToken(context, ComponentName(context, MediaPlayerService::class.java))
        val future = onMainThread { MediaController.Builder(context, token).buildAsync() }
        controllerFuture = future
        return runCatching { future.get(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS) }.getOrElse {
            fail(
                "timed out on API ${Build.VERSION.SDK_INT} connecting a MediaController " +
                    "to MediaPlayerService: ${it.javaClass.simpleName}"
            )
            error("unreachable")
        }
    }

    private fun releaseController() {
        val future = controllerFuture ?: return
        controllerFuture = null
        instrumentation.runOnMainSync { MediaController.releaseFuture(future) }
    }

    private fun <T> onMainThread(block: () -> T): T {
        var out: T? = null
        instrumentation.runOnMainSync { out = block() }
        @Suppress("UNCHECKED_CAST")
        return out as T
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 20_000L
        const val COMMAND_TIMEOUT_MS = 10_000L

        /** Long enough for a Play to have shown itself as a loaded media item. */
        const val QUIET_MS = 2_000L

        const val POLL_MS = 50L
    }
}
