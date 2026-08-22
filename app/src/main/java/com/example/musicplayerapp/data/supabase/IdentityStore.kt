package com.example.musicplayerapp.data.supabase

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit

/**
 * Where [IdentityState] lives on disk, and the only thing allowed to change it.
 *
 * ## Everything is written with `commit()`
 *
 * Not `apply()`, and not as a style preference. `apply()` returns immediately and
 * flushes on a background thread, so a process death shortly afterwards - which is
 * exactly the window that matters - can lose the write and leave a zero-length
 * preferences file. An install that then cannot see its own state believes it has
 * never had an identity, and the next sync boundary mints a **second** uid for the
 * same person.
 *
 * That is not hypothetical: an API 36 force-stop straight after a sign-in produced
 * exactly that empty file, before the marker was made synchronous. Every transition
 * here is a handful of short strings written off the main thread, so paying for the
 * synchronous write costs nothing worth having.
 *
 * ## Legacy migration
 *
 * Installs in the field hold the old single `listener_uid` marker and no state. It is
 * read, not discarded, and it means what it always meant: **this install owns that
 * uid.** It becomes [IdentityState.Anonymous], which is truthful today because
 * registration does not exist yet, so every marker that exists was written by an
 * anonymous sign-in.
 *
 * The migration deliberately does **not** consult the Supabase session. Whether a
 * session can be restored right now is a fact about the network, not about who this
 * install is, and letting a failed restore downgrade a known identity toward
 * [IdentityState.None] would reintroduce the duplicate-uid bug the marker was
 * invented to prevent.
 *
 * The legacy key keeps being written alongside the new state for as long as it costs
 * nothing. If a build without this class is ever installed over one with it, that
 * build still finds a marker and still refuses to mint - the safe direction.
 */
object IdentityStore {

    private const val TAG = "SupabaseAuth"

    /** Same file the legacy marker used, so an upgrade reads its own history. */
    private const val PREFS = "supabase_identity"

    /** The pre-G-A2 marker. Still written; never the source of truth once state exists. */
    private const val KEY_LEGACY_UID = "listener_uid"

    private const val KEY_STATE = "identity_state"
    private const val KEY_UID = "identity_uid"
    private const val KEY_EMAIL = "identity_email"

    private const val NONE = "NONE"
    private const val ANONYMOUS = "ANONYMOUS"
    private const val EMAIL_PENDING = "EMAIL_PENDING"
    private const val EMAIL_VERIFIED = "EMAIL_VERIFIED"
    private const val REGISTERED = "REGISTERED"
    private const val SIGNED_OUT = "SIGNED_OUT"

    /**
     * This install's identity state, migrating the legacy marker on the way if that
     * is all there is.
     *
     * Reads only. It does not write the migrated state back, because a read is not a
     * transition and a decision this cheap does not need caching - the first real
     * transition persists the new shape anyway.
     */
    fun state(context: Context): IdentityState {
        val prefs = prefs(context)

        val raw = prefs.getString(KEY_STATE, null)
            ?: return migrateLegacy(prefs)

        val uid = prefs.getString(KEY_UID, null)

        return when (raw) {
            NONE -> IdentityState.None
            ANONYMOUS -> uid?.let { IdentityState.Anonymous(it) } ?: IdentityState.None
            EMAIL_PENDING -> uid?.let {
                IdentityState.EmailPending(it, prefs.getString(KEY_EMAIL, null).orEmpty())
            } ?: IdentityState.None
            EMAIL_VERIFIED -> uid?.let { IdentityState.EmailVerified(it) } ?: IdentityState.None
            REGISTERED -> uid?.let { IdentityState.Registered(it) } ?: IdentityState.None
            SIGNED_OUT -> uid?.let { IdentityState.SignedOut(it) } ?: IdentityState.None

            // A state string this build does not know, which in practice means a newer
            // build wrote it and was then downgraded. Treated as "owns an identity of
            // some kind", never as None: guessing the wrong *kind* is recoverable,
            // minting a second uid is not.
            else -> {
                Log.w(TAG, "unrecognised identity state; treating as owned")
                uid?.let { IdentityState.Anonymous(it) } ?: IdentityState.None
            }
        }
    }

    /**
     * The old marker, read as what it meant.
     *
     * Three field situations, and all three land somewhere deterministic:
     *
     * | on disk | result |
     * |---|---|
     * | nothing | [IdentityState.None] - a fresh install |
     * | `listener_uid`, session restorable | [IdentityState.Anonymous] |
     * | `listener_uid`, session **not** restorable | [IdentityState.Anonymous] |
     *
     * The last two are the same row on purpose. That is the entire point: what the
     * network can do this second has no bearing on who this install is.
     */
    private fun migrateLegacy(prefs: SharedPreferences): IdentityState {
        val legacy = prefs.getString(KEY_LEGACY_UID, null) ?: return IdentityState.None
        Log.d(TAG, "migrating legacy identity marker to ANONYMOUS")
        return IdentityState.Anonymous(legacy)
    }

