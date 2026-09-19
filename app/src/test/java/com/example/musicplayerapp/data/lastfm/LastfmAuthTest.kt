package com.example.musicplayerapp.data.lastfm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The browser-auth state machine, against a scripted transport.
 *
 * Nothing here can reach Last.fm: [ScriptedApi] answers from a queue and records
 * what it was asked, the store is a variable, and the clock is a number. Every
 * credential is fabricated.
 */
class LastfmAuthTest {

    private var now = 1_789_700_000_000L
    private val store = MemoryStore()
    private val api = ScriptedApi()
    private val requests = LastfmRequestFactory(
        "0123456789abcdef0123456789abcdef",
        SharedSecretSigner("fedcba9876543210fedcba9876543210"),
    )

    /** Every purge of the scrobble queue, with the session as it stood at that moment. */
    private val purges = mutableListOf<Pair<String, LastfmStoredSession>>()

    private fun auth(requests: LastfmRequestFactory? = this.requests) =
        LastfmAuth(
            api, requests, store, clock = { now }, io = Dispatchers.Unconfined,
            queue = { username -> purges += username to store.session },
        )

    private val hour = LastfmLink.PENDING_TOKEN_LIFETIME_MS

    // ---- begin ------------------------------------------------------------------

    @Test
    fun `connect commits the pending token before the browser can open`() = runBlocking {
        api.reply("auth.getToken", """{"token":"tok-1"}""")
        val issuedBefore = now
        // Advance the clock from inside the transport: issued-at must be the time
        // BEFORE the request, not after it.
        api.onSend = { now += 5_000 }

        val result = auth().begin()

        // The caller opens the browser only once it holds OpenBrowser, and by then
        // the token is already on disk - that is the whole guarantee.
        assertTrue("$result", result is LastfmAuth.Begin.OpenBrowser)
        assertEquals("tok-1", store.session.pendingToken)
        assertEquals("issued-at is taken before the request", issuedBefore, store.session.pendingTokenIssuedAt)
        assertEquals(1, store.writes)
        assertTrue((result as LastfmAuth.Begin.OpenBrowser).url.contains("token=tok-1"))
    }

    @Test
    fun `a network failure while connecting stores nothing`() = runBlocking {
        api.unreachable("auth.getToken")

        assertEquals(LastfmAuth.Begin.Unreachable, auth().begin())
        assertEquals(LastfmStoredSession.EMPTY, store.session)
    }

    @Test
    fun `a refused api key while connecting stores nothing`() = runBlocking {
        api.reply("auth.getToken", """{"error":10,"message":"Invalid API key"}""")

        assertEquals(LastfmAuth.Begin.Refused, auth().begin())
        assertEquals(LastfmStoredSession.EMPTY, store.session)
    }

    @Test
    fun `an unconfigured build cannot connect and sends nothing`() = runBlocking {
        assertEquals(LastfmAuth.Begin.NotConfigured, auth(requests = null).begin())
        assertEquals(0, api.calls.size)
    }

    // ---- resume -----------------------------------------------------------------

    @Test
    fun `resuming with nothing pending makes no request`() = runBlocking {
        assertEquals(LastfmAuth.Resume.Idle, auth().resume())
        assertEquals(0, api.calls.size)
    }

    @Test
    fun `resuming with a live token exchanges it once and links`() = runBlocking {
        pending("tok-1")
        api.reply("auth.getSession", SESSION_OK)

        assertEquals(LastfmAuth.Resume.Linked, auth().resume())
        assertEquals(listOf("auth.getSession"), api.calls)
        assertEquals(
            "the session in and the token out, in one write",
            LastfmStoredSession(sessionKey = "sk-1", username = "f0ul482"),
            store.session,
        )
        assertTrue(auth().link() is LastfmLink.Linked)
    }

