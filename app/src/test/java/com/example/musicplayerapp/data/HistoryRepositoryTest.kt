package com.example.musicplayerapp.data

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * What a history request is reported as (G4b).
 *
 * The repository used to answer every failure with an empty list, which is why
 * the full-screen history could not tell `history-empty` from `history-error`.
 * These pin the split: an answer the endpoint calls successful is [HistoryResult.Loaded]
 * - including an empty one - and everything else is [HistoryResult.Failed].
 *
 * No server: an interceptor answers in place of the network, so the request the
 * repository builds is the real one and only the reply is canned.
 */
class HistoryRepositoryTest {

    @After
    fun clearOverride() {
        HistoryRepository.sourceOverrideForTest = null
    }

    @Test
    fun `a successful answer with tracks is loaded`() {
        val result = fetch(200, """{"success":true,"stream":"myata","data":[
            {"artist":"MUSE","track":"CRYOGEN","played_at":1757590000,"played_at_formatted":"10:45"},
            {"artist":"FOALS","track":"NORTHERN LINE","played_at":1757589700,"played_at_formatted":"10:40"}
        ]}""")

        assertTrue(result is HistoryResult.Loaded)
        val tracks = (result as HistoryResult.Loaded).tracks
        assertEquals(listOf("CRYOGEN", "NORTHERN LINE"), tracks.map { it.title })
        assertEquals("10:45", tracks.first().getFormattedTime())
    }

    /** `history-empty`: the station answered, and had nothing. */
    @Test
    fun `a successful answer with no tracks is loaded and empty, not failed`() {
        assertEquals(HistoryResult.Loaded(emptyList()), fetch(200, """{"success":true,"data":[]}"""))
    }

    /** What the old code did with a missing `data`, kept: the endpoint said it succeeded. */
    @Test
    fun `a successful answer with no data field is loaded and empty`() {
        assertEquals(HistoryResult.Loaded(emptyList()), fetch(200, """{"success":true}"""))
    }

    @Test
    fun `an HTTP error is failed`() {
        assertTrue(fetch(503, "Service Unavailable") is HistoryResult.Failed)
        assertTrue(fetch(404, "") is HistoryResult.Failed)
    }

    @Test
    fun `success false is failed`() {
        assertTrue(fetch(200, """{"success":false,"data":null}""") is HistoryResult.Failed)
    }

    /** A captive portal or a PHP error page answers 200 with HTML. */
    @Test
    fun `a body that is not JSON is failed`() {
        assertTrue(fetch(200, "<html><body>Fatal error</body></html>") is HistoryResult.Failed)
    }

    @Test
    fun `an empty body is failed`() {
        assertTrue(fetch(200, "") is HistoryResult.Failed)
    }

    @Test
    fun `no network is failed`() {
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { throw IOException("offline") })
            .build()
        val result = runBlocking { HistoryRepository(client).fetchHistory("myata", 30) }
        assertTrue(result is HistoryResult.Failed)
    }

    @Test
    fun `the request is the one that always shipped`() {
        var seen: okhttp3.Request? = null
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                seen = chain.request()
                respond(chain, 200, """{"success":true,"data":[]}""")
            })
            .build()
        runBlocking { HistoryRepository(client).fetchHistory("gold", 30) }

        val url = seen!!.url
        assertEquals("radiomyata.ru", url.host)
        assertEquals("/api_track_history.php", url.encodedPath)
        assertEquals("gold", url.queryParameter("stream"))
        assertEquals("30", url.queryParameter("limit"))
        assertEquals("no-cache", seen!!.header("Cache-Control"))
    }

    @Test
    fun `the test override answers in place of the network`() {
        val canned = HistoryResult.Failed("canned")
        HistoryRepository.sourceOverrideForTest = { _, _ -> canned }
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { error("the network must not be reached") })
            .build()
        assertEquals(canned, runBlocking { HistoryRepository(client).fetchHistory("myata", 30) })
    }

    // ---- harness ----

    private fun fetch(code: Int, body: String): HistoryResult {
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain -> respond(chain, code, body) })
            .build()
        return runBlocking { HistoryRepository(client).fetchHistory("myata", 30) }
    }

    private fun respond(chain: Interceptor.Chain, code: Int, body: String): Response =
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("canned")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
}
