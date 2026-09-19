package com.example.musicplayerapp.data.lastfm

import com.example.musicplayerapp.BuildConfig
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The write gate at the transport, the last line (G6b P6b).
 *
 * The real [HttpLastfmApi], over a client whose only interceptor answers locally:
 * no socket is ever opened, and the interceptor records whether a request would
 * have left the app. Credentials are fabricated.
 */
class LastfmWriteGateTest {

    private val factory = LastfmRequestFactory(
        "0123456789abcdef0123456789abcdef",
        SharedSecretSigner("fedcba9876543210fedcba9876543210"),
    )
    private val reachedNetwork = mutableListOf<String>()
    private val client = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            reachedNetwork += chain.request().method
            Response.Builder()
                .request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"ok":true}""".toResponseBody("application/json".toMediaType()))
                .build()
        })
        .build()
    private val api = HttpLastfmApi(client)

    private val scrobble = factory.scrobble(listOf(LastfmScrobble("A", "T", 1_789_000_000L, durationSec = 200)), "sk-fake")!!
    private val nowPlaying = factory.updateNowPlaying("A", "T", "sk-fake", durationSec = 200)!!

    @After
    fun reset() {
        LastfmBackend.overrideWritesForTest(null)
        LastfmBackend.disarmForTest()
    }

    @Test
    fun `this build is write-disabled by default`() {
        assertFalse("no -PlastfmLiveWrites=true: writes are off", BuildConfig.LASTFM_LIVE_WRITES)
        assertFalse(LastfmBackend.writesEnabled)
    }

    @Test
    fun `track scrobble and updateNowPlaying never leave the app while the gate is closed`() = runBlocking {
        LastfmBackend.armForProduction()                     // even an armed transport refuses
        for (request in listOf(scrobble, nowPlaying)) {
            assertEquals(
                request.method,
                LastfmTransportResult.Unreachable(HttpLastfmApi.WRITES_DISABLED),
                api.send(request),
            )
        }
        assertTrue("no request reached the network", reachedNetwork.isEmpty())
    }

    @Test
    fun `the refusal comes before anything else, armed or not`() = runBlocking {
        assertEquals(LastfmTransportResult.Unreachable(HttpLastfmApi.WRITES_DISABLED), api.send(scrobble))
        assertTrue(reachedNetwork.isEmpty())
    }

    @Test
    fun `reads and auth are untouched by the gate`() = runBlocking {
        LastfmBackend.armForProduction()
        val read = api.send(factory.userGetInfo("someone"))
        val auth = api.send(factory.authGetToken()!!)
        assertTrue(read is LastfmTransportResult.Body)
        assertTrue(auth is LastfmTransportResult.Body)
        assertEquals(listOf("GET", "GET"), reachedNetwork)
    }

    @Test
    fun `only the test override opens it, and then writes go through the transport`() = runBlocking {
        LastfmBackend.armForProduction()
        LastfmBackend.overrideWritesForTest(true)
        assertTrue(api.send(scrobble) is LastfmTransportResult.Body)
        assertEquals(listOf("POST"), reachedNetwork)
    }

    @Test
    fun `the write methods are exactly the two that write`() {
        assertEquals(setOf("track.scrobble", "track.updateNowPlaying"), LastfmBackend.WRITE_METHODS)
        assertEquals("track.scrobble", scrobble.method)
        assertEquals("track.updateNowPlaying", nowPlaying.method)
    }
}
