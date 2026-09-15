package com.example.musicplayerapp.data.supabase

/**
 * The last account this process verified, so an account surface can open already showing it.
 *
 * ## Why this exists
 *
 * HOME, Settings and the account card read the account off the main thread, and each of
 * their views is inflated from a layout that shows the guest state - `Привет!` and the
 * person glyph, the row's own default, an invisible card. Every time one of those views was
 * recreated (Back to HOME, opening the card) the guest state was on screen for the frames
 * the read took, then the account came back. The session underneath was never empty: a
 * refresh swaps one authenticated session for the next in one step (supabase-kt 3.2.6, read
 * off the artifact). Only the first frame was wrong, so the first frame is what this fixes.
 *
 * ## What it is and is not
 *
 * - **Memory only.** Nothing is persisted: a cold start begins empty and waits for the
 *   restored session like before. `markRegistered` still stores a uid and nothing else.
 * - **Never a source of truth.** A surface paints it only while `IdentityStore` says
 *   `REGISTERED` for the same uid, and every surface still reads the session afterwards and
 *   draws what that says. It only decides what is on screen until then.
 * - **Replaced, not merged.** A newer verified account ([remember]), a saved avatar
 *   ([avatarSaved]) or a definitive "no account" ([forget]) are the only writes.
 */
object KnownAccount {

    @Volatile
    private var last: AccountInfo? = null

    /** The registered uid whose restored session this process has seen hold no account. */
    @Volatile
    private var absentUid: String? = null

    /** A verified account for the registered install: a session read or a server refresh. */
    fun remember(account: AccountInfo) {
        last = account
        absentUid = null
    }

    /**
     * The restored session holds no account for the registered [uid] - a definitive answer,
     * not a read that has yet to return. Lets HOME draw the guest header at once instead of
     * a neutral one.
     */
    fun markAbsent(uid: String) {
        last = null
        absentUid = uid
    }

    /**
     * The startup gate's answer, applied only if nothing is known yet. The gate reads the
     * session before it publishes on the main thread; a read or refresh that landed in
     * between is newer, and must not be overwritten by the older answer.
     *
     * @param account the restored account, or null for a definitive no-account for [uid].
     * @return whether it was applied.
     */
    @Synchronized
    fun settleIfUnknown(uid: String, account: AccountInfo?): Boolean {
        if (!isEmpty) return false
        if (account != null) remember(account) else markAbsent(uid)
        return true
    }

    /** Whether [state] is `REGISTERED` for a uid this process has definitively seen no account for. */
    fun isAbsent(state: IdentityState): Boolean {
        val uid = (state as? IdentityState.Registered)?.uid ?: return false
        return absentUid == uid
    }

    /** The picker saved [avatarId] for the account this process knows. */
    fun avatarSaved(uid: String, avatarId: String) {
        val known = last ?: return
        if (known.uid == uid) last = known.copy(avatarId = avatarId)
    }

    /** A surface concluded there is no account - signed out, deleted, or no session. */
    fun forget() {
        last = null
        absentUid = null
    }

    /** The last verified account for [state], or null unless [state] is `REGISTERED` for its uid. */
    fun of(state: IdentityState): AccountInfo? {
        val uid = (state as? IdentityState.Registered)?.uid ?: return null
        return last?.takeIf { it.uid == uid }
    }

    /** Whether anything is known at all - cheap, so a cold start skips the preferences read. */
    val isEmpty: Boolean get() = last == null && absentUid == null
}
