package com.example.musicplayerapp.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Domain-aware supersession, on the JVM.
 *
 * ## The P1 this closes
 *
 * The queue is durable and FIFO, and both matter: a record is a listener's gesture and
 * the order they made the gestures in is part of what they meant. FIFO alone left one
 * hole, and it was the last P1 of this slice. A critical command at the head that can
 * never be delivered - a Play whose handler throws every time, a Play whose foreground
 * promotion Android keeps refusing - stood in front of every later gesture for the life
 * of the install. The listener pressed Stop, and the radio did not stop.
 *
 * The fix is not "drop critical commands after N failures" and not a reordered queue: it
 * is that a newer command in the *same logical domain* replaces what an older one meant.
 * [PlaybackDomain] defines the domains, and the record does not even need a new field for
 * it - a command's generation in its domain is the sequence it is stored and acknowledged
 * under, and the domain's newest generation is one more key in the same app-private file,
 * written in the same commit as the command that advanced it.
 *
 * ## What is real here
 *
 * The same store as `PlaybackCommandChannelTest`: [FaithfulPrefs] on a [QueueFile], so a
 * new [PlaybackCommandInbox] over a new process's view of that file reads records that
 * outlived the old one, and so "the commit failed" is the disk refusing the edit *after*
 * the change has landed in the process's map - which is what Android does, and what the
 * rollback in [PlaybackCommandInbox.TransactionalSlot] is for. The Android half - a real
 * `SharedPreferences` commit, a real service start, and the notification and durable
 * intent a real handler writes - is `PlaybackCommandHandoffTest` on a device.
 */
class PlaybackCommandSupersessionTest {

    private val file = QueueFile()

    /** The device under the store, as the process making the gestures sees it. */
    private val raw = FaithfulPrefs(file)

    /** The one transaction boundary every read and write below goes through. */
    private val store = PlaybackCommandInbox.TransactionalSlot(raw)

    /** The queue as the process that made the gestures sees it. */
    private fun process() = PlaybackCommandInbox(store)

    /** The queue as a process that has not run yet sees it: [file], and nothing else. */
    private fun newProcess() = PlaybackCommandInbox(PlaybackCommandInbox.TransactionalSlot(FaithfulPrefs(file)))

    private fun pendingIds(): List<String> = process().pending().map { it.id }

    private fun pendingActions(): List<String> = process().pending().map { it.command.action }

    /** What one pass over the queue did, and what it was offered. */
    private class Pass {
        val prepared = mutableListOf<PlaybackCommandInbox.Entry>()
        val ran = mutableListOf<PlaybackCommandInbox.Entry>()

        val preparedActions: List<String> get() = prepared.map { it.command.action }
        val ranActions: List<String> get() = ran.map { it.command.action }
        val ranIds: List<String> get() = ran.map { it.id }
    }

    /**
     * One pass, exactly as the service makes it: [fails] names the actions whose handler
     * throws, and [agreeToPrepare] answers the foreground obligation for the head.
     */
    private fun drainPass(
        fails: Set<String> = emptySet(),
        agreeToPrepare: (PlaybackCommandInbox.Entry) -> Boolean = { true },
    ): Pass {
        val pass = Pass()
        process().drain(prepare = { entry -> pass.prepared += entry; agreeToPrepare(entry) }) { entry ->
            pass.ran += entry
            if (entry.command.action in fails) throw IllegalStateException("this handler cannot run")
        }
        return pass
    }

    private fun enqueuePlay(stream: String = "gold", openForeground: Boolean = false) =
        process().enqueue(
            PlaybackCommand.of(
                "play", stream = stream, openForeground = openForeground, nowElapsedMs = 0L,
            ),
        )!!

    private fun enqueueSwitch(stream: String) =
        process().enqueue(
            PlaybackCommand.of("switch", stream = stream, forcePlay = true, nowElapsedMs = 0L),
        )!!

    private fun enqueueTimerSet(minutes: Int, bootId: Int = 7) =
        process().enqueue(
            PlaybackCommand.of(
                SleepTimerContract.ACTION_SET, minutes = minutes,
                nowElapsedMs = 0L, bootId = bootId,
            ),
        )!!

