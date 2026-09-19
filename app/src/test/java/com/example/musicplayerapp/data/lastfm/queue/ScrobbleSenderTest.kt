package com.example.musicplayerapp.data.lastfm.queue

import com.example.musicplayerapp.data.lastfm.LastfmApi
import com.example.musicplayerapp.data.lastfm.LastfmRequest
import com.example.musicplayerapp.data.lastfm.LastfmRequestFactory
import com.example.musicplayerapp.data.lastfm.LastfmStoredSession
import com.example.musicplayerapp.data.lastfm.LastfmTransportResult
import com.example.musicplayerapp.data.lastfm.SharedSecretSigner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sender against a scripted transport (G6b P6a). Nothing here can reach
 * Last.fm: [ScriptedApi] answers from the test, the key and secret are fabricated,
 * and the queue is an in-memory DAO with the real ordering and key semantics.
 */
class ScrobbleSenderTest {

    private var now = 1_789_000_000_000L
    private val t0 = 1_789_000_000L
    private val dao = MemoryDao()
    private val api = ScriptedApi()
    private var stored = LastfmStoredSession(sessionKey = "sk-x-1", username = "listener-x")
    private val invalidated = mutableListOf<Pair<String, String>>()
    private val logged = mutableListOf<String>()

    private fun sender() = ScrobbleSender(
        dao = { dao },
        session = { stored },
        requests = { LastfmRequestFactory("0123456789abcdef0123456789abcdef", SharedSecretSigner("fedcba9876543210fedcba9876543210")) },
        api = { api },
        invalidateSession = { u, k -> invalidated += u to k; stored = stored.copy(sessionKey = null) },
        clock = { now },
        jitter = { 0.0 },
        log = { name, fields -> logged += name + fields.toList() },
    )

    private fun row(
        startedAt: Long,
        stream: String = "myata",
        user: String = "listener-x",
        attempts: Int = 0,
        next: Long = 0L,
    ) = ScrobbleQueueEntry(stream, startedAt, "key$startedAt", "Artist $startedAt", "Title $startedAt", 200, user, 1L, attempts, next)

    private fun seed(vararg rows: ScrobbleQueueEntry) = rows.forEach { dao.rows += it }

    /** A well-formed answer: one verdict per scrobble sent, with the timestamps sent. */
    private fun answer(request: LastfmRequest, code: (Int) -> String? = { "0" }): String =
        answerFor(sentTimestamps(request), code)

    private fun answerFor(timestamps: List<Long>, code: (Int) -> String? = { "0" }): String {
        val n = timestamps.size
        val items = (0 until n).map { i ->
            val c = code(i)
            val ignored = if (c == null) "" else ""","ignoredMessage":{"code":"$c","#text":""}"""
            """{"artist":{"corrected":"0","#text":"a"},"track":{"corrected":"0","#text":"t"},""" +
                """"timestamp":"${timestamps[i]}"$ignored}"""
        }
        val accepted = (0 until n).count { code(it) == "0" }
        return """{"scrobbles":{"scrobble":[${items.joinToString(",")}],""" +
            """"@attr":{"accepted":$accepted,"ignored":${n - accepted}}}}"""
    }

    private fun sentTimestamps(request: LastfmRequest): List<Long> =
        (0 until 50).mapNotNull { request.params["timestamp[$it]"]?.toLong() }

    // ---- requests ------------------------------------------------------------------

    @Test
    fun `a single row becomes one exact scrobble request`() = runBlocking {
        seed(row(t0))
        api.always { answer(it) }
        assertEquals(ScrobbleSender.Result.Idle, sender().drain())

        val p = api.requests.single().params
        assertEquals("track.scrobble", p["method"])
        assertEquals("Artist $t0", p["artist[0]"])
        assertEquals("Title $t0", p["track[0]"])
        assertEquals("$t0", p["timestamp[0]"])
        assertEquals("200", p["duration[0]"])
        assertEquals("0", p["chosenByUser[0]"])
        assertEquals("sk-x-1", p["sk"])
        for (absent in listOf("streamId", "album", "mbid", "trackNumber", "albumArtist", "context")) {
            assertTrue(absent, p.keys.none { it.startsWith(absent) })
        }
        assertTrue("accepted rows are deleted", dao.rows.isEmpty())
    }