    @Test
    fun `error 14 keeps the same token and stays pending`() = runBlocking {
        pending("tok-1")
        api.reply("auth.getSession", """{"error":14,"message":"Unauthorized Token"}""")

        assertEquals(LastfmAuth.Resume.StillPending, auth().resume())
        assertEquals("tok-1", store.session.pendingToken)
        assertTrue(auth().link() is LastfmLink.Pending)
    }

    @Test
    fun `error 4 discards the token`() = runBlocking {
        pending("tok-1")
        api.reply("auth.getSession", """{"error":4,"message":"Invalid authentication token"}""")

        assertEquals(LastfmAuth.Resume.Restart, auth().resume())
        assertNull(store.session.pendingToken)
        assertNull(store.session.pendingTokenIssuedAt)
        assertEquals(LastfmLink.NotLinked, auth().link())
    }

    @Test
    fun `error 15 discards the token`() = runBlocking {
        pending("tok-1")
        api.reply("auth.getSession", """{"error":15,"message":"Token expired"}""")

        assertEquals(LastfmAuth.Resume.Restart, auth().resume())
        assertNull(store.session.pendingToken)
    }

    @Test
    fun `a network failure keeps the token and never asks for another`() = runBlocking {
        pending("tok-1")
        api.unreachable("auth.getSession")

        assertEquals(LastfmAuth.Resume.Unreachable, auth().resume())
        assertEquals("tok-1", store.session.pendingToken)
        assertFalse("no silent new token", "auth.getToken" in api.calls)
    }

    @Test
    fun `a temporary Last fm error keeps the token`() = runBlocking {
        pending("tok-1")
        api.reply("auth.getSession", """{"error":16,"message":"Temporary error"}""")

        assertEquals(LastfmAuth.Resume.Unreachable, auth().resume())
        assertEquals("tok-1", store.session.pendingToken)
    }

    @Test
    fun `an unreadable answer keeps the token`() = runBlocking {
        pending("tok-1")
        api.reply("auth.getSession", "<html>captive portal</html>")

        assertEquals(LastfmAuth.Resume.Unreachable, auth().resume())
        assertEquals("tok-1", store.session.pendingToken)
    }

    @Test
    fun `a refused api key during the exchange discards the token`() = runBlocking {
        pending("tok-1")
        api.reply("auth.getSession", """{"error":10,"message":"Invalid API key"}""")

        assertEquals(LastfmAuth.Resume.Refused, auth().resume())
        assertNull(store.session.pendingToken)
    }

    // ---- expiry -----------------------------------------------------------------

    @Test
    fun `a token at exactly 60 minutes is discarded locally without a request`() = runBlocking {
        pending("tok-1", issuedAt = now - hour)

        assertEquals(LastfmAuth.Resume.Expired, auth().resume())
        assertEquals(0, api.calls.size)
        assertNull(store.session.pendingToken)
    }

    @Test
    fun `a token one millisecond short of 60 minutes is still exchanged`() = runBlocking {
        pending("tok-1", issuedAt = now - hour + 1)
        api.reply("auth.getSession", SESSION_OK)

        assertEquals(LastfmAuth.Resume.Linked, auth().resume())
    }

    @Test
    fun `a clock that moved backwards invalidates the token`() = runBlocking {
        // Issued "in the future": there is no way to know how old it really is.
        pending("tok-1", issuedAt = now + 60_000)

        assertEquals(LastfmAuth.Resume.Expired, auth().resume())
        assertEquals(0, api.calls.size)
        assertNull(store.session.pendingToken)
    }

    // ---- reopen / cancel / disconnect ------------------------------------------

    @Test
    fun `reopening Last fm uses the same token and makes no request`() = runBlocking {
        pending("tok-1")

        val result = auth().openOrBegin()

        assertTrue((result as LastfmAuth.Begin.OpenBrowser).url.contains("token=tok-1"))
        assertEquals(0, api.calls.size)
        assertEquals("tok-1", store.session.pendingToken)
    }

