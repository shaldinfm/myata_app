package com.example.musicplayerapp

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayerapp.data.supabase.DeletionStatusOutcome
import com.example.musicplayerapp.data.supabase.SupabaseEmailAuthApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The exact request `checkDeletionStatus` puts on the wire.
 *
 * ## Why this suite exists at all
 *
 * The status route is the one call in the app that must work with **no session**, and
 * supabase-kt 3.2.6 cannot express it correctly for this project's key. Left on the
 * library's authenticated path it would send the publishable key as
 * `Authorization: Bearer`, which Supabase documents as rejected - a
 * `sb_publishable_...` key is not a JWT, and the platform parses that header as one.
 * The library fixed this in 3.7.0 (`checkIsNewApiKey`); the version here is pinned by
 * a Kotlin-toolchain constraint and predates the concept entirely.
 *
 * So the request is built directly, and **the absence of the `Authorization` header is
 * the property under test**. Nothing else in an offline suite would notice it coming
 * back: a future refactor routing this through the authenticated path would compile,
 * pass every other test, and fail only against the live project, on the one code path
 * that runs when a listener's account is already gone.
 *
 * ## No network, no real project
 *
 * Every request is short-circuited by an OkHttp interceptor that answers from memory.
 * The base URL and key are **synthetic** - the real ones are never read, so this suite
 * cannot reach production even by mistake, and needs no `liveSupabase` opt-in.
 */
@RunWith(AndroidJUnit4::class)
class DeletionStatusTransportTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val syntheticBaseUrl = "https://probe.invalid"
    private val syntheticKey = "sb_publishable_SYNTHETIC0TEST0KEY0NOT0REAL"

    private val request = "99999999-9999-4999-8999-999999999999"
    private val uid = "11111111-1111-4111-8111-111111111111"

    /** What the interceptor saw, and what it answered with. */
    private class Capture(private val body: String, private val code: Int = 200) {
        var seen: Request? = null
        var requestBody: String? = null

        fun client(): OkHttpClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                seen = req
                requestBody = Buffer().also { req.body?.writeTo(it) }.readUtf8()
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("synthetic")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()
    }

    private fun api(capture: Capture) = SupabaseEmailAuthApi(
        context = context,
        statusHttpClient = capture.client(),
        statusBaseUrl = syntheticBaseUrl,
        statusApiKey = syntheticKey,
    )

    /** Method, path, and that the project origin is the one configured. */
    @Test
    fun a_posts_to_the_rpc_path() = runBlocking {
        val capture = Capture("""{"outcome":"UNKNOWN"}""")

        api(capture).checkDeletionStatus(request, uid)

        val seen = capture.seen
        assertNotNull("no request reached the engine", seen)
        assertEquals("POST", seen!!.method)
        assertEquals("/rest/v1/rpc/account_deletion_status", seen.url.encodedPath)
        assertEquals("probe.invalid", seen.url.host)
        assertEquals("https", seen.url.scheme)
        // No PostgREST parameters smuggled into the query string.
        assertEquals(0, seen.url.querySize)
    }

    /**
     * **The load-bearing assertion.** `apikey` carries the key; `Authorization` is
     * absent entirely.
     */
    @Test
    fun b_sends_apikey_and_no_authorization() = runBlocking {
        val capture = Capture("""{"outcome":"UNKNOWN"}""")

        api(capture).checkDeletionStatus(request, uid)

        val seen = capture.seen!!
        assertEquals(syntheticKey, seen.header("apikey"))
        assertNull("Authorization must be absent", seen.header("Authorization"))
        // Case-insensitively too: OkHttp headers are case-insensitive on lookup, but
        // spell the lower-case form out so a hand-rolled header cannot slip past.
        assertNull(seen.header("authorization"))
        assertTrue(
            "no header may carry the key except apikey",
            seen.headers.none { (name, value) -> value == syntheticKey && name != "apikey" },
        )
    }

    /** Exactly two parameters, exactly the values passed in, and nothing else. */
    @Test
    fun c_body_carries_only_the_two_parameters() = runBlocking {
        val capture = Capture("""{"outcome":"UNKNOWN"}""")

        api(capture).checkDeletionStatus(request, uid)

        val body = Json.parseToJsonElement(capture.requestBody!!).jsonObject
        assertEquals(setOf("p_request_id", "p_deleted_uid"), body.keys)
        assertEquals(request, body["p_request_id"]!!.jsonPrimitive.contentOrNull)
        assertEquals(uid, body["p_deleted_uid"]!!.jsonPrimitive.contentOrNull)
        assertEquals(
            "application/json; charset=utf-8",
            capture.seen!!.body!!.contentType().toString(),
        )
    }

    /** UNKNOWN decodes to Unknown. */
    @Test
    fun d_decodes_unknown() = runBlocking {
        val capture = Capture("""{"outcome":"UNKNOWN"}""")

        val result = api(capture).checkDeletionStatus(request, uid)

        assertEquals(DeletionStatusOutcome.Unknown, result)
    }

    /** COMPLETED decodes to Completed. */
    @Test
    fun e_decodes_completed() = runBlocking {
        val capture = Capture("""{"outcome":"COMPLETED"}""")

        val result = api(capture).checkDeletionStatus(request, uid)

        assertEquals(DeletionStatusOutcome.Completed, result)
    }

    /** An outcome this build does not know is a failure, never a silent Unknown. */
    @Test
    fun f_an_unrecognised_outcome_is_a_failure() = runBlocking {
        val capture = Capture("""{"outcome":"SOMETHING_NEW"}""")

        val result = api(capture).checkDeletionStatus(request, uid)

        assertTrue("$result", result is DeletionStatusOutcome.Failed)
    }

    /**
     * A non-2xx is a failure whose detail carries the status code and nothing else.
     *
     * Asserted rather than assumed because this failure is the one most likely to be
     * logged, and a response body could echo the request back.
     */
    @Test
    fun g_an_http_error_is_a_failure_without_the_key() = runBlocking {
        val capture = Capture("""{"message":"boom"}""", code = 401)

        val result = api(capture).checkDeletionStatus(request, uid)

        assertTrue("$result", result is DeletionStatusOutcome.Failed)
        val detail = result.toString()
        assertTrue("the status code should be reported", detail.contains("401"))
        assertTrue("the key must never appear in a failure", !detail.contains(syntheticKey))
    }

    /** A build with no project configured fails without attempting a request. */
    @Test
    fun h_no_configuration_makes_no_request() = runBlocking {
        val capture = Capture("""{"outcome":"UNKNOWN"}""")

        val result = SupabaseEmailAuthApi(
            context = context,
            statusHttpClient = capture.client(),
            statusBaseUrl = "",
            statusApiKey = "",
        ).checkDeletionStatus(request, uid)

        assertTrue("$result", result is DeletionStatusOutcome.Failed)
        assertNull("nothing should have been sent", capture.seen)
    }

    /** A trailing slash on the project URL does not produce a doubled path separator. */
    @Test
    fun i_a_trailing_slash_in_the_base_url_is_tolerated() = runBlocking {
        val capture = Capture("""{"outcome":"UNKNOWN"}""")

        SupabaseEmailAuthApi(
            context = context,
            statusHttpClient = capture.client(),
            statusBaseUrl = "$syntheticBaseUrl/",
            statusApiKey = syntheticKey,
        ).checkDeletionStatus(request, uid)

        assertEquals("/rest/v1/rpc/account_deletion_status", capture.seen!!.url.encodedPath)
    }
}
