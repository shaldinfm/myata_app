package com.example.musicplayerapp.data.lastfm

import android.content.Context
import com.example.musicplayerapp.data.lastfm.queue.LastfmScrobbleScheduler
import com.example.musicplayerapp.data.lastfm.queue.ScrobbleQueue
import com.example.musicplayerapp.data.lastfm.queue.ScrobbleQueuePurger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Last.fm's desktop/browser authorisation, as a set of rules over the stored session.
 *
 *  1. `auth.getToken`, then open `last.fm/api/auth/?api_key=…&token=…` in a browser;
 *  2. the listener grants access there - this app never sees their password;
 *  3. when the Last.fm screen resumes, `auth.getSession` exchanges the token.
 *
 * No deep link and no callback: the listener comes back to the app on their own,
 * and resuming is the only trigger.
 *
 * Every rule here is expressed against a [LastfmApi], a [LastfmSessionStore] and a
 * clock, all passed in - so the whole state machine is a JVM test with fakes, and
 * nothing here knows whether it is talking to Last.fm or to a script.
 *
 * ## The pending token
 *
 *  - committed **before** the browser is opened, so a process death while the
 *    listener is in the browser loses nothing;
 *  - usable for [LastfmLink.PENDING_TOKEN_LIFETIME_MS] from before it was requested;
 *    an expired one - or one a backwards clock has made unknowable - is discarded
 *    locally, without a request;
 *  - error 14 (not authorised yet) keeps it, and `Открыть Last.fm` reopens the
 *    browser with the **same** token;
 *  - error 4 or 15 discards it, and the next tap starts over with a new one;
 *  - a network failure keeps it, and never silently asks for another.
 *
 * ## One lock for the whole process
 *
 * Every operation runs under one [Mutex] and re-reads the store inside it. Without
 * that, a second resume arriving while an exchange is in flight would send the same
 * token again, get 4 or 15 for a token the first call already spent, and discard the
 * pending state *after* the first call had linked the account.
 */
