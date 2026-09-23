package com.example.musicplayerapp.service

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import com.example.musicplayerapp.data.BootIdentity
import com.example.musicplayerapp.data.SleepTimerStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The private command channel, on the JVM.
 *
 * The channel is [PlaybackCommandInbox]: the app's own storage, written before the
 * service start is requested and read by the service, because the RAM queue this
 * replaced lost the listener's gesture whenever the process died between the two
 * (the P1 this file pins shut).
 *
 * ## What is real here and what is not
 *
 * The device under the store is a fake - a map of the *encoded strings* on a file that
 * outlives it - so the whole queue can be exercised without a device, and so "the
 * process died and was recreated" is honest: nothing but those strings crosses from one
 * process to the next. It is [FaithfulPrefs], deliberately, and not a convenient fake:
 * a commit that does not reach the file still lands in the process's map, which is what
 * Android's `SharedPreferences` does and what the queue's rollback exists for. The
 * transaction layer itself is never faked - [PlaybackCommandInbox.TransactionalSlot] is
 * production code, and every write below goes through it.
 *
 * The Android half - `commit = true`, the file's name and its exclusion from
 * backup, and a service that really does act on a record it finds after its start
 * - is `PlaybackCommandHandoffTest` on a device, plus the source-level assertions
 * at the end of this file for the parts of the wiring a JVM test can read.
 */
class PlaybackCommandChannelTest {

    private val file = QueueFile()

    /** The device under the store, as the process making the gestures sees it. */
    private val raw = FaithfulPrefs(file)

    /**
     * The channel as the process that made the gestures sees it: the one store
     * [PlaybackCommandInbox.forContext] would hand out, behind the one transaction
     * boundary every write goes through.
     */
    private val store = PlaybackCommandInbox.TransactionalSlot(raw)

    private fun process() = PlaybackCommandInbox(store)

    /** The channel as a process that has not run yet sees it: [file], and nothing else. */
    private fun newProcess() = PlaybackCommandInbox(PlaybackCommandInbox.TransactionalSlot(FaithfulPrefs(file)))

    private fun actions(): List<String> = process().pending().map { it.command.action }

    // ==================== durable before the start ====================

    /**
     * The order [ServiceUtils] depends on: [PlaybackCommandInbox.enqueue] returns
     * only once the record is on disk, so a start requested after it cannot outrun
     * the command it is a start for - whether or not this process survives.
     */
    @Test
    fun `a command is on disk before the caller asks for a start`() {
        val queued = process().enqueue(PlaybackCommand.of("play", stream = "myata", nowElapsedMs = 0L))!!

        assertTrue("enqueue must have written before it returned", raw.onDisk().isNotEmpty())
        assertNotNull(queued.id)
        assertEquals(listOf("play"), actions())
    }

    /**
     * The P1 itself: the start request survives a kill, so the command has to.
     * Nothing of the enqueueing process is reused to read it back.
     */
    @Test
    fun `a recreated process finds the command the dead one never handled`() {
        process().enqueue(PlaybackCommand.of("play", stream = "gold", nowElapsedMs = 0L))

        val recreated = newProcess()
        val pending = recreated.pending()

        assertEquals(1, pending.size)
        assertEquals("play", pending.single().command.action)
        assertEquals("gold", pending.single().command.stream)
    }

    // ==================== each command, across the boundary ====================

    @Test
    fun `play survives recreation with everything it carries`() {
        process().enqueue(
            PlaybackCommand.of(
                "play", stream = "myata_hits", artist = "Аквариум", song = "Поезд",
                forcePlay = true, openForeground = true, nowElapsedMs = 0L,
            ),
        )

        val command = process().pending().single().command
        assertEquals("play", command.action)
        assertEquals("myata_hits", command.stream)
        assertEquals("Аквариум", command.artist)
        assertEquals("Поезд", command.song)
        assertTrue(command.forcePlay)
        assertTrue("a Play is a foreground start", command.openForeground)
    }

    @Test
    fun `stop survives recreation and does not come back as anything else`() {
        process().enqueue(PlaybackCommand.of("stop", nowElapsedMs = 0L))

        assertEquals(listOf("stop"), actions())
    }

    @Test
    fun `a station switch survives recreation with the station it names`() {
        process().enqueue(
            PlaybackCommand.of("switch", stream = "gold", forcePlay = true, openForeground = true, nowElapsedMs = 0L),
        )

        val command = process().pending().single().command
        assertEquals("switch", command.action)
        assertEquals("gold", command.stream)
        assertTrue(command.forcePlay)
    }

    @Test
    fun `a track metadata update survives recreation`() {
        process().enqueue(
            PlaybackCommand.of("switch_track", stream = "myata", artist = "A", song = "B", nowElapsedMs = 0L),
        )

        val entry = process().pending().single()
        assertEquals("switch_track", entry.command.action)
        assertEquals("A", entry.command.artist)
        assertEquals("B", entry.command.song)
    }

