package com.example.musicplayerapp.data.supabase

import io.github.jan.supabase.auth.exception.AuthErrorCode
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.exception.AuthWeakPasswordException
import io.github.jan.supabase.exceptions.RestException
import java.io.IOException

/**
 * What an authentication attempt did, as a value the UI can switch on.
 *
 * Deliberately word-free. Nothing in this file, or anywhere below the UI, holds a
 * sentence a listener will read: the same [AuthFailure.InvalidCredentials] has to
 * serve a create-account form, a sign-in form and a log line, and only the screen
 * knows which words those want. Putting the Russian here would make the repository
 * un-reusable and un-testable in one move.
 */
sealed interface AuthResult {

    /** Authenticated. [uid] is the identity the device has committed to, on disk. */
    data class Success(val uid: String) : AuthResult

    /** Not authenticated, and [failure] says why in terms a screen can act on. */
    data class Failed(val failure: AuthFailure) : AuthResult
}

/**
 * Password recovery, whose three steps succeed in three different shapes.
 *
 * One type rather than three because they are one conversation - request a code,
 * prove you have it, choose a password - and a screen that drives the flow wants to
 * `when` over the whole thing rather than over three unrelated sealed hierarchies.
 */
sealed interface RecoveryResult {

    /**
     * A recovery mail has been *asked for*. Not "delivered", and not "the address
     * exists".
     *
     * Supabase answers `resetPasswordForEmail` with 200 whether or not the address
     * has an account, on purpose: a different answer would turn the endpoint into a
     * way to ask whether somebody has one. So this carries no claim about the
     * mailbox, and the screen after it must say "if that address has an account..."
     * rather than "check your mail".
     */
    data object Requested : RecoveryResult

    /**
     * The typed code was accepted and a session now exists for [uid].
     *
     * This is the password-reset state, and it is a real authenticated session -
     * which is the whole reason recovery is allowed to exist under an
     * unverified-email model. Receiving the code proves control of the mailbox at
     * the moment it matters, which registration never did.
     */
    data class PasswordResetAuthorized(val uid: String) : RecoveryResult

    /** The new password is in place for [uid]. The recovery session remains live. */
    data class PasswordUpdated(val uid: String) : RecoveryResult

    /** The step did not happen, and [failure] says why. */
    data class Failed(val failure: AuthFailure) : RecoveryResult
}

/**
 * Why an auth or recovery call did not succeed, classified into the cases a screen
 * genuinely has to behave differently about.
 *
 * The classification is the contract. Two failures land in one case only when the
 * listener's next move is the same in both, which is why "the email provider is
 * switched off in the dashboard" and "the request was malformed" are both
 * [Unknown] - neither is anything the person holding the phone can act on - while
 * "that address already has an account" is its own case, because it turns a
 * create-account form into a sign-in form.
 *
 * Every case keeps [cause] and [detail]. The original exception is the only thing
 * that can tell an owner *which* dashboard setting drifted, and a taxonomy that
 * discarded it would turn every configuration mistake into the same shrug.
 */
sealed interface AuthFailure {

    /** The Supabase exception this was derived from, where there was one. */
    val cause: Throwable?

    /** Server-supplied text, for logs and bug reports. Never shown to a listener. */
    val detail: String

    /**
     * Wrong password, or no account for that address.
     *
     * One case, and it has to stay one case: Supabase answers both with the same
     * refusal deliberately, because a reply that distinguished them would answer
     * "does this person have an account here" to anybody who asked.
     */
    data class InvalidCredentials(
        override val detail: String = "",
        override val cause: Throwable? = null,
    ) : AuthFailure

    /** Registration refused: that address already has an account. */
    data class EmailAlreadyRegistered(
        override val detail: String = "",
        override val cause: Throwable? = null,
    ) : AuthFailure

    /**
     * The password was refused by the server's own rules.
     *
     * [reasons] is Supabase's machine-readable list (`length`, `characters`, ...)
     * from [AuthWeakPasswordException], kept so a screen can say which rule was
     * missed rather than restating the whole policy every time.
     */
    data class WeakOrInvalidPassword(
        val reasons: List<String> = emptyList(),
        override val detail: String = "",
        override val cause: Throwable? = null,
    ) : AuthFailure

    /** The address is not a valid address. A form-level problem, not an auth one. */
    data class InvalidEmail(
        override val detail: String = "",
        override val cause: Throwable? = null,
    ) : AuthFailure

    /**
     * The new password is the one already on the account.
     *
     * Its own case because it is the one recovery outcome that means *nothing is
     * wrong* - the account is fine, the code was good, the person simply retyped the
     * password they already had - and reporting it as an error would be a lie.
     */
    data class PasswordUnchanged(
        override val detail: String = "",
        override val cause: Throwable? = null,
    ) : AuthFailure