class LastfmAuth(
    private val api: LastfmApi,
    private val requests: LastfmRequestFactory?,
    private val store: LastfmSessionStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val queue: ScrobbleQueuePurger = ScrobbleQueuePurger.NONE,
    /**
     * Called once a new session is **stored** (G6b P6b): production asks for a
     * scrobble drain for the account now linked. Never for a pending, failed or
     * cancelled authorisation, and never on disconnect. Only schedules.
     */
    private val onLinked: () -> Unit = {},
) {

    /** What starting (or reopening) authorisation produced. */
    sealed class Begin {

        /** Open this URL in a browser. The token in it is already committed. */
        data class OpenBrowser(val url: String) : Begin() {
            /** The URL carries the request token. */
            override fun toString(): String = "OpenBrowser(<redacted>)"
        }

        /** No answer from Last.fm. Nothing was stored. */
        data object Unreachable : Begin()

        /** Last.fm answered with an error, or with something that was not a token. */
        data object Refused : Begin()

        /** This build has no credentials, so no request can be built. */
        data object NotConfigured : Begin()
    }

    /** What resuming did with a pending token. */
    sealed class Resume {
        /** There was nothing to exchange. No request was made. */
        data object Idle : Resume()

        /** The account is linked. */
        data object Linked : Resume()

        /** Error 14: the listener has not granted access yet. The same token is kept. */
        data object StillPending : Resume()

        /** The token had expired locally and was discarded. No request was made. */
        data object Expired : Resume()

        /** Error 4 or 15: the token is unusable and was discarded. */
        data object Restart : Resume()

        /** No answer. The token is kept, and no new one was requested. */
        data object Unreachable : Resume()

        /** Last.fm refused the exchange for a reason no retry can fix. The token was discarded. */
        data object Refused : Resume()
    }

    /** The link as it is right now. A read of the store and the clock; no request. */
    fun link(): LastfmLink = LastfmLink.of(store.read(), clock())

    /** `Подключить` and `Подключить заново`: always a new token. */
    suspend fun begin(): Begin = lock.withLock { beginLocked() }

    /**
     * `Открыть Last.fm`: reopen the browser with the **same** token while it is live;
     * only when it is not, start over with a new one.
     */
    suspend fun openOrBegin(): Begin = lock.withLock {
        val requests = requests ?: return@withLock Begin.NotConfigured
        when (val link = link()) {
            is LastfmLink.Pending -> Begin.OpenBrowser(requests.authorizationUrlFor(link.token))
            else -> beginLocked()
        }
    }

    /** Called when the Last.fm screen resumes. At most one `auth.getSession`. */
    suspend fun resume(): Resume = lock.withLock {
        val stored = read()
        val now = clock()
        val link = LastfmLink.of(stored, now)
        if (link is LastfmLink.Linked) return@withLock Resume.Idle

        val token = stored.pendingToken
        if (token.isNullOrEmpty()) return@withLock Resume.Idle

        // Expired, or unknowable because the clock went backwards: discard locally
        // rather than spend a request on a token Last.fm will refuse.
        if (link !is LastfmLink.Pending) {
            write(stored.withoutPending())
            return@withLock Resume.Expired
        }

        val request = requests?.authGetSession(token) ?: return@withLock Resume.Idle
        when (val sent = api.send(request)) {
            is LastfmTransportResult.Unreachable -> Resume.Unreachable
            is LastfmTransportResult.Body -> when (val parsed = LastfmResponses.session(sent.text)) {
                is LastfmResult.Ok -> {
                    val retained = stored.username
                    val linkedAs = parsed.value.username
                    withContext(NonCancellable) {
                        // G6b P6a: re-authenticating as a *different* account replaces
                        // the one that needed it. Its queued scrobbles could never be
                        // sent again - never as the new account, and it is no longer
                        // linked to be sent as itself - so they go, before the new
                        // session is stored. Only on that mismatch: the same account
                        // coming back keeps its queue, and a first link has nothing
                        // retained to replace. Not `disconnect()`, which would clear
                        // the session being installed.
                        if (!retained.isNullOrEmpty() && retained != linkedAs) queue.purge(retained)

                        // One write: the session in, the token out. There is no state in
                        // which the token is spent and the session not yet stored.
                        write(LastfmStoredSession(sessionKey = parsed.value.sessionKey, username = linkedAs))

                        // After the commit: whatever the linked account has queued can go.
                        try {
                            onLinked()
                        } catch (e: Exception) {
                            // Linking succeeded; the next trigger or app start will ask again.
                        }
                    }
                    Resume.Linked
                }
                is LastfmResult.Malformed -> Resume.Unreachable
                is LastfmResult.Failure -> when (parsed.action) {
                    LastfmAction.AwaitAuthorization -> Resume.StillPending
                    LastfmAction.RestartAuthorization -> {
                        write(stored.withoutPending())
                        Resume.Restart
                    }
                    LastfmAction.Retry, is LastfmAction.Backoff -> Resume.Unreachable
                    // Anything else - a refused api key, our own signature bug, a code
                    // nobody has named - means this token will never be exchanged.
                    else -> {
                        write(stored.withoutPending())
                        Resume.Refused
                    }
                }
            }
        }
    }

    /** `Отменить`: forget the pending token. The account, if any, is untouched. */
    suspend fun cancelPending() = lock.withLock {
        write(read().withoutPending())
    }

    /**
     * `Отключить`: clear every stored field.
     *
     * Local only. The authorisation this app was granted stays listed at
     * last.fm/settings/applications until the listener revokes it there; nothing
     * here tells Last.fm anything, and no UI may suggest otherwise.
     */
    suspend fun disconnect() = lock.withLock {
        // G6b P5: an explicit disconnect also drops that account's queued scrobbles,
        // so they can never be sent if the account is linked again. Re-auth is not
        // a disconnect and never comes through here: its rows wait for the same
        // account. Purged before the session goes, and again after, so nothing
        // queued in between survives; another account's rows are never touched.
        //
        // The sequence cannot be cancelled once begun: the screen that asked for it may
        // be destroyed at any point, and stopping between the session going and the
        // final purge would leave a row the listener was promised was gone. It is a
        // few milliseconds of suspend work - nothing here blocks a thread.
        withContext(NonCancellable) {
            val username = read().username
            if (!username.isNullOrEmpty()) queue.purge(username)
            write(LastfmStoredSession.EMPTY)
            if (!username.isNullOrEmpty()) queue.purge(username)
        }
    }

    /**
     * Error 9 on an authenticated write: the session [sessionKeyUsed] of [username]
     * is no longer valid (G6b P6a).
     *
     * Clears that session key and nothing else. The username stays, which is what
     * makes the link [LastfmLink.ReauthRequired] - the existing `Требуется вход`
     * state - and keeps every queued scrobble of that account waiting for it. Not a
     * disconnect: the queue is never purged here.
     *
     * Compare-and-clear: only if the stored session is still exactly the one that
     * failed. A late answer about an old key must not throw out a session the
     * listener has since re-established.
     *
     * @return whether the stored session was cleared.
     */
    suspend fun invalidateSession(username: String, sessionKeyUsed: String): Boolean = lock.withLock {
        withContext(NonCancellable) {
            val stored = read()
            if (stored.username != username || stored.sessionKey != sessionKeyUsed) {
                false
            } else {
                write(stored.copy(sessionKey = null))
                true
            }
        }
    }

    /**
     * The lifetime playcount for [username], or null if it could not be read.
     *
     * Enrichment only. Every failure - no answer, an error, a response without a
     * count - is null, and **none of them changes the link**: the account stays
     * linked and the card simply shows no count. In particular this never leads to
     * the re-auth state, because `user.getInfo` is unauthenticated and says nothing
     * about whether the session is still valid.
     *
     * Not under the lock: it reads and writes nothing.
     */
    suspend fun fetchPlaycount(username: String): Long? {
        val request = requests?.userGetInfo(username) ?: return null
        val sent = api.send(request) as? LastfmTransportResult.Body ?: return null
        val parsed = LastfmResponses.userInfo(sent.text) as? LastfmResult.Ok ?: return null
        return parsed.value.playcount
    }

    private suspend fun beginLocked(): Begin {
        val requests = requests ?: return Begin.NotConfigured
        val request = requests.authGetToken() ?: return Begin.NotConfigured

        // Taken before the request is sent, so it can only be earlier than the
        // moment Last.fm issues the token - see PENDING_TOKEN_LIFETIME_MS.
        val issuedAt = clock()

        return when (val sent = api.send(request)) {
            is LastfmTransportResult.Unreachable -> Begin.Unreachable
            is LastfmTransportResult.Body -> when (val parsed = LastfmResponses.token(sent.text)) {
                is LastfmResult.Ok -> {
                    // Committed before the caller can open the browser.
                    write(read().copy(pendingToken = parsed.value, pendingTokenIssuedAt = issuedAt))
                    Begin.OpenBrowser(requests.authorizationUrlFor(parsed.value))
                }
                is LastfmResult.Malformed -> Begin.Unreachable
                is LastfmResult.Failure -> when (parsed.action) {
                    LastfmAction.Retry, is LastfmAction.Backoff -> Begin.Unreachable
                    else -> Begin.Refused
                }
            }
        }
    }

    private suspend fun read(): LastfmStoredSession = withContext(io) { store.read() }

    private suspend fun write(session: LastfmStoredSession) = withContext(io) { store.write(session) }

    private fun LastfmStoredSession.withoutPending() =
        copy(pendingToken = null, pendingTokenIssuedAt = null)

    companion object {

        /**
         * One lock for every instance in the process. The ViewModel builds a fresh
         * [LastfmAuth] per operation so a test's fake applies at once; the lock is
         * what they share.
         */
        private val lock = Mutex()

        /** The instance a running app uses: the backend's transport and factory, and real storage. */
        fun forContext(context: Context): LastfmAuth = LastfmAuth(
            api = LastfmBackend.api(context),
            requests = LastfmBackend.requests(),
            store = PrefsLastfmSessionStore(context),
            queue = ScrobbleQueue.forContext(context),
            onLinked = { LastfmScrobbleScheduler.requestDrain(context.applicationContext) },
        )
    }
}
