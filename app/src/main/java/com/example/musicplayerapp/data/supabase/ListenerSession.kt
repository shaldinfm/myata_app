package com.example.musicplayerapp.data.supabase

import android.content.Context
import android.util.Log
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The listener's remote identity - created only when there is something to own.
 *
 * Was `AnonymousSession`. The name stopped being true at G-A2: this boundary now
 * hands back registered identities as readily as anonymous ones, and calling it
 * "anonymous" would mislead whoever reads the next call site.
 *
 * A Supabase **anonymous** sign-in produces a real row in `auth.users` and a real
 * `auth.uid()`, which is what every RLS policy is written against, so reactions can
 * belong to their listener without an account, a screen, or a decision the listener
 * has to make. Linking an email later keeps the *same* uid, so rows written now
 * already belong to an account made afterwards.
 *
 * ## Two entry points, deliberately unequal
 *
 *  - [restore] - what startup calls. Loads a session that already exists and stops
 *    there. It **never** signs anyone in, so a listener who only presses Play creates
 *    nothing: no request on the cold path, no row in `auth.users`, no call handed to
 *    an open sign-up endpoint.
 *  - [identity] - the sync boundary. The first caller that genuinely needs to own
 *    remote data may mint an identity, and only from [IdentityState.None].
 *
 * ## The persisted state decides; the session only reports
 *
 * [IdentityStore] is authoritative, and the split matters most in one case. A missing
 * session can mean the token expired, or that the listener signed out on purpose, and
 * the Auth plugin cannot tell those apart - `currentUserOrNull()` is null either way.
 * Only the stored state knows which, so it is consulted **first**, and the session is
 * asked afterwards to fill in the uid. Getting this backwards would either retry a
 * paused account forever or treat a flat battery as a sign-out.
 *
 * ## Never a second identity
 *
 * The rule the whole file exists for: an install that already owns a uid and cannot
 * reach it right now reports [ListenerIdentity.Unavailable] and waits. It does not
 * sign in again. That would mint a second `auth.uid()` for one person and split their
 * data permanently, on exactly the flaky networks this app is used on. Losing one
 * sync is recoverable; splitting a listener is not.
 */
object ListenerSession {

    private const val TAG = "SupabaseAuth"

    /**
     * Serialises the whole boundary, which is what stops a double-mint.
     *
     * Two threads reaching [identity] at once on a fresh install would otherwise both
     * see "no session, state is None" and both sign in, producing two uids for one
     * person - the exact failure everything else here is built to avoid. The lock is
     * held across the check *and* the sign-in, so the second caller re-reads a state
     * the first has already written.
     */
    private val mutex = Mutex()

    /**
     * The current `auth.uid()`, or null if this install has no live session.
     *
     * A cache of what the Auth plugin knows, not the source of truth: the plugin owns
     * the session, persists it and refreshes it. For *who this install is* even when
     * offline, ask [IdentityStore].
     */
    @Volatile
    var uid: String? = null
        private set

    /** This install's persisted identity state. Survives sign-out and reinstall-free upgrades. */
    fun state(context: Context): IdentityState = IdentityStore.state(context)

    /** The uid this install owns or last owned, from its own storage. Survives sign-out. */
    fun knownUid(context: Context): String? = IdentityStore.state(context).uid

    /** Whether this install has ever owned an identity. */
    fun hasKnownIdentity(context: Context): Boolean = knownUid(context) != null

    /**
     * Loads an existing session, and creates nothing.
     *
     * What startup calls. If this install has never signed in there is nothing to load
     * and nothing happens.
     *
     * Two states short-circuit before the client is touched at all:
     * [IdentityState.SignedOut], because restoring a session the listener deliberately
     * ended would silently sign them back in; and a build with no project configured,
     * which has no client to ask.
     *
     * @return the uid of the restored session, or null if there was none.
     */
    suspend fun restore(context: Context): String? {
        if (IdentityStore.isSignedOut(context)) {
            Log.d(TAG, "signed out; not restoring a session")
            uid = null
            return null
        }

        val client = SupabaseModule.client(context) ?: return null

        return mutex.withLock {
            runCatching {
                val user = client.auth.currentUserOrNull()
                    ?: run {
                        client.auth.loadFromStorage()
                        client.auth.currentUserOrNull()
                    }

                if (user != null) {
                    Log.d(TAG, "session restored")
                    reconcile(context, user.id)
                } else {
                    Log.d(TAG, "no stored session; not signing in")
                }
                user?.id
            }.onFailure {
                // A failed restore says nothing about who this install is. The stored
                // state is untouched, so the identity survives the failure.
                Log.w(TAG, "could not restore a session: ${it.message}")
            }.getOrNull().also { uid = it }
        }
    }