    /**
     * A sleep timer is a duration when the listener picks it and an instant when it
     * is recorded. Handling it after a process death must re-arm *that* instant, or
     * the radio stops later than it was asked to - which is why the deadline is
     * resolved before the command is stored.
     */
    @Test
    fun `a sleep timer set survives recreation with the deadline the listener chose`() {
        val chosenAt = 1_000_000L
        process().enqueue(
            PlaybackCommand.of(
                SleepTimerContract.ACTION_SET, minutes = 30, isCustom = true,
                nowElapsedMs = chosenAt,
            ),
        )

        val command = process().pending().single().command
        assertEquals(SleepTimerContract.ACTION_SET, command.action)
        assertEquals(30, command.minutes)
        assertTrue(command.isCustom)
        assertEquals(
            "the deadline must be the instant the listener chose, not the moment of handling",
            chosenAt + 30 * 60_000L,
            command.deadlineElapsedMs,
        )
        assertFalse("a timer is not a foreground start", command.openForeground)
    }

    @Test
    fun `a sleep timer cancel survives recreation`() {
        process().enqueue(PlaybackCommand.of(SleepTimerContract.ACTION_CANCEL, nowElapsedMs = 0L))

        val command = process().pending().single().command
        assertEquals(SleepTimerContract.ACTION_CANCEL, command.action)
        assertNull("only a set carries a deadline", command.deadlineElapsedMs)
    }

    /**
     * Item 4: a deadline is meaningless without the boot it was measured on.
     *
     * `elapsedRealtime` restarts at zero at boot and climbs from there, so a deadline
     * from a previous boot is a number that looks entirely ordinary in the new epoch.
     * The boot is therefore stored with the deadline - as `BootIdentity` stores it, so
     * "the platform would not tell us" is stored as unknown rather than as a boot - and
     * the service refuses a deadline that cannot prove it belongs to this boot.
     */
    @Test
    fun `a deadline is stored with the boot it was measured on`() {
        val set = PlaybackCommand.of(
            SleepTimerContract.ACTION_SET, minutes = 30, nowElapsedMs = 1_000L, bootId = 7,
        )
        assertEquals(7, set.deadlineBootId)

        assertEquals(
            "an unreadable boot counter is stored as unknown, never as \"this boot\"",
            BootIdentity.UNKNOWN,
            PlaybackCommand.of(SleepTimerContract.ACTION_SET, minutes = 30, bootId = null).deadlineBootId,
        )

        for (action in listOf(
            SleepTimerContract.ACTION_CANCEL,
            SleepTimerContract.ACTION_UNDO,
            SleepTimerContract.ACTION_SYNC,
            "play",
            "stop",
            "switch",
        )) {
            assertNull(
                "$action carries no deadline, so it has no boot to belong to",
                PlaybackCommand.of(action, bootId = 7).deadlineBootId,
            )
        }
    }

    @Test
    fun `the boot a deadline was measured on survives recreation`() {
        process().enqueue(
            PlaybackCommand.of(SleepTimerContract.ACTION_SET, minutes = 15, nowElapsedMs = 0L, bootId = 42),
        )

        assertEquals(42, process().pending().single().command.deadlineBootId)
    }

    // ==================== the foreground obligation ====================

    /**
     * The other half of the P1: a `startForegroundService` call owes Android a
     * `startForeground` within five seconds, and only the caller knows it asked for
     * one. That fact is stored with the command, so the obligation is still known
     * by a service the system recreated.
     */
    @Test
    fun `the foreground obligation survives recreation`() {
        process().enqueue(PlaybackCommand.of("play", stream = "myata", openForeground = true, nowElapsedMs = 0L))
        process().enqueue(PlaybackCommand.of(SleepTimerContract.ACTION_SYNC, nowElapsedMs = 0L))

        val pending = process().pending()
        assertTrue(pending.first { it.command.action == "play" }.command.openForeground)
        assertFalse(
            "a command that came in on a plain startService must not manufacture one",
            pending.first { it.command.action == SleepTimerContract.ACTION_SYNC }.command.openForeground,
        )
    }

    // ==================== order ====================

    @Test
    fun `several commands survive recreation in the order they were made`() {
        val first = process().enqueue(PlaybackCommand.of("play", stream = "myata", nowElapsedMs = 0L))!!
        val second = process().enqueue(PlaybackCommand.of("switch", stream = "gold", nowElapsedMs = 0L))!!
        val third = process().enqueue(PlaybackCommand.of("stop", nowElapsedMs = 0L))!!

        val pending = process().pending()
        assertEquals(listOf("play", "switch", "stop"), pending.map { it.command.action })
        assertEquals(
            "identity is assigned in order and survives the boundary",
            listOf(first.id, second.id, third.id),
            pending.map { it.id },
        )
    }

