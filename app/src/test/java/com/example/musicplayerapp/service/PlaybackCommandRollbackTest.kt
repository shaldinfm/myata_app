package com.example.musicplayerapp.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The persistence model of the durable inbox, on the JVM: what a failed commit does, what
 * putting it back costs, and what the queue does when that fails too.
 *
 * ## The P1 this closes
 *
 * `SharedPreferences.Editor.commit()` returning false does **not** mean the transaction
 * did not happen. The editor's changes are applied to the process's own map first and the
 * file is written afterwards, so a failed commit leaves the file alone and leaves the
 * change visible in this process - and the next commit that succeeds writes the whole map,
 * so the value that never reached the file can become durable later through a write that
 * has nothing to do with it.
 *
 * Everything the queue promises rests on that not being true. An enqueue that did not
 * commit must not be an accepted command *anywhere*: not in [PlaybackCommandInbox.pending],
 * not in the domain generation, and not sitting in the process's map waiting for the next
 * successful write to make it permanent. And an acknowledgement that did not commit must
 * leave its command exactly where it was, rather than disappearing from the logical queue
 * while the record lingers.
 *
 * The fix is [PlaybackCommandInbox.TransactionalSlot]: every write is a transaction with
 * an explicit rollback that is *verified* by reading the map back, and a rollback that
 * cannot be committed puts the store in a failure state where reads answer from the last
 * state the file is known to hold and every write is refused.
 *
 * ## What is real here
 *
 * [FaithfulPrefs] is the device - Android's own memory-first `SharedPreferences` - and the
 * transaction layer above it is production code, not a test double. Two refusals are
 * therefore the same thing: the write that did not reach the file, and the undo that was
 * supposed to take it back out. `failedCommits = 1` fails the first and lets the undo
 * through; `failedCommits = 2` fails both, which is the case that has to fail closed.
 */
class PlaybackCommandRollbackTest {

    private val file = QueueFile()

    /** The device, as the process making the gestures sees it. */
    private val raw = FaithfulPrefs(file)

    /** The one transaction boundary every read and write goes through. */
    private val store = PlaybackCommandInbox.TransactionalSlot(raw)

    private fun process() = PlaybackCommandInbox(store)

    private fun pendingIds(): List<String> = process().pending().map { it.id }

    private fun enqueuePlay(stream: String = "myata"): PlaybackCommandInbox.Entry =
        process().enqueue(PlaybackCommand.of("play", stream = stream, nowElapsedMs = 0L))!!

    private fun enqueueStop(): PlaybackCommandInbox.Entry =
        process().enqueue(PlaybackCommand.of("stop", nowElapsedMs = 0L))!!

    private fun enqueueTimer(minutes: Int): PlaybackCommandInbox.Entry =
        process().enqueue(
            PlaybackCommand.of(
                SleepTimerContract.ACTION_SET, minutes = minutes,
                nowElapsedMs = 0L, bootId = 7,
            ),
        )!!

    private class Pass {
        val prepared = mutableListOf<PlaybackCommandInbox.Entry>()
        val ran = mutableListOf<PlaybackCommandInbox.Entry>()
        val ranIds: List<String> get() = ran.map { it.id }
    }

    private fun drainPass(): Pass {
        val pass = Pass()
        process().drain(prepare = { pass.prepared += it; true }) { pass.ran += it }
        return pass
    }

    private fun generationOf(domain: String): String? = raw.onDisk()["generation_$domain"]

    // ==================== what the device actually does ====================

    /**
     * The property the whole file is about, pinned on the fake itself: a commit that does
     * not reach the file has *already* changed what this process reads, which is why the
     * queue cannot take the answer for "nothing happened". A fake that returned false
     * before touching its map would make every rollback test below pass vacuously.
     */
    @Test
    fun `a commit that did not reach the file is still visible in the process`() {
        raw.failedCommits = 1

        assertFalse(raw.edit(mapOf("command_1" to "a record")))

        assertEquals("the file never got it", emptyMap<String, String>(), raw.onDisk())
        assertEquals(
            "and this process's map did - which is the bug, not the behaviour the queue wants",
            mapOf("command_1" to "a record"),
            raw.visible(),
        )
    }

    // ==================== atomic enqueue ====================

