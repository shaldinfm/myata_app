package com.example.musicplayerapp.data.lastfm.queue

import com.example.musicplayerapp.data.TrackKey
import com.example.musicplayerapp.scrobble.FeedObservation
import com.example.musicplayerapp.scrobble.OccurrenceId
import com.example.musicplayerapp.scrobble.ScrobbleCandidate
import com.example.musicplayerapp.scrobble.ScrobbleEmissions
import com.example.musicplayerapp.scrobble.ScrobbleTracker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The queue's rules, against an in-memory DAO with the same insert-or-ignore
 * semantics as the real primary key. The real SQLite is covered by the
 * instrumented `ScrobbleQueueDaoTest` and `ScrobbleQueueIntegrationTest`.
 */
class ScrobbleQueueTest {

    private var linked: String? = "listener-x"
    private val dao = FakeDao()
    private val logged = mutableListOf<Pair<String, Map<String, Any?>>>()
    private val queueJob = SupervisorJob()
    private val queue = ScrobbleQueue(
        dao = { dao },
        linkedUsername = { linked },
        scope = CoroutineScope(queueJob + Dispatchers.Default),
        clock = { 1_789_000_500_123L },
        log = { name, fields -> logged += name to fields.toMap() },
    )

    private val t0 = 1_789_000_000L

    private fun candidate(
        stream: String = "myata",
        startedAt: Long = t0,
        artist: String = "Nick Cave & The Bad Seeds",
        title: String = "Red Right Hand",
    ) = ScrobbleCandidate(
        occurrenceId = OccurrenceId(stream, startedAt),
        trackKey = TrackKey.of(artist, title)!!,
        artist = artist,
        title = title,
        startedAt = startedAt,
        durationSec = 372,
    )

    private fun names() = logged.map { it.first }

    private fun awaitQueue() = runBlocking { withTimeout(5_000) { queueJob.children.toList().joinAll() } }

    // ---- writing ---------------------------------------------------------------

    @Test
    fun `a candidate becomes a row exactly as emitted, bound to the linked account`() = runBlocking {
        val c = candidate(artist = "Björk ", title = " Jóga (Live)")
        assertEquals(ScrobbleQueue.Outcome.Queued, queue.write(c, "listener-x"))
        val row = dao.rows.single()
        assertEquals(
            ScrobbleQueueEntry(
                streamId = "myata", startedAt = t0, trackKey = c.trackKey,
                artist = "Björk ", title = " Jóga (Live)", durationSec = 372,
                lastfmUsername = "listener-x", queuedAt = 1_789_000_500_123L,
                attempts = 0, nextAttemptAt = 0L,
            ),
            row,
        )
        assertEquals(listOf("SCROBBLE_QUEUED"), names())
    }

    @Test
    fun `a duplicate occurrence is harmless`() = runBlocking {
        queue.write(candidate(), "listener-x")
        assertEquals(ScrobbleQueue.Outcome.Duplicate, queue.write(candidate(title = "Corrected"), "listener-x"))
        assertEquals(1, dao.rows.size)
        assertEquals("Red Right Hand", dao.rows.single().title)
        assertEquals(listOf("SCROBBLE_QUEUED", "SCROBBLE_QUEUE_DUPLICATE"), names())
    }

    @Test
    fun `a database error drops the candidate and logs only the exception class`() = runBlocking {
        dao.failWith = IllegalStateException("disk full at /data/.../Red Right Hand")
        val outcome = queue.write(candidate(), "listener-x")
        assertEquals(ScrobbleQueue.Outcome.Failed("IllegalStateException"), outcome)
        assertTrue(dao.rows.isEmpty())
        val (name, fields) = logged.single()
        assertEquals("SCROBBLE_QUEUE_FAILED", name)
        assertEquals("IllegalStateException", fields["error"])
    }