    private fun enqueueStop() = process().enqueue(PlaybackCommand.of("stop", nowElapsedMs = 0L))!!

    private fun enqueueUndo() =
        process().enqueue(PlaybackCommand.of(SleepTimerContract.ACTION_UNDO, nowElapsedMs = 0L))!!

    private fun enqueueCancel() =
        process().enqueue(PlaybackCommand.of(SleepTimerContract.ACTION_CANCEL, nowElapsedMs = 0L))!!

    // ==================== the failing head, and the intent that replaces it ====================

    /**
     * The first example the review proved: a Play whose handler always fails is a Stop that
     * cannot execute. The Stop is what the listener asked for *now*, so the pass that finds
     * it removes the Play it replaced rather than offering it again.
     */
    @Test
    fun `a failing play does not block the stop that replaced it`() {
        val play = enqueuePlay()

        val first = drainPass(fails = setOf("play"))
        assertEquals("the pass stops at the failing head", listOf("play"), first.ranActions)
        assertEquals(listOf(play.id), pendingIds())

        val stop = enqueueStop()

        val second = drainPass(fails = setOf("play"))
        assertEquals("the newer command is the one that runs", listOf("stop"), second.ranActions)
        assertEquals(
            "and it is the only one offered to the foreground gate",
            listOf(stop.id),
            second.prepared.map { it.id },
        )
        assertTrue("the superseded play is gone", process().pending().isEmpty())
    }

    @Test
    fun `a failing switch does not block the stop that replaced it`() {
        val b = enqueueSwitch("gold")

        drainPass(fails = setOf("switch"))
        assertEquals(listOf(b.id), pendingIds())

        val stop = enqueueStop()
        val pass = drainPass(fails = setOf("switch"))

        assertEquals(listOf("stop"), pass.ranActions)
        assertEquals(listOf(stop.id), pass.ranIds)
    }

    /**
     * The foreground case, exactly as the review asked for it: the Play is refused
     * promotion, and the newer Stop must be recognised as newer *before* the stale Play is
     * offered to the gate again. The gate is the witness - it is recorded, so "it was not
     * asked a second time" is an assertion rather than an inference.
     */
    @Test
    fun `a refused foreground promotion is not attempted again for a superseded play`() {
        val play = enqueuePlay(openForeground = true)

        val refused = Pass()
        process().drain(prepare = { refused.prepared += it; false }) { refused.ran += it }
        assertEquals(
            "the gate was asked once, and the handler never ran",
            listOf(play.id),
            refused.prepared.map { it.id },
        )
        assertTrue(refused.ran.isEmpty())
        assertEquals(listOf(play.id), pendingIds())

        val stop = enqueueStop()
        val pass = drainPass()

        assertEquals(
            "the stale play is not offered to the foreground gate again",
            listOf(stop.id),
            pass.prepared.map { it.id },
        )
        assertEquals(listOf("stop"), pass.ranActions)
        assertTrue(process().pending().isEmpty())
    }

    /**
     * The other examples: the newest explicit timer action escapes a stale pending one. The
     * stale command's *handler* never runs, which is what makes it safe for the undo
     * snapshot - a command that was never executed cannot consume or manufacture one.
     */
    @Test
    fun `a failing timer set does not block the cancel that replaced it`() {
        enqueueTimerSet(minutes = 30)
        drainPass(fails = setOf(SleepTimerContract.ACTION_SET))

        val cancel = enqueueCancel()
        val pass = drainPass(fails = setOf(SleepTimerContract.ACTION_SET))

        assertEquals(listOf(SleepTimerContract.ACTION_CANCEL), pass.ranActions)
        assertEquals(listOf(cancel.id), pass.ranIds)
        assertTrue(process().pending().isEmpty())
    }