    /**
     * Order is the sequence, oldest first, and every command the app makes comes out in it.
     * The four here are in different domains, or in none - a command that only restates
     * derived state - because the one thing that outranks order is a *newer command in the
     * same domain*, which is supersession rather than ordering, and is
     * `PlaybackCommandSupersessionTest`.
     */
    @Test
    fun `handling runs oldest first`() {
        val inbox = process()
        inbox.enqueue(PlaybackCommand.of("play", stream = "myata", nowElapsedMs = 0L))
        inbox.enqueue(PlaybackCommand.of("switch_track", artist = "A", song = "A", nowElapsedMs = 0L))
        inbox.enqueue(PlaybackCommand.of(SleepTimerContract.ACTION_SET, minutes = 15, nowElapsedMs = 0L))
        inbox.enqueue(PlaybackCommand.of("get_status", nowElapsedMs = 0L))

        val ran = mutableListOf<String>()
        inbox.drain { ran += it.command.action }

        assertEquals(
            listOf("play", "switch_track", SleepTimerContract.ACTION_SET, "get_status"),
            ran,
        )
    }

    /** A gesture made while the service is working is this pass's work, not the next one's. */
    @Test
    fun `a command appended during a pass is handled by that pass`() {
        val inbox = process()
        inbox.enqueue(PlaybackCommand.of("play", stream = "myata", nowElapsedMs = 0L))

        val ran = mutableListOf<String>()
        inbox.drain { entry ->
            ran += entry.command.action
            if (entry.command.action == "play") {
                process().enqueue(PlaybackCommand.of("stop", nowElapsedMs = 0L))
            }
        }

        assertEquals(listOf("play", "stop"), ran)
        assertTrue(process().pending().isEmpty())
    }

    // ==================== the obligation before the handler ====================

    /**
     * Item 7's race, closed where it is closed: the head is offered to [prepare] *as it is
     * on disk at that instant*, so a command appended while a pass is running is gated
     * before its own handler rather than handled because it happened to be in a snapshot
     * taken when the pass began.
     */
    @Test
    fun `a command appended during a pass is prepared before it is handled`() {
        val inbox = process()
        inbox.enqueue(PlaybackCommand.of("switch_track", artist = "A", song = "A", nowElapsedMs = 0L))

        val prepared = mutableListOf<String>()
        val ran = mutableListOf<String>()
        inbox.drain(prepare = { entry -> prepared += entry.command.action; true }) { entry ->
            ran += entry.command.action
            if (entry.command.action == "switch_track") {
                process().enqueue(
                    PlaybackCommand.of("play", stream = "myata", openForeground = true, nowElapsedMs = 0L),
                )
            }
        }

        assertEquals("the appended command is this pass's work", listOf("switch_track", "play"), ran)
        assertEquals("and it was gated first, like the one that was already there", listOf("switch_track", "play"), prepared)
    }

    /**
     * Item 12: preparation that fails means the command is **not delivered**. It is not
     * handled, not acknowledged, and the pass stops with it - and everything behind it -
     * still pending.
     *
     * The command behind it belongs to the other domain, so this stays a test about a
     * refused head rather than about supersession: a newer command *in the same domain*
     * would replace the refused one instead of waiting behind it.
     */
    @Test
    fun `a command whose preparation fails is neither handled nor acknowledged`() {
        val inbox = process()
        val refused = inbox.enqueue(
            PlaybackCommand.of("play", stream = "myata", openForeground = true, nowElapsedMs = 0L),
        )!!
        val behind = inbox.enqueue(
            PlaybackCommand.of(SleepTimerContract.ACTION_CANCEL, nowElapsedMs = 0L),
        )!!

        val prepared = mutableListOf<String>()
        val ran = mutableListOf<String>()
        inbox.drain(prepare = { prepared += it.id; false }) { ran += it.command.action }

        assertEquals("the handler never ran", emptyList<String>(), ran)
        assertEquals("the gate was asked about the head, and the pass stopped there", listOf(refused.id), prepared)
        assertEquals(listOf(refused.id, behind.id), process().pending().map { it.id })
    }

    // ==================== withdraw has three answers ====================

    /**
     * Item 8: taking a command back and finding it already gone are different facts, and
     * only the first is a delivery failure. `ServiceUtils` reads the same distinction.
     */
    @Test
    fun `withdraw distinguishes a command taken back from one already consumed`() {
        val queued = process().enqueue(PlaybackCommand.of("play", stream = "myata", nowElapsedMs = 0L))!!

        assertEquals(PlaybackCommandInbox.Withdraw.REMOVED_PENDING, process().withdraw(queued.id))
        assertTrue("and it is out of the queue", process().pending().isEmpty())

        assertEquals(
            "already consumed is not the same answer as taken back",
            PlaybackCommandInbox.Withdraw.NOT_FOUND,
            process().withdraw(queued.id),
        )
    }

