package com.example.musicplayerapp.data.lastfm

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * What a Last.fm call came back with.
 *
 * Three outcomes, not two. "The service said no" and "that was not a Last.fm
 * response at all" need different handling - the first has a code that says
 * exactly what to do, the second usually means a captive portal or a proxy page
 * and is worth retrying - and collapsing them loses that.
 */
sealed class LastfmResult<out T> {

    /** The call succeeded and [value] is what it produced. */
    data class Ok<out T>(val value: T) : LastfmResult<T>()

    /** Anything that is not a success. Always carries the action to take. */
    sealed class NotOk : LastfmResult<Nothing>() {
        abstract val action: LastfmAction
    }

    /**
     * Last.fm answered with an error envelope.
     *
     * @property code the API error code, mapped by [actionForCode].
     * @property message Last.fm's own text. **For logs only** - it is English,
     *   unlocalised and written for developers, and no screen in this app shows it.
     */
    data class Failure(val code: Int, val message: String) : NotOk() {
        val error: LastfmError? get() = LastfmError.from(code)
        override val action: LastfmAction get() = actionForCode(code)
    }

    /**
     * The body was not a Last.fm response: not JSON, not an object, or missing the
     * field the method is defined to return.
     *
     * Retried rather than dropped. In the field this is overwhelmingly a network
     * telling us something other than what we asked for - a hotel portal, a
     * transparent proxy, a 502 page - which is transient by nature. A genuine
     * protocol change would show up as this too, and retrying a handful of times
     * before the queue's attempt cap parks the row is an acceptable price for not
     * discarding scrobbles on a flaky connection.
     *
     * @property reason for the log. Never contains the body, which could hold a
     *   session key on the auth path.
     */
    data class Malformed(val reason: String) : NotOk() {
        override val action: LastfmAction get() = LastfmAction.Retry
    }
}

/**
 * A listener's authenticated session.
 *
 * @property sessionKey has an infinite lifetime by default and dies only when the
 *   listener revokes this application. It is the one value in this feature that
 *   belongs to a person rather than to the app, and it must never be logged - see
 *   [toString].
 */
data class LastfmSession(
    val username: String,
    val sessionKey: String,
    val subscriber: Boolean,
) {
    /** The username is safe to log; the key never is. */
    override fun toString(): String = "LastfmSession($username, key=<redacted>, subscriber=$subscriber)"
}

/**
 * A Last.fm account as `user.getInfo` describes it.
 *
 * @property playcount the account's **lifetime** scrobble total across every client
 *   it has ever used - not this app's contribution, which is why the card labels it
 *   `Всего в Last.fm`. Null when the response carried none.
 */
data class LastfmAccount(val username: String, val playcount: Long?)

/**
 * What Last.fm did with a batch of scrobbles.
 *
 * @property accepted how many it recorded, from the response's own `@attr`.
 * @property ignored how many it declined, likewise.
 * @property verdicts one entry per scrobble in the batch, in request order.
 */
data class LastfmScrobbleOutcome(
    val accepted: Int,
    val ignored: Int,
    val verdicts: List<LastfmScrobbleVerdict>,
)

/**
 * Last.fm's judgement on one scrobble in a batch.
 *
 * What the sender does with each [ignored] value is `ScrobblePolicy`'s decision -
 * some are terminal for the row, some defer it, an unknown one quarantines it, and
 * [IgnoredScrobble.MISSING] means the disposition is not known at all.
 *
 * @property artist and [track] as Last.fm recorded them, which may differ from what
 *   was sent - it corrects spellings against its own catalogue. Kept because a
 *   later phase may want to show what was actually scrobbled.
 */
data class LastfmScrobbleVerdict(
    val artist: String,
    val track: String,
    val timestamp: Long,
    val ignored: IgnoredScrobble,
)

/**
 * Turns Last.fm's JSON into the types above.
 *
 * Pure string-to-value, with no transport anywhere near it, so every case here is
 * a JVM test over a literal body.
 *
 * ## Why this reads the tree by hand instead of binding data classes
 *
 * Last.fm's JSON is a mechanical translation of its XML and carries two habits
 * that defeat straightforward binding:
 *
 *  - **a one-element list is an object, not an array.** `track.scrobble` with one
 *    scrobble returns `"scrobble": { ... }`; with two it returns
 *    `"scrobble": [ ... ]`. A batch of one is the overwhelmingly common case in a
 *    radio app, and a parser that assumed an array would work all through
 *    development and fail on almost every real drain;
 *  - **values arrive as strings or as numbers interchangeably.** `@attr.accepted`
 *    and `ignoredMessage.code` are both seen either way.
 *
 * Both are handled once, here, by [asObjectList] and [intOf].
 */
object LastfmResponses {

    /** `auth.getToken` -> the request token. */
    fun token(body: String): LastfmResult<String> = parse(body) { root ->
        val token = root.stringOrNull("token")
            ?: return@parse LastfmResult.Malformed("no token in auth.getToken response")
        LastfmResult.Ok(token)
    }

    /** `auth.getSession` -> the listener's session. */
    fun session(body: String): LastfmResult<LastfmSession> = parse(body) { root ->
        val session = root.objectOrNull("session")
            ?: return@parse LastfmResult.Malformed("no session in auth.getSession response")
        val name = session.stringOrNull("name")
        val key = session.stringOrNull("key")
        if (name == null || key == null) {
            return@parse LastfmResult.Malformed("session without a name or a key")
        }
        LastfmResult.Ok(
            LastfmSession(
                username = name,
                sessionKey = key,
                // Absent on some responses; absent means not a subscriber.
                subscriber = (session.intOf("subscriber") ?: 0) != 0,
            )
        )
    }