    @Test
    fun `120 rows go as 50, 50 and 20, strictly oldest first`() = runBlocking {
        (0 until 120).shuffled().forEach { seed(row(t0 + it)) }
        api.always { answer(it) }
        sender().drain()
        assertEquals(listOf(50, 50, 20), api.requests.map { sentTimestamps(it).size })
        assertEquals((0L until 120L).map { t0 + it }, api.requests.flatMap { sentTimestamps(it) })
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `the same started_at on two streams orders by stream id`() = runBlocking {
        seed(row(t0, stream = "myata"), row(t0, stream = "gold"))
        api.always { answer(it) }
        sender().drain()
        val p = api.requests.single().params
        assertEquals("Artist $t0", p["artist[0]"])
        assertEquals(2, sentTimestamps(api.requests.single()).size)
    }

    // ---- strict head-of-line order ---------------------------------------------------

    @Test
    fun `an old row in backoff holds back a newer due row`() = runBlocking {
        seed(row(t0, next = now + 60_000), row(t0 + 300))
        assertEquals(ScrobbleSender.Result.WaitUntil(now + 60_000), sender().drain())
        assertTrue("nothing sent", api.requests.isEmpty())
    }

    @Test
    fun `a quarantined row does not block the active queue`() = runBlocking {
        seed(row(t0, next = ScrobblePolicy.QUARANTINE), row(t0 + 300))
        api.always { answer(it) }
        sender().drain()
        assertEquals(listOf(t0 + 300), sentTimestamps(api.requests.single()))
        assertEquals("the quarantined row is kept", listOf(t0), dao.rows.map { it.startedAt })
    }

    // ---- per-item verdicts ---------------------------------------------------------------

    @Test
    fun `accepted and ignored 1, 2 and 3 are deleted`() = runBlocking {
        (0 until 4).forEach { seed(row(t0 + it)) }
        api.always { answer(it) { i -> listOf("0", "1", "2", "3")[i] } }
        sender().drain()
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `too new is deferred 10 minutes, then quarantined on the third attempt`() = runBlocking {
        seed(row(t0))
        api.always { answer(it) { "4" } }
        val s = sender()
        assertEquals(ScrobbleSender.Result.WaitUntil(now + 600_000), s.drain())
        assertEquals(1, dao.rows.single().attempts)
        now += 600_000
        s.drain()
        assertEquals(2, dao.rows.single().attempts)
        now += 600_000
        assertEquals(ScrobbleSender.Result.Idle, s.drain())
        assertEquals(ScrobblePolicy.QUARANTINE, dao.rows.single().nextAttemptAt)
        assertEquals(3, api.requests.size)
    }

    @Test
    fun `the daily limit defers a day and holds back everything newer`() = runBlocking {
        seed(row(t0), row(t0 + 300))
        api.always { answer(it) { i -> if (i == 0) "5" else "0" } }
        sender().drain()
        assertEquals(listOf(t0), dao.rows.map { it.startedAt })
        assertEquals(now + 86_400_000, dao.rows.single().nextAttemptAt)
        seed(row(t0 + 600))
        assertEquals(ScrobbleSender.Result.WaitUntil(now + 86_400_000), sender().drain())
        assertEquals("the newer row waits", 1, api.requests.size)
    }

    @Test
    fun `an unknown ignored code quarantines and keeps the row`() = runBlocking {
        seed(row(t0))
        api.always { answer(it) { "42" } }
        sender().drain()
        assertEquals(ScrobblePolicy.QUARANTINE, dao.rows.single().nextAttemptAt)
    }

    @Test
    fun `a missing ignoredMessage is not accepted - only that row is kept and backs off`() = runBlocking {
        seed(row(t0), row(t0 + 300))
        api.once { req ->
            // Row 0 answered with no code; row 1 accepted; totals honest about it.
            val body = answer(req) { i -> if (i == 0) null else "0" }
            body.replace(""""accepted":1,"ignored":1""", """"accepted":2,"ignored":0""")
        }
        sender().drain()
        assertEquals(listOf(t0), dao.rows.map { it.startedAt })
        assertEquals(now + 30_000, dao.rows.single().nextAttemptAt)
    }

    // ---- unsafe answers ----------------------------------------------------------------

    @Test
    fun `a verdict count that does not match deletes nothing and backs the batch off`() = runBlocking {
        seed(row(t0), row(t0 + 300))
        api.once { req -> answerFor(sentTimestamps(req).drop(1)) }      // one verdict short
        sender().drain()
        assertEquals(2, dao.rows.size)
        assertTrue(dao.rows.all { it.attempts == 1 && it.nextAttemptAt == now + 30_000 })
    }

    @Test
    fun `a timestamp that does not match its position deletes nothing`() = runBlocking {
        seed(row(t0), row(t0 + 300))
        api.once { req -> answer(req).replace("\"$t0\"", "\"${t0 + 1}\"") }
        sender().drain()
        assertEquals(2, dao.rows.size)
    }

    @Test
    fun `totals that contradict the verdicts delete nothing`() = runBlocking {
        seed(row(t0))
        api.once { req -> answer(req).replace(""""accepted":1,"ignored":0""", """"accepted":0,"ignored":1""") }
        sender().drain()
        assertEquals(1, dao.rows.size)
    }

    @Test
    fun `an unparseable body deletes nothing and backs off`() = runBlocking {
        seed(row(t0))
        api.once { "<html>502</html>" }
        sender().drain()
        assertEquals(now + 30_000, dao.rows.single().nextAttemptAt)
    }

    // ---- retryable global failures -------------------------------------------------------

    @Test
    fun `no network, 11 and 16 back the whole batch off together, exponentially`() = runBlocking {
        seed(row(t0), row(t0 + 300))
        val s = sender()
        api.onceUnreachable()
        assertEquals(ScrobbleSender.Result.WaitUntil(now + 30_000), s.drain())
        now += 30_000
        api.once { """{"error":11,"message":"offline"}""" }
        assertEquals(ScrobbleSender.Result.WaitUntil(now + 60_000), s.drain())
        now += 60_000
        api.once { """{"error":16,"message":"temporary"}""" }
        assertEquals(ScrobbleSender.Result.WaitUntil(now + 120_000), s.drain())
        assertTrue(dao.rows.all { it.attempts == 3 })
        assertEquals(3, api.requests.size)
    }

    // ---- error 9 ---------------------------------------------------------------------------

    @Test
    fun `error 9 invalidates exactly that session, keeps every row and stops`() = runBlocking {
        seed(row(t0), row(t0 + 300))
        api.once { """{"error":9,"message":"Invalid session key"}""" }
        assertEquals(ScrobbleSender.Result.Reauth, sender().drain())
        assertEquals(listOf("listener-x" to "sk-x-1"), invalidated)
        assertEquals(2, dao.rows.size)
        assertTrue("rows untouched", dao.rows.all { it.attempts == 0 && it.nextAttemptAt == 0L })
        assertEquals("no retry", 1, api.requests.size)
        assertEquals(ScrobbleSender.Result.NotLinked, sender().drain())
    }

    @Test
    fun `after re-authenticating as the same account the rows go`() = runBlocking {
        seed(row(t0))
        api.once { """{"error":9,"message":"Invalid session key"}""" }
        val s = sender()
        s.drain()
        stored = LastfmStoredSession(sessionKey = "sk-x-2", username = "listener-x")
        api.always { answer(it) }
        s.drain()
        assertEquals("sk-x-2", api.requests.last().params["sk"])
        assertTrue(dao.rows.isEmpty())
    }

    // ---- non-retryable global failures ------------------------------------------------------

    @Test
    fun `8, 29, 6, 7 and config errors keep rows, never retry, and block the process`() = runBlocking {
        for (code in listOf(8, 29, 6, 7, 10, 13, 26, 4, 2, 3, 5, 27, 77)) {
            dao.rows.clear(); api.requests.clear()
            seed(row(t0), row(t0 + 300))
            val s = sender()
            api.once { """{"error":$code,"message":"x"}""" }
            assertEquals("code $code", ScrobbleSender.Result.Blocked, s.drain())
            assertEquals("one request, no split, no retry for $code", 1, api.requests.size)
            assertTrue("rows untouched for $code", dao.rows.size == 2 && dao.rows.all { it.attempts == 0 && it.nextAttemptAt == 0L })
            assertTrue(s.blockedUntilProcessRestart)
            now += 24 * 3_600_000L
            assertEquals(ScrobbleSender.Result.Blocked, s.drain())
            assertEquals("later triggers are no-ops for $code", 1, api.requests.size)
        }
    }

    @Test
    fun `a new process may try again once`() = runBlocking {
        seed(row(t0))
        api.once { """{"error":8,"message":"x"}""" }
        sender().drain()
        api.always { answer(it) }
        sender().drain()                                           // a fresh instance
        assertTrue(dao.rows.isEmpty())
    }

    // ---- accounts --------------------------------------------------------------------------

    @Test
    fun `rows of another account are never sent`() = runBlocking {
        seed(row(t0, user = "listener-y"))
        assertEquals(ScrobbleSender.Result.Idle, sender().drain())
        assertTrue(api.requests.isEmpty())
        stored = LastfmStoredSession.EMPTY
        assertEquals(ScrobbleSender.Result.NotLinked, sender().drain())
    }

    @Test
    fun `a disconnect during a request cannot bring a purged row back`() = runBlocking {
        seed(row(t0))
        val gate = CompletableDeferred<Unit>()
        api.once { req -> answer(req) { "4" } }                    // would defer the row
        api.holdUntil = gate
        val drain = async(Dispatchers.Default) { sender().drain() }
        api.started.await()
        dao.rows.clear()                                           // explicit disconnect purged it
        stored = LastfmStoredSession.EMPTY
        gate.complete(Unit)
        drain.await()
        assertTrue("an UPDATE by key recreates nothing", dao.rows.isEmpty())
        assertEquals("and no new request starts", 1, api.requests.size)
    }

    // ---- concurrency and restart ---------------------------------------------------------------

    @Test
    fun `two drains at once send each row exactly once`() = runBlocking {
        (0 until 3).forEach { seed(row(t0 + it)) }
        val gate = CompletableDeferred<Unit>()
        api.always { answer(it) }
        api.holdUntil = gate
        val s = sender()
        val a = async(Dispatchers.Default) { s.drain() }
        api.started.await()
        val b = async(Dispatchers.Default) { s.drain() }
        kotlinx.coroutines.delay(100)
        gate.complete(Unit)
        a.await(); b.await()
        assertEquals(1, api.requests.size)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `rows in backoff wait across a restart and go when due`() = runBlocking {
        seed(row(t0))
        api.onceUnreachable()
        sender().drain()
        val next = dao.rows.single().nextAttemptAt
        assertEquals(ScrobbleSender.Result.WaitUntil(next), sender().drain())   // restarted, too early
        now = next
        api.always { answer(it) }
        sender().drain()
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `logs carry no key, signature, artist, title or username`() = runBlocking {
        seed(row(t0))
        api.always { answer(it) }
        sender().drain()
        val text = logged.toString()
        for (secret in listOf("sk-x-1", "Artist", "Title", "listener-x", "api_sig", "fedcba")) {
            assertTrue(secret, !text.contains(secret))
        }
    }

    // ---- fakes ------------------------------------------------------------------------------

    private class ScriptedApi : LastfmApi {
        val requests = mutableListOf<LastfmRequest>()
        private val once = ArrayDeque<(LastfmRequest) -> LastfmTransportResult>()
        private var always: ((LastfmRequest) -> LastfmTransportResult)? = null
        @Volatile var holdUntil: CompletableDeferred<Unit>? = null
        val started = CompletableDeferred<Unit>()

        fun once(body: (LastfmRequest) -> String) { once += { LastfmTransportResult.Body(body(it)) } }
        fun onceUnreachable() { once += { LastfmTransportResult.Unreachable("IOException") } }
        fun always(body: (LastfmRequest) -> String) { always = { LastfmTransportResult.Body(body(it)) } }

        override suspend fun send(request: LastfmRequest): LastfmTransportResult {
            synchronized(requests) { requests += request }
            started.complete(Unit)
            holdUntil?.await()
            val next = synchronized(once) { once.removeFirstOrNull() } ?: always
            return next?.invoke(request) ?: LastfmTransportResult.Unreachable("Unscripted")
        }
    }

    private class MemoryDao : ScrobbleQueueDao {
        val rows = java.util.Collections.synchronizedList(mutableListOf<ScrobbleQueueEntry>())

        private fun key(r: ScrobbleQueueEntry) = r.streamId to r.startedAt

        override suspend fun insert(entry: ScrobbleQueueEntry): Long = synchronized(rows) {
            if (rows.any { key(it) == key(entry) }) -1L else { rows += entry; rows.size.toLong() }
        }
        override suspend fun find(streamId: String, startedAt: Long) =
            synchronized(rows) { rows.firstOrNull { key(it) == (streamId to startedAt) } }
        override suspend fun count() = rows.size
        override suspend fun pendingFor(username: String, limit: Int) = activeHead(username, Long.MIN_VALUE, limit)
        override suspend fun deleteForUsername(username: String) = synchronized(rows) {
            val before = rows.size; rows.removeAll { it.lastfmUsername == username }; before - rows.size
        }
        override suspend fun activeHead(username: String, quarantined: Long, limit: Int) = synchronized(rows) {
            rows.filter { it.lastfmUsername == username && it.nextAttemptAt != quarantined }
                .sortedWith(compareBy({ it.startedAt }, { it.streamId })).take(limit)
        }
        override suspend fun deleteOccurrence(streamId: String, startedAt: Long) = synchronized(rows) {
            if (rows.removeAll { key(it) == (streamId to startedAt) }) 1 else 0
        }
        override suspend fun recordAttempt(streamId: String, startedAt: Long, nextAttemptAt: Long) = synchronized(rows) {
            val i = rows.indexOfFirst { key(it) == (streamId to startedAt) }
            if (i < 0) 0 else { rows[i] = rows[i].copy(attempts = rows[i].attempts + 1, nextAttemptAt = nextAttemptAt); 1 }
        }
    }
}