    /**
     * Item 8's race, one level down: another start drained the command while this
     * caller's own start was being refused. The record is not there to take back, and
     * nothing else may be touched.
     */
    @Test
    fun `a command another start already consumed cannot be withdrawn`() {
        val queued = process().enqueue(PlaybackCommand.of("play", stream = "myata", nowElapsedMs = 0L))!!

        // Another start takes the command and handles it.
        val ran = mutableListOf<String>()
        process().drain { ran += it.command.action }

        assertEquals(
            "the caller must not be told its command is still waiting",
            PlaybackCommandInbox.Withdraw.NOT_FOUND,
            process().withdraw(queued.id),
        )
        assertEquals(listOf("play"), ran)
    }

    /**
     * The third answer, and the reason it is not either of the other two: the removal
     * itself did not commit. The command is still in the inbox - a later start will run
     * it - so claiming it was taken back would be wrong, and claiming another start
     * already carried it out would be worse: that is the answer `ServiceUtils` turns
     * into "the gesture was delivered". A store that could not be written is reported as
     * what it is.
     */
    @Test
    fun `a withdrawal that did not commit is neither a removal nor a delivery`() {
        val queued = process().enqueue(PlaybackCommand.of("play", stream = "myata", nowElapsedMs = 0L))!!

        raw.failedCommits = 1
        assertEquals(
            "a removal the store did not take is not a removal",
            PlaybackCommandInbox.Withdraw.NOT_REMOVED,
            process().withdraw(queued.id),
        )
        assertEquals("and the record is still where it was", listOf(queued.id), process().pending().map { it.id })
        assertEquals(
            "the file still holds it for a later start",
            listOf(queued.id),
            newProcess().pending().map { it.id },
        )

        // And once the store commits again, the same call does what it says.
        assertEquals(PlaybackCommandInbox.Withdraw.REMOVED_PENDING, process().withdraw(queued.id))
        assertTrue(process().pending().isEmpty())
    }

    // ==================== acknowledgement ====================

    @Test
    fun `a command that was handled is acknowledged exactly once`() {
        val inbox = process()
        inbox.enqueue(PlaybackCommand.of("play", stream = "myata", nowElapsedMs = 0L))

        val ran = mutableListOf<String>()
        inbox.drain { ran += it.command.action }
        // The next start of the service, on the same records.
        process().drain { ran += it.command.action }

        assertEquals("handled once, and not again on the next start", listOf("play"), ran)
        assertTrue(process().pending().isEmpty())
    }

    @Test
    fun `a start that was refused leaves nothing behind`() {
        val queued = process().enqueue(PlaybackCommand.of("play", stream = "myata", openForeground = true, nowElapsedMs = 0L))!!

        process().withdraw(queued.id)

        assertTrue(
            "a command whose start never happened must not be run by an unrelated start later",
            process().pending().isEmpty(),
        )
    }

    @Test
    fun `withdraw takes back its own command and not the one behind it`() {
        val refused = process().enqueue(PlaybackCommand.of("play", stream = "myata", nowElapsedMs = 0L))!!
        process().enqueue(PlaybackCommand.of("switch", stream = "gold", nowElapsedMs = 0L))

        process().withdraw(refused.id)

        assertEquals(listOf("switch"), actions())
    }

    // ==================== failure ====================

    /**
     * The requirement that makes the queue trustworthy: a handler that fails must
     * not cost the listener anything. Its command stays, and so does everything
     * behind it - and it is *not* silently dropped on the way past.
     *
     * What is behind it is a sleep-timer command, deliberately: a newer command in the
     * *same* domain is not something the failing head waits behind, it is what supersedes
     * it (`PlaybackCommandSupersessionTest`), and this test is about the case where
     * nothing has replaced the listener's intent.
     */
    @Test
    fun `a handler that fails leaves its command and everything behind it pending`() {
        val inbox = process()
        val failing = inbox.enqueue(PlaybackCommand.of("play", stream = "myata", nowElapsedMs = 0L))!!
        val later = inbox.enqueue(PlaybackCommand.of(SleepTimerContract.ACTION_CANCEL, nowElapsedMs = 0L))!!

        val attempted = mutableListOf<String>()
        inbox.drain { entry ->
            attempted += entry.command.action
            throw IllegalStateException("the handler could not run")
        }

        assertEquals("the pass stops at the failure", listOf("play"), attempted)
        assertEquals(listOf(failing.id, later.id), process().pending().map { it.id })

        // The next start retries it, and the queue moves on once it works.
        val ran = mutableListOf<String>()
        process().drain { ran += it.command.action }
        assertEquals(listOf("play", SleepTimerContract.ACTION_CANCEL), ran)
        assertTrue(process().pending().isEmpty())
    }

