package com.example.musicplayerapp.data.lastfm

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Everything this app keeps about a listener's Last.fm link, as it sits on disk.
 *
 * Four fields and no more (owner decision). There is deliberately no `linked_at`,
 * no stored expiry and no re-auth flag: expiry is derived from
 * [pendingTokenIssuedAt], and the re-auth state is derived from which fields are
 * present - see [LastfmLink.of].
 *
 * The session key and the pending token are the two values in this feature that
 * belong to a person rather than to the app, so [toString] never renders either.
 */
data class LastfmStoredSession(
    val sessionKey: String? = null,
    val username: String? = null,
    val pendingToken: String? = null,
    val pendingTokenIssuedAt: Long? = null,
) {
    override fun toString(): String =
        "LastfmStoredSession(sessionKey=${present(sessionKey)}, username=$username, " +
            "pendingToken=${present(pendingToken)}, pendingTokenIssuedAt=$pendingTokenIssuedAt)"

    private fun present(value: String?): String = if (value.isNullOrEmpty()) "none" else "<redacted>"

    companion object {
        /** Nothing linked and nothing in flight - what Disconnect writes. */
        val EMPTY = LastfmStoredSession()
    }
}

/**
 * The link, as the rest of the feature reasons about it: derived from the four
 * stored fields and the current time, never stored itself.
 *
 * ## Why the re-auth state needs no field of its own
 *
 * A username with no session key and no live pending token can only mean "was
 * linked, and the session is gone" - which is exactly [ReauthRequired]. Deriving it
 * keeps the store at four fields and means the future error-9 path (scrobbling) only
 * has to clear the session key and keep the username; no migration, no new key.
 *
 * Nothing in P3b produces that combination. A successful exchange writes the key and
 * the username together; Disconnect clears all four. So in a P3b build this state is
 * reachable only by a test that writes the store directly - supported, rendered,
 * and dormant until an authenticated method can answer error 9.
 *
 * ## Precedence
 *
 * [Linked] over everything; then a *live* [Pending] token; then [ReauthRequired];
 * then [NotLinked]. So starting a reconnect from the re-auth state shows the pending
 * card, and cancelling it returns to re-auth rather than pretending the account was
 * never there.
 */
sealed class LastfmLink {

    data object NotLinked : LastfmLink()

    data class Pending(val token: String, val issuedAt: Long) : LastfmLink() {
        override fun toString(): String = "Pending(token=<redacted>, issuedAt=$issuedAt)"
    }

    data class Linked(val sessionKey: String, val username: String) : LastfmLink() {
        override fun toString(): String = "Linked(sessionKey=<redacted>, username=$username)"
    }

    data class ReauthRequired(val username: String) : LastfmLink()

    companion object {

        /**
         * How long a request token is usable: Last.fm's documented 60 minutes.
         *
         * Measured from [LastfmStoredSession.pendingTokenIssuedAt], which is taken
         * **before** `auth.getToken` is sent - so it is never later than the moment
         * Last.fm issued the token, and this window always closes no later than
         * Last.fm's. No safety margin is needed on top.
         */
        const val PENDING_TOKEN_LIFETIME_MS: Long = 60 * 60 * 1000L

        fun of(stored: LastfmStoredSession, nowMs: Long): LastfmLink {
            val key = stored.sessionKey
            val name = stored.username
            if (!key.isNullOrEmpty() && !name.isNullOrEmpty()) return Linked(key, name)

            val token = stored.pendingToken
            val issuedAt = stored.pendingTokenIssuedAt
            if (!token.isNullOrEmpty() && issuedAt != null && isPendingLive(issuedAt, nowMs)) {
                return Pending(token, issuedAt)
            }

            if (!name.isNullOrEmpty()) return ReauthRequired(name)
            return NotLinked
        }

        /**
         * Whether a token issued at [issuedAt] may still be exchanged at [nowMs].
         *
         * A clock that has moved **backwards** past the issue time invalidates the
         * token rather than extending it: there is then no way to know how long it
         * has really been, and guessing long is how a dead token gets sent.
         */
        fun isPendingLive(issuedAt: Long, nowMs: Long): Boolean =
            nowMs >= issuedAt && nowMs - issuedAt < PENDING_TOKEN_LIFETIME_MS
    }
}

/**
 * Reads and replaces the stored session.
 *
 * An interface so the auth rules can be tested on the JVM against an in-memory
 * store; the app uses [PrefsLastfmSessionStore].
 */
interface LastfmSessionStore {

    fun read(): LastfmStoredSession

    /** Replaces all four fields at once. Never a partial update. */
    fun write(session: LastfmStoredSession)
}

/**
 * `lastfm_session` preferences.
 *
 * ## Every write is `commit()`, and every write is all four fields
 *
 * Not `apply()`: `IdentityStore` records why - an `apply()` shortly before a
 * process death can be lost, and here the process death is not hypothetical. The
 * pending token is committed immediately before the browser opens, and the browser
 * is exactly when Android is most likely to reclaim this process. A token that did
 * not survive would leave the listener authorising a request this app has forgotten.
 *
 * All four keys go in one editor, so a successful exchange - session key and
 * username in, pending token out - lands as one file write. There is no state in
 * which the token has been spent and the session not yet stored.
 *
 * ## Excluded from backup
 *
 * `res/xml/lastfm_backup_rules.xml` and `lastfm_data_extraction_rules.xml` exclude
 * this file and only this file, so a session key never travels to cloud backup or a
 * new device. The rest of the app's backup behaviour is unchanged.
 */
class PrefsLastfmSessionStore(context: Context) : LastfmSessionStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun read(): LastfmStoredSession = LastfmStoredSession(
        sessionKey = prefs.getString(KEY_SESSION_KEY, null),
        username = prefs.getString(KEY_USERNAME, null),
        pendingToken = prefs.getString(KEY_PENDING_TOKEN, null),
        pendingTokenIssuedAt =
            if (prefs.contains(KEY_PENDING_ISSUED_AT)) prefs.getLong(KEY_PENDING_ISSUED_AT, 0L) else null,
    )

    override fun write(session: LastfmStoredSession) {
        // IdentityStore's idiom: one KTX editor, committed synchronously.
        prefs.edit(commit = true) {
            putOrRemove(KEY_SESSION_KEY, session.sessionKey)
            putOrRemove(KEY_USERNAME, session.username)
            putOrRemove(KEY_PENDING_TOKEN, session.pendingToken)
            val issuedAt = session.pendingTokenIssuedAt
            if (issuedAt == null) remove(KEY_PENDING_ISSUED_AT) else putLong(KEY_PENDING_ISSUED_AT, issuedAt)
        }
    }

    private fun SharedPreferences.Editor.putOrRemove(key: String, value: String?) {
        if (value.isNullOrEmpty()) remove(key) else putString(key, value)
    }

    companion object {
        /** The file name the backup rules exclude. Change both together. */
        const val FILE = "lastfm_session"

        const val KEY_SESSION_KEY = "session_key"
        const val KEY_USERNAME = "username"
        const val KEY_PENDING_TOKEN = "pending_token"
        const val KEY_PENDING_ISSUED_AT = "pending_token_issued_at"
    }
}