    /**
     * The unaccepted superseding enqueue, in full. The record, the advanced sequence and
     * the domain generation are one commit; when it fails, none of the three is anywhere:
     * not in the queue, not in the file, and not left in the process's map for the next
     * successful write to make durable. The command that was accepted before it is still
     * the intent, and the generation that says so is unchanged - so nothing an unaccepted
     * gesture claimed can make it stale.
     */
    @Test
    fun `an enqueue that did not commit leaves nothing observable to any reader`() {
        val play = enqueuePlay()
        val before = raw.onDisk()
        assertEquals("the accepted command owns its domain", play.id, generationOf("playback"))

        raw.failedCommits = 1
        assertNull(
            "a write that did not reach the file is not an accepted command",
            process().enqueue(PlaybackCommand.of("stop", nowElapsedMs = 0L)),
        )

        assertEquals("the queue shows the accepted command and nothing else", listOf(play.id), pendingIds())
        assertEquals("the generation did not move", play.id, generationOf("playback"))
        assertEquals("the file did not move", before, raw.onDisk())
        assertEquals(
            "and the process's map did not either: no later write can flush it",
            before,
            raw.visible(),
        )

        val pass = drainPass()
        assertEquals(
            "so the accepted command is what runs, and the one that was refused never reaches a handler",
            listOf(play.id),
            pass.ranIds,
        )
        assertEquals(listOf("play"), pass.ran.map { it.command.action })
        assertTrue(process().pending().isEmpty())
    }

    // ==================== the failure state ====================

    /**
     * A write that did not reach the file *and* could not be put back. The store does not
     * pretend to be healthy: reads answer from the last state the file is known to hold,
     * every write is refused - so nothing that failed to commit can be reported as done or
     * become durable later - and the failure is logged loud enough to find.
     */
    @Test
    fun `a write that could not be put back freezes the store instead of letting it win`() {
        val play = enqueuePlay()
        val before = raw.onDisk()

        // The enqueue and the undo that would take it back out, both refused.
        raw.failedCommits = 2
        assertNull(
            "a command whose record could not be written is not accepted",
            process().enqueue(PlaybackCommand.of("stop", nowElapsedMs = 0L)),
        )

        assertFalse("the store says so, rather than pretending", store.healthy)
        assertEquals("and reads answer from the last state the file is known to hold", listOf(play.id), pendingIds())
        assertEquals("which is the state the file still holds", before, raw.onDisk())

        // Healthy again, and still refused: this is what keeps a value that failed to
        // commit from becoming durable through an unrelated write later on.
        raw.failedCommits = 0
        assertNull(
            "no write of any kind is accepted from a store in the failure state",
            process().enqueue(PlaybackCommand.of("play", stream = "gold", nowElapsedMs = 0L)),
        )
        val pass = drainPass()
        assertEquals(
            "the accepted command from the last known state is what runs",
            listOf(play.id),
            pass.ranIds,
        )
        assertEquals(
            "and it cannot be acknowledged, so it is still there for the next start",
            listOf(play.id),
            pendingIds(),
        )
        assertEquals(
            "a withdrawal is never claimed in either direction either",
            PlaybackCommandInbox.Withdraw.NOT_REMOVED,
            process().withdraw(play.id),
        )
        assertEquals("and nothing new reached the file", before, raw.onDisk())
    }

    /**
     * The other half of the failure state: it belongs to the process and stops with it.
     * Nothing about it ever reached the file, so a process that starts afterwards reads
     * exactly the records this one did and is healthy again - written to and read from
     * normally.
     */
    @Test
    fun `the failure state ends with the process that entered it`() {
        val play = enqueuePlay()

        raw.failedCommits = 2
        assertNull(process().enqueue(PlaybackCommand.of("stop", nowElapsedMs = 0L)))
        assertFalse(store.healthy)

        val next = PlaybackCommandInbox(PlaybackCommandInbox.TransactionalSlot(FaithfulPrefs(file)))
        assertEquals("the next process reads the records the file holds", listOf(play.id), next.pending().map { it.id })
        assertNotNull("and can write again", next.enqueue(PlaybackCommand.of("stop", nowElapsedMs = 0L)))
    }

    // ==================== the wiring ====================

    /**
     * The production half of the boundary, as a JVM test can read it.
     *
     * Everything above pins the transaction layer against a fake device. What is left is
     * that the app's own inbox is actually built on it: `forContext` hands the queue the
     * transactional store wrapping the real `SharedPreferences`, and the raw device is
     * never handed to an inbox on its own. An inbox built directly on `PrefsSlot` would
     * behave exactly like the version this fix is about, and would do it silently.
     */
    @Test
    fun `the process's one inbox is built on the transaction boundary, not on the device`() {
        val source = file("src/main/java/com/example/musicplayerapp/service/PlaybackCommandInbox.kt").readText()

        assertEquals(
            "the real SharedPreferences is wrapped in the transaction layer, once",
            1,
            Regex("""PlaybackCommandInbox\(\s*TransactionalSlot\(""").findAll(source).count(),
        )
        assertTrue(
            "and the wrapper it wraps is the real device",
            Regex("""TransactionalSlot\(PrefsSlot\(context\.applicationContext\)\)""").containsMatchIn(source),
        )
        assertFalse(
            "no inbox is ever built straight on the raw device",
            Regex("""PlaybackCommandInbox\(\s*PrefsSlot\(""").containsMatchIn(source),
        )
    }

