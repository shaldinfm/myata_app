package com.example.musicplayerapp.data.lastfm

/**
 * One Last.fm call, fully described and not yet made.
 *
 * Deliberately inert. This package constructs and parses; it does not own a
 * transport, does not import OkHttp, and cannot reach the network by accident -
 * which is what makes every test in it a pure JVM test and what keeps Foundation's
 * "zero Last.fm network calls" a property of the code rather than a promise about
 * it. Whatever eventually performs these will take a [LastfmRequest] and the app's
 * shared `SecureNetModule` client.
 *
 * @property method the Last.fm method name, e.g. `track.scrobble`. Also present in
 *   [params]; kept separately because logs and errors want to name the call
 *   without rummaging through a map that holds a signature.
 * @property httpMethod what the specification requires. Not a preference: the
 *   write methods fail over GET.
 * @property params every parameter to send, `api_sig` and `format` included. The
 *   map is complete - a caller adds nothing.
 */
data class LastfmRequest(
    val method: String,
    val httpMethod: LastfmHttpMethod,
    val params: Map<String, String>,
) {

    /**
     * Never the parameters.
     *
     * `sk` is a listener's session key and `api_sig` is derived from the shared
     * secret; a request interpolated into a log line must not carry either. The
     * method name is the part that is useful in a log anyway.
     */
    override fun toString(): String = "LastfmRequest($method, $httpMethod, ${params.size} params)"
}

/** The HTTP verb a method requires. */
enum class LastfmHttpMethod { GET, POST }

/**
 * Builds signed Last.fm requests.
 *
 * Takes its api key and [LastfmSigner] as constructor arguments rather than
 * reading [LastfmConfig], for the reason [SharedSecretSigner] does: the tests
 * construct one with fake credentials, so no test can involve a real key, and the
 * builders stay pure functions of their inputs. [forThisBuild] is the single place
 * that binds one to the build's own credentials.
 *
 * ## The signing order is the part that is easy to get wrong
 *
 * Every builder does the same three steps, and their order is the contract:
 *
 *  1. assemble the parameters the call needs, plus `method` and `api_key`;
 *  2. sign **that** map - before `format` is added and obviously before `api_sig`
 *     itself is, since neither may be part of its own input;
 *  3. add `api_sig` and `format=json`.
 *
 * `format` is excluded from the signature by [LastfmSignature] rather than by
 * being added late, so step 3 could safely happen in either order. It is written
 * this way anyway: two independent reasons for the same thing to be right is how
 * this stops being a source of [LastfmError.INVALID_METHOD_SIGNATURE].
 *
 * ## Every builder can return null
 *
 * Null means this build has no credentials, which is an ordinary state and not an
 * error - see [LastfmSigner.sign]. A caller with null has nothing to send.
 */