    /**
     * The other side of that, and it only applies to the commands whose loss cannot cost
     * the listener anything: one that can never work must not sit in front of every later
     * Play press for the life of the install. It is dropped after a bounded number of
     * attempts, and the queue behind it is not.
     */
    @Test
    fun `a command that always fails is dropped after a bounded number of attempts`() {
        val inbox = process()
        inbox.enqueue(PlaybackCommand.of("switch_track", artist = "A", song = "A", nowElapsedMs = 0L))
        inbox.enqueue(PlaybackCommand.of("stop", nowElapsedMs = 0L))

        repeat(PlaybackCommandInbox.MAX_HANDLER_ATTEMPTS) {
            inbox.drain { throw IllegalStateException("still broken") }
        }

        assertEquals("the broken command is gone, the one behind it is not", listOf("stop"), actions())

        val ran = mutableListOf<String>()
        process().drain { ran += it.command.action }
        assertEquals(listOf("stop"), ran)
    }

    @Test
    fun `an attempt against a command that is not there is not a record left behind`() {
        val inbox = process()
        val queued = inbox.enqueue(PlaybackCommand.of("play", stream = "myata", nowElapsedMs = 0L))!!
        inbox.ack(queued.id)

        assertEquals("nothing left to fail", PlaybackCommandInbox.Failure.Dropped, inbox.noteFailure(queued.id))
        assertTrue(process().pending().isEmpty())
    }

    /**
     * The rule a retry count may not override: a command the listener asked for is never
     * dropped, however often its handler fails, and everything behind it waits with it.
     * Retrying happens on the next legitimate start, never in a loop here.
     *
     * The command behind it is a sleep-timer one on purpose. A newer command in the *same*
     * domain would not wait behind the failing head at all - it is what supersedes it, and
     * that is `PlaybackCommandSupersessionTest`. What is held here is the case the review
     * named separately: no newer intent, so nothing may be discarded.
     */
    @Test
    fun `a critical command is never discarded by its retry count`() {
        val inbox = process()
        val wanted = inbox.enqueue(PlaybackCommand.of("play", stream = "myata", nowElapsedMs = 0L))!!
        val behind = inbox.enqueue(PlaybackCommand.of(SleepTimerContract.ACTION_SET, minutes = 15, nowElapsedMs = 0L))!!

        repeat(PlaybackCommandInbox.MAX_HANDLER_ATTEMPTS * 4) {
            val attempts = mutableListOf<String>()
            inbox.drain { entry ->
                attempts += entry.command.action
                throw IllegalStateException("still broken")
            }
            assertEquals("the pass always stops at the head", listOf("play"), attempts)
        }

        assertEquals(
            "kept, and everything behind it with it",
            listOf(wanted.id, behind.id),
            process().pending().map { it.id },
        )
    }

    /**
     * The classification itself, in full: the seven commands whose loss would take a
     * choice away are critical, and the five that only restate state the app derives for
     * itself are not.
     */
    @Test
    fun `only the commands whose loss costs the listener nothing can be discarded`() {
        for (action in listOf(
            "play",
            PlaybackCommand.ACTION_START_STOP,
            "stop",
            "switch",
            SleepTimerContract.ACTION_SET,
            SleepTimerContract.ACTION_CANCEL,
            SleepTimerContract.ACTION_UNDO,
        )) {
            assertTrue("$action is a choice, not a restatement", PlaybackCommand.of(action).critical)
        }

        for (action in listOf(
            "switch_track",
            "get_status",
            SleepTimerContract.ACTION_SYNC,
            PlaybackIntentContract.ACTION_RESTORE,
            SystemPlaybackEventContract.ACTION_BECOMING_NOISY,
        )) {
            assertFalse("$action is re-derived anyway", PlaybackCommand.of(action).critical)
        }
    }

    // ==================== the disk has to say yes ====================

    /**
     * Item 1, first half. A commit that did not reach the disk is not an accepted
     * command: no entry, nothing on disk, and - in `ServiceUtils` - no start request
     * either, which is the whole point of the check.
     */
    @Test
    fun `a command whose record did not commit is not accepted`() {
        raw.failedCommits = 1

        val queued = process().enqueue(PlaybackCommand.of("play", stream = "myata", nowElapsedMs = 0L))

        assertNull("a write that did not reach the disk is not a queued command", queued)
        assertTrue("and nothing is left behind for a later start either", process().pending().isEmpty())
        assertTrue("nor in the file this process would write next", raw.onDisk().isEmpty())
        assertTrue(
            "nor in the process's own map, where a failed commit puts it before undo",
            raw.visible().isEmpty(),
        )
    }

    @Test
    fun `the id of a command that did not commit is not spent`() {
        val first = process().enqueue(PlaybackCommand.of("play", stream = "myata", nowElapsedMs = 0L))!!
        raw.failedCommits = 1
        assertNull(process().enqueue(PlaybackCommand.of("stop", nowElapsedMs = 0L)))
        val third = process().enqueue(PlaybackCommand.of("switch", stream = "gold", nowElapsedMs = 0L))!!

        assertTrue("ids keep climbing", third.id.toLong() > first.id.toLong())
        assertEquals(listOf("play", "switch"), actions())
    }