    // ==================== the three exact scenarios ====================

    /**
     * WITHDRAW RACE. A is pending; B and then C are accepted; B's start is refused, so the
     * caller withdraws B.
     *
     * B is not the newest command in its domain any more, so taking B back must not touch
     * the generation - C owns it - and must not disturb C. C is the newest accepted intent
     * and is what runs; A, superseded by C, is removed on the way past.
     */
    @Test
    fun `withdrawing a start that failed after a newer command was accepted leaves the newer one on top`() {
        val a = enqueuePlay(stream = "myata")
        val b = enqueuePlay(stream = "gold")
        val c = enqueuePlay(stream = "myata_hits")

        assertEquals(
            "the refused start takes its own command back",
            PlaybackCommandInbox.Withdraw.REMOVED_PENDING,
            process().withdraw(b.id),
        )

        assertEquals("C is still the newest accepted intent", c.id, generationOf("playback"))
        assertEquals("and it is untouched: only B left", listOf(a.id, c.id), pendingIds())

        val pass = drainPass()
        assertEquals("A is stale against C and B is gone", listOf(c.id), pass.ranIds)
        assertEquals("and C is the station the listener last named", "myata_hits", pass.ran.first().command.stream)
        assertTrue(process().pending().isEmpty())
    }

    /**
     * LOST ACK AFTER A NEW GENERATION. A is handled; the listener's newer command B in the
     * same domain is accepted before A's acknowledgement is written; the acknowledgement
     * then does not reach the file.
     *
     * A has not been acknowledged, so it must still be logically present and replayable -
     * not gone from the logical queue while its record sits in the file. And it cannot
     * replay *ahead* of B: B advanced the generation, so the next pass recognises A as
     * stale, removes it, and runs B.
     */
    @Test
    fun `an acknowledgement lost after a newer command was accepted cannot replay ahead of it`() {
        val a = enqueuePlay(stream = "myata")
        val b = enqueuePlay(stream = "gold")

        raw.failedCommits = 1
        assertFalse("the acknowledgement did not reach the file", process().ack(a.id))

        assertEquals("A is still logically there, exactly as it is still in the file", listOf(a.id, b.id), pendingIds())
        assertEquals("and B is still the newest intent", b.id, generationOf("playback"))

        val pass = drainPass()
        assertEquals("so the replayed record is stale and the newer intent runs", listOf(b.id), pass.ranIds)
        assertEquals("gold", pass.ran.single().command.stream)
        assertEquals(
            "and the stale record was never offered to the foreground gate",
            listOf(b.id),
            pass.prepared.map { it.id },
        )
        assertTrue(process().pending().isEmpty())
    }

    /**
     * CROSS-DOMAIN SURVIVOR ORDER. Playback A, timer B, playback C, timer D.
     *
     * Supersession is per domain, so the newest of each survives - C and D - and the two
     * that were replaced go. What must not happen is the queue reordering what is left:
     * the survivors run in the order the listener made them, C then D.
     */
    @Test
    fun `the survivors of both domains keep the order the listener made them in`() {
        val a = enqueuePlay(stream = "myata")
        val b = enqueueTimer(minutes = 15)
        val c = enqueuePlay(stream = "gold")
        val d = enqueueTimer(minutes = 45)

        val pass = drainPass()

        assertEquals("the survivors are the newest of each domain, in order", listOf(c.id, d.id), pass.ranIds)
        assertEquals(
            "and each one is what the listener chose",
            listOf("play", SleepTimerContract.ACTION_SET),
            pass.ran.map { it.command.action },
        )
        assertEquals("gold", pass.ran.first().command.stream)
        assertEquals(45, pass.ran.last().command.minutes)
        assertEquals("A and B were replaced", emptyList<String>(), listOf(a.id, b.id).filter { it in pass.ranIds })
        assertTrue(process().pending().isEmpty())
    }

    // ============================ plumbing ============================

    private fun file(path: String): File = File(path).also {
        assertTrue("expected ${it.absolutePath} to exist", it.isFile)
    }
}
