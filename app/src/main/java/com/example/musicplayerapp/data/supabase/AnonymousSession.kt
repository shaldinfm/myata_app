package com.example.musicplayerapp.data.supabase

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The listener's remote identity - created only when there is something to own.
 *
 * A Supabase **anonymous** sign-in produces a real row in `auth.users` and a real
 * `auth.uid()`, which is what every RLS policy is written against, so reactions can
 * later belong to their listener without an account, a screen, or a decision the
 * listener has to make. Linking an email or OAuth identity later keeps the *same*
 * uid, so rows written now already belong to an account made afterwards.
 *
 * ## When an identity is created, and when it is not
 *
 * Opening the radio is not a reason to exist in a database. Most listeners never
 * react to anything, and minting a user row for each of them would fill
 * `auth.users` with identities that own nothing, put a request on every cold
 * launch, and hand an open sign-up endpoint one call per app start.
 *
 * So the two entry points are deliberately different:
 *
 *  - [restore] - what startup calls. Loads a session that already exists and stops
 *    there. It **never** signs anyone in, so a passive listener creates nothing.
 *  - [ensureAuthenticatedListener] - the sync boundary. The first caller that
 *    actually needs to own remote data may mint an identity. Nothing calls it yet:
 *    no reaction is synced in this phase, and it exists so the phase that does has
 *    a tested boundary to call rather than inventing one.
 *
 * ## Never a second identity
 *
 * The rule that matters most here is what happens when a session cannot be
 * refreshed - offline, project paused, token expired. The wrong answer is to sign
 * in again: that mints a **second** `auth.uid()` for the same person and splits
 * their data permanently, and it would happen on exactly the flaky networks this
 * app is used on.
 *
 * So an install remembers, in its own preferences, that it has had an identity
 * ([hasKnownIdentity]). Once that is set, [ensureAuthenticatedListener] will never
 * mint another: it returns null instead, and the caller tries again later. Losing
 * one sync is recoverable; splitting a listener in two is not.
 */
object AnonymousSession {

    private const val TAG = "SupabaseAuth"
    private const val PREFS = "supabase_identity"
    private const val KEY_UID = "listener_uid"

    private val mutex = Mutex()

    /**
     * The current `auth.uid()`, or null if this install has no live session.
     *
     * A cache of what the Auth plugin knows, not the source of truth: the plugin
     * owns the session, persists it and refreshes it.
     */
    @Volatile
    var uid: String? = null
        private set

    /**
     * Whether this install has ever had an identity.
     *
     * Survives the session itself, which is the point: it is what stops a failed
     * refresh being mistaken for a new listener.
     */
    fun hasKnownIdentity(context: Context): Boolean = knownUid(context) != null

    /** The uid this install last held, from its own storage. Survives sign-out. */
    fun knownUid(context: Context): String? = prefs(context).getString(KEY_UID, null)

    /**
     * Loads an existing session, and creates nothing.
     *
     * What startup calls. If this install has never signed in there is nothing to
     * load and nothing happens - no request, no user row, no cost to a listener who
     * only ever presses Play.
     *
     * @return the uid of the restored session, or null if there was none.
     */
    suspend fun restore(context: Context): String? {
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
                    remember(context, user.id)
                } else {
                    Log.d(TAG, "no stored session; not signing in")
                }
                user?.id
            }.onFailure {
                Log.w(TAG, "could not restore a session: ${it.message}")
            }.getOrNull().also { uid = it }
        }
    }

    /**
     * The boundary a caller crosses when it genuinely needs a listener identity to
     * own remote data - and the only place an identity is ever created.
     *
     * Order matters, and each step is a rule:
     *
     *  1. a live session is the answer;
     *  2. a stored session is loaded and reused - never replaced;
     *  3. an install that has had an identity but cannot produce one now returns
     *     **null**, because the alternative is minting a second uid for the same
     *     person over a temporary network failure;
     *  4. only an install that has never had one signs in anonymously.
     *
     * @return the uid, or null when there is no identity and none could be created
     *   (offline, anonymous sign-ins disabled, rate limited, no project). Callers
     *   treat null as "not now" and try again; nothing local depends on it.
     */
    suspend fun ensureAuthenticatedListener(context: Context): String? {
        val client = SupabaseModule.client(context) ?: return null

        return mutex.withLock {
            runCatching {
                client.auth.currentUserOrNull()?.let { return@runCatching it.id }

                client.auth.loadFromStorage()
                client.auth.currentUserOrNull()?.let {
                    Log.d(TAG, "session restored at the sync boundary")
                    return@runCatching it.id
                }

                if (hasKnownIdentity(context)) {
                    // This install has an identity; it just cannot be reached right
                    // now. Signing in again would create a second listener out of
                    // one person, which no later merge can fully undo.
                    Log.w(TAG, "identity known but no session available; not minting a replacement")
                    return@runCatching null
                }

                client.auth.signInAnonymously()
                val user = client.auth.currentUserOrNull()
                Log.d(TAG, if (user == null) "anonymous sign-in returned no user" else "signed in anonymously")
                user?.id
            }.onFailure {
                Log.w(TAG, "no listener identity: ${it.message}")
            }.getOrNull()?.also { remember(context, it); uid = it }
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

    /**
     * Writes the identity marker to disk, synchronously.
     *
     * `commit()`, not `apply()`, and the difference is the whole guarantee. `apply()`
     * returns immediately and flushes on a background thread, so a process death
     * shortly after - which is exactly when this matters - can lose the write and
     * leave a zero-length file behind. An install that then cannot see its own
     * marker believes it has never had an identity, and the next call to
     * [ensureAuthenticatedListener] mints a second uid for the same person.
     *
     * This was not hypothetical: an API 36 force-stop straight after a sign-in
     * produced exactly that empty file. The write is one short string, already off
     * the main thread, so paying for it synchronously costs nothing worth having.
     */
    private fun remember(context: Context, id: String) {
        uid = id
        prefs(context).edit(commit = true) { putString(KEY_UID, id) }
    }

    /** Test-only: forget this install's identity marker. */
    fun forgetKnownIdentityForTest(context: Context) {
        uid = null
        prefs(context).edit(commit = true) { remove(KEY_UID) }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
