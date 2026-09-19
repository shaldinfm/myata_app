package com.example.musicplayerapp.data.lastfm.nowplaying

import com.example.musicplayerapp.data.lastfm.LastfmApi
import com.example.musicplayerapp.data.lastfm.LastfmRequest
import com.example.musicplayerapp.data.lastfm.LastfmRequestFactory
import com.example.musicplayerapp.data.lastfm.LastfmStoredSession
import com.example.musicplayerapp.data.lastfm.LastfmTransportResult
import com.example.musicplayerapp.data.lastfm.SharedSecretSigner
import com.example.musicplayerapp.scrobble.FeedObservation
import com.example.musicplayerapp.scrobble.OccurrenceId
import com.example.musicplayerapp.scrobble.OpenedOccurrence
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
 * Now Playing against a scripted transport (G6b P6b). Nothing reaches Last.fm; the
 * key and secret are fabricated.
 */
class NowPlayingSenderTest {

    private val t0 = 1_789_000_000L
    private var stored = LastfmStoredSession(sessionKey = "sk-x-1", username = "listener-x")
    private var writes = true
    private var configured = true
    private val api = ScriptedApi()
    private val invalidated = mutableListOf<Pair<String, String>>()
    private val logged = mutableListOf<String>()
    private var attempts = NowPlayingAttempts()

    private fun sender() = NowPlayingSender(
        session = { stored },
        requests = {
            if (configured) LastfmRequestFactory("0123456789abcdef0123456789abcdef", SharedSecretSigner("fedcba9876543210fedcba9876543210"))
            else null
        },
        api = { api },
        writesEnabled = { writes },
        invalidateSession = { u, k -> invalidated += u to k },
        attempts = attempts,
        scope = CoroutineScope(Dispatchers.Unconfined),
        log = { name, fields -> logged += name + fields.toList() },
    )

    private fun opened(startedAt: Long = t0, stream: String = "myata") =
        OpenedOccurrence(OccurrenceId(stream, startedAt), "k$startedAt", "Some Artist", "Some Title", 215)

    private fun send(s: NowPlayingSender, o: OpenedOccurrence) = runBlocking { s.onOccurrenceOpened(o)?.join() }

    @Test
    fun `one exact updateNowPlaying - artist, track, duration and auth, nothing else`() {
        api.reply = """{"nowplaying":{"track":{"#text":"t"},"artist":{"#text":"a"}}}"""
        send(sender(), opened())
        val p = api.requests.single().params
        assertEquals("track.updateNowPlaying", p["method"])
        assertEquals("Some Artist", p["artist"])
        assertEquals("Some Title", p["track"])
        assertEquals("215", p["duration"])
        assertEquals("sk-x-1", p["sk"])
        for (absent in listOf("chosenByUser", "streamId", "album", "mbid", "timestamp")) {
            assertTrue(absent, p.keys.none { it.startsWith(absent) })
        }
    }

    @Test
    fun `the same airing is attempted once per account per process`() {
        val s = sender()
        send(s, opened())
        send(s, opened())                                   // repeat, or a switch back to it
        send(sender(), opened())                            // a recreated service, same process
        assertEquals(1, api.requests.size)
    }

    @Test
    fun `a new airing, another account, or a fresh process may each announce`() {
        val s = sender()
        send(s, opened(t0))
        send(s, opened(t0 + 200))                           // new started_at
        stored = LastfmStoredSession(sessionKey = "sk-y", username = "listener-y")
        send(s, opened(t0 + 200))                           // another account
        attempts = NowPlayingAttempts()                     // a fresh process
        stored = LastfmStoredSession(sessionKey = "sk-x-1", username = "listener-x")
        send(sender(), opened(t0 + 200))
        assertEquals(4, api.requests.size)
    }

    @Test
    fun `a failed attempt is never retried`() {
        val s = sender()
        api.unreachable = true
        send(s, opened())
        api.unreachable = false
        api.reply = """{"error":16,"message":"temporary"}"""
        send(s, opened())
        assertEquals(1, api.requests.size)
    }

    @Test
    fun `error 9 invalidates exactly the session the request used`() {
        api.reply = """{"error":9,"message":"Invalid session key"}"""
        send(sender(), opened())
        assertEquals(listOf("listener-x" to "sk-x-1"), invalidated)
    }

    @Test
    fun `other errors only log`() {
        for (code in listOf(8, 11, 16, 29, 6)) {
            attempts = NowPlayingAttempts()
            api.reply = """{"error":$code,"message":"x"}"""
            send(sender(), opened())
        }
        assertTrue(invalidated.isEmpty())
    }

    @Test
    fun `nothing is sent with the write gate closed, unlinked, or unconfigured`() {
        writes = false
        assertNull(sender().onOccurrenceOpened(opened()))
        writes = true
        stored = LastfmStoredSession(username = "listener-x")          // re-auth required
        assertNull(sender().onOccurrenceOpened(opened()))
        stored = LastfmStoredSession.EMPTY
        assertNull(sender().onOccurrenceOpened(opened()))
        stored = LastfmStoredSession(sessionKey = "sk-x-1", username = "listener-x")
        configured = false
        assertNull(sender().onOccurrenceOpened(opened()))
        assertTrue(api.requests.isEmpty())
    }

    @Test
    fun `logs carry no artist, title, username, key or signature`() {
        api.reply = """{"error":9,"message":"x"}"""
        send(sender(), opened())
        val text = logged.toString()
        for (secret in listOf("Some Artist", "Some Title", "listener-x", "sk-x-1", "api_sig")) {
            assertTrue(secret, !text.contains(secret))
        }
    }

    // ---- with the real tracker ------------------------------------------------------

    @Test
    fun `tracker to sender - one request per airing, even across a recreated service`() {
        api.reply = """{"nowplaying":{}}"""
        fun serviceInstance(): ScrobbleTracker {
            val s = sender()
            return ScrobbleTracker(
                isEnabled = { true }, sink = { }, emissions = ScrobbleEmissions(),
                onOccurrenceOpened = { runBlocking { s.onOccurrenceOpened(it)?.join() } },
            )
        }
        val a = serviceInstance()
        a.onPlaying(true, "myata", 0)
        a.onObservation(FeedObservation("myata", "A", "T", t0, 180, t0 + 180, t0), 0)
        a.onObservation(FeedObservation("myata", "A", "T", t0, 180, t0 + 180, t0 + 10), 10_000)
        a.release()
        val b = serviceInstance()                           // the service is recreated
        b.onPlaying(true, "myata", 20_000)
        b.onObservation(FeedObservation("myata", "A", "T", t0, 180, t0 + 180, t0 + 20), 20_000)
        assertEquals(1, api.requests.size)
    }

    private class ScriptedApi : LastfmApi {
        val requests = mutableListOf<LastfmRequest>()
        var reply = """{"nowplaying":{}}"""
        var unreachable = false
        override suspend fun send(request: LastfmRequest): LastfmTransportResult {
            requests += request
            return if (unreachable) LastfmTransportResult.Unreachable("IOException") else LastfmTransportResult.Body(reply)
        }
    }
}
