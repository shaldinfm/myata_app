package com.example.musicplayerapp.data.lastfm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The only thing in this app that talks to Last.fm.
 *
 * Built on the app's shared `SecureNetModule` client, so it gets the same
 * connection pool, the same timeouts and the same `network_security_config` as
 * everything else - no second HTTP stack with its own TLS to get right on API 24.
 *
 * `internal`, and constructed only by [LastfmBackend.api]. Nothing else in the app
 * can make one, which is what makes the live-traffic gate a property of the code
 * rather than a convention.
 *
 * ## It refuses to send until the app has armed it
 *
 * [LastfmBackend.armForProduction] is called from `MyataApplication.onCreate` and
 * nowhere else. A JVM test never runs that, so if one ever reached this class it
 * would fail loudly here instead of reaching the network - and that holds even on a
 * developer machine with real credentials, where signing alone would not stop it:
 * `user.getInfo` is unsigned and needs nothing but the public api key.
 *
 * ## Nothing that could hold a secret ever leaves this class
 *
 * `auth.getToken` and `auth.getSession` stay GETs for now (owner decision - POST for
 * them is undocumented and will be verified live first), which puts `api_sig` and
 * the pending token in the query string. So:
 *
 *  - the URL is never logged, and neither is any parameter;
 *  - an exception's message is never kept - some network exceptions quote the URL -
 *    only its class name, via [LastfmTransportResult.Unreachable];
 *  - the body is returned to the parser and never logged here either.
 *
 * The shared client carries no logging interceptor; if one is ever added there, it
 * must not see these requests.
 */
internal class HttpLastfmApi(private val client: OkHttpClient) : LastfmApi {

    override suspend fun send(request: LastfmRequest): LastfmTransportResult {
        // G6b P6b, defence in depth: a write never leaves the app while the write
        // gate is closed, whoever built the request. Before anything else, and before
        // any socket: an answer of "unreachable" that no retry policy may read as
        // transient - the senders check the gate themselves and never get here.
        if (request.method in LastfmBackend.WRITE_METHODS && !LastfmBackend.writesEnabled) {
            return LastfmTransportResult.Unreachable(WRITES_DISABLED)
        }
        check(LastfmBackend.isArmed) {
            // Deliberately says nothing about the request.
            "Last.fm transport used before MyataApplication armed it"
        }

        return withContext(Dispatchers.IO) {
            try {
                client.newCall(toHttp(request)).execute().use { response ->
                    // Any status with a body is an answer: Last.fm's errors are a JSON
                    // envelope on a 4xx, and the envelope decides what happens next.
                    val body = response.body?.string()
                    if (body == null) {
                        LastfmTransportResult.Unreachable("EmptyBody")
                    } else {
                        LastfmTransportResult.Body(body)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The class name and nothing else - see the class comment.
                LastfmTransportResult.Unreachable(e.javaClass.simpleName)
            }
        }
    }

    private fun toHttp(request: LastfmRequest): Request {
        val endpoint = LastfmRequestFactory.ENDPOINT.toHttpUrl()
        return when (request.httpMethod) {
            LastfmHttpMethod.GET -> {
                val url = endpoint.newBuilder().apply {
                    request.params.forEach { (name, value) -> addQueryParameter(name, value) }
                }.build()
                Request.Builder().url(url).get().build()
            }
            LastfmHttpMethod.POST -> {
                val form = FormBody.Builder().apply {
                    request.params.forEach { (name, value) -> add(name, value) }
                }.build()
                Request.Builder().url(endpoint).post(form).build()
            }
        }
    }

    companion object {
        /** The kind reported when the write gate refused a write. Says nothing about the request. */
        const val WRITES_DISABLED = "WritesDisabled"
    }
}