    @Test
    fun `reopening with an expired token starts over with a new one`() = runBlocking {
        pending("tok-old", issuedAt = now - hour)
        api.reply("auth.getToken", """{"token":"tok-new"}""")

        val result = auth().openOrBegin()

        assertTrue((result as LastfmAuth.Begin.OpenBrowser).url.contains("token=tok-new"))
        assertEquals("tok-new", store.session.pendingToken)
    }

    @Test
    fun `cancel forgets the pending token and returns to disconnected`() = runBlocking {
        pending("tok-1")

        auth().cancelPending()

        assertEquals(LastfmStoredSession.EMPTY, store.session)
        assertEquals(LastfmLink.NotLinked, auth().link())
        assertEquals(0, api.calls.size)
    }

    @Test
    fun `disconnect clears all four fields and tells Last fm nothing`() = runBlocking {
        store.session = LastfmStoredSession(
            sessionKey = "sk-1", username = "f0ul482",
            pendingToken = "tok-stale", pendingTokenIssuedAt = now,
        )

        auth().disconnect()

        assertEquals(LastfmStoredSession.EMPTY, store.session)
        assertEquals(LastfmLink.NotLinked, auth().link())
        assertEquals("disconnect is local only", 0, api.calls.size)
    }

    @Test
    fun `disconnect from the re-auth state clears the retained username too`() = runBlocking {
        store.session = LastfmStoredSession(username = "f0ul482")
        assertTrue(auth().link() is LastfmLink.ReauthRequired)

        auth().disconnect()

        assertEquals(LastfmStoredSession.EMPTY, store.session)
    }

    // ---- the scrobble queue (G6b P5) --------------------------------------------

    @Test
    fun `explicit disconnect purges that account's queue before and after the session goes`() = runBlocking {
        val linked = LastfmStoredSession(sessionKey = "sk-1", username = "f0ul482")
        store.session = linked

        auth().disconnect()

        assertEquals(
            "once while still linked, once after - so nothing queued in between survives",
            listOf("f0ul482" to linked, "f0ul482" to LastfmStoredSession.EMPTY),
            purges,
        )
        assertEquals(LastfmStoredSession.EMPTY, store.session)
    }

    @Test
    fun `disconnect from the re-auth state is still explicit, and purges that account`() = runBlocking {
        store.session = LastfmStoredSession(username = "f0ul482")

        auth().disconnect()

        assertEquals(listOf("f0ul482", "f0ul482"), purges.map { it.first })
    }

    @Test
    fun `disconnect with no account purges nothing`() = runBlocking {
        pending("tok-1")
        auth().disconnect()
        store.session = LastfmStoredSession.EMPTY
        auth().disconnect()
        assertTrue(purges.isEmpty())
    }

    @Test
    fun `re-auth, cancel, connect and resume never touch the queue`() = runBlocking {
        store.session = LastfmStoredSession(username = "f0ul482")        // re-auth required
        auth().link()
        auth().resume()
        pending("tok-1")
        auth().cancelPending()
        api.reply("auth.getToken", """{"token":"tok-2"}""")
        auth().begin()
        api.reply("auth.getSession", SESSION_OK)
        auth().resume()
        assertTrue("only an explicit disconnect purges", purges.isEmpty())
    }

    // ---- error 9 on a write (G6b P6a) ---------------------------------------------

    @Test
    fun `error 9 clears exactly the failing session key and keeps the username`() = runBlocking {
        store.session = LastfmStoredSession(sessionKey = "sk-1", username = "f0ul482")

        assertTrue(auth().invalidateSession("f0ul482", "sk-1"))

        assertEquals(LastfmStoredSession(username = "f0ul482"), store.session)
        assertEquals(LastfmLink.ReauthRequired("f0ul482"), auth().link())
        assertTrue("re-auth is not a disconnect: the queue is untouched", purges.isEmpty())
        assertEquals("nothing sent", 0, api.calls.size)
    }