    /**
     * The server wants the address confirmed before it will issue a session.
     *
     * **Unreachable while the project is configured as v1 requires it.** Confirm
     * Email is off, so a sign-up returns a session immediately and a sign-in never
     * asks. It is kept as a distinct case precisely because it is the fingerprint of
     * that setting having drifted back on - a diagnosis that is obvious here and
     * completely opaque if it arrives folded into [Unknown].
     */
    data class EmailNotConfirmed(
        override val detail: String = "",
        override val cause: Throwable? = null,
    ) : AuthFailure

    /** Offline, DNS, a reset connection, a 5xx. Nothing is wrong with the input. */
    data class NetworkFailure(
        override val detail: String = "",
        override val cause: Throwable? = null,
    ) : AuthFailure

    /** Too many attempts, or too many mails. The only correct response is to wait. */
    data class RateLimited(
        override val detail: String = "",
        override val cause: Throwable? = null,
    ) : AuthFailure

    /**
     * The typed recovery code was not accepted, and the server did not say why.
     *
     * See [RecoveryCodeExpired] for the caveat that governs both.
     */
    data class InvalidRecoveryCode(
        override val detail: String = "",
        override val cause: Throwable? = null,
    ) : AuthFailure

    /**
     * The recovery code was issued, and its window has closed.
     *
     * Supabase does expose this distinctly - [AuthErrorCode.OtpExpired] - and it is
     * mapped distinctly here. What it does **not** promise is that the code is used
     * only for genuinely expired tokens: GoTrue answers a wrong token and a stale
     * token with the same `otp_expired`, because saying which would let somebody
     * probe for valid codes. So a screen should treat this as *that code is expired
     * or wrong, ask for a new one*, which is the right move for both, and the two
     * cases exist here because the distinction is real whenever the server does draw
     * it.
     */
    data class RecoveryCodeExpired(
        override val detail: String = "",
        override val cause: Throwable? = null,
    ) : AuthFailure

    /**
     * Something else. [status] and [code] are kept because they are the difference
     * between "a listener hit a bug" and "the owner left the email provider off".
     */
    data class Unknown(
        val status: Int? = null,
        val code: String? = null,
        override val detail: String = "",
        override val cause: Throwable? = null,
    ) : AuthFailure
}

/**
 * Which call failed, because the same status does not mean the same thing on every
 * endpoint.
 *
 * A 400 from `/token?grant_type=password` is a bad password; a 403 from `/verify` is
 * a bad recovery code. Nothing in the status separates them, so the classifier is
 * told rather than left to guess - and the two guesses available to it would each be
 * wrong about half the time.
 */
enum class AuthOperation {
    SIGN_UP,
    SIGN_IN,
    RECOVERY_REQUEST,
    RECOVERY_VERIFY,
    PASSWORD_UPDATE,
}

/**
 * Turns whatever supabase-kt threw into an [AuthFailure].
 *
 * Split from [classifyAuthError] the same way [classifyFailure] is split from
 * [classifyStatus], and for the same reason: the taxonomy is the part worth
 * asserting exhaustively, and asserting it should not require building a Ktor
 * `HttpResponse` to wrap in an `AuthRestException`.
 */
fun classifyAuthFailure(t: Throwable, operation: AuthOperation): AuthFailure {
    val chain = generateSequence(t) { it.cause }

    // Checked before the general case because it is the only failure that carries
    // structured detail worth keeping - which rule the password missed.
    val weak = chain.filterIsInstance<AuthWeakPasswordException>().firstOrNull()
    if (weak != null) {
        return AuthFailure.WeakOrInvalidPassword(
            reasons = weak.reasons,
            detail = weak.errorDescription.ifBlank { weak.message.orEmpty() },
            cause = t,
        )
    }

    val auth = chain.filterIsInstance<AuthRestException>().firstOrNull()
    if (auth != null) {
        return classifyAuthError(
            status = auth.statusCode,
            code = auth.errorCode,
            detail = auth.errorDescription.ifBlank { auth.error.ifBlank { auth.message.orEmpty() } },
            operation = operation,
        ).withCause(t)
    }

    // A REST failure the Auth plugin did not recognise as one of its own - a
    // gateway's own 502 page, for instance, which never reaches the error-code parser.
    val rest = chain.filterIsInstance<RestException>().firstOrNull()
    if (rest != null) {
        return classifyAuthError(
            status = rest.statusCode,
            code = null,
            detail = rest.error.ifBlank { rest.message.orEmpty() },
            operation = operation,
        ).withCause(t)
    }

    if (chain.any { it is IOException }) {
        return AuthFailure.NetworkFailure(detail = t.javaClass.simpleName, cause = t)
    }

    // Unrecognised, and deliberately **not** folded into [AuthFailure.NetworkFailure].
    // The reaction drain guesses "transient" for the unknown because being wrong
    // there costs a few pointless retries; being wrong here tells somebody their
    // sign-in failed because of their connection when it did not, and sends them off
    // to fight their wifi over a bug of ours.
    return AuthFailure.Unknown(
        detail = t.javaClass.simpleName + ": " + t.message.orEmpty().take(120),
        cause = t,
    )
}

