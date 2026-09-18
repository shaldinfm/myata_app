package com.example.musicplayerapp.data.lastfm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `user.getInfo`: an unsigned read by explicit username, used for the lifetime count
 * and for nothing else. Fabricated credentials, a scripted transport.
 */
class LastfmAccountTest {

    private val requests = LastfmRequestFactory(
        "0123456789abcdef0123456789abcdef",
        SharedSecretSigner("fedcba9876543210fedcba9876543210"),
    )

    @Test
    fun `user getInfo is an unsigned GET for the explicit username`() {
        val request = requests.userGetInfo("f0ul482")

        assertEquals("user.getInfo", request.method)
        assertEquals(LastfmHttpMethod.GET, request.httpMethod)
        assertEquals(
            mapOf(
                "method" to "user.getInfo",
                "user" to "f0ul482",
                "api_key" to "0123456789abcdef0123456789abcdef",
                "format" to "json",
            ),
            request.params,
        )
        // Owner decision: never signed, never carries the session key - it is not a
        // session check and must not be able to act as one.
        assertFalse("no signature", "api_sig" in request.params)
        assertFalse("no session key", "sk" in request.params)
    }

    @Test
    fun `the lifetime playcount is read whether it is a string or a number`() {
        fun count(raw: String) = (LastfmResponses.userInfo(
            """{"user":{"name":"f0ul482","playcount":$raw,"subscriber":"0"}}"""
        ) as LastfmResult.Ok).value.playcount

        assertEquals(22_258L, count("\"22258\""))
        assertEquals(22_258L, count("22258"))
        // Past Int range - a lifetime count on a heavy account can be large, and it
        // is a Long end to end.
        assertEquals(3_000_000_000L, count("\"3000000000\""))
    }

    @Test
    fun `an account with no playcount is still an account`() {
        val result = LastfmResponses.userInfo("""{"user":{"name":"f0ul482"}}""")

        val account = (result as LastfmResult.Ok).value
        assertEquals("f0ul482", account.username)
        assertNull(account.playcount)
    }

    @Test
    fun `a response without a user is malformed`() {
        assertTrue(LastfmResponses.userInfo("""{"ok":true}""") is LastfmResult.Malformed)
        assertTrue(LastfmResponses.userInfo("""{"user":{"playcount":"1"}}""") is LastfmResult.Malformed)
    }

    @Test
    fun `an error envelope is a failure`() {
        val result = LastfmResponses.userInfo("""{"error":6,"message":"User not found"}""")
        assertEquals(6, (result as LastfmResult.Failure).code)
    }

    // ---- through the auth rules ---------------------------------------------------

    private class OneShotApi(private val reply: LastfmTransportResult) : LastfmApi {
        val sent = mutableListOf<LastfmRequest>()
        override suspend fun send(request: LastfmRequest): LastfmTransportResult {
            sent += request
            return reply
        }
    }

    private class LinkedStore : LastfmSessionStore {
        var session = LastfmStoredSession(sessionKey = "sk-1", username = "f0ul482")
        var writes = 0
        override fun read() = session
        override fun write(session: LastfmStoredSession) { writes++; this.session = session }
    }

    private fun auth(api: LastfmApi, store: LinkedStore) =
        LastfmAuth(api, requests, store, clock = { 1_789_700_000_000L }, io = Dispatchers.Unconfined)

    @Test
    fun `the playcount is fetched for the stored username`() = runBlocking {
        val api = OneShotApi(LastfmTransportResult.Body("""{"user":{"name":"f0ul482","playcount":"1248"}}"""))
        val store = LinkedStore()

        assertEquals(1_248L, auth(api, store).fetchPlaycount("f0ul482"))
        assertEquals("f0ul482", api.sent.single().params["user"])
        assertEquals("a read changes nothing on disk", 0, store.writes)
    }

    @Test
    fun `every failure hides the count and none of them unlinks`() = runBlocking {
        val failures = listOf(
            LastfmTransportResult.Unreachable("IOException"),
            LastfmTransportResult.Body("""{"error":6,"message":"User not found"}"""),
            LastfmTransportResult.Body("""{"error":29,"message":"Rate limit"}"""),
            // Even error 9: user.getInfo is unauthenticated, so it says nothing about
            // whether the session is valid, and must never lead to re-auth.
            LastfmTransportResult.Body("""{"error":9,"message":"Invalid session key"}"""),
            LastfmTransportResult.Body("<html>portal</html>"),
        )
        for (reply in failures) {
            val store = LinkedStore()
            val auth = auth(OneShotApi(reply), store)

            assertNull("$reply", auth.fetchPlaycount("f0ul482"))
            assertEquals("$reply must not touch the store", 0, store.writes)
            assertTrue("$reply must leave the account linked", auth.link() is LastfmLink.Linked)
        }
    }
}