    @Test
    fun `a late error 9 about an old key cannot clear a newer session`() = runBlocking {
        store.session = LastfmStoredSession(sessionKey = "sk-2", username = "f0ul482")

        assertFalse(auth().invalidateSession("f0ul482", "sk-1"))

        assertEquals(LastfmStoredSession(sessionKey = "sk-2", username = "f0ul482"), store.session)
    }

    @Test
    fun `error 9 for another account changes nothing`() = runBlocking {
        store.session = LastfmStoredSession(sessionKey = "sk-1", username = "someone-else")
        assertFalse(auth().invalidateSession("f0ul482", "sk-1"))
        assertEquals("someone-else", store.session.username)
        assertEquals("sk-1", store.session.sessionKey)
    }

    // ---- the lock ---------------------------------------------------------------

    @Test
    fun `two resumes at once make exactly one exchange and stay linked`() = runBlocking {
        pending("tok-1")
        val gate = CompletableDeferred<Unit>()
        api.reply("auth.getSession", SESSION_OK, holdUntil = gate)
        // A second exchange of the same token would be refused - and without the
        // lock that refusal would discard the pending state after the link.
        api.reply("auth.getSession", """{"error":4,"message":"Invalid authentication token"}""")

        // Hold the first exchange open long enough that the second resume is
        // certainly waiting on the lock rather than finishing first.
        val first = async(Dispatchers.Default) { auth().resume() }
        api.awaitCalls(1)
        val second = async(Dispatchers.Default) { auth().resume() }
        kotlinx.coroutines.delay(150)
        assertEquals("the second resume must be blocked, not sending", 1, api.calls.size)
        gate.complete(Unit)
        val results = listOf(first, second).awaitAll()

        assertEquals(1, api.calls.count { it == "auth.getSession" })
        assertTrue(LastfmAuth.Resume.Linked in results)
        assertTrue(auth().link() is LastfmLink.Linked)
    }

    // ---- helpers ----------------------------------------------------------------

    private fun pending(token: String, issuedAt: Long = now - 1_000) {
        store.session = LastfmStoredSession(pendingToken = token, pendingTokenIssuedAt = issuedAt)
    }

    private class MemoryStore : LastfmSessionStore {
        @Volatile var session = LastfmStoredSession.EMPTY
        @Volatile var writes = 0
        override fun read() = session
        override fun write(session: LastfmStoredSession) {
            writes++
            this.session = session
        }
    }

    private class ScriptedApi : LastfmApi {
        private val replies = mutableMapOf<String, ArrayDeque<Pair<LastfmTransportResult, CompletableDeferred<Unit>?>>>()
        val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
        var onSend: () -> Unit = {}

        suspend fun awaitCalls(n: Int) {
            val deadline = System.currentTimeMillis() + 5_000
            while (calls.size < n) {
                check(System.currentTimeMillis() < deadline) { "expected $n calls, saw ${calls.size}" }
                kotlinx.coroutines.delay(5)
            }
        }

        fun reply(method: String, body: String, holdUntil: CompletableDeferred<Unit>? = null) {
            replies.getOrPut(method) { ArrayDeque() }.addLast(LastfmTransportResult.Body(body) to holdUntil)
        }

        fun unreachable(method: String) {
            replies.getOrPut(method) { ArrayDeque() }.addLast(LastfmTransportResult.Unreachable("IOException") to null)
        }

        override suspend fun send(request: LastfmRequest): LastfmTransportResult {
            calls += request.method
            onSend()
            val (result, hold) = synchronized(replies) {
                replies[request.method]?.removeFirstOrNull()
            } ?: error("unscripted call to ${request.method}")
            hold?.await()
            return result
        }
    }

    private companion object {
        const val SESSION_OK = """{"session":{"name":"f0ul482","key":"sk-1","subscriber":0}}"""
    }
}