    @Test
    fun `a failing timer undo cannot block the set that replaced it`() {
        val undo = enqueueUndo()
        drainPass(fails = setOf(SleepTimerContract.ACTION_UNDO))

        val set = enqueueTimerSet(minutes = 45)
        val pass = drainPass(fails = setOf(SleepTimerContract.ACTION_UNDO))

        assertEquals(listOf(SleepTimerContract.ACTION_SET), pass.ranActions)
        assertEquals(
            "the stale undo must not reach a handler: a command that never ran cannot put a timer back",
            listOf(set.id),
            pass.ranIds,
        )
        assertEquals(45, pass.ran.single().command.minutes)
        assertFalse("and it is not offered to the gate either", undo.id in pass.prepared.map { it.id })
    }

    @Test
    fun `a failing timer undo cannot block a cancel`() {
        enqueueUndo()
        drainPass(fails = setOf(SleepTimerContract.ACTION_UNDO))

        val cancel = enqueueCancel()
        val pass = drainPass(fails = setOf(SleepTimerContract.ACTION_UNDO))

        assertEquals(listOf(SleepTimerContract.ACTION_CANCEL), pass.ranActions)
        assertEquals(listOf(cancel.id), pass.ranIds)
    }

    // ==================== the newest accepted command wins ====================

    /** Play, then Stop, then Play: the listener's last word is a Play. */
    @Test
    fun `the newest playback command wins whatever the ones before it said`() {
        enqueuePlay(stream = "myata")
        enqueueStop()
        val last = enqueuePlay(stream = "gold")

        val pass = drainPass()

        assertEquals(listOf("play"), pass.ranActions)
        assertEquals(listOf(last.id), pass.ranIds)
        assertEquals("and it is the station the last command named", "gold", pass.ran.single().command.stream)
        assertTrue(process().pending().isEmpty())
    }

    @Test
    fun `the newest switch wins and the station it names is the one that runs`() {
        enqueueSwitch("gold")
        val c = enqueueSwitch("myata_hits")

        val pass = drainPass()

        assertEquals(listOf("switch"), pass.ranActions)
        assertEquals(listOf(c.id), pass.ranIds)
        assertEquals("myata_hits", pass.ran.single().command.stream)
    }

    @Test
    fun `the newest timer set wins`() {
        enqueueTimerSet(minutes = 15)
        val b = enqueueTimerSet(minutes = 45)

        val pass = drainPass()

        assertEquals(listOf(SleepTimerContract.ACTION_SET), pass.ranActions)
        assertEquals(listOf(b.id), pass.ranIds)
        assertEquals(45, pass.ran.single().command.minutes)
    }

    /**
     * The newest playback command wins even when the older one is a Play and the newer one
     * is a station switch: the switch is a statement about the same thing - what should be
     * playing - and it is the later one.
     */
    @Test
    fun `a newer switch supersedes an older play`() {
        enqueuePlay(stream = "myata")
        val switch = enqueueSwitch("gold")

        val pass = drainPass()

        assertEquals(listOf("switch"), pass.ranActions)
        assertEquals(listOf(switch.id), pass.ranIds)
    }

    // ==================== the domains stay apart ====================

    /**
     * The cross-domain requirement, exactly: the newer Stop supersedes the older Play and
     * must not take the Timer Set with it. The drain walks past what is stale and runs what
     * is left, in the order the listener made it.
     */
    @Test
    fun `a newer stop supersedes an older play and leaves the timer command alone`() {
        enqueuePlay(stream = "myata")
        val timer = enqueueTimerSet(minutes = 30)
        val stop = enqueueStop()

        val pass = drainPass()

        assertEquals(
            "the stale play is skipped, and the two survivors keep their order",
            listOf(SleepTimerContract.ACTION_SET, "stop"),
            pass.ranActions,
        )
        assertEquals(listOf(timer.id, stop.id), pass.ranIds)
        assertEquals(
            "the timer command is what the listener chose, down to its duration",
            30,
            pass.ran.first().command.minutes,
        )
        assertEquals(
            "and to the instant it was resolved to, and the boot that instant belongs to",
            listOf(timer.command.deadlineElapsedMs, timer.command.deadlineBootId),
            listOf(
                pass.ran.first().command.deadlineElapsedMs,
                pass.ran.first().command.deadlineBootId,
            ),
        )
        assertTrue(process().pending().isEmpty())
    }

