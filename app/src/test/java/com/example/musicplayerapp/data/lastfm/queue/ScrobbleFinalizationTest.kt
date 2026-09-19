package com.example.musicplayerapp.data.lastfm.queue

import com.example.musicplayerapp.data.lastfm.LastfmApi
import com.example.musicplayerapp.data.lastfm.LastfmRequest
import com.example.musicplayerapp.data.lastfm.LastfmRequestFactory
import com.example.musicplayerapp.data.lastfm.LastfmStoredSession
import com.example.musicplayerapp.data.lastfm.LastfmTransportResult
import com.example.musicplayerapp.data.lastfm.SharedSecretSigner
import com.example.musicplayerapp.data.TrackKey
import com.example.musicplayerapp.scrobble.FeedObservation
import com.example.musicplayerapp.scrobble.OccurrenceId
import com.example.musicplayerapp.scrobble.ScrobbleCandidate
import com.example.musicplayerapp.scrobble.ScrobbleEmissions
import com.example.musicplayerapp.scrobble.ScrobbleTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The finalized mark (G6b P6b): once Last.fm has answered an airing terminally, no
 * later process can queue it again for that account.
 *
 * The restart tests run the real tracker, queue and sender twice over one [Disk] -
 * the only thing that survives the simulated process death - with every in-memory
 * store (P4's emitted mark, the queue's generations, the sender) created afresh.
 * The transport is [FakeLastfm]; the key, secret and session are fabricated, and
 * nothing here can reach Last.fm. The same rules against real SQLite are in the
 * instrumented `ScrobbleFinalizationDaoTest`.
 */
class ScrobbleFinalizationTest {

    private companion object {
        /** Occurrence X: 600 s long, so two separate 240 s listens fit inside it. */
        const val X = 1_789_000_000L
        const val DURATION = 600L
        const val U = "listener-u"
        const val V = "listener-v"
    }

    private var linked: String? = U
    private val disk = Disk()

    // ==================== the 600-second restart regression ====================

    @Test
    fun `accepted - a restarted process cannot queue or send the airing again`() = restartScenario(code = 0)

    @Test
    fun `ignored 1 - artist ignored is final across a restart`() = restartScenario(code = 1)

    @Test
    fun `ignored 2 - track ignored is final across a restart`() = restartScenario(code = 2)

    @Test
    fun `ignored 3 - timestamp too old is final across a restart`() = restartScenario(code = 3)

    private fun restartScenario(code: Int) = runBlocking {
        val lastfm = FakeLastfm(code)

        // Process A: tune in at X+10, listen 240 s, queue, send, a terminal answer.
        val a = Process(lastfm)
        a.listen(fromServerSec = X + 10, seconds = 250)
        assertEquals(listOf("SCROBBLE_QUEUED"), a.queueEvents())
        assertEquals(1, disk.rows.size)
        a.sender.drain()
        assertTrue("the row is finalized", disk.rows.isEmpty())
        assertEquals(X, disk.finalized[U to "myata"])

        // Process death at X+260; process B at X+270, same account, X on air until X+600.
        val b = Process(lastfm)
        b.listen(fromServerSec = X + 270, seconds = 270)
        assertEquals(
            "fresh P4 memory emits again, and the durable enqueue refuses it",
            listOf("SCROBBLE_QUEUE_FINALIZED"), b.queueEvents(),
        )
        assertTrue("no second row", disk.rows.isEmpty())
        assertEquals(ScrobbleSender.Result.Idle, b.sender.drain())

        assertEquals("exactly one scrobble ever reached the transport", listOf(X), lastfm.sent)
    }

    @Test
    fun `a pending row is not finalized - across a restart its key still refuses a second`() = runBlocking {
        val lastfm = FakeLastfm(0)
        val a = Process(lastfm)
        a.listen(fromServerSec = X + 10, seconds = 250)            // queued, never sent: the process dies
        assertNull(disk.finalized[U to "myata"])

        val b = Process(lastfm)
        b.listen(fromServerSec = X + 270, seconds = 270)
        assertEquals(listOf("SCROBBLE_QUEUE_DUPLICATE"), b.queueEvents())
        assertEquals(1, disk.rows.size)

        b.sender.drain()
        assertEquals(listOf(X), lastfm.sent)
        assertEquals(X, disk.finalized[U to "myata"])
    }

