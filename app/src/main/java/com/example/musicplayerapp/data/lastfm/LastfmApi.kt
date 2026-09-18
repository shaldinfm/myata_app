package com.example.musicplayerapp.data.lastfm

/**
 * The one boundary between this feature and the network.
 *
 * Everything above it builds inert [LastfmRequest]s and parses strings; everything
 * that actually reaches Last.fm is an implementation of this. There are exactly
 * three: [HttpLastfmApi] in a shipped app, `OfflineLastfmApi` in every ordinary
 * instrumentation run, and hand-written fakes in the JVM tests.
 *
 * Deliberately below the protocol. It sends a request and returns what came back,
 * and knows nothing about tokens, sessions or error codes - so the auth rules live
 * in [LastfmAuth], where they can be tested against a fake that returns any body at
 * all, and this stays small enough that the real one is obviously correct.
 */
interface LastfmApi {

    /** Sends [request]. Never throws for a network failure - see [LastfmTransportResult]. */
    suspend fun send(request: LastfmRequest): LastfmTransportResult
}

/**
 * What one send produced.
 *
 * Two outcomes and no more: a body, or no body. An HTTP error status with a body is
 * still [Body], because Last.fm answers errors with a JSON envelope and a 4xx, and
 * the envelope is what says whether to retry, restart or give up. Only a failure to
 * get any answer at all is [Unreachable].
 */
sealed class LastfmTransportResult {

    /** The response body, whatever the status. Parsed by [LastfmResponses]. */
    data class Body(val text: String) : LastfmTransportResult() {
        /** Never the body: on the auth path it holds a token or a session key. */
        override fun toString(): String = "Body(${text.length} chars)"
    }

    /**
     * No response.
     *
     * @property kind the exception's class name and **only** that. Network
     *   exception messages can quote the request URL, and `auth.getToken` /
     *   `auth.getSession` are GETs whose query carries `api_sig` and the pending
     *   token - so a message is never kept, logged or passed on. The class name is
     *   enough to tell a timeout from a DNS failure in a log.
     */
    data class Unreachable(val kind: String) : LastfmTransportResult()
}
