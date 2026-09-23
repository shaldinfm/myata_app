package com.example.musicplayerapp.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pass, on the JVM: which commands reach their handler, and what the answer about
 * stickiness is allowed to do to that.
 *
 * ## The P1 this file holds shut
 *
 * `keepSticky` and `handleOneCommand` used to be one expression -
 * `keepSticky = keepSticky && handleOneCommand(entry)` - so the first command that answered
 * "do not keep me" also stopped every command behind it from being *offered to its handler*,
 * while the inbox went on acknowledging them. A station-less `switch` followed by a timer
 * set therefore consumed the timer set and armed nothing: the listener's gesture was
 * recorded as delivered and no timer existed.
 *
 * The two are separate now: [CommandHandling] is what a handled command answers, and
 * [PlaybackCommandPass] folds that answer *after* the handler has run. The tests below are
 * the two halves - a command behind a station-less switch still runs and is still
 * acknowledged, and a handler that genuinely fails still stops the pass without an
 * acknowledgement.
 *
 * ## What is real here
 *
 * The queue is the real one ([PlaybackCommandInbox]) over `FaithfulPrefs`, the fake device
 * the rest of this suite uses, and the rule the service itself applies to a record is the
 * real one ([CommandHandling.forCommand]) rather than a copy of it. What is not exercised
 * is the *effect* of each command on the player, which needs a device
 * (`PlaybackCommandHandoffTest`).
 */
class PlaybackCommandPassTest {

    private val file = QueueFile()
    private val store = PlaybackCommandInbox.TransactionalSlot(FaithfulPrefs(file))

    private fun process() = PlaybackCommandInbox(store)

    private fun actions(): List<String> = process().pending().map { it.command.action }

    /** The service's own answer for a command, as the pass reads it. */
    private fun answer(entry: PlaybackCommandInbox.Entry): CommandHandling =
        CommandHandling.forCommand(entry.command)

    // ==================== the answer itself ====================

    /**
     * The rule, in full: one command releases the service, and it is the one that names no
     * station. Everything else - including a `switch` that *does* name one - keeps it.
     */
    @Test
    fun `only a station-less switch answers do-not-keep-me`() {
        assertFalse(
            "a switch without a station is the gesture that names nothing",
            CommandHandling.forCommand(PlaybackCommand.of("switch", stream = null, nowElapsedMs = 0L)).keepSticky,
        )
        assertTrue(
            "a switch that names a station is a change of station like any other",
            CommandHandling.forCommand(PlaybackCommand.of("switch", stream = "gold", nowElapsedMs = 0L)).keepSticky,
        )

        for (action in listOf(
            "play",
            PlaybackCommand.ACTION_START_STOP,
            "stop",
            "switch_track",
            "get_status",
            SleepTimerContract.ACTION_SET,
            SleepTimerContract.ACTION_CANCEL,
            SleepTimerContract.ACTION_UNDO,
            SleepTimerContract.ACTION_SYNC,
        )) {
            assertTrue(
                "$action leaves the service sticky",
                CommandHandling.forCommand(PlaybackCommand.of(action, nowElapsedMs = 0L)).keepSticky,
            )
        }
    }

    // ==================== the commands behind it ====================

    /**
     * The station-less switch itself: handled, acknowledged, and the start it woke is not
     * held on to.
     */
    @Test
    fun `a station-less switch is handled and releases stickiness`() {
        val inbox = process()
        inbox.enqueue(PlaybackCommand.of("switch", stream = null, nowElapsedMs = 0L))

        val ran = mutableListOf<String>()
        val pass = PlaybackCommandPass { entry -> ran += entry.command.action; answer(entry) }
        pass.run(inbox)

        assertEquals(listOf("switch"), ran)
        assertFalse("the start is not kept", pass.keepSticky)
        assertTrue("and the switch is acknowledged like any other command", process().pending().isEmpty())
    }