    /** True when cloud sync is paused by a deliberate sign-out. */
    fun isSignedOut(context: Context): Boolean = state(context) is IdentityState.SignedOut

    /**
     * Records a freshly minted anonymous identity.
     *
     * Refuses to overwrite an account state. A call arriving in
     * [IdentityState.Registered] would mean an anonymous sign-in happened underneath a
     * real account, which is a bug rather than a transition, and quietly demoting the
     * account would hide it.
     */
    fun adoptAnonymous(context: Context, uid: String) {
        when (val current = state(context)) {
            is IdentityState.Registered,
            is IdentityState.EmailVerified,
            is IdentityState.EmailPending -> {
                Log.w(TAG, "refusing to demote an account state to ANONYMOUS")
                return
            }
            else -> {
                if (current is IdentityState.Anonymous && current.uid == uid) return
                write(context, ANONYMOUS, uid)
            }
        }
    }

    /** **Reserved for G-A4.** Records an unconfirmed email claim against [uid]. */
    fun markEmailPending(context: Context, uid: String, email: String) =
        write(context, EMAIL_PENDING, uid, email)

    /** **Reserved for G-A4.** The address is confirmed; no password yet. */
    fun markEmailVerified(context: Context, uid: String) = write(context, EMAIL_VERIFIED, uid)

    /** The account is complete. Same uid throughout - registration never re-keys data. */
    fun markRegistered(context: Context, uid: String) = write(context, REGISTERED, uid)

    /**
     * Deliberate local sign-out. Cloud sync pauses; nothing local is touched.
     *
     * Keeps the uid as [IdentityState.SignedOut.lastUid] rather than clearing it,
     * which is what stops the next sync boundary treating this install as new. Signing
     * out is not a way back to [IdentityState.None] and there is deliberately no
     * method here that is.
     *
     * **This writes the state and nothing else.** Clearing the Supabase session is the
     * other half of the frozen contract in [IdentityState.SignedOut]'s KDoc - LOCAL
     * scope, tokens cleared, no session retained for a fast re-login - and it belongs
     * to G-A4 along with the ordering and the crash-recovery rule. Calling this alone
     * is still safe: the persisted state is authoritative, so an install is paused
     * from this moment whether or not a session is still sitting on disk.
     */
    fun signOut(context: Context) {
        val last = state(context).uid
        if (last == null) {
            Log.w(TAG, "sign-out with no identity; staying at NONE")
            return
        }
        write(context, SIGNED_OUT, last)
        Log.d(TAG, "signed out locally; cloud sync paused, local collection untouched")
    }

    /**
     * Resumes a paused install as [uid] after an explicit sign-in.
     *
     * **Reserved for G-A4** - nothing calls it yet, because nothing can sign in yet.
     * It is here so the paused state has a documented way out that is not "mint a new
     * identity", which is the mistake it exists to prevent.
     */
    fun resumeAs(context: Context, uid: String, registered: Boolean) =
        write(context, if (registered) REGISTERED else ANONYMOUS, uid)

    /** Test-only: return this install to a never-signed-in state. */
    fun clearForTest(context: Context) {
        prefs(context).edit(commit = true) {
            remove(KEY_STATE)
            remove(KEY_UID)
            remove(KEY_EMAIL)
            remove(KEY_LEGACY_UID)
        }
    }

    /** Test-only: put the pre-G-A2 marker on disk, with no state beside it. */
    fun writeLegacyMarkerForTest(context: Context, uid: String) {
        prefs(context).edit(commit = true) {
            remove(KEY_STATE)
            remove(KEY_UID)
            remove(KEY_EMAIL)
            putString(KEY_LEGACY_UID, uid)
        }
    }

    private fun write(context: Context, state: String, uid: String?, email: String? = null) {
        prefs(context).edit(commit = true) {
            putString(KEY_STATE, state)
            if (uid == null) remove(KEY_UID) else putString(KEY_UID, uid)
            if (email == null) remove(KEY_EMAIL) else putString(KEY_EMAIL, email)
            // The legacy marker, kept in step. A downgraded build reads only this and
            // must still conclude "identity owned, do not mint".
            if (uid == null) remove(KEY_LEGACY_UID) else putString(KEY_LEGACY_UID, uid)
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