    /** And the other direction: a newer timer command supersedes the timer, not the Play. */
    @Test
    fun `a newer timer cancel supersedes an older timer set and leaves playback alone`() {
        enqueueTimerSet(minutes = 30)
        val play = enqueuePlay(stream = "gold")
        val cancel = enqueueCancel()

        val pass = drainPass()

        assertEquals(listOf("play", SleepTimerContract.ACTION_CANCEL), pass.ranActions)
        assertEquals(listOf(play.id, cancel.id), pass.ranIds)
    }

    /**
     * Nothing about a status command is a choice, so nothing supersedes it and it
     * supersedes nothing - even standing between two playback commands that do.
     */
    @Test
    fun `a status command is never superseded and never supersedes`() {
        enqueuePlay(stream = "myata")
        val status = process().enqueue(PlaybackCommand.of("get_status", nowElapsedMs = 0L))!!
        enqueueStop()

        val pass = drainPass()

        assertEquals(
            "the play is superseded and the read is not: both survivors run, in order",
            listOf("get_status", "stop"),
            pass.ranActions,
        )
        assertEquals(listOf(status.id), pass.ran.take(1).map { it.id })
    }

    // ==================== the executing-command race ====================

    /**
     * A command that is already being handled is still on the disk - the acknowledgement
     * comes after the handler - so the newer command the listener makes in that window is
     * enqueued while the older one is running. The acknowledgement names the older one and
     * only the older one, so the newer command is left exactly where it is.
     */
    @Test
    fun `acknowledging the command that is running cannot remove the newer one`() {
        val running = enqueuePlay()
        val newer = enqueueStop()

        assertTrue("the running command is acknowledged by its own id", process().ack(running.id))

        assertEquals(
            "the newer record is a different key and the acknowledgement cannot reach it",
            listOf(newer.id),
            pendingIds(),
        )
        assertEquals(listOf("stop"), drainPass().ranActions)
    }

    /**
     * And the newer command is the pass's work as well as the next start's: the pass
     * re-reads the head between commands, so a gesture made while a handler runs is not
     * left for a start that may never come.
     */
    @Test
    fun `a newer command enqueued while one is running executes afterwards`() {
        process().enqueue(PlaybackCommand.of("play", stream = "myata", nowElapsedMs = 0L))

        val ran = mutableListOf<String>()
        process().drain { entry ->
            ran += entry.command.action
            if (entry.command.action == "play") {
                process().enqueue(PlaybackCommand.of("stop", nowElapsedMs = 0L))
            }
        }

        assertEquals(listOf("play", "stop"), ran)
        assertTrue(process().pending().isEmpty())
    }

    /**
     * The half that supersession is *for*: the running command's handler fails after the
     * newer one arrived. The pass ends at the failure, and the next one does not retry the
     * command that was replaced - it removes it and runs the newer one.
     */
    @Test
    fun `a running command that then fails is not retried in front of the newer one`() {
        val running = enqueuePlay()

        val first = Pass()
        process().drain(prepare = { first.prepared += it; true }) { entry ->
            first.ran += entry
            process().enqueue(PlaybackCommand.of("stop", nowElapsedMs = 0L))
            throw IllegalStateException("this handler cannot run")
        }
        assertEquals("the newer command arrived too late for this pass", listOf(running.id), first.ranIds)
        assertEquals("both records are still on disk", 2, pendingIds().size)

        val second = drainPass()

        assertEquals("the newer intent runs instead of the old one being retried", listOf("stop"), second.ranActions)
        assertTrue(process().pending().isEmpty())
    }

    // ==================== the commits have to say yes ====================

    /**
     * The generation and the record commit together, so a commit that failed leaves
     * neither: the older command is exactly as valid as it was, and the newer one was never
     * accepted. A queue that advanced the generation in memory - or in a second write -
     * would have made the older command stale on behalf of a command that does not exist.
     */
    @Test
    fun `a commit that fails while advancing a generation leaves the older command valid`() {
        val play = enqueuePlay()
        val before = raw.onDisk()

        raw.failedCommits = 1
        assertNull(
            "a write that did not reach the disk is not an accepted command",
            process().enqueue(PlaybackCommand.of("stop", nowElapsedMs = 0L)),
        )

        assertEquals("nothing at all moved on the disk", before, raw.onDisk())
        assertEquals(
            "and nothing was left in the process's map for a later write to make durable",
            before,
            raw.visible(),
        )
        val pass = drainPass()
        assertEquals("the older command is still the intent", listOf("play"), pass.ranActions)
        assertEquals(listOf(play.id), pass.ranIds)
    }