    /**
     * The review's case, exactly: the timer set behind a station-less switch is *executed*,
     * both commands are acknowledged, and the start is still one that does not keep the
     * service.
     */
    @Test
    fun `a timer set behind a station-less switch is still executed`() {
        val inbox = process()
        inbox.enqueue(PlaybackCommand.of("switch", stream = null, nowElapsedMs = 0L))
        inbox.enqueue(PlaybackCommand.of(SleepTimerContract.ACTION_SET, minutes = 15, nowElapsedMs = 0L))

        val ran = mutableListOf<String>()
        val pass = PlaybackCommandPass { entry -> ran += entry.command.action; answer(entry) }
        pass.run(inbox)

        assertEquals(
            "the timer set behind the switch is the listener's gesture and has to run",
            listOf("switch", SleepTimerContract.ACTION_SET),
            ran,
        )
        assertTrue("both commands are acknowledged", process().pending().isEmpty())
        assertFalse("and a later command does not resurrect the service", pass.keepSticky)
    }

    /**
     * Something the listener asked for behind the switch is never consumed unrun, however
     * many commands are queued: FIFO, every eligible one, and only then the acknowledgement
     * of the whole pass.
     */
    @Test
    fun `every command behind a station-less switch runs in order`() {
        val inbox = process()
        inbox.enqueue(PlaybackCommand.of("switch", stream = null, nowElapsedMs = 0L))
        inbox.enqueue(PlaybackCommand.of(SleepTimerContract.ACTION_SET, minutes = 15, nowElapsedMs = 0L))
        inbox.enqueue(PlaybackCommand.of("switch_track", artist = "A", song = "B", nowElapsedMs = 0L))
        inbox.enqueue(PlaybackCommand.of("get_status", nowElapsedMs = 0L))

        val ran = mutableListOf<String>()
        val pass = PlaybackCommandPass { entry -> ran += entry.command.action; answer(entry) }
        pass.run(inbox)

        assertEquals(
            listOf(
                "switch",
                SleepTimerContract.ACTION_SET,
                "switch_track",
                "get_status",
            ),
            ran,
        )
        assertTrue(process().pending().isEmpty())
        assertFalse(pass.keepSticky)
    }

    /**
     * And the answers a pass produces are not each other's business: a command that keeps
     * the service is handled and acknowledged next to one that did not, and the start is
     * still not sticky at the end of it.
     */
    @Test
    fun `handling and stickiness are decided separately`() {
        val inbox = process()
        val released = inbox.enqueue(PlaybackCommand.of("switch", stream = null, nowElapsedMs = 0L))!!
        val kept = inbox.enqueue(
            PlaybackCommand.of(SleepTimerContract.ACTION_SET, minutes = 15, nowElapsedMs = 0L),
        )!!

        val answers = mutableMapOf<String, Boolean>()
        val pass = PlaybackCommandPass { entry ->
            val handling = answer(entry)
            answers[entry.id] = handling.keepSticky
            handling
        }
        pass.run(inbox)

        assertFalse("the switch answered for the whole start", answers.getValue(released.id))
        assertTrue("the timer set answered for itself", answers.getValue(kept.id))
        assertFalse("and the start keeps the first answer", pass.keepSticky)
        assertTrue("both commands were delivered", process().pending().isEmpty())
    }

    // ==================== failure, which is not an answer ====================

    /**
     * A handler that fails is not a handler that answered: its command is not acknowledged,
     * everything behind it stays pending, and the pass stops. The existing policy for what
     * happens to the record from there - retried, or dropped once it has exhausted its
     * attempts - is the inbox's (`PlaybackCommandChannelTest`) and is unchanged.
     */
    @Test
    fun `a handler that fails is not acknowledged and stops the pass`() {
        val inbox = process()
        val failing = inbox.enqueue(PlaybackCommand.of("play", stream = "myata", nowElapsedMs = 0L))!!
        val behind = inbox.enqueue(
            PlaybackCommand.of(SleepTimerContract.ACTION_SET, minutes = 15, nowElapsedMs = 0L),
        )!!

        val attempted = mutableListOf<String>()
        val pass = PlaybackCommandPass { entry ->
            attempted += entry.command.action
            throw PlaybackCommandFailure("the handler could not run")
        }
        pass.run(inbox)

        assertEquals("the pass stops at the failure", listOf("play"), attempted)
        assertEquals(listOf(failing.id, behind.id), process().pending().map { it.id })
        assertTrue("a failure is not an answer about stickiness", pass.keepSticky)
    }