    @Test
    fun `answers that are not terminal finalize nothing`() = runBlocking {
        for (code in listOf(4, 5, 99)) {                            // too new, daily limit, unknown
            disk.rows.clear(); disk.finalized.clear()
            val p = Process(FakeLastfm(code))
            p.listen(fromServerSec = X + 10, seconds = 250)
            p.sender.drain()
            assertEquals("code $code keeps its row", 1, disk.rows.size)
            assertNull("code $code finalizes nothing", disk.finalized[U to "myata"])
        }
    }

    // ==================== the queue's rules ====================

    @Test
    fun `a purged pending airing leaves no mark - after reconnecting it may queue again`() = runBlocking {
        val queue = queue()
        assertEquals(ScrobbleQueue.Outcome.Queued, queue.write(candidate(X), U))
        queue.purge(U)                                              // explicit disconnect
        assertTrue(disk.rows.isEmpty())
        assertNull(disk.finalized[U to "myata"])
        // Reconnected, and the listener earned it again.
        assertEquals(ScrobbleQueue.Outcome.Queued, queue.write(candidate(X), U))
    }

    @Test
    fun `a finalized mark survives disconnect and reconnect`() = runBlocking {
        val queue = queue()
        queue.write(candidate(X), U)
        disk.finalizeOccurrence(U, "myata", X)
        queue.purge(U)
        assertEquals(X, disk.finalized[U to "myata"])
        assertEquals(ScrobbleQueue.Outcome.AlreadyFinalized, queue.write(candidate(X), U))
        assertTrue(disk.rows.isEmpty())
    }

    @Test
    fun `one account's mark never suppresses another's`() = runBlocking {
        disk.finalizeOccurrence(U, "myata", X)
        linked = V
        assertEquals(ScrobbleQueue.Outcome.Queued, queue().write(candidate(X), V))
    }

    @Test
    fun `one stream's mark never suppresses another stream at the same second`() = runBlocking {
        disk.finalizeOccurrence(U, "myata", X)
        assertEquals(ScrobbleQueue.Outcome.Queued, queue().write(candidate(X, stream = "gold"), U))
    }

    @Test
    fun `a newer airing queues - older ones and the same one do not`() = runBlocking {
        disk.finalizeOccurrence(U, "myata", X)
        val queue = queue()
        assertEquals(ScrobbleQueue.Outcome.Queued, queue.write(candidate(X + 600), U))
        assertEquals(ScrobbleQueue.Outcome.AlreadyFinalized, queue.write(candidate(X), U))
        assertEquals(ScrobbleQueue.Outcome.AlreadyFinalized, queue.write(candidate(X - 300), U))
    }

    @Test
    fun `the mark only moves forward`() = runBlocking {
        disk.finalizeOccurrence(U, "myata", X + 600)
        disk.finalizeOccurrence(U, "myata", X)                      // a late, older answer
        assertEquals(X + 600, disk.finalized[U to "myata"])
        assertEquals(ScrobbleQueue.Outcome.AlreadyFinalized, queue().write(candidate(X + 300), U))
    }

    @Test
    fun `finalizing deletes only the finalizing account's row`() = runBlocking {
        linked = V
        queue().write(candidate(X), V)                              // V queued the same airing
        assertEquals(0, disk.finalizeOccurrence(U, "myata", X))    // U's late answer
        assertEquals(listOf(V), disk.rows.map { it.lastfmUsername })
        assertEquals(X, disk.finalized[U to "myata"])
        assertNull(disk.finalized[V to "myata"])
    }

    @Test
    fun `an already finalized airing asks for no drain`() = runBlocking {
        disk.finalizeOccurrence(U, "myata", X)
        var drains = 0
        val queue = ScrobbleQueue(dao = { disk }, linkedUsername = { linked }, scope = CoroutineScope(Dispatchers.Unconfined), onQueued = { drains++ })
        queue.write(candidate(X), U)
        assertEquals(0, drains)
    }

    // ==================== harness ====================

    private fun queue(log: MutableList<String>? = null) = ScrobbleQueue(
        dao = { disk },
        linkedUsername = { linked },
        scope = CoroutineScope(Dispatchers.Unconfined),
        log = { name, _ -> log?.add(name) },
    )

    private fun candidate(startedAt: Long, stream: String = "myata") = ScrobbleCandidate(
        OccurrenceId(stream, startedAt), TrackKey.of("Artist", "Title")!!, "Artist", "Title", startedAt, DURATION,
    )