    /**
     * A superseded record that cannot be removed is not skipped. It is still on the disk, so
     * the pass ends with it in place and the next one recognises it as stale by the same
     * comparison - the alternative would be running the newer command behind a record that
     * is still there to be read again.
     */
    @Test
    fun `a superseded command that could not be removed is recognised as stale again`() {
        val play = enqueuePlay(openForeground = true)
        val stop = enqueueStop()

        raw.failedCommits = 1
        val blocked = drainPass()
        assertEquals("nothing ran", emptyList<String>(), blocked.ranActions)
        assertEquals(
            "and the stale record was not promoted to the foreground",
            emptyList<String>(),
            blocked.preparedActions,
        )
        assertEquals(listOf(play.id, stop.id), pendingIds())

        val pass = drainPass()
        assertEquals(
            "stale again on the next pass, and this time it goes",
            listOf(stop.id),
            pass.prepared.map { it.id },
        )
        assertEquals(listOf("stop"), pass.ranActions)
        assertTrue(process().pending().isEmpty())
    }

    /**
     * `withdraw`'s half of it: a start the platform refused means the command never
     * happened, so it must not leave a supersession behind either. The listener's Play is
     * still their intent, and the Stop that was taken back out is not what replaces it.
     */
    @Test
    fun `a withdrawn command takes its own supersession with it`() {
        val play = enqueuePlay()
        val stop = enqueueStop()

        assertEquals(
            PlaybackCommandInbox.Withdraw.REMOVED_PENDING,
            process().withdraw(stop.id),
        )

        val pass = drainPass()
        assertEquals(
            "a gesture whose start never happened cannot have superseded the one before it",
            listOf("play"),
            pass.ranActions,
        )
        assertEquals(listOf(play.id), pass.ranIds)
        assertTrue(process().pending().isEmpty())
    }

    /**
     * And the other half: a withdrawal cannot reach back past a command accepted after it.
     * The later command owns the domain's newest generation, so the older one takes nothing
     * back with it.
     */
    @Test
    fun `withdrawing an older command does not resurrect it in front of a newer one`() {
        val play = enqueuePlay()
        val stop = enqueueStop()

        assertEquals(PlaybackCommandInbox.Withdraw.REMOVED_PENDING, process().withdraw(play.id))

        assertEquals(listOf(stop.id), pendingIds())
        assertEquals(listOf("stop"), drainPass().ranActions)
    }

    // ==================== no newer intent, no removal ====================

    /**
     * The other side of the rule, and the reason it is not "drop what fails": with no newer
     * command in its domain, a critical head that fails forever is kept, and everything
     * behind it waits with it. Only a newer *intent* may retire a command.
     */
    @Test
    fun `a failing critical head with no newer intent is not discarded`() {
        val play = enqueuePlay()
        val timer = enqueueTimerSet(minutes = 30)

        repeat(PlaybackCommandInbox.MAX_HANDLER_ATTEMPTS * 4) {
            val pass = drainPass(fails = setOf("play"))
            assertEquals("the pass always stops at the head", listOf("play"), pass.ranActions)
        }

        assertEquals(
            "kept, and everything behind it with it",
            listOf(play.id, timer.id),
            pendingIds(),
        )
    }

    // ==================== durable, not remembered ====================

    /**
     * The generation is a record, not a field in a live process. Two more processes over the
     * same file - one that made the second command, and one that drains both - reach the
     * same answer, and the domain's newest generation is on the disk beside the commands it
     * decides about.
     */
    @Test
    fun `supersession survives the process that made the commands`() {
        enqueuePlay(stream = "myata")
        val stop = newProcess().enqueue(PlaybackCommand.of("stop", nowElapsedMs = 0L))!!

        assertEquals(
            "the domain's newest generation is durable, and it is the command that advanced it",
            stop.id,
            raw.onDisk()["generation_playback"],
        )

        val pass = Pass()
        newProcess().drain(prepare = { pass.prepared += it; true }) { pass.ran += it }

        assertEquals(listOf("stop"), pass.ranActions)
        assertEquals(listOf(stop.id), pass.ranIds)
    }