    /**
     * Item 1, second half: a removal that did not commit is not an acknowledgement, and
     * the command stays replayable rather than being treated as delivered.
     */
    @Test
    fun `an acknowledgement that did not commit leaves the command replayable`() {
        val inbox = process()
        val queued = inbox.enqueue(PlaybackCommand.of("play", stream = "myata", nowElapsedMs = 0L))!!

        raw.failedCommits = 1
        assertFalse("a removal that did not reach the disk is not an acknowledgement", inbox.ack(queued.id))

        assertEquals("the record is still there, so the next start runs it", listOf("play"), actions())
    }

    /**
     * And a pass that cannot acknowledge stops rather than handling the same head twice.
     *
     * The command behind the head is a sleep-timer one, because a newer command in the same
     * domain is not what waits behind a head - it is what supersedes it, and this test is
     * about the head itself being handled once and then left replayable.
     */
    @Test
    fun `a pass whose acknowledgement does not commit stops instead of repeating itself`() {
        val inbox = process()
        inbox.enqueue(PlaybackCommand.of("play", stream = "myata", nowElapsedMs = 0L))
        inbox.enqueue(PlaybackCommand.of(SleepTimerContract.ACTION_CANCEL, nowElapsedMs = 0L))

        val ran = mutableListOf<String>()
        inbox.drain { entry ->
            ran += entry.command.action
            // The disk fails exactly when the acknowledgement is written.
            raw.failedCommits = 1
        }

        assertEquals("handled once, then the pass stopped", listOf("play"), ran)
        assertEquals(listOf("play", SleepTimerContract.ACTION_CANCEL), actions())
    }

    /**
     * Item 1, third part: a failure that could not be written down must not change what
     * happens to the command. The record is untouched, so the command is still pending -
     * and, for a command whose loss costs nothing, still droppable later.
     */
    @Test
    fun `a failure that cannot be written down leaves the command exactly as it was`() {
        val inbox = process()
        val queued = inbox.enqueue(PlaybackCommand.of("switch_track", nowElapsedMs = 0L))!!

        raw.failedCommits = 1
        assertEquals(PlaybackCommandInbox.Failure.NotPersisted, inbox.noteFailure(queued.id))

        assertEquals(listOf("switch_track"), actions())
    }

    @Test
    fun `a discard that cannot be committed keeps the command pending`() {
        val inbox = process()
        inbox.enqueue(PlaybackCommand.of("switch_track", nowElapsedMs = 0L))
        repeat(PlaybackCommandInbox.MAX_HANDLER_ATTEMPTS - 1) {
            inbox.drain { throw IllegalStateException("still broken") }
        }

        // The attempt that would have reached the bound, with a disk that refuses it.
        raw.failedCommits = 1
        assertEquals(
            PlaybackCommandInbox.Failure.NotPersisted,
            inbox.noteFailure(process().pending().single().id),
        )

        assertEquals(listOf("switch_track"), actions())
    }

    // ==================== unreadable records ====================

    /**
     * A record this code cannot read is not run and not retried: what it said is
     * unknowable, and guessing would be guessing with the speaker.
     */
    @Test
    fun `an unreadable record is dropped rather than run or retried`() {
        val inbox = process()
        inbox.enqueue(PlaybackCommand.of("play", stream = "myata", nowElapsedMs = 0L))

        val corrupt = raw.read().keys.associateWith { "<not a record>" }
        raw.edit(corrupt)

        assertTrue(process().pending().isEmpty())

        // And the queue still works afterwards.
        process().enqueue(PlaybackCommand.of("stop", nowElapsedMs = 0L))
        assertEquals(listOf("stop"), actions())
    }

    // ==================== identity ====================

    @Test
    fun `identity keeps climbing, so a re-used slot cannot re-acknowledge an old id`() {
        val first = process().enqueue(PlaybackCommand.of("play", stream = "myata", nowElapsedMs = 0L))!!
        process().ack(first.id)
        val second = process().enqueue(PlaybackCommand.of("play", stream = "myata", nowElapsedMs = 0L))!!

        assertTrue(
            "ids must never be reused: an id is how a command is acknowledged",
            second.id.toLong() > first.id.toLong(),
        )
    }

    // ==================== the resolved requests ====================

    /**
     * The replay-safety of the two commands that could not state their own meaning.
     * Both are resolved where the listener makes the gesture, by the one factory
     * every caller goes through.
     */
    @Test
    fun `startStop is stored as a desire, never as a question`() {
        val toggle = PlaybackCommand.of(PlaybackCommand.ACTION_START_STOP, stream = "myata", nowElapsedMs = 0L)

        assertEquals(
            "every call site means \"start playback\"; a stored toggle replayed as Pause",
            true,
            toggle.desiredPlaying,
        )
        assertNull("only startStop has a desire to resolve", PlaybackCommand.of("play", nowElapsedMs = 0L).desiredPlaying)
        assertNull(PlaybackCommand.of("stop", nowElapsedMs = 0L).desiredPlaying)
        assertNull(PlaybackCommand.of("switch", stream = "gold", nowElapsedMs = 0L).desiredPlaying)
    }

