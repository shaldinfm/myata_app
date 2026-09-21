package com.example.musicplayerapp

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayerapp.data.BootIdentity
import com.example.musicplayerapp.data.PlaybackIntentStore
import com.example.musicplayerapp.data.SleepTimerStore
import com.example.musicplayerapp.data.Streams
import com.example.musicplayerapp.service.MediaPlayerService
import com.example.musicplayerapp.service.PlaybackCommand
import com.example.musicplayerapp.service.PlaybackCommandInbox
import com.example.musicplayerapp.service.SleepTimerContract
import com.example.musicplayerapp.ui.sleeptimer.SleepTimerState
import com.example.musicplayerapp.utils.ServiceUtils
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The durable handover, on a device.
 *
 * ## What is staged here, and what is not
 *
 * The P1: a caller records a command and asks for a start; the system accepts the
 * start and kills the process before the service ever runs. The start request
 * survives (START_STICKY), so the command has to as well.
 *
 * A process death cannot be staged from a test that lives in the process being
 * killed - the instrumentation dies with it - so the two halves are separated
 * instead, and each half is the real thing:
 *
 *  1. the command is planted in the app's **real** durable inbox, written the way
 *     the app's own `ServiceUtils` writes it, and then read back from disk by a
 *     service that has nothing in memory that knows anything about it;
 *  2. the start is a **bare** intent for the exported component - no action, no
 *     extras, the shape any app on the device may send, and also the shape the
 *     app's own callers send (see `PlaybackCommandExposureTest`). Nothing in the
 *     start says what to do; the record is the only instruction.
 *
 * So this file does not have to kill anything to reach the state it is about: the
 * command the service acts on is one it never saw being made, arriving through a
 * start that carries nothing.
 *
 * ## What the app's own path still does
 *
 * `PlaybackCommandBoundaryTest` holds the other end of it: that the same commands
 * sent through `ServiceUtils` are honoured, and that the old private protocol in
 * intent extras is inert. Neither file opens a stream it does not have to.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackCommandHandoffTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val inbox get() = PlaybackCommandInbox.forContext(context)
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
        // A plain startService is only accepted on API 26+ while the app is in
        // front, which is the condition every real caller meets.
        scenario = ActivityScenario.launch(MainActivity::class.java)

        // Nothing carried over from another class in the suite.
        inbox.clearForTest()
        ServiceUtils.sendSleepTimerCommand(context, SleepTimerContract.ACTION_CANCEL)
        awaitNoTimerRecord()
        SleepTimerStore.clearForTest(context)
        PlaybackIntentStore.clearForTest(context)
        awaitStoppedAndEmpty()
        inbox.clearForTest()
    }

    @After
    fun tidy() {
        inbox.clearForTest()
        ServiceUtils.sendSleepTimerCommand(context, SleepTimerContract.ACTION_CANCEL)
        runCatching { awaitStoppedAndEmpty() }
        // The placeholder a foreground test asked for is only replaced once
        // playback starts. With nothing playing, take it down here rather than
        // leave it on the next class's screen.
        runCatching {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(PLACEHOLDER_ID)
        }
        releaseController()
        SleepTimerStore.clearForTest(context)
        PlaybackIntentStore.clearForTest(context)
        inbox.clearForTest()
        scenario?.close()
        scenario = null
    }

    // ==================== playback ====================

    /**
     * A Play recorded before the start is the one that plays. The station on the
     * player is the witness: an empty player would mean the command never arrived.
     */
    @Test
    fun a_play_recorded_before_the_start_still_plays() {
        inbox.enqueue(PlaybackCommand.of("play", stream = Streams.GOLD))

        bareStart()
        awaitMediaItem(Streams.GOLD)

        assertTrue("the command was handled, so nothing may be left pending", inbox.pending().isEmpty())
    }

    /**
     * A Stop recorded before the start stops: it does not come back as a Play, and
     * the durable record it writes is what a restart would read.
     */
    @Test
    fun a_stop_recorded_before_the_start_still_stops() {
        // Something the listener was listening to, through the app's own path.
        ServiceUtils.sendUiCommand(context, "play", Streams.GOLD)
        awaitMediaItem(Streams.GOLD)

        inbox.enqueue(PlaybackCommand.of("stop"))
        bareStart()
        awaitStoppedAndEmpty()

        assertEquals(
            "a stop must end the intent durably, whatever a later start finds",
            PlaybackIntentStore.Stored.Known(Streams.GOLD, false),
            PlaybackIntentStore.read(context),
        )

        // And it stays stopped: a further start has nothing left to act on.
        bareStart()
        Thread.sleep(QUIET_MS)
        assertStoppedAndEmpty()
    }

    @Test
    fun a_stream_switch_recorded_before_the_start_still_switches() {
        ServiceUtils.sendUiCommand(context, "play", Streams.MYATA)
        awaitMediaItem(Streams.MYATA)

        inbox.enqueue(
            PlaybackCommand.of("switch", stream = Streams.XTRA, forcePlay = true, openForeground = true),
        )
        bareStart()

        awaitMediaItem(Streams.XTRA)
        assertTrue(inbox.pending().isEmpty())
    }

    // ==================== sleep timer ====================

    /**
     * The timer's deadline is the instant the listener chose, not the instant the
     * service happened to handle it: a duration re-armed after a process death
     * would stop the radio later than it was asked to.
     */
    @Test
    fun a_sleep_timer_set_recorded_before_the_start_keeps_the_deadline_it_was_given() {
        assumeNotNull(BootIdentity.read(context))
        val chosenAt = SystemClock.elapsedRealtime()

        inbox.enqueue(
            PlaybackCommand.of(SleepTimerContract.ACTION_SET, minutes = 30, nowElapsedMs = chosenAt),
        )
        bareStart()

        val armed = awaitArmedTimer()
        assertEquals(30, armed.durationMinutes)
        assertEquals(
            "the deadline must be the one the command carried, not one measured on arrival",
            chosenAt + 30 * 60_000L,
            armed.deadlineElapsedMs,
        )
    }

    @Test
    fun a_sleep_timer_cancel_recorded_before_the_start_still_cancels() {
        assumeNotNull(BootIdentity.read(context))

        ServiceUtils.sendSleepTimerCommand(context, SleepTimerContract.ACTION_SET, minutes = 30)
        awaitArmedTimer()
        inbox.clearForTest()

        inbox.enqueue(PlaybackCommand.of(SleepTimerContract.ACTION_CANCEL))
        bareStart()

        awaitNoTimerRecord()
    }

    /**
     * The acknowledgement, on a device: a handled command is removed, so the next
     * start of the service does not run it a second time. The deadline is the
     * witness - a second arming would be measured from the later moment.
     */
    @Test
    fun a_handled_command_is_not_handled_again_by_the_next_start() {
        assumeNotNull(BootIdentity.read(context))
        val chosenAt = SystemClock.elapsedRealtime()
        inbox.enqueue(PlaybackCommand.of(SleepTimerContract.ACTION_SET, minutes = 45, nowElapsedMs = chosenAt))

        bareStart()
        val first = awaitArmedTimer()
        assertEquals(chosenAt + 45 * 60_000L, first.deadlineElapsedMs)

        bareStart()
        bareStart()
        Thread.sleep(QUIET_MS)

        val after = restoredTimer()
        assertNotNull("the timer must still be armed", after)
        assertEquals(
            "a second handling would re-arm from now and move the deadline",
            first.deadlineElapsedMs,
            after!!.deadlineElapsedMs,
        )
        assertTrue(inbox.pending().isEmpty())
    }

    /**
     * Order, across the boundary: a timer set and then cancelled comes out
     * cancelled, and the control below shows that the same two commands in the
     * other order arm one - so the assertion is about the order, not about a
     * command that never ran.
     */
    @Test
    fun commands_recorded_before_the_start_keep_their_order() {
        assumeNotNull(BootIdentity.read(context))

        inbox.enqueue(PlaybackCommand.of(SleepTimerContract.ACTION_SET, minutes = 15))
        inbox.enqueue(PlaybackCommand.of(SleepTimerContract.ACTION_CANCEL))

        bareStart()
        awaitInboxEmpty()

        assertFalse(
            "the cancel came last, so nothing may be left armed",
            SleepTimerStore.hasRecordForTest(context),
        )

        // The control: reversed, the same two commands leave a timer running.
        inbox.enqueue(PlaybackCommand.of(SleepTimerContract.ACTION_CANCEL))
        inbox.enqueue(PlaybackCommand.of(SleepTimerContract.ACTION_SET, minutes = 15))

        bareStart()
        awaitInboxEmpty()

        assertEquals(15, awaitArmedTimer().durationMinutes)
    }

    // ==================== the foreground obligation ====================

    /**
     * A `startForegroundService` call leaves the service five seconds to call
     * `startForeground`, and only the caller knows it asked for one. The obligation
     * is stored with the command, and this start - a plain, bare one - still
     * answers it.
     *
     * The command is a station-less `switch`, deliberately: it touches nothing, so
     * nothing but this obligation could have promoted the service, and nothing
     * replaces the placeholder notification it posts. A notification under that id
     * can only have come from `startForeground`.
     */
    @Test
    fun the_foreground_obligation_recorded_with_the_command_survives_the_start() {
        inbox.enqueue(PlaybackCommand.of("switch", stream = null, openForeground = true))

        bareStart()

        assertTrue(
            "a command recorded as a foreground start must still be one after the start",
            awaitPlaceholderNotification(),
        )
        assertTrue(inbox.pending().isEmpty())
    }

    // ==================== what an outside start can and cannot do ================

    /**
     * The start an arbitrary app can send is the same bare intent this file uses
     * everywhere above, with the old private protocol in extras as the strongest
     * form of it - and here with a *different* station in them, so a start that
     * could rewrite a pending command would be caught saying so.
     *
     * It cannot add a command (nothing is left pending afterwards), it cannot
     * rewrite the pending one (the player ends on the station the record names, not
     * the one the extras name) and it cannot cost one (the record was handled at
     * all). `PlaybackCommandBoundaryTest` proves the extras themselves are inert.
     */
    @Test
    fun a_hostile_bare_start_cannot_add_or_rewrite_a_command() {
        // The listener's own gesture, waiting for the service.
        inbox.enqueue(PlaybackCommand.of("play", stream = Streams.GOLD))

        val hostile = Intent(context, MediaPlayerService::class.java).apply {
            putExtra("ACTION", "play")
            putExtra("STREAM", Streams.XTRA)
            putExtra("FOREGROUND_START", true)
        }
        context.startService(hostile)

        awaitMediaItem(Streams.GOLD)
        assertTrue("nothing an outside start carries may become a command", inbox.pending().isEmpty())
    }

    // ============================ plumbing ============================

    /**
     * The start request as anyone can make it: the exported component and nothing
     * else. No action, no extra, no station - the same intent `ServiceUtils` sends.
     */
    private fun bareStart() {
        context.startService(Intent(context, MediaPlayerService::class.java))
    }

    private fun restoredTimer(): SleepTimerState.Armed? =
        (SleepTimerStore.restore(context, boot, SystemClock.elapsedRealtime())
            as? SleepTimerStore.Restored.Armed)?.timer

    private fun awaitArmedTimer(timeoutMs: Long = COMMAND_TIMEOUT_MS): SleepTimerState.Armed {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            restoredTimer()?.let { return it }
            Thread.sleep(POLL_MS)
        }
        fail("no armed timer within ${timeoutMs}ms")
        error("unreachable")
    }

    private fun awaitNoTimerRecord(timeoutMs: Long = COMMAND_TIMEOUT_MS) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (!SleepTimerStore.hasRecordForTest(context)) return
            Thread.sleep(POLL_MS)
        }
        fail("a sleep-timer record was still on disk after ${timeoutMs}ms")
    }

    /**
     * Waits until the service has consumed everything recorded before the start.
     * The acknowledgement is the last thing a command's handling does, so an empty
     * inbox is proof that every handler has returned.
     */
    private fun awaitInboxEmpty(timeoutMs: Long = COMMAND_TIMEOUT_MS) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (inbox.pending().isEmpty()) return
            Thread.sleep(POLL_MS)
        }
        fail("the service did not consume the recorded commands within ${timeoutMs}ms")
    }

    private fun awaitMediaItem(stream: String, timeoutMs: Long = COMMAND_TIMEOUT_MS) {
        val controller = awaitController()
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var last = "none"
        while (SystemClock.uptimeMillis() < deadline) {
            last = onMainThread { controller.currentMediaItem?.mediaId ?: "none" }
            if (last == stream) return
            Thread.sleep(POLL_MS)
        }
        fail("the shared player never loaded '$stream' (it shows '$last')")
    }

    private fun assertStoppedAndEmpty() {
        val controller = awaitController()
        assertEquals("a stopped player keeps nothing loaded", 0, onMainThread { controller.mediaItemCount })
        assertFalse("a stopped player is not playing", onMainThread { controller.isPlaying })
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

    /**
     * True once the foreground placeholder this app posts is on screen. That
     * notification is only ever posted by `startForeground` - Media3 posts its own
     * under a different id - so it is the evidence that the obligation was met.
     */
    private fun awaitPlaceholderNotification(timeoutMs: Long = COMMAND_TIMEOUT_MS): Boolean {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (manager.activeNotifications.any { it.id == PLACEHOLDER_ID }) return true
            Thread.sleep(POLL_MS)
        }
        return false
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

        /** Long enough for a command to have shown itself, or not to have. */
        const val QUIET_MS = 2_000L

        const val POLL_MS = 50L

        /** The id `postPlaceholderForegroundNotification` promotes the service with. */
        const val PLACEHOLDER_ID = 1
    }
}
