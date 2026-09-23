package com.example.musicplayerapp

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
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
import org.junit.Assert.assertNull
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
            PlaybackCommand.of(
                SleepTimerContract.ACTION_SET, minutes = 30,
                nowElapsedMs = chosenAt, bootId = boot,
            ),
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
     * Item 4, on a device. A `sleep_timer_set` that belongs to another boot can carry a
     * deadline that looks perfectly live in this boot's `elapsedRealtime` epoch - which is
     * exactly the trap: arming it would stop the radio at a moment nobody chose. The boot
     * it was measured on travels with the command, and a mismatch means it is refused
     * rather than reinterpreted.
     */
    @Test
    fun a_timer_set_from_another_boot_is_refused_rather_than_reinterpreted() {
        assumeNotNull(BootIdentity.read(context))
        val previousBoot = boot - 1

        inbox.enqueue(
            PlaybackCommand.of(
                SleepTimerContract.ACTION_SET, minutes = 30,
                nowElapsedMs = SystemClock.elapsedRealtime(), bootId = previousBoot,
            ),
        )
        bareStart()
        awaitInboxEmpty()
        Thread.sleep(QUIET_MS)

        assertFalse(
            "the deadline is not this boot's, so it is not this boot's timer",
            SleepTimerStore.hasRecordForTest(context),
        )
        assertEquals(
            "and no surface may be shown a timer that was refused",
            SleepTimerState.Off,
            SleepTimerStore.peek(context, SystemClock.elapsedRealtime()),
        )
    }

    /**
     * Item 6, exactly the sequence the review asked for: the cancel runs, its effect lands
     * (the timer is disarmed and the snapshot is written), the acknowledgement is lost -
     * so the same record with the same id is read again by the next start - and the
     * replayed cancel must not be able to clear the snapshot it created.
     *
     * The lost acknowledgement is staged by putting the *same entry* back, which is
     * precisely the state it leaves behind: the handler ran, the removal never reached the
     * disk. Nothing about the command's identity is faked.
     */
    @Test
    fun a_cancel_replayed_after_a_lost_acknowledgement_leaves_undo_intact() {
        assumeNotNull(BootIdentity.read(context))

        inbox.enqueue(PlaybackCommand.of(SleepTimerContract.ACTION_SET, minutes = 30, bootId = boot))
        bareStart()
        val armed = awaitArmedTimer()

        val cancelRecord = inbox.enqueue(PlaybackCommand.of(SleepTimerContract.ACTION_CANCEL))
        assertNotNull("the cancel record must be written", cancelRecord)
        val cancel = cancelRecord!!
        bareStart()
        awaitCancelledSnapshot(armed.deadlineElapsedMs)
        val afterFirstRun = SleepTimerStore.readCancelled(context, boot)
        assertNotNull("a cancel puts `Вернуть` on offer", afterFirstRun)

        // The acknowledgement was lost: the same command, under the same id, comes back.
        inbox.plantForTest(cancel)
        bareStart()
        awaitInboxEmpty()

        assertEquals(
            "the replay may not clear the snapshot the first run wrote",
            afterFirstRun,
            SleepTimerStore.readCancelled(context, boot),
        )

        // And it still works: the original deadline is what comes back.
        ServiceUtils.sendSleepTimerCommand(context, SleepTimerContract.ACTION_UNDO)
        assertEquals(armed.deadlineElapsedMs, awaitArmedTimer().deadlineElapsedMs)
    }

    /**
     * Item 5, as far as a suite can stage it: the snapshot is on disk, so the undo works
     * on a **service instance that had nothing to do with the cancel** - the app's own
     * stop/start, which is what a recreated process looks like from the durable state's
     * point of view. A snapshot in RAM would make the undo do nothing at all here.
     */
    @Test
    fun undo_survives_a_recreated_service_and_restores_the_same_deadline() {
        assumeNotNull(BootIdentity.read(context))

        ServiceUtils.sendSleepTimerCommand(context, SleepTimerContract.ACTION_SET, minutes = 30)
        val original = awaitArmedTimer()

        ServiceUtils.sendSleepTimerCommand(context, SleepTimerContract.ACTION_CANCEL)
        awaitCancelledSnapshot(original.deadlineElapsedMs)
        assertEquals(
            "the snapshot is on disk before the service that made it goes away",
            original.deadlineElapsedMs,
            SleepTimerStore.readCancelled(context, boot)?.timer?.deadlineElapsedMs,
        )

        context.stopService(Intent(context, MediaPlayerService::class.java))
        Thread.sleep(RECREATE_MS)

        ServiceUtils.sendSleepTimerCommand(context, SleepTimerContract.ACTION_UNDO)
        val restored = awaitArmedTimer()

        assertEquals(
            "Вернуть restores the deadline it had, not a deadline measured now",
            original.deadlineElapsedMs,
            restored.deadlineElapsedMs,
        )
        assertEquals(30, restored.durationMinutes)
        assertNull(
            "one gesture: the snapshot is consumed by the undo that used it",
            SleepTimerStore.readCancelled(context, boot),
        )
    }

    /**
     * The undo's own replay rule, staged the same way: after a cancel (snapshot A), an
     * undo (consumes A), a new timer and a new cancel (snapshot B), a replay of the *first*
     * undo must not consume B. Without the record of which undo took which snapshot, that
     * replay would put back a timer the listener had cancelled after the undo.
     *
     * Two rules now stand between that replay and snapshot B, and this test is deliberately
     * about the invariant rather than about which one fires: the replayed undo is older than
     * the timer commands that came after it, so the queue retires it as superseded before any
     * handler sees it, and the store's own identity rule - the undo that already consumed a
     * snapshot cannot consume a later one - is the second line, pinned on the JVM in
     * `SleepTimerUndoTest`. What must never happen is what is asserted here.
     */
    @Test
    fun a_replayed_undo_does_not_consume_a_later_cancels_snapshot() {
        assumeNotNull(BootIdentity.read(context))

        inbox.enqueue(PlaybackCommand.of(SleepTimerContract.ACTION_SET, minutes = 30, bootId = boot))
        bareStart()
        awaitArmedTimer()
        inbox.enqueue(PlaybackCommand.of(SleepTimerContract.ACTION_CANCEL))
        bareStart()
        awaitNoTimerRecord()

        val firstUndoRecord = inbox.enqueue(PlaybackCommand.of(SleepTimerContract.ACTION_UNDO))
        assertNotNull("the undo record must be written", firstUndoRecord)
        val firstUndo = firstUndoRecord!!
        bareStart()
        awaitArmedTimer()

        // A later choice, cancelled: snapshot B.
        inbox.enqueue(PlaybackCommand.of(SleepTimerContract.ACTION_SET, minutes = 45, bootId = boot))
        bareStart()
        val second = awaitArmedTimer(accept = { it.durationMinutes == 45 })
        inbox.enqueue(PlaybackCommand.of(SleepTimerContract.ACTION_CANCEL))
        bareStart()
        awaitCancelledSnapshot(second.deadlineElapsedMs)
        val laterSnapshot = SleepTimerStore.readCancelled(context, boot)
        assertNotNull(laterSnapshot)

        // The first undo, replayed, finds a snapshot that is not the one it consumed.
        inbox.plantForTest(firstUndo)
        bareStart()
        awaitInboxEmpty()
        Thread.sleep(QUIET_MS)

        assertEquals(
            "a replayed undo leaves a later cancel's snapshot alone",
            laterSnapshot,
            SleepTimerStore.readCancelled(context, boot),
        )
        assertNull("and nothing is armed from it", restoredTimer())
    }

    /**
     * The second P1, on a device: an undo whose snapshot could not be consumed.
     *
     * `SleepTimerStore.consumeCancelled` answers `NotConsumed` when the write that would
     * have taken the snapshot did not reach the disk, and the handler used to return from
     * there - and a handler that returns is a command the inbox acknowledges. So the
     * command disappeared, the snapshot stayed where the cancel left it, and the `Вернуть`
     * the listener pressed had not happened: nothing put the timer back, and there was no
     * record left to try again.
     *
     * The refusal is staged on the store's own seam ([SleepTimerStore.failUndoCommitsForTest]),
     * and what is asserted first is the pair that has to survive it: the record is still
     * waiting and the snapshot is still there to be consumed. Then the service goes away -
     * the state that survives is the durable pair, not this instance's memory - and the next
     * legitimate start retries the same record, puts the same deadline back, and consumes the
     * snapshot exactly once.
     */
    @Test
    fun an_undo_whose_snapshot_could_not_be_consumed_is_not_acknowledged() {
        assumeNotNull(BootIdentity.read(context))

        ServiceUtils.sendSleepTimerCommand(context, SleepTimerContract.ACTION_SET, minutes = 30)
        val armed = awaitArmedTimer()
        ServiceUtils.sendSleepTimerCommand(context, SleepTimerContract.ACTION_CANCEL)
        awaitCancelledSnapshot(armed.deadlineElapsedMs)

        SleepTimerStore.failUndoCommitsForTest(1)
        val undo = inbox.enqueue(PlaybackCommand.of(SleepTimerContract.ACTION_UNDO))
        assertNotNull("the undo record must be written", undo)
        bareStart()

        // The attempt happened - the store refused it - and it is not a delivery.
        awaitRefusedUndoCommit()
        assertEquals(
            "an undo that could not consume its snapshot is not acknowledged",
            listOf(SleepTimerContract.ACTION_UNDO),
            inbox.pending().map { it.command.action },
        )
        assertEquals(
            "and the snapshot is still the one the cancel left",
            armed.deadlineElapsedMs,
            SleepTimerStore.readCancelled(context, boot)?.timer?.deadlineElapsedMs,
        )
        assertNull("nothing was put back", restoredTimer())
        assertNull("and no undo is recorded as having consumed it", SleepTimerStore.lastUndo(context))

        context.stopService(Intent(context, MediaPlayerService::class.java))
        Thread.sleep(RECREATE_MS)

        // The retry: the same record, read off the disk by a service that never saw the
        // failed attempt, with storage that takes the write this time.
        bareStart()
        awaitInboxEmpty()

        assertEquals(
            "Вернуть puts back the deadline it had, not one measured now",
            armed.deadlineElapsedMs,
            awaitArmedTimer().deadlineElapsedMs,
        )
        assertEquals(
            "and exactly once, by that record",
            undo!!.id,
            SleepTimerStore.lastUndo(context),
        )
        assertNull(
            "one gesture: the snapshot is consumed by the undo that used it",
            SleepTimerStore.readCancelled(context, boot),
        )
    }

    // ==================== a command that lets the service go ====================

    /**
     * The P1, on a device: a station-less `switch` is the one command that answers "do not
     * keep me", and that answer used to be folded into the handling with a short-circuiting
     * `&&` - so the commands behind it were never offered to their handlers and the inbox
     * acknowledged them anyway. The timer set here is such a command, and the armed timer
     * is the witness: a timer that is acknowledged without being armed is a gesture the
     * listener made and lost.
     */
    @Test
    fun a_stationless_switch_does_not_swallow_the_timer_behind_it() {
        assumeNotNull(BootIdentity.read(context))

        inbox.enqueue(PlaybackCommand.of("switch", stream = null, openForeground = true))
        inbox.enqueue(PlaybackCommand.of(SleepTimerContract.ACTION_SET, minutes = 15, bootId = boot))

        bareStart()

        assertEquals(
            "the timer set behind the switch has to run",
            15,
            awaitArmedTimer().durationMinutes,
        )
        assertTrue("and both commands are acknowledged", inbox.pending().isEmpty())
    }

    /**
     * The other command a listener leaves behind such a switch, with the durable record as
     * the witness: a Stop that ran writes "playback is no longer wanted", and a Stop that
     * was only acknowledged does not.
     *
     * (A Stop is also newer in the same [PlaybackDomain] as the switch, so it supersedes it
     * before either handler runs - which is the ordering rule that already covered this
     * pair, and is what `PlaybackCommandSupersessionTest` holds on the JVM.)
     */
    @Test
    fun a_stationless_switch_does_not_swallow_the_stop_behind_it() {
        PlaybackIntentStore.writeRawForTest(context, Streams.MYATA, wantsPlayback = true)

        inbox.enqueue(PlaybackCommand.of("switch", stream = null, openForeground = true))
        inbox.enqueue(PlaybackCommand.of("stop"))

        bareStart()

        awaitInboxEmpty()
        Thread.sleep(QUIET_MS)

        assertEquals(
            "the stop behind the switch ran, and the station it stopped is still the one loaded",
            PlaybackIntentStore.Stored.Known(Streams.MYATA, false),
            PlaybackIntentStore.read(context),
        )
        assertTrue("and it is acknowledged", inbox.pending().isEmpty())
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
        inbox.enqueue(
            PlaybackCommand.of(
                SleepTimerContract.ACTION_SET, minutes = 45,
                nowElapsedMs = chosenAt, bootId = boot,
            ),
        )

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
     * Across the boundary, the listener's last word is what happens: a timer set and then
     * cancelled comes out cancelled, and the control below shows that the same two commands
     * in the other order arm one - so the assertion is about which command is newest, not
     * about a command that never ran.
     *
     * Both halves are decided before either handler runs: the older command in the domain is
     * retired as superseded, which is what `PlaybackCommandSupersessionTest` holds on the
     * JVM. What is held here is the visible result, on a device.
     */
    @Test
    fun commands_recorded_before_the_start_keep_their_order() {
        assumeNotNull(BootIdentity.read(context))

        inbox.enqueue(PlaybackCommand.of(SleepTimerContract.ACTION_SET, minutes = 15, bootId = boot))
        inbox.enqueue(PlaybackCommand.of(SleepTimerContract.ACTION_CANCEL))

        bareStart()
        awaitInboxEmpty()

        assertFalse(
            "the cancel came last, so nothing may be left armed",
            SleepTimerStore.hasRecordForTest(context),
        )

        // The control: reversed, the same two commands leave a timer running.
        inbox.enqueue(PlaybackCommand.of(SleepTimerContract.ACTION_CANCEL))
        inbox.enqueue(PlaybackCommand.of(SleepTimerContract.ACTION_SET, minutes = 15, bootId = boot))

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

    // ==================== pending commands outrank the durable state ====================

    /**
     * Item 2's consequence, as far as a test in this process can stage it.
     *
     * The null-intent restart itself cannot be sent by a suite (see
     * `PlaybackIntentContract`), so `PlaybackCommandChannelTest` holds the ordering
     * against the service's source. What is held here is what the ordering is for: the
     * durable state a restart would read says the listener wanted audio, a Stop is
     * waiting in the inbox, and the Stop is what the durable state ends up saying.
     */
    @Test
    fun a_pending_stop_outranks_a_durable_want_playback() {
        PlaybackIntentStore.writeRawForTest(context, Streams.GOLD, wantsPlayback = true)
        inbox.enqueue(PlaybackCommand.of("stop"))

        bareStart()
        awaitStoppedAndEmpty()

        assertEquals(
            "a restart after this must find silence, not the audio the listener stopped",
            PlaybackIntentStore.Stored.Known(Streams.GOLD, false),
            PlaybackIntentStore.read(context),
        )
    }

    /**
     * And the station: a pending switch names the one that is wanted now, and the station
     * the durable record was still pointing at is never what plays.
     */
    @Test
    fun a_pending_switch_outranks_the_stored_station() {
        PlaybackIntentStore.writeRawForTest(context, Streams.MYATA, wantsPlayback = true)
        inbox.enqueue(
            PlaybackCommand.of(
                "switch", stream = Streams.XTRA, forcePlay = true, openForeground = true,
            ),
        )

        bareStart()

        val controller = awaitController()
        val deadline = SystemClock.uptimeMillis() + COMMAND_TIMEOUT_MS
        val seen = mutableListOf<String>()
        while (SystemClock.uptimeMillis() < deadline) {
            val current = onMainThread { controller.currentMediaItem?.mediaId ?: "none" }
            if (seen.lastOrNull() != current) seen += current
            if (current == Streams.XTRA) break
            Thread.sleep(POLL_MS)
        }

        assertEquals("the station the command names is the one that ends up on the player", Streams.XTRA, seen.lastOrNull())
        assertFalse("the station the durable record still held never gets installed", Streams.MYATA in seen)
        assertEquals(
            PlaybackIntentStore.Stored.Known(Streams.XTRA, true),
            PlaybackIntentStore.read(context),
        )
    }

    // ==================== a newer intent supersedes an older one ====================

    /**
     * The last P1 of this slice, on a device: a Play the listener has since replaced must
     * not run at all.
     *
     * Nothing here is broken. The Play's handler is fine and its record is intact - a newer
     * command in the same domain simply means the older one is no longer anything the
     * listener is waiting for, so the pass retires it and runs the Stop. The witnesses are
     * the two things its handler would have changed and could not have changed invisibly:
     * the station the durable record holds (a Play installs the one it names; a Stop keeps
     * the one that is there), and the foreground notification a Play owes the platform.
     */
    @Test
    fun a_play_superseded_by_a_newer_stop_never_reaches_the_player() {
        assumeNotNull(BootIdentity.read(context))
        PlaybackIntentStore.writeRawForTest(context, Streams.MYATA, wantsPlayback = true)

        inbox.enqueue(PlaybackCommand.of("play", stream = Streams.GOLD, openForeground = true))
        inbox.enqueue(PlaybackCommand.of("stop"))
        bareStart()

        awaitInboxEmpty()
        Thread.sleep(QUIET_MS)

        assertEquals(
            "the station the superseded play named must never have been installed",
            PlaybackIntentStore.Stored.Known(Streams.MYATA, false),
            PlaybackIntentStore.read(context),
        )
        assertFalse(
            "and a stale play must not be promoted to the foreground on its way past",
            placeholderIsUp(),
        )
        assertStoppedAndEmpty()
        assertTrue("nothing is left waiting either", inbox.pending().isEmpty())
    }

    /**
     * The same thing across a service that was recreated in between, which is the state the
     * durable inbox exists for: the record and the generation it wrote are on the disk, and
     * the service that reads them had nothing to do with the process that made them.
     */
    @Test
    fun a_play_the_dead_service_never_delivered_is_still_superseded_by_a_later_stop() {
        assumeNotNull(BootIdentity.read(context))
        PlaybackIntentStore.writeRawForTest(context, Streams.MYATA, wantsPlayback = true)

        inbox.enqueue(PlaybackCommand.of("play", stream = Streams.GOLD, openForeground = true))

        context.stopService(Intent(context, MediaPlayerService::class.java))
        Thread.sleep(RECREATE_MS)

        inbox.enqueue(PlaybackCommand.of("stop"))
        bareStart()

        awaitInboxEmpty()
        Thread.sleep(QUIET_MS)

        assertEquals(
            "the newer stop wins over the play no service ever delivered",
            PlaybackIntentStore.Stored.Known(Streams.MYATA, false),
            PlaybackIntentStore.read(context),
        )
        assertFalse(placeholderIsUp())
    }

    /**
     * Cross-domain, on a device: the newer Stop supersedes the older Play and must not take
     * the timer command with it. If the timer command had been dropped as "stale" too, there
     * would be no timer at all when this returns.
     */
    @Test
    fun a_newer_stop_supersedes_a_play_and_leaves_the_timer_command_alone() {
        assumeNotNull(BootIdentity.read(context))
        PlaybackIntentStore.writeRawForTest(context, Streams.MYATA, wantsPlayback = true)
        val chosenAt = SystemClock.elapsedRealtime()

        inbox.enqueue(PlaybackCommand.of("play", stream = Streams.GOLD, openForeground = true))
        inbox.enqueue(
            PlaybackCommand.of(
                SleepTimerContract.ACTION_SET, minutes = 30,
                nowElapsedMs = chosenAt, bootId = boot,
            ),
        )
        inbox.enqueue(PlaybackCommand.of("stop"))
        bareStart()

        val armed = awaitArmedTimer()
        awaitInboxEmpty()

        assertEquals("the timer command survives the playback domain's supersession", 30, armed.durationMinutes)
        assertEquals(chosenAt + 30 * 60_000L, armed.deadlineElapsedMs)
        assertEquals(
            "and the playback side ends where the newest playback command said",
            PlaybackIntentStore.Stored.Known(Streams.MYATA, false),
            PlaybackIntentStore.read(context),
        )
    }

    /**
     * The timer's half, with a witness that tells the two answers apart. A stale
     * `sleep_timer_undo` that had reached its handler would put the cancelled timer back on
     * the player, and the newer `cancel` behind it would then have something to put on offer
     * again - `Вернуть` would be live. It never ran, so the later cancel finds nothing armed
     * and takes the offer down, which is what a cancel with nothing to restore means.
     */
    @Test
    fun a_stale_timer_undo_cannot_put_a_cancelled_timer_back() {
        assumeNotNull(BootIdentity.read(context))

        inbox.enqueue(PlaybackCommand.of(SleepTimerContract.ACTION_SET, minutes = 30, bootId = boot))
        bareStart()
        awaitArmedTimer()

        inbox.enqueue(PlaybackCommand.of(SleepTimerContract.ACTION_CANCEL))
        bareStart()
        awaitNoTimerRecord()

        // The listener's newer intent replaces the undo that never reached the service.
        inbox.enqueue(PlaybackCommand.of(SleepTimerContract.ACTION_UNDO))
        inbox.enqueue(PlaybackCommand.of(SleepTimerContract.ACTION_CANCEL))
        bareStart()
        awaitInboxEmpty()
        Thread.sleep(QUIET_MS)

        assertNull(
            "only the undo that never ran could have left Вернуть on offer here",
            SleepTimerStore.readCancelled(context, boot),
        )
        assertNull("nothing is armed", restoredTimer())
        assertFalse(SleepTimerStore.hasRecordForTest(context))
    }

    /**
     * And what any app on the device can send. The bare start is the same one this file uses
     * everywhere, here carrying the old private protocol in extras - including the
     * superseded Play's own station, which is the one thing a start that could rewrite the
     * queue would say. It cannot: the newest command is still the Stop afterwards, nothing
     * it carried promoted the service, and nothing was added to the queue.
     */
    @Test
    fun an_external_start_cannot_resurrect_a_superseded_command() {
        assumeNotNull(BootIdentity.read(context))
        PlaybackIntentStore.writeRawForTest(context, Streams.MYATA, wantsPlayback = true)

        inbox.enqueue(PlaybackCommand.of("play", stream = Streams.GOLD, openForeground = true))
        inbox.enqueue(PlaybackCommand.of("stop"))

        val hostile = Intent(context, MediaPlayerService::class.java).apply {
            putExtra("ACTION", "play")
            putExtra("STREAM", Streams.GOLD)
            putExtra("force_play", true)
            putExtra("FOREGROUND_START", true)
        }
        context.startService(hostile)

        awaitInboxEmpty()
        Thread.sleep(QUIET_MS)

        assertEquals(
            "a start carries no command, so it cannot change which command is newest",
            PlaybackIntentStore.Stored.Known(Streams.MYATA, false),
            PlaybackIntentStore.read(context),
        )
        assertFalse("nor can it promote the service", placeholderIsUp())
        assertTrue("nor add anything to the queue", inbox.pending().isEmpty())
        assertStoppedAndEmpty()
    }

    // ==================== a refused start that was already delivered ================

    /**
     * Item 8 and item 13 on a device: the caller records its command and asks for the
     * start; another start drains the command first; this caller's own start is then
     * refused.
     *
     * The wrapper is what makes that order deterministic - it hands the start to the real
     * service, waits for the command to have been consumed, and only then throws the way a
     * refused start does. `ServiceUtils` must not answer with a delivery failure, because
     * the command *was* delivered.
     */
    @Test
    fun a_refused_start_after_another_consumed_the_command_is_not_reported_as_failed() {
        val delivered = ServiceUtils.sendUiCommand(ConsumingThenFailingContext(context), "play", Streams.GOLD)

        assertTrue("the command reached the service, so this is not a failure", delivered)
        awaitMediaItem(Streams.GOLD)
        assertTrue(inbox.pending().isEmpty())
    }

    /**
     * The other half of the same rule: a start refused *before* the command reached the
     * service is a delivery failure, and the unused command is taken back out rather than
     * left for an unrelated start to find later.
     */
    @Test
    fun a_refused_start_before_the_command_reached_the_service_is_a_failure() {
        val delivered = ServiceUtils.sendUiCommand(AlwaysRefusingContext(context), "play", Streams.GOLD)

        assertFalse("nothing was delivered, and the caller has to be able to say so", delivered)
        assertTrue(
            "a command whose start never happened must not be left behind",
            inbox.pending().isEmpty(),
        )
    }

    // ============================ plumbing ============================

    /**
     * The start request as anyone can make it: the exported component and nothing
     * else. No action, no extra, no station - the same intent `ServiceUtils` sends.
     */
    private fun bareStart() {
        context.startService(Intent(context, MediaPlayerService::class.java))
    }

    /**
     * A start that is accepted by the system, turns into a real drain of the inbox, and is
     * then refused anyway - the exact order item 8 is about.
     */
    private class ConsumingThenFailingContext(base: Context) : ContextWrapper(base) {

        override fun startService(service: Intent?): ComponentName? {
            val started = super.startService(service)
            // Wait until the start that *did* happen has consumed the record, or the test
            // would be racing the very race it is about.
            val deadline = SystemClock.uptimeMillis() + CONSUME_TIMEOUT_MS
            while (SystemClock.uptimeMillis() < deadline) {
                if (PlaybackCommandInbox.forContext(this).pending().isEmpty()) break
                Thread.sleep(POLL_MS)
            }
            throw IllegalStateException("the platform refused this start")
        }
    }

    /** A start the platform refuses outright: nothing was handed over. */
    private class AlwaysRefusingContext(base: Context) : ContextWrapper(base) {

        override fun startService(service: Intent?): ComponentName? =
            throw IllegalStateException("the platform refused this start")

        override fun startForegroundService(service: Intent?): ComponentName =
            throw IllegalStateException("the platform refused this start")
    }

    private fun restoredTimer(): SleepTimerState.Armed? =
        (SleepTimerStore.restore(context, boot, SystemClock.elapsedRealtime())
            as? SleepTimerStore.Restored.Armed)?.timer

    private fun awaitArmedTimer(
        timeoutMs: Long = COMMAND_TIMEOUT_MS,
        accept: (SleepTimerState.Armed) -> Boolean = { true },
    ): SleepTimerState.Armed {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            restoredTimer()?.let { if (accept(it)) return it }
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
     * Waits for what `Вернуть` would put back to be on disk with the deadline it was
     * holding. The cancel clears the armed record *before* it writes the snapshot, so
     * "the timer is gone" is not by itself proof that the cancel has finished.
     */
    private fun awaitCancelledSnapshot(deadlineElapsedMs: Long, timeoutMs: Long = COMMAND_TIMEOUT_MS) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (SleepTimerStore.readCancelled(context, boot)?.timer?.deadlineElapsedMs == deadlineElapsedMs) return
            Thread.sleep(POLL_MS)
        }
        fail("no cancel snapshot holding $deadlineElapsedMs within ${timeoutMs}ms")
    }

    /**
     * Waits for the store to have refused a write to its undo file, which is proof that the
     * undo's consumption was *attempted* - the fact a test about a failed consumption has to
     * wait for, since a refusal leaves the record and the snapshot exactly where they were
     * and nothing else moves.
     */
    private fun awaitRefusedUndoCommit(timeoutMs: Long = COMMAND_TIMEOUT_MS) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (SleepTimerStore.refusedUndoCommitsForTest() >= 1) return
            Thread.sleep(POLL_MS)
        }
        fail("the undo's consumption was never attempted within ${timeoutMs}ms")
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

    /**
     * The same question asked without waiting, for the tests whose answer is *no*: a stale
     * command's promotion would have been posted by the time the pass it belonged to has
     * ended and the queue is empty, so what a quiet moment and this check say together is
     * "it never happened".
     */
    private fun placeholderIsUp(): Boolean =
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .activeNotifications
            .any { it.id == PLACEHOLDER_ID }

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

        /** Long enough for a start that did happen to have been handled. */
        const val CONSUME_TIMEOUT_MS = 10_000L

        /** Long enough for a stopped service to be really gone before it is started again. */
        const val RECREATE_MS = 1_000L

        /** Long enough for a command to have shown itself, or not to have. */
        const val QUIET_MS = 2_000L

        const val POLL_MS = 50L

        /** The id `postPlaceholderForegroundNotification` promotes the service with. */
        const val PLACEHOLDER_ID = 1
    }
}