    /**
     * The one way the two are allowed to meet: a release that already happened stands, even
     * if a later handler fails. The service must not be held on to because a command behind
     * it went wrong.
     */
    @Test
    fun `a failure behind a release does not take the release back`() {
        val inbox = process()
        val released = inbox.enqueue(PlaybackCommand.of("switch", stream = null, nowElapsedMs = 0L))!!
        val failing = inbox.enqueue(
            PlaybackCommand.of(SleepTimerContract.ACTION_SET, minutes = 15, nowElapsedMs = 0L),
        )!!

        val pass = PlaybackCommandPass { entry ->
            if (entry.id == failing.id) throw PlaybackCommandFailure("the timer could not be armed")
            answer(entry)
        }
        pass.run(inbox)

        assertFalse("the switch's answer already stands", pass.keepSticky)
        assertEquals(
            "the command that failed is the one left behind",
            listOf(failing.id),
            process().pending().map { it.id },
        )
        assertTrue("and the switch was handled, so it is gone", released.id !in process().pending().map { it.id })
    }

    // ==================== the wiring a JVM test can read ====================

    /**
     * The pass is the service's, and it is the only place the queue is drained - which is
     * the property the inbox leans on to keep one pass at a time on one thread. The answer
     * is asserted not to be folded into the handling again, because that fold is the P1
     * this file exists for and it is invisible until two commands are queued.
     */
    @Test
    fun `the sticky answer is never a gate in front of a handler`() {
        val passSource = source("service/PlaybackCommandPass.kt")
        val service = source("service/MediaPlayerService.kt")

        assertEquals(
            "one pass, and it is this class that runs it",
            1,
            Regex("""inbox\.drain\(""").findAll(passSource).count(),
        )
        assertFalse(
            "the answer may not short-circuit the handler",
            passSource.contains("keepSticky &&"),
        )
        assertTrue(service.contains("val pass = PlaybackCommandPass(::handleOneCommand)"))
        assertTrue(service.contains("pass.run(inbox, prepare = ::prepareHeadCommand)"))
        assertTrue(service.contains("return if (keepSticky) START_STICKY else START_NOT_STICKY"))
        assertFalse(
            "and nothing in the service may gate a handler on the answer either",
            service.contains("keepSticky &&"),
        )
    }

    /**
     * The failure the second P1 is about, at the source that has to raise it: an undo whose
     * snapshot could not be consumed must not return normally, because a normal return is
     * the acknowledgement. The device test that drives it is in
     * `PlaybackCommandHandoffTest`.
     */
    @Test
    fun `an undo that could not be consumed is reported as a failure`() {
        val service = source("service/MediaPlayerService.kt")
        val handler = service.substringAfter("private fun undoSleepTimerCancel(")
            .substringBefore("\n    /**")

        val notConsumed = handler.indexOf("SleepTimerStore.Consumed.NotConsumed ->")
        val raised = handler.indexOf("throw PlaybackCommandFailure(")

        assertTrue("the branch exists", notConsumed >= 0)
        assertTrue("and it raises rather than returning", raised > notConsumed)
        assertFalse(
            "returning there would acknowledge a consumption that did not happen",
            Regex("""\breturn\b""").containsMatchIn(handler.substring(notConsumed, raised)),
        )
    }

    private fun source(path: String): String =
        File("src/main/java/com/example/musicplayerapp/$path").readText()
}
