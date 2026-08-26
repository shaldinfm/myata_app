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

    private const val KEY_AUTH_ATTEMPT = "auth_attempt"

    private const val KEY_HANDOFF_STAGE = "handoff_stage"
    private const val KEY_HANDOFF_FROM = "handoff_from_uid"
    private const val KEY_HANDOFF_TO = "handoff_to_uid"

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

    /**
     * The account is complete and this install is [uid].
     *
     * It used to say "same uid throughout - registration never re-keys data", which
     * was true of the in-place email upgrade that was planned then and is not true of
     * what shipped. Supabase will not turn an anonymous user into a password account,
     * so registering from an anonymous install *is* a change of uid, performed by
     * [IdentityHandoff] and committed by [markHandoffSwitched] rather than here. This
     * method is for the paths where no handoff is involved - registering or signing in
     * from [IdentityState.None] or [IdentityState.SignedOut] - and for
     * [IdentityReconciler] promoting a state that a process death left behind.
     */
    fun markRegistered(context: Context, uid: String) = write(context, REGISTERED, uid)

    // ------------------------------------------------------- auth attempt --
    //
    // One durable bit, and it exists to answer a question that is otherwise
    // unanswerable at startup.
    //
    // A direct sign-in or registration - one with no handoff, so from NONE or
    // SIGNED_OUT - is two steps: authenticate remotely, then commit the identity to
    // disk. A process death between them leaves a device holding a live session for
    // an identity its own storage has never heard of, and from the disk alone that is
    // indistinguishable from the *other* thing that produces a live session under a
    // SIGNED_OUT state: a logout that died after writing the state and before clearing
    // the token.
    //
    // Those two want opposite repairs. One must finish forward into REGISTERED; the
    // other must finish backwards by clearing the session, which is rule 7 of the
    // frozen logout contract in IdentityState.SignedOut. Guessing would be wrong half
    // the time, and the half that is wrong silently signs somebody back in after they
    // asked not to be.
    //
    // So the intent is written down before the remote call, exactly as HandoffStage
    // writes PREPARED before the action it describes, and for the same reason: what
    // cannot be inferred afterwards has to be recorded beforehand. The handoff path
    // needs none of this - SWITCH_PENDING already covers it.

    /**
     * Records that a direct authentication is about to be attempted.
     *
     * **Committed before the remote call**, and cleared once the identity is on disk.
     * Its presence at startup beside a session means the call succeeded and the commit
     * did not.
     */
    fun markAuthAttempt(context: Context, attempt: AuthAttempt) {
        prefs(context).edit(commit = true) { putString(KEY_AUTH_ATTEMPT, attempt.name) }
    }

    /** The direct authentication in flight, or null. Authoritative across process death. */
    fun authAttempt(context: Context): AuthAttempt? {
        val raw = prefs(context).getString(KEY_AUTH_ATTEMPT, null) ?: return null
        return AuthAttempt.entries.firstOrNull { it.name == raw }
            ?: run {
                // A kind a newer build wrote. Treated as "some direct authentication
                // was under way", because the kind only affects a log line, while
                // losing the fact would send recovery down the interrupted-logout path.
                Log.w(TAG, "unrecognised auth attempt; treating as in flight")
                AuthAttempt.SIGN_IN
            }
    }

    /** The attempt is resolved, one way or the other. Nothing is owed. */
    fun clearAuthAttempt(context: Context) {
        if (prefs(context).getString(KEY_AUTH_ATTEMPT, null) == null) return
        prefs(context).edit(commit = true) { remove(KEY_AUTH_ATTEMPT) }
    }

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

    // ------------------------------------------------------------ handoff --
    //
    // The identity handoff's durable stage, in the same file as the state it will
    // change - so a stage and an identity can be written in one commit() and cannot
    // disagree with each other after a process death between two separate writes.

    /**
     * Records that a handoff from [from] is about to begin.
     *
     * **Committed before the first destructive remote action**, and that ordering is
     * the whole reason this stage exists. Retiring an identity's remote state is a
     * DELETE; if the marker were written afterwards, a death between the two would
     * leave a disk with no handoff record at all, startup would find nothing to
     * recover, and the identity's remote state would stay retired forever with
     * nothing pointing at the local rows that could rebuild it.
     *
     * So `PREPARED` means: *a handoff was intended from this uid; its remote current
     * state may be intact, partly deleted or wholly deleted; local Room is
     * authoritative.* One stage covers all three because retiring is idempotent - a
     * DELETE matching nothing is success - so the three cases have one recovery.
     */
    fun markHandoffPrepared(context: Context, from: String) =
        writeHandoff(context, HandoffStage.PREPARED, from, null)

    /** The destination is about to be authenticated or created. Written before it can exist. */
    fun markHandoffSwitchPending(context: Context, from: String) =
        writeHandoff(context, HandoffStage.SWITCH_PENDING, from, null)

    /**
     * The destination [to] is authenticated. Written together with
     * [IdentityState.Registered] in **one** commit, so the pair cannot tear.
     */
    fun markHandoffSwitched(context: Context, from: String, to: String) {
        prefs(context).edit(commit = true) {
            putString(KEY_STATE, REGISTERED)
            putString(KEY_UID, to)
            remove(KEY_EMAIL)
            putString(KEY_LEGACY_UID, to)
            putString(KEY_HANDOFF_STAGE, HandoffStage.SWITCHED.name)
            putString(KEY_HANDOFF_FROM, from)
            putString(KEY_HANDOFF_TO, to)
        }
        Log.d(TAG, "handoff switched; adoption owed")
    }

    /** The handoff finished, or was rolled back. Either way nothing is owed. */
    fun clearHandoff(context: Context) {
        prefs(context).edit(commit = true) {
            remove(KEY_HANDOFF_STAGE); remove(KEY_HANDOFF_FROM); remove(KEY_HANDOFF_TO)
        }
    }

    /** The handoff in flight, or null. Authoritative across process death. */
    fun handoff(context: Context): HandoffRecord? {
        val prefs = prefs(context)
        val raw = prefs.getString(KEY_HANDOFF_STAGE, null) ?: return null
        val from = prefs.getString(KEY_HANDOFF_FROM, null) ?: return null
        val stage = HandoffStage.entries.firstOrNull { it.name == raw }
            ?: run {
                // A stage a newer build wrote. Treated as in-flight rather than
                // ignored: gating sync for too long is recoverable, letting a drain
                // run through somebody else's handoff is not.
                Log.w(TAG, "unrecognised handoff stage; treating as in flight")
                HandoffStage.PREPARED
            }
        return HandoffRecord(stage, from, prefs.getString(KEY_HANDOFF_TO, null))
    }

    /** True while a handoff is in flight. The gate every sync entry point consults. */
    fun handoffInProgress(context: Context): Boolean = handoff(context) != null

    private fun writeHandoff(context: Context, stage: HandoffStage, from: String, to: String?) {
        prefs(context).edit(commit = true) {
            putString(KEY_HANDOFF_STAGE, stage.name)
            putString(KEY_HANDOFF_FROM, from)
            if (to == null) remove(KEY_HANDOFF_TO) else putString(KEY_HANDOFF_TO, to)
        }
    }

    /** Test-only: return this install to a never-signed-in state. */
    fun clearForTest(context: Context) {
        prefs(context).edit(commit = true) {
            remove(KEY_STATE)
            remove(KEY_UID)
            remove(KEY_EMAIL)
            remove(KEY_LEGACY_UID)
            remove(KEY_AUTH_ATTEMPT)
            remove(KEY_HANDOFF_STAGE)
            remove(KEY_HANDOFF_FROM)
            remove(KEY_HANDOFF_TO)
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

/**
 * Which direct authentication was under way when the process died.
 *
 * The repair is the same for both - promote to [IdentityState.Registered] as the
 * session's uid - so this is not a branch in the recovery logic. It is kept because
 * "a registration was interrupted" and "a sign-in was interrupted" are different
 * sentences in a log, and the log is where an odd identity gets explained.
 */
enum class AuthAttempt {
    REGISTER,
    SIGN_IN,

    /**
     * A deliberate local sign-out is under way.
     *
     * The same durable bit as the other two, doing the same job in the other
     * direction. Logout is also two steps - clear the session, then commit
     * `SIGNED_OUT` - and a death between them leaves `REGISTERED(uid)` on disk with
     * no session, which is **indistinguishable from an ordinary offline install**.
     * Promoting every such install to `SIGNED_OUT` would sign people out for losing
     * wifi; leaving them all alone would strand a half-finished logout forever. Only
     * a marker written before the first step separates the two.
     */
    SIGN_OUT,
}