    /**
     * `user.getInfo` -> the account's name and lifetime playcount.
     *
     * A response without a playcount is still an account: the count is an
     * enrichment, and its absence is shown as absence rather than as a failure.
     */
    fun userInfo(body: String): LastfmResult<LastfmAccount> = parse(body) { root ->
        val user = root.objectOrNull("user")
            ?: return@parse LastfmResult.Malformed("no user in user.getInfo response")
        val name = user.stringOrNull("name")
            ?: return@parse LastfmResult.Malformed("user without a name")
        LastfmResult.Ok(LastfmAccount(username = name, playcount = user.longOf("playcount")))
    }

    /**
     * `track.updateNowPlaying` -> nothing worth keeping.
     *
     * The response echoes the corrected track back, and this app has no use for it:
     * the announcement is already on screen from the station's own metadata. All
     * that matters is whether it was an error, which [parse] has already decided.
     */
    fun nowPlaying(body: String): LastfmResult<Unit> = parse(body) { root ->
        if (root.objectOrNull("nowplaying") == null) {
            return@parse LastfmResult.Malformed("no nowplaying in track.updateNowPlaying response")
        }
        LastfmResult.Ok(Unit)
    }

    /** `track.scrobble` -> the per-scrobble verdicts and the totals. */
    fun scrobbles(body: String): LastfmResult<LastfmScrobbleOutcome> = parse(body) { root ->
        val scrobbles = root.objectOrNull("scrobbles")
            ?: return@parse LastfmResult.Malformed("no scrobbles in track.scrobble response")

        val attr = scrobbles.objectOrNull("@attr")
        val verdicts = asObjectList(scrobbles["scrobble"]).map { it.toVerdict() }

        LastfmResult.Ok(
            LastfmScrobbleOutcome(
                // The totals are Last.fm's, not ours - but a response that omits them
                // is still usable, so they fall back to counting the verdicts rather
                // than making the whole batch malformed.
                accepted = attr?.intOf("accepted") ?: verdicts.count { it.ignored.accepted },
                ignored = attr?.intOf("ignored") ?: verdicts.count { !it.ignored.accepted },
                verdicts = verdicts,
            )
        )
    }

    /**
     * The shared front half of every parser: valid JSON, an object, and not an
     * error envelope.
     *
     * The error check comes first and applies to every method, because Last.fm
     * returns `{"error":N,"message":"..."}` in place of the expected shape for all
     * of them - including with a `200`, which is why status codes are not what this
     * feature branches on.
     */
    private inline fun <T> parse(
        body: String,
        payload: (JsonObject) -> LastfmResult<T>,
    ): LastfmResult<T> {
        val root = try {
            JsonParser.parseString(body) as? JsonObject
        } catch (e: RuntimeException) {
            // Gson throws JsonSyntaxException, and JsonParseException for a few
            // malformed inputs; both are RuntimeException and both mean the same
            // thing here. The exception is not included in the reason - a parse
            // error can quote the input, and on the auth path the input holds a
            // session key.
            null
        } ?: return LastfmResult.Malformed("response was not a JSON object")

        root.intOf("error")?.let { code ->
            return LastfmResult.Failure(code, root.stringOrNull("message").orEmpty())
        }

        return payload(root)
    }

    private fun JsonObject.toVerdict(): LastfmScrobbleVerdict = LastfmScrobbleVerdict(
        // These are `{"corrected":"0","#text":"..."}` wrappers rather than plain
        // strings, and are read as such; a plain string is accepted too, so a
        // simplification on Last.fm's side would not break this.
        artist = textOf("artist"),
        track = textOf("track"),
        // Long, not Int: these are UTC seconds, and an Int stops being able to hold
        // one in 2038. Nothing else in this app would notice, and a truncated
        // timestamp is the kind of thing that is only ever found in production.
        timestamp = longOf("timestamp") ?: 0L,
        // No code is not "code 0". A verdict Last.fm did not give is unknown, and the
        // sender never deletes a row on an unknown verdict (G6b P6a).
        ignored = objectOrNull("ignoredMessage")?.intOf("code")
            ?.let { IgnoredScrobble.from(it) }
            ?: IgnoredScrobble.MISSING,
    )

    /**
     * The one-element-is-an-object rule, applied once.
     *
     * An absent element is an empty list rather than an error: a batch in which
     * every scrobble was ignored has still been answered.
     */
    private fun asObjectList(element: JsonElement?): List<JsonObject> = when (element) {
        null -> emptyList()
        is JsonArray -> element.mapNotNull { it as? JsonObject }
        is JsonObject -> listOf(element)
        else -> emptyList()
    }

    /** An int whether Last.fm sent it as a number or as a string. */
    private fun JsonObject.intOf(key: String): Int? = longOf(key)?.let {
        if (it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) it.toInt() else null
    }

    /** A long whether Last.fm sent it as a number or as a string. */
    private fun JsonObject.longOf(key: String): Long? {
        val primitive = get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
        return when {
            primitive.isNumber -> primitive.asNumber.toLong()
            primitive.isString -> primitive.asString.trim().toLongOrNull()
            else -> null
        }
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.objectOrNull(key: String): JsonObject? = get(key) as? JsonObject

    /** A `{"#text": "..."}` wrapper, or a plain string, or nothing. */
    private fun JsonObject.textOf(key: String): String = when (val element = get(key)) {
        is JsonObject -> element.stringOrNull("#text").orEmpty()
        else -> element?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
    }
}