    /** One app process: every in-memory store fresh, [disk] shared. */
    private inner class Process(lastfm: FakeLastfm) {
        private val events = mutableListOf<String>()
        private val session = LastfmStoredSession(sessionKey = "sk-fabricated", username = linked)
        val tracker = ScrobbleTracker(isEnabled = { true }, sink = queue(events).sink(), emissions = ScrobbleEmissions())
        val sender = ScrobbleSender(
            dao = { disk },
            session = { session },
            requests = { LastfmRequestFactory("0123456789abcdef0123456789abcdef", SharedSecretSigner("fedcba9876543210fedcba9876543210")) },
            api = { lastfm },
            invalidateSession = { _, _ -> },
            writesEnabled = { true },
        )

        fun queueEvents() = events.filter { it.startsWith("SCROBBLE_QUEUE") || it == "SCROBBLE_QUEUED" }

        /** Plays from [fromServerSec] for [seconds], polled every 30 s like the service. */
        fun listen(fromServerSec: Long, seconds: Long) {
            tracker.onPlaying(true, "myata", 0)
            for (s in 0..seconds step 30) {
                tracker.onObservation(
                    FeedObservation("myata", "Artist", "Title", X, DURATION, X + DURATION, fromServerSec + s),
                    s * 1000L,
                )
            }
            tracker.onTick(seconds * 1000L)
        }
    }

    /** Answers every scrobble with ignored [code] (0 = accepted), recording what it was sent. */
    private class FakeLastfm(val code: Int) : LastfmApi {
        val sent = mutableListOf<Long>()
        override suspend fun send(request: LastfmRequest): LastfmTransportResult {
            val stamps = (0 until 50).mapNotNull { request.params["timestamp[$it]"]?.toLong() }
            sent += stamps
            val items = stamps.joinToString(",") {
                """{"artist":{"#text":"a"},"track":{"#text":"t"},"timestamp":"$it","ignoredMessage":{"code":"$code","#text":""}}"""
            }
            val accepted = if (code == 0) stamps.size else 0
            return LastfmTransportResult.Body(
                """{"scrobbles":{"scrobble":[$items],"@attr":{"accepted":$accepted,"ignored":${stamps.size - accepted}}}}"""
            )
        }
    }

    /**
     * The database that outlives a process: the queue's key semantics and the
     * finalized primitives, with the DAO's real `enqueue`/`finalizeOccurrence` bodies
     * running over them.
     */
    private class Disk : ScrobbleQueueDao() {
        val rows = mutableListOf<ScrobbleQueueEntry>()
        val finalized = HashMap<Pair<String, String>, Long>()
        private fun key(r: ScrobbleQueueEntry) = r.streamId to r.startedAt

        override suspend fun insert(entry: ScrobbleQueueEntry) =
            if (rows.any { key(it) == key(entry) }) -1L else { rows += entry; rows.size.toLong() }
        override suspend fun find(streamId: String, startedAt: Long) = rows.firstOrNull { key(it) == (streamId to startedAt) }
        override suspend fun count() = rows.size
        override suspend fun pendingFor(username: String, limit: Int) = activeHead(username, Long.MIN_VALUE, limit)
        override suspend fun deleteForUsername(username: String): Int {
            val before = rows.size
            rows.removeAll { it.lastfmUsername == username }
            return before - rows.size
        }
        override suspend fun activeHead(username: String, quarantined: Long, limit: Int) =
            rows.filter { it.lastfmUsername == username && it.nextAttemptAt != quarantined }
                .sortedWith(compareBy({ it.startedAt }, { it.streamId })).take(limit)
        override suspend fun deleteOccurrence(streamId: String, startedAt: Long) =
            if (rows.removeAll { key(it) == (streamId to startedAt) }) 1 else 0
        override suspend fun recordAttempt(streamId: String, startedAt: Long, nextAttemptAt: Long): Int {
            val i = rows.indexOfFirst { key(it) == (streamId to startedAt) }
            if (i < 0) return 0
            rows[i] = rows[i].copy(attempts = rows[i].attempts + 1, nextAttemptAt = nextAttemptAt)
            return 1
        }
        override suspend fun finalizedThrough(username: String, streamId: String) = finalized[username to streamId]
        override suspend fun insertFinalizedIfAbsent(username: String, streamId: String, startedAt: Long) {
            finalized.putIfAbsent(username to streamId, startedAt)
        }
        override suspend fun raiseFinalized(username: String, streamId: String, startedAt: Long): Int {
            val current = finalized[username to streamId] ?: return 0
            if (current >= startedAt) return 0
            finalized[username to streamId] = startedAt
            return 1
        }
        override suspend fun deleteOccurrenceOf(username: String, streamId: String, startedAt: Long) =
            if (rows.removeAll { it.lastfmUsername == username && key(it) == (streamId to startedAt) }) 1 else 0
    }
}