    @Test
    fun `only a sleep timer set is resolved to a deadline`() {
        val set = PlaybackCommand.of(SleepTimerContract.ACTION_SET, minutes = 15, nowElapsedMs = 500L)
        assertEquals(500L + 15 * 60_000L, set.deadlineElapsedMs)

        for (action in listOf(
            SleepTimerContract.ACTION_CANCEL,
            SleepTimerContract.ACTION_UNDO,
            SleepTimerContract.ACTION_SYNC,
            "play",
            "startStop",
        )) {
            assertNull("$action carries no deadline", PlaybackCommand.of(action, nowElapsedMs = 500L).deadlineElapsedMs)
        }
    }

    /**
     * A command with awkward characters in it - the artist and title come from a
     * stream's own metadata feed - has to come back as itself, which is what the
     * record's escaping is for.
     */
    @Test
    fun `a record survives the characters that would break a positional format`() {
        val artist = "AC%\tDC\n\"AC/DC\"" //tab, newline, percent, quotes
        val song = "Back in Black\r\n"
        process().enqueue(PlaybackCommand.of("switch_track", artist = artist, song = song, nowElapsedMs = 0L))

        val command = process().pending().single().command
        assertEquals(artist, command.artist)
        assertEquals(song, command.song)
    }

    // ==================== the wiring a JVM test can read ====================

    /**
     * The start is requested *after* the record is written, in the one place a
     * command meets a start, and the record is taken back when the platform refuses
     * that start. Both halves are asserted against the source because the ordering
     * between them is the property, and a JVM test cannot make a real start.
     */
    @Test
    fun `the command is written before the start and taken back when the start is refused`() {
        val source = file("src/main/java/com/example/musicplayerapp/utils/ServiceUtils.kt").readText()

        val enqueue = source.indexOf("inbox.enqueue(command)")
        val startForeground = source.indexOf("startForegroundService(context, intent)")
        val start = source.indexOf("context.startService(intent)")
        val withdraw = source.indexOf("inbox.withdraw(queued.id)")

        assertTrue("the inbox is written in ServiceUtils", enqueue >= 0)
        assertTrue("a command is recorded before the foreground start", enqueue < startForeground)
        assertTrue("a command is recorded before the plain start", enqueue < start)
        assertTrue("a refused start takes its command back out", withdraw > enqueue)
    }

    /**
     * Item 1, at the caller: the start is requested only for a command that was accepted,
     * and the answer decides the return value the UI shows. Both are properties of the
     * order and the branch, which is what a JVM test can read - a real start is a device
     * test (`PlaybackCommandHandoffTest`).
     */
    @Test
    fun `a command that was not stored is never started for`() {
        val source = file("src/main/java/com/example/musicplayerapp/utils/ServiceUtils.kt").readText()

        val enqueue = source.indexOf("val queued = inbox.enqueue(command)")
        val refused = source.indexOf("if (queued == null)")
        val start = source.indexOf("context.startService(intent)")

        assertTrue("the record is written first", enqueue >= 0)
        assertTrue("and a write that did not commit stops here", refused > enqueue)
        assertTrue("before any start is requested", refused < start)
    }

    /** `commit`, not `apply`: a write that returns before the file does is not durable. */
    @Test
    fun `the production slot commits synchronously`() {
        val source = source("service/PlaybackCommandInbox.kt")

        assertTrue("the inbox must commit, not apply", source.contains("editor.commit()"))
        assertTrue(
            "and the commit's answer is what the slot reports - a discarded result is a " +
                "write the queue would report as successful without knowing",
            source.contains("return editor.commit()"),
        )
        assertFalse("apply() returns before the file is written", source.contains(".apply()"))
    }

