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
 * The listener's remote identity, obtained without ever asking them for anything.
 *
 * A Supabase **anonymous** sign-in produces a real row in `auth.users` and a real
 * `auth.uid()`, which is what every RLS policy is written against - so reactions
 * can later be owned by their listener without an account, a screen, or a
 * decision the listener has to make. Registration stays optional because this is
 * what registration would otherwise have been needed for.
 *
 * It is also the reason a later account is not a migration: linking an email or an
 * OAuth identity keeps **the same user id**, so rows written now already belong to
 * the account made later. Nothing about reaction identity has to be redesigned for
 * accounts to arrive.
 *
 * ### What this deliberately does not do
 *
 * It does not block anything. [ensure] is called once at startup, off the main
 * thread, and every failure path ends in a log line - no retry storm, no error
 * surfaced to the listener, no effect on playback or on the Collection. An app with
 * no network, or with no Supabase project configured at all, behaves exactly as it
 * did before this class existed. Nothing yet reads [uid]; the phase that syncs
 * reactions will.
 */
object AnonymousSession {

    private const val TAG = "SupabaseAuth"

    private val mutex = Mutex()

    /**
     * The current listener's `auth.uid()`, or null if there is no session.
     *
     * Read it as a cache of what the Auth plugin knows, not as the source of truth:
     * the plugin owns the session, persists it, and refreshes it.
     */
    @Volatile
    var uid: String? = null
        private set

    /**
     * Makes sure this install has a session, signing in anonymously if it does not.
     *
     * Idempotent, and safe to call from anywhere: a restored session is reused
     * rather than replaced, because a second anonymous sign-in would be a second
     * listener with the same person behind it.
     *
     * @return the uid, or null when the build has no project, when the network is
     *   unavailable, or when anonymous sign-ins are switched off for the project.
     */
    suspend fun ensure(context: Context): String? {
        val client = SupabaseModule.client(context) ?: return null

        return mutex.withLock {
            runCatching {
                val existing = client.auth.currentUserOrNull()
                if (existing != null) {
                    Log.d(TAG, "session restored")
                    return@runCatching existing.id
                }

                client.auth.signInAnonymously()
                val user = client.auth.currentUserOrNull()
                Log.d(TAG, if (user == null) "anonymous sign-in returned no user" else "signed in anonymously")
                user?.id
            }.onFailure {
                // Offline, project paused, anonymous sign-ins disabled, rate limited.
                // All of them mean the same thing here: no remote identity this time,
                // and nothing else changes.
                Log.w(TAG, "no anonymous session: ${it.message}")
            }.getOrNull().also { uid = it }
        }
    }

    /**
     * Fire-and-forget [ensure], for callers with no coroutine scope of their own -
     * which is to say the Application, at startup.
     */
    fun ensureInBackground(context: Context) {
        if (!SupabaseConfig.isConfigured) return
        CoroutineScope(Dispatchers.IO).launch { ensure(context) }
    }
}