class LastfmRequestFactory(
    private val apiKey: String,
    private val signer: LastfmSigner,
) {

    /**
     * Step 1 of authentication: ask for a request token.
     *
     * Signed, though it authenticates nobody - `auth.getToken` requires `api_sig`
     * like every other method that is not a plain read. The token it returns is
     * valid for 60 minutes and is what the listener authorises in their browser.
     */
    fun authGetToken(): LastfmRequest? =
        build("auth.getToken", LastfmHttpMethod.GET, emptyMap())

    /**
     * Step 3 of authentication: exchange an authorised token for a session key.
     *
     * Consumes [token]. The three ways this fails are all expected at least once -
     * [LastfmError.TOKEN_NOT_AUTHORIZED] while the listener has not granted access
     * yet, [LastfmError.TOKEN_EXPIRED] after an hour,
     * [LastfmError.AUTHENTICATION_FAILED] for a token that was never ours - and
     * `actionForCode` tells them apart.
     */
    fun authGetSession(token: String): LastfmRequest? =
        build("auth.getSession", LastfmHttpMethod.GET, mapOf("token" to token))

    /**
     * "Now playing", announced once when a track starts.
     *
     * Not durable and never queued: it describes the present, so a failure has
     * nothing worth retrying - by the time a retry landed it would be announcing
     * the wrong track.
     *
     * [durationSec] is optional and is sent only when the station's metadata can
     * actually prove it. Sending a guess would be worse than sending nothing:
     * Last.fm shows it to the listener.
     *
     * There is no `chosenByUser` here. The parameter does not exist on this method -
     * it belongs to `track.scrobble` alone.
     */
    fun updateNowPlaying(
        artist: String,
        track: String,
        sessionKey: String,
        album: String? = null,
        durationSec: Int? = null,
    ): LastfmRequest? {
        val params = buildMap {
            put("artist", artist)
            put("track", track)
            put("sk", sessionKey)
            album?.takeIf { it.isNotBlank() }?.let { put("album", it) }
            durationSec?.takeIf { it > 0 }?.let { put("duration", it.toString()) }
        }
        return build("track.updateNowPlaying", LastfmHttpMethod.POST, params)
    }

    /**
     * A batch of finished plays.
     *
     * Array notation, `artist[0]`, `track[0]`, `timestamp[0]` and so on, at most
     * [MAX_BATCH] per request - Last.fm's documented ceiling. A larger list is a
     * caller bug rather than something to silently truncate, because truncating
     * would drop scrobbles without anybody noticing.
     *
     * Returns null for an empty list as well as for an unconfigured build: there is
     * no request to make either way.
     */
    fun scrobble(scrobbles: List<LastfmScrobble>, sessionKey: String): LastfmRequest? {
        if (scrobbles.isEmpty()) return null
        require(scrobbles.size <= MAX_BATCH) {
            "a scrobble batch may hold at most $MAX_BATCH entries, got ${scrobbles.size}"
        }

        val params = buildMap {
            put("sk", sessionKey)
            scrobbles.forEachIndexed { i, s ->
                put("artist[$i]", s.artist)
                put("track[$i]", s.track)
                put("timestamp[$i]", s.startedAtUtcSeconds.toString())
                // Always sent, always 0. Radio is never user-selected, and Last.fm
                // assumes 1 when the parameter is absent - so leaving it out would
                // quietly claim the opposite of what is true.
                put("chosenByUser[$i]", "0")
                s.album?.takeIf { it.isNotBlank() }?.let { put("album[$i]", it) }
                s.durationSec?.takeIf { it > 0 }?.let { put("duration[$i]", it.toString()) }
            }
        }
        return build("track.scrobble", LastfmHttpMethod.POST, params)
    }

    /**
     * The browser URL for [token], with this factory's own api key - so the key in
     * the URL can never disagree with the key the token was requested with.
     */
    fun authorizationUrlFor(token: String): String = authorizationUrl(apiKey, token)

    private fun build(
        method: String,
        httpMethod: LastfmHttpMethod,
        params: Map<String, String>,
    ): LastfmRequest? {
        // Step 1: what the call is, and who is calling.
        val signed = buildMap {
            putAll(params)
            put("method", method)
            put("api_key", apiKey)
        }

        // Step 2: sign exactly that, before anything unsignable joins it.
        val signature = signer.sign(signed) ?: return null

        // Step 3: the two parameters that are sent but never signed.
        return LastfmRequest(
            method = method,
            httpMethod = httpMethod,
            params = signed + mapOf("api_sig" to signature, "format" to "json"),
        )
    }

    companion object {

        /** Where every one of these is sent. */
        const val ENDPOINT = "https://ws.audioscrobbler.com/2.0/"

        /**
         * Where the listener authorises this application, opened in their browser.
         *
         * The desktop authentication flow, which needs no callback: the listener
         * grants access on Last.fm's own site and simply returns to the app, which
         * retries `auth.getSession` on resume. No password is ever typed into this
         * app, and no deep link or intent filter exists for Last.fm to redirect to.
         */
        fun authorizationUrl(apiKey: String, token: String): String =
            "https://www.last.fm/api/auth/?api_key=$apiKey&token=$token"

        /** Last.fm's documented maximum number of scrobbles in one `track.scrobble`. */
        const val MAX_BATCH = 50

        /**
         * The factory for this build, or null when it has no credentials.
         *
         * The one place [LastfmConfig] and request construction meet.
         */
        fun forThisBuild(): LastfmRequestFactory? {
            val signer = LastfmSigners.forThisBuild() ?: return null
            return LastfmRequestFactory(LastfmConfig.apiKey, signer)
        }
    }
}

/**
 * One finished play, as `track.scrobble` needs it.
 *
 * Transport-shaped rather than storage-shaped: this is what a queue row becomes on
 * the way out, and it deliberately has no id, no attempt count and no stream. The
 * queue row that owns those arrives with the Room migration in a later phase.
 *
 * @property startedAtUtcSeconds when the track started, in UTC seconds. Last.fm's
 *   own requirement, and in this app it comes from the station's `server_time`
 *   rather than the device clock - so a phone with the wrong time still scrobbles
 *   at the right moment.
 * @property durationSec the track's length, when the station's metadata can prove
 *   it. Null rather than a guess.
 */
data class LastfmScrobble(
    val artist: String,
    val track: String,
    val startedAtUtcSeconds: Long,
    val album: String? = null,
    val durationSec: Int? = null,
)