    /**
     * Item 2, item 7 and item 10, as properties of the service's start path.
     *
     * The null-intent restart cannot be staged by a test - a process death cannot be
     * performed from inside the process being killed, and nothing else sends a null
     * intent (see `PlaybackIntentContract`) - so the ordering is asserted against the
     * source, the way the write-before-start order above is. What is being held:
     *
     *  - the inbox is drained *before* the sticky restore is evaluated, so a pending
     *    Stop or switch has already changed the durable state the restore reads;
     *  - a pass that left a command undelivered defers the restore entirely, because
     *    that command is newer than the state it would be restoring;
     *  - the obligation to promote to foreground is part of the per-command pass
     *    (`prepare`), not a snapshot taken before it;
     *  - and the drain has exactly one caller - the pass the service's start path drives -
     *    which is what makes "one head at a time" a property of the main thread rather
     *    than something a second thread could interleave with.
     */
    @Test
    fun `the service applies pending commands before it evaluates the sticky restore`() {
        val service = source("service/MediaPlayerService.kt")

        val drain = service.indexOf("pass.run(inbox, prepare = ::prepareHeadCommand)")
        val stillPending = service.indexOf("val stillPending = inbox.pending()")
        val guard = service.indexOf("if (stillPending.isEmpty()) {")
        val restore = service.indexOf("restorePlaybackIntent(\"sticky_restart\")")
        val deferral = service.indexOf("\"PLAYBACK_INTENT_DEFERRED\"")

        assertTrue("the start path drains the inbox, through the pass", drain >= 0)
        assertTrue("and reads the queue again afterwards", stillPending > drain)
        assertTrue("the sticky restore is decided on what is left", guard > stillPending)
        assertTrue("and only runs when nothing is left", restore > guard)
        assertTrue(
            "a command that was not delivered defers the restore rather than losing to it",
            deferral > restore,
        )
        assertTrue(
            "the foreground obligation is part of the per-command pass, not a snapshot " +
                "taken before it",
            service.contains("private fun prepareHeadCommand(") &&
                service.contains("pass.run(inbox, prepare = ::prepareHeadCommand)"),
        )
        assertTrue(
            "and the pass asks about each head before it runs it",
            source("service/PlaybackCommandInbox.kt").contains("if (!prepare(entry)) {"),
        )

        assertEquals(
            "one pass, on the start path, and it is the pass class that runs it: no other " +
                "thread may take the same head",
            1,
            Regex("""inbox\.drain\(""").findAll(source("service/PlaybackCommandPass.kt")).count(),
        )
    }

    /**
     * One writer, one reader, and both inside the app: a command can only be added
     * by the app's own screens and can only be consumed by its own service.
     */
    @Test
    fun `the inbox has exactly two ends, one in the app's screens and one in the service`() {
        val reachingIt = productionFiles()
            .filter { it.readText().contains("PlaybackCommandInbox.forContext(") }
            .map { it.name }
            .toSortedSet()

        assertEquals(
            "a command is added by the one door that also starts the service, and consumed " +
                "by the service's start path - nothing else in the app touches the inbox",
            sortedSetOf("MediaPlayerService.kt", "ServiceUtils.kt"),
            reachingIt,
        )

        val screens = source("utils/ServiceUtils.kt")
        assertTrue(
            "the adding end",
            screens.contains("PlaybackCommandInbox.forContext(context)") && screens.contains(".enqueue(command)"),
        )
        assertTrue(
            "and the end that takes a refused start back out",
            screens.contains(".withdraw(queued.id)"),
        )

        val service = source("service/MediaPlayerService.kt")
        assertTrue("the consuming end reads the inbox", service.contains("inbox.pending()"))
        assertTrue(
            "and hands it to the one pass that acknowledges",
            service.contains("pass.run(inbox, prepare = ::prepareHeadCommand)") &&
                source("service/PlaybackCommandPass.kt").contains("inbox.drain(prepare = prepare) {"),
        )
    }

    // ==================== not a thing a backup may carry ====================

    /**
     * One device's gesture at one device's speaker. Restored onto a new phone it
     * would be an instruction nobody gave there, and the exclusion has to be in
     * both rule files: `fullBackupContent` is API 24-30, `dataExtractionRules` is
     * 31+ and has a separate section per direction data can leave in.
     */
    @Test
    fun `the inbox is excluded from cloud backup and device transfer`() {
        val excluded = "sharedpref:${PlaybackCommandInbox.FILE}.xml"
        assertEquals("myata_playback_commands", PlaybackCommandInbox.FILE)

        assertTrue(
            "$excluded must be excluded on API 24-30",
            excluded in excludes("src/main/res/xml/lastfm_backup_rules.xml"),
        )

        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(file("src/main/res/xml/lastfm_data_extraction_rules.xml"))
        for (section in listOf("cloud-backup", "device-transfer")) {
            val nodes = doc.getElementsByTagName(section)
            assertEquals("one <$section>", 1, nodes.length)
            val found = excludes(nodes.item(0) as Element)
            assertTrue("$excluded must be excluded from <$section>, found $found", excluded in found)
        }
    }

    // ============================ plumbing ============================

    private fun excludes(path: String): List<String> =
        excludes(
            DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(file(path))
                .documentElement,
        )

    private fun excludes(root: Element): List<String> {
        val nodes = root.getElementsByTagName("exclude")
        return (0 until nodes.length).map {
            val e = nodes.item(it) as Element
            "${e.getAttribute("domain")}:${e.getAttribute("path")}"
        }
    }

    private fun productionFiles(): List<File> =
        File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun source(path: String): String =
        file("src/main/java/com/example/musicplayerapp/$path").readText()

    /** Unit tests run from the module directory; the positive control is that the file exists. */
    private fun file(path: String): File = File(path).also {
        assertTrue("expected ${it.absolutePath} to exist", it.isFile)
    }
}