    /**
     * A read is a read: [PlaybackCommandInbox.pending] changes nothing, so a superseded
     * command is still listed until a pass retires it. That is where the decision belongs -
     * the pass is the one place a command is prepared, handled and acknowledged.
     */
    @Test
    fun `the supersession decision belongs to the pass, not to a read`() {
        enqueuePlay()
        enqueueStop()

        assertEquals(listOf("play", "stop"), pendingActions())
        assertEquals(listOf("play", "stop"), pendingActions())

        assertEquals(listOf("stop"), drainPass().ranActions)
    }

    // ==================== the domains are the choices, exactly ====================

    /**
     * A supersedable command is a command the listener *chose*, and that is the same list
     * as [PlaybackCommand.critical] - not because the two rules are the same rule, but
     * because a choice is what a newer choice can replace and a restatement has nothing in
     * it to replace. Asserted here so neither list can drift.
     */
    @Test
    fun `the domains hold exactly the critical commands`() {
        val everyAction = listOf(
            "play",
            PlaybackCommand.ACTION_START_STOP,
            "stop",
            "switch",
            SleepTimerContract.ACTION_SET,
            SleepTimerContract.ACTION_CANCEL,
            SleepTimerContract.ACTION_UNDO,
            "switch_track",
            "get_status",
            SleepTimerContract.ACTION_SYNC,
            PlaybackIntentContract.ACTION_RESTORE,
            SystemPlaybackEventContract.ACTION_BECOMING_NOISY,
        )

        for (action in everyAction) {
            assertEquals(
                "$action: a supersedable command is exactly a choice",
                PlaybackCommand.isCritical(action),
                PlaybackDomain.of(action) != null,
            )
        }

        assertEquals(PlaybackDomain.PLAYBACK, PlaybackDomain.of("play"))
        assertEquals(PlaybackDomain.PLAYBACK, PlaybackDomain.of("stop"))
        assertEquals(PlaybackDomain.TIMER, PlaybackDomain.of(SleepTimerContract.ACTION_UNDO))
        assertNull("a status read is in no domain", PlaybackDomain.of(SleepTimerContract.ACTION_SYNC))
        assertNull(PlaybackDomain.of("switch_track"))
        assertNull(PlaybackDomain.of("get_status"))
    }

    // ==================== only the app's own callers can advance one ====================

    /**
     * A generation is advanced by [PlaybackCommandInbox.enqueue] and by nothing else, and an
     * enqueue happens in one place: the app's own screens, writing app-private storage
     * through `ServiceUtils`. No start path produces a command - the service consumes them -
     * so no start, from this app or from any other, can supersede anything. The device half
     * of this is in `PlaybackCommandHandoffTest`.
     */
    @Test
    fun `only the app's own queue writes a generation`() {
        val writers = productionFiles()
            .filter { it.readText().contains("generation_") }
            .map { it.name }
            .toSortedSet()

        assertEquals(
            "the durable generation keys belong to the queue and to nothing else",
            sortedSetOf("PlaybackCommandInbox.kt"),
            writers,
        )

        val service = source("service/MediaPlayerService.kt")
        assertFalse(
            "the service consumes commands; a start path that could enqueue one would be a " +
                "start path that could supersede one",
            service.contains(".enqueue("),
        )
        assertFalse(
            "and the start intent still carries nothing that could name a domain or a generation",
            source("utils/ServiceUtils.kt").contains("generation"),
        )
    }

    // ============================ plumbing ============================

    private fun productionFiles(): List<File> =
        File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun source(path: String): String =
        file("src/main/java/com/example/musicplayerapp/$path").readText()

    private fun file(path: String): File = File(path).also {
        assertTrue("expected ${it.absolutePath} to exist", it.isFile)
    }
}
