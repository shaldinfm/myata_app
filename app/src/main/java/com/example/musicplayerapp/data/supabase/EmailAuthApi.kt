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
     * reason it is a method of its own rather than a flag on something else: the
     * owner's SMTP allowance is shared with another product, and any future caller
     * added here is spending it.
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