/**
 * The mapping table itself: an HTTP status plus Supabase's own error code, read in
 * the context of the call that produced them.
 *
 * The codes are [AuthErrorCode] entries from supabase-kt 3.2.6 rather than strings
 * spelled out here, so a code the library knows about cannot be typo'd into silence.
 */
internal fun classifyAuthError(
    status: Int,
    code: AuthErrorCode?,
    detail: String,
    operation: AuthOperation,
): AuthFailure = when (code) {

    AuthErrorCode.InvalidCredentials -> AuthFailure.InvalidCredentials(detail)

    AuthErrorCode.UserAlreadyExists,
    AuthErrorCode.EmailExists,
    -> AuthFailure.EmailAlreadyRegistered(detail)

    AuthErrorCode.WeakPassword -> AuthFailure.WeakOrInvalidPassword(detail = detail)

    AuthErrorCode.EmailAddressInvalid -> AuthFailure.InvalidEmail(detail)

    AuthErrorCode.SamePassword -> AuthFailure.PasswordUnchanged(detail)

    AuthErrorCode.EmailNotConfirmed -> AuthFailure.EmailNotConfirmed(detail)

    AuthErrorCode.OtpExpired -> AuthFailure.RecoveryCodeExpired(detail)

    // Three separate quotas - requests, mail, SMS - and one response: wait. The mail
    // one is the one v1 can realistically hit, because the Maileroo quota behind it
    // is shared with another product.
    AuthErrorCode.OverRequestRateLimit,
    AuthErrorCode.OverEmailSendRateLimit,
    AuthErrorCode.OverSmsSendRateLimit,
    -> AuthFailure.RateLimited(detail)

    else -> byStatus(status, code, detail, operation)
}

/**
 * What to conclude when the error code is absent or unrecognised.
 *
 * Only here does [AuthOperation] earn its place: a refusal from `/verify` is a
 * refusal of a code, and a refusal from `/token` is a refusal of a password.
 */
private fun byStatus(
    status: Int,
    code: AuthErrorCode?,
    detail: String,
    operation: AuthOperation,
): AuthFailure = when {
    status == 429 -> AuthFailure.RateLimited(detail)

    // A server that is unwell is a network failure from where the listener sits: the
    // input was fine and waiting is the whole of the remedy.
    status >= 500 -> AuthFailure.NetworkFailure("$status $detail")

    status == 400 || status == 401 || status == 403 || status == 422 ->
        when (operation) {
            AuthOperation.SIGN_IN -> AuthFailure.InvalidCredentials(detail)
            AuthOperation.RECOVERY_VERIFY -> AuthFailure.InvalidRecoveryCode(detail)
            // A refused sign-up, reset request or password change has no single
            // obvious reading, and inventing one would be worse than admitting it -
            // so the status and the code travel with the failure instead.
            else -> AuthFailure.Unknown(status, code?.value, detail)
        }

    else -> AuthFailure.Unknown(status, code?.value, detail)
}

/** Re-attaches the original throwable after the status-only classification. */
private fun AuthFailure.withCause(t: Throwable): AuthFailure = when (this) {
    is AuthFailure.InvalidCredentials -> copy(cause = t)
    is AuthFailure.EmailAlreadyRegistered -> copy(cause = t)
    is AuthFailure.WeakOrInvalidPassword -> copy(cause = t)
    is AuthFailure.InvalidEmail -> copy(cause = t)
    is AuthFailure.PasswordUnchanged -> copy(cause = t)
    is AuthFailure.EmailNotConfirmed -> copy(cause = t)
    is AuthFailure.NetworkFailure -> copy(cause = t)
    is AuthFailure.RateLimited -> copy(cause = t)
    is AuthFailure.InvalidRecoveryCode -> copy(cause = t)
    is AuthFailure.RecoveryCodeExpired -> copy(cause = t)
    is AuthFailure.Unknown -> copy(cause = t)
}