    /**
     * The boundary a caller crosses when it genuinely needs a listener identity to own
     * remote data - and the only place an identity is ever created.
     *
     * Order matters, and each step is a rule:
     *
     *  1. **signed out short-circuits everything.** [ListenerIdentity.Paused], before
     *     any client call. Nothing is retried and nothing is minted;
     *  2. a live session is the answer;
     *  3. a stored session is loaded and reused - never replaced;
     *  4. an install that owns an identity but cannot produce one now is
     *     [ListenerIdentity.Unavailable], because the alternative is minting a second
     *     uid for the same person over a temporary network failure;
     *  5. only [IdentityState.None] signs in anonymously.
     */
    suspend fun identity(context: Context): ListenerIdentity {
        val persisted = IdentityStore.state(context)
        if (persisted is IdentityState.SignedOut) {
            return ListenerIdentity.Paused(persisted.lastUid)
        }

        val client = SupabaseModule.client(context)
            ?: return ListenerIdentity.Unavailable("no supabase client")

        return mutex.withLock {
            // Re-read inside the lock. A concurrent caller may have signed in - or
            // signed out - between the check above and here.
            val current = IdentityStore.state(context)
            if (current is IdentityState.SignedOut) {
                return@withLock ListenerIdentity.Paused(current.lastUid)
            }

            runCatching {
                client.auth.currentUserOrNull()?.let {
                    return@runCatching reconcileAndReport(context, it.id)
                }

                client.auth.loadFromStorage()
                client.auth.currentUserOrNull()?.let {
                    Log.d(TAG, "session restored at the sync boundary")
                    return@runCatching reconcileAndReport(context, it.id)
                }

                if (current !is IdentityState.None) {
                    // This install owns an identity; it just cannot be reached right
                    // now. Signing in again would create a second listener out of one
                    // person, which no later merge fully undoes.
                    Log.w(TAG, "identity known but no session available; not minting a replacement")
                    return@runCatching ListenerIdentity.Unavailable("no session for a known identity")
                }

                client.auth.signInAnonymously()
                val user = client.auth.currentUserOrNull()
                if (user == null) {
                    Log.d(TAG, "anonymous sign-in returned no user")
                    ListenerIdentity.Unavailable("anonymous sign-in returned no user")
                } else {
                    Log.d(TAG, "signed in anonymously")
                    IdentityStore.adoptAnonymous(context, user.id)
                    uid = user.id
                    ListenerIdentity.Available(user.id)
                }
            }.getOrElse {
                Log.w(TAG, "no listener identity: ${it.message}")
                ListenerIdentity.Unavailable(it.javaClass.simpleName)
            }
        }
    }

    /**
     * Fire-and-forget [restore], for the Application at startup.
     *
     * Restores only. A build with no project configured does not even start it.
     */
    fun restoreInBackground(context: Context) {
        if (!SupabaseConfig.isConfigured) return
        CoroutineScope(Dispatchers.IO).launch { restore(context) }
    }

    private fun reconcileAndReport(context: Context, sessionUid: String): ListenerIdentity {
        reconcile(context, sessionUid)
        return ListenerIdentity.Available(sessionUid)
    }

    /**
     * Brings the stored state into line with a session that just proved itself.
     *
     * [IdentityStore.adoptAnonymous] refuses to demote an account state, so a restored
     * [IdentityState.Registered] session stays registered and only [IdentityState.None]
     * is filled in - which is the case that matters: preferences cleared while the
     * Auth plugin kept its session. Adopting the live uid there is what stops the next
     * boundary deciding this is a fresh install and minting a duplicate.
     */
    private fun reconcile(context: Context, sessionUid: String) {
        val stored = IdentityStore.state(context)
        if (stored.uid != null && stored.uid != sessionUid) {
            // Should not happen. The session is the one RLS will actually enforce, so
            // it wins - but it is worth saying loudly, because the alternative reading
            // is that two identities are in play on one install.
            Log.w(TAG, "stored uid differs from the live session; adopting the session's")
        }
        uid = sessionUid
        IdentityStore.adoptAnonymous(context, sessionUid)
    }
}
