package com.example.musicplayerapp.data.supabase

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Brings this device's copy of the signed-in account up to date with the server, once,
 * when an account surface opens.
 *
 * ## Why this exists
 *
 * Every surface reads [EmailAuthApi.currentAccount], which answers from the user stored
 * with the session. supabase-kt replaces that copy only on a token refresh or a new
 * sign-in, so an avatar saved on another device stayed invisible here - through
 * relaunches - until the token next refreshed. G6a's live validation caught exactly that.
 * [EmailAuthApi.refreshAccount] re-reads the user and stores it in the session, so after
 * one successful call every surface, HOME included, reads the server's value.
 *
 * ## What it never does
 *
 * - **Poll.** It runs when a surface opens: Profile and Settings every time, HOME at most
 *   once per [HOME_MIN_INTERVAL_MS], because HOME resumes on every tab switch.
 * - **Sign out.** Offline, an expired token or any server error returns null and the
 *   cached account stands.
 * - **Mint.** Only a registered install with a live session for that same uid is asked.
 * - **Write.** It is a read.
 *
 * ## Writes queue behind it
 *
 * A refresh stores the server's user in the session. If a `GET /user` that started before
 * an avatar save returned after it, it would store the pre-save user over the saved one -
 * and HOME, Settings and the card would show the old avatar until the next refresh. So the
 * account writes that change what a refresh reads go through [write], which takes the same
 * lock: a refresh and a write never overlap, and whichever finishes last is also later.
 */
object AccountRefresh {

    /** HOME's minimum gap between refreshes. Profile and Settings pass 0. */
    const val HOME_MIN_INTERVAL_MS = 60_000L

    private val mutex = Mutex()

    @Volatile private var lastSuccessAt = 0L
    @Volatile private var lastUid: String? = null

    /**
     * The refreshed account, or null when there is nothing newer to draw: not registered,
     * no matching session, refreshed too recently for [minIntervalMs], or unavailable.
     * Null always means "keep what you are showing".
     */
    suspend fun refresh(context: Context, minIntervalMs: Long = 0L): AccountInfo? {
        val registered = IdentityStore.state(context) as? IdentityState.Registered ?: return null
        val api = EmailAuthBackend.api(context)
        if (api.currentUid() != registered.uid) return null

        return mutex.withLock {
            val now = System.currentTimeMillis()
            if (!due(lastSuccessAt, lastUid, registered.uid, now, minIntervalMs)) return@withLock null

            when (val result = api.refreshAccount()) {
                is AccountRefreshResult.Refreshed -> {
                    // A session that changed hands between the check and the answer is
                    // not this account's news.
                    if (result.account.uid != registered.uid) return@withLock null
                    lastSuccessAt = now
                    lastUid = registered.uid
                    KnownAccount.remember(result.account)
                    result.account
                }
                is AccountRefreshResult.Unavailable -> null
            }
        }
    }

    /**
     * Runs an account write - the avatar save - under the refresh lock, so no refresh can
     * interleave with it and store an older copy of the user after it. See the class notes.
     */
    suspend fun <T> write(block: suspend () -> T): T = mutex.withLock { block() }

    /** Whether a refresh should run now. Pure, so the throttle is unit-testable. */
    fun due(lastAt: Long, lastUid: String?, uid: String, now: Long, minIntervalMs: Long): Boolean =
        minIntervalMs <= 0L || uid != lastUid || now < lastAt || now - lastAt >= minIntervalMs

    /** Instrumentation: forget the throttle, so one test's refresh cannot hide the next's. */
    fun resetForTest() {
        lastSuccessAt = 0L
        lastUid = null
    }
}
