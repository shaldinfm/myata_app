package com.example.musicplayerapp.data.supabase

/**
 * Every remote auth call this app makes, behind one interface.
 *
 * The same shape as [ReactionSyncApi], for the same reason: the part worth testing
 * hard is what the *repository* does around these calls - which identity it commits,
 * when it routes through a handoff, what it does when the process dies mid-switch -
 * and none of that should need a network or a live project to exercise.
 *
 * ## Outcome-typed, not exception-typed
 *
 * Each method returns a classified result rather than throwing, exactly as
 * [ReactionSyncApi] returns [SyncOutcome]. Classification lives with the
 * implementation that has the exception in its hands - see [SupabaseEmailAuthApi] -
 * so the repository above it never sees a `Throwable` and a fake below it never has
 * to manufacture an `AuthRestException` around a Ktor `HttpResponse` it has no way
 * to build.
 *
 * ## What is deliberately absent
 *
 * There is no `signUpAnonymously` here, and no `linkIdentity`. Anonymous sign-in
 * belongs to [ListenerSession], which is the only place in the app allowed to mint
 * an identity; and linking an email onto an anonymous user in place - the
 * `updateUser(email, password)` upgrade - is refused by the frozen G-A4b1 contract.
 * An anonymous install that registers goes through [IdentityHandoff], because
 * Supabase will not turn an anonymous user into a password account and pretending
 * otherwise is how a listener's rows get split across two uids.
 */
interface EmailAuthApi {

    /**
     * Creates an account and, with Confirm Email off, a session with it.
     *
     * [displayName] is written to `user_metadata.display_name`. There is no
     * `public.profiles` table and no uniqueness rule: it is the name the person
     * typed into the form, and two listeners called the same thing is not a problem
     * anybody needs solving.
     */
    suspend fun signUp(email: String, password: String, displayName: String): AuthResult

    /** Authenticates an existing account. */
    suspend fun signIn(email: String, password: String): AuthResult

    /**
     * Asks Supabase to send a recovery mail to [email].
     *
     * **This is the only call in the app that consumes mail quota**, which is the
     * reason it is a method of its own rather than a flag on something else: it sends
     * through the project's custom SMTP sending identity, which has a real allowance,
     * and any future caller added here is spending it.
     *
     * Custom SMTP became part of the production setup during G-A4c2's live validation.
     * Before that the project was on Supabase's built-in mail service, whose templates
     * cannot be edited - which is what made this flow undeliverable in practice.
     *
     * No redirect URL is passed. v1 has no deep link to come back to, and the flow
     * is a typed code instead - see [verifyRecoveryCode].
     */
    suspend fun requestPasswordReset(email: String): RecoveryResult

    /**
     * Exchanges a typed recovery code for a session.
     *
     * The code is the `{{ .Token }}` from the recovery mail template, verified as
     * `OtpType.Email.RECOVERY`. Success means the caller now holds a real
     * authenticated session and may set a new password.
     */
    suspend fun verifyRecoveryCode(email: String, code: String): RecoveryResult

    /** Sets a new password on whatever session is currently live. */
    suspend fun updatePassword(newPassword: String): RecoveryResult

    /**
     * The uid of the session that exists right now, or null.
     *
     * Local: it reads what the Auth plugin holds and makes no request. The
     * repository needs it because a session is what RLS actually enforces, so "who
     * did this call authenticate me as" is a question only the session can answer -
     * the `UserInfo` a sign-up returns is a description of a row, not proof of a
     * token.
     */
    suspend fun currentUid(): String?

    /**
     * Who the live session says this device is, or null when there is no session.
     *
     * The uid alone is not enough for the authenticated profile: it shows a name and
     * an address, and `markRegistered` deliberately persists neither - an identity is
     * a uid, and the rest belongs to whoever is holding the token. So the screen asks
     * the session, and gets null when there is not one rather than a fabricated
     * account.
     *
     * Both fields are nullable inside a present [AccountInfo] for the same reason:
     * `user_metadata.display_name` is whatever was written at registration and may be
     * absent, and an address can be missing on an account created by other means.
     * A session that exists with neither is still a session.
     */
    suspend fun currentAccount(): AccountInfo?

    /**
     * Permanently deletes the account this session authenticates as.
     *
     * `public.delete_my_account(p_request_id)`, migration 0004. One transaction on
     * the server: the `auth.users` row, every app row keyed to it, and a receipt for
     * `(requestId, uid)`. A failure anywhere rolls back all of it, so there is no
     * outcome in which the identity survives its data or the data survives its
     * identity.
     *
     * **[requestId] is not an argument the server trusts.** It never chooses whose
     * account is deleted - that comes from `auth.uid()` inside the transaction, and
     * the function has no uid parameter at all. What the token does is make the
     * outcome *provable later*: deleting the auth row invalidates the refresh
     * credentials at once, so a device whose response is lost and whose access token
     * then expires has nothing left to authenticate with. The receipt is what
     * [checkDeletionStatus] can then read without a session.
     *
     * The caller must therefore mint [requestId] and commit it durably **before**
     * calling this, and retry with the *same* token. See `docs/ACCOUNT-DELETION.md`.
     *
     * Registered accounts only: the server refuses an anonymous caller.
     */
    suspend fun deleteAccount(requestId: String): DeleteAccountOutcome

    /**
     * Asks whether one deletion completed, **without a session**.
     *
     * `public.account_deletion_status(p_request_id, p_deleted_uid)`, migration 0004,
     * granted to `anon`. This is the only call in the app deliberately designed to
     * work for a device that has no credentials at all, because that is exactly the
     * state a device is in when it needs the answer.
     *
     * Both halves are required and neither is an authorisation claim: they select a
     * row, and no privilege anywhere derives from [deletedUid]. Knowing a uid without
     * its 122-bit token answers nothing, and the reverse is equally useless - which
     * is what stops one account's deletion certifying another's.
     */
    suspend fun checkDeletionStatus(requestId: String, deletedUid: String): DeletionStatusOutcome

    /**
     * Clears this device's session, and only this device's.
     *
     * `LOCAL` scope, per the frozen logout contract in [IdentityState.SignedOut]:
     * one person signing out of their phone is not a statement about their tablet.
     * Used by [IdentityReconciler] to finish a logout that a process death
     * interrupted, which is the one case where a live session is the part that is
     * wrong.
     *
     * @return true if the session is gone afterwards.
     */
    suspend fun signOutLocal(): Boolean
}

/**
 * What the live session knows about the listener.
 *
 * Deliberately three nullable-or-not fields and no behaviour: it crosses the auth
 * boundary outwards, so anything richer would be the boundary growing a screen's
 * concerns.
 */
data class AccountInfo(
    val uid: String,
    val displayName: String?,
    val email: String?,
)