    @Test
    fun `an account that changed between emission and write is never written to`() = runBlocking {
        linked = "listener-y"
        assertEquals(ScrobbleQueue.Outcome.Skipped("account_changed"), queue.write(candidate(), "listener-x"))
        linked = null
        assertEquals(ScrobbleQueue.Outcome.Skipped("account_changed"), queue.write(candidate(), "listener-x"))
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `the sink captures the account linked at emission`() {
        queue.sink().onEligible(candidate())
        awaitQueue()
        assertEquals("listener-x", dao.rows.single().lastfmUsername)
    }

    @Test
    fun `the sink drops a candidate when no account is linked`() {
        linked = null
        queue.sink().onEligible(candidate())
        awaitQueue()
        assertTrue(dao.rows.isEmpty())
        assertEquals("SCROBBLE_QUEUE_SKIPPED", logged.single().first)
        assertEquals("not_linked", logged.single().second["reason"])
    }

    @Test
    fun `logs never carry artist, title or the username`() = runBlocking {
        queue.write(candidate(), "listener-x")
        queue.write(candidate(), "listener-x")
        queue.purge("listener-x")
        val text = logged.toString()
        assertFalse(text, text.contains("Nick Cave"))
        assertFalse(text, text.contains("Red Right Hand"))
        assertFalse(text, text.contains("listener-x"))
    }

    // ---- P4 -> P5, end to end ----------------------------------------------------

    @Test
    fun `a candidate emitted by the real P4 tracker reaches the queue through the sink`() {
        val tracker = ScrobbleTracker(isEnabled = { true }, sink = queue.sink(), emissions = ScrobbleEmissions())
        tracker.onPlaying(true, "gold", 0)
        tracker.onObservation(FeedObservation("gold", "Artist", "Title", t0, 60, t0 + 60, t0), 0)
        tracker.onTick(30_000)
        awaitQueue()
        val row = dao.rows.single()
        assertEquals("gold" to t0, row.streamId to row.startedAt)
        assertEquals(60L, row.durationSec)
        assertEquals("listener-x", row.lastfmUsername)
    }

    // ---- lifetime ------------------------------------------------------------------

    @Test
    fun `destroying the service does not cancel an insert it already launched`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        dao.holdInsertsUntil = gate
        val service = CoroutineScope(Job() + Dispatchers.Default)
        service.launch { queue.sink().onEligible(candidate()) }.join()
        service.cancel()                                     // onDestroy
        gate.complete(Unit)
        awaitQueue()
        assertEquals("the insert finished on the queue's own scope", 1, dao.rows.size)
    }

    // ---- purge -----------------------------------------------------------------------

    @Test
    fun `purge removes only that account's rows`() = runBlocking {
        queue.write(candidate(startedAt = t0), "listener-x")
        linked = "listener-y"
        queue.write(candidate(startedAt = t0 + 100), "listener-y")
        queue.purge("listener-x")
        assertEquals(listOf("listener-y"), dao.rows.map { it.lastfmUsername })
        assertEquals(1, logged.last().second["rows"])
    }

    @Test
    fun `a write in flight when the purge starts is purged too`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        dao.holdInsertsUntil = gate
        queue.enqueue(candidate(), "listener-x")
        dao.insertStarted.await()                            // the write holds the lock
        val purge = launch(Dispatchers.Default) { queue.purge("listener-x") }
        kotlinx.coroutines.delay(100)
        gate.complete(Unit)                                  // the insert lands...
        purge.join()                                         // ...then the purge runs
        awaitQueue()
        assertTrue("nothing of listener-x survives", dao.rows.isEmpty())
    }

    @Test
    fun `a purge failure is logged, not thrown`() = runBlocking {
        dao.failWith = IllegalStateException("x")
        queue.purge("listener-x")
        assertEquals("SCROBBLE_QUEUE_PURGE_FAILED", logged.single().first)
        assertNull(logged.single().second["rows"])
    }

    // ---- fake ------------------------------------------------------------------------

    private class FakeDao : ScrobbleQueueDao {
        val rows = mutableListOf<ScrobbleQueueEntry>()
        @Volatile var failWith: Exception? = null
        @Volatile var holdInsertsUntil: CompletableDeferred<Unit>? = null
        val insertStarted = CompletableDeferred<Unit>()

        override suspend fun insert(entry: ScrobbleQueueEntry): Long {
            failWith?.let { throw it }
            insertStarted.complete(Unit)
            holdInsertsUntil?.await()
            synchronized(rows) {
                if (rows.any { it.streamId == entry.streamId && it.startedAt == entry.startedAt }) return -1L
                rows += entry
                return rows.size.toLong()
            }
        }

        override suspend fun find(streamId: String, startedAt: Long) =
            synchronized(rows) { rows.firstOrNull { it.streamId == streamId && it.startedAt == startedAt } }

        override suspend fun count() = synchronized(rows) { rows.size }

        override suspend fun pendingFor(username: String, limit: Int) = synchronized(rows) {
            rows.filter { it.lastfmUsername == username }
                .sortedWith(compareBy({ it.startedAt }, { it.streamId })).take(limit)
        }

        override suspend fun deleteForUsername(username: String): Int {
            failWith?.let { throw it }
            return synchronized(rows) {
                val before = rows.size
                rows.removeAll { it.lastfmUsername == username }
                before - rows.size
            }
        }
    }
}
