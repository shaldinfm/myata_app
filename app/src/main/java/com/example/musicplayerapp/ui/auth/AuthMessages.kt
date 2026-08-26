package com.example.musicplayerapp.ui.auth

import androidx.annotation.StringRes
import com.example.musicplayerapp.R
import com.example.musicplayerapp.data.supabase.AuthFailure

/**
 * The one place a typed domain failure becomes something a listener reads.
 *
 * G-A4b2 kept every word out of the repository on purpose - the same
 * [AuthFailure.InvalidCredentials] has to serve a sign-in form, a create-account
 * form and a log line, and only a screen knows which words those want. This is the
 * screen's half of that bargain, and it is exhaustive: a `when` over a sealed
 * interface, so a failure case added to the domain fails to compile here rather than
 * arriving on a phone as a blank message.
 *
 * ## Nothing the server said is ever shown
 *
 * Every branch returns a string resource. `AuthFailure.detail` and `AuthFailure.cause`
 * exist for logs and bug reports and are never rendered: server text is English, is
 * written for developers, and on a bad day contains a column name or a policy
 * expression. What the listener gets is a sentence in their language about what they
 * can do next.
 *
 * ## The cases v1 cannot reach still have answers
 *
 * `EmailNotConfirmed`, `PasswordUnchanged`, `InvalidRecoveryCode` and
 * `RecoveryCodeExpired` are unreachable from these two screens: registration sends no
 * confirmation mail and recovery has no UI until G-A4c2. They map to the general
 * message rather than to nothing, because "unreachable" is a statement about today's
 * wiring and a blank screen is a statement about nothing at all.
 */
@StringRes
fun authFailureMessage(failure: AuthFailure): Int = when (failure) {

    is AuthFailure.InvalidCredentials -> R.string.auth_error_invalid_credentials

    is AuthFailure.EmailAlreadyRegistered -> R.string.auth_error_email_taken

    is AuthFailure.InvalidEmail -> R.string.auth_error_email_format

    // The same sentence the create-account screen states as a rule, because it is
    // the same rule - and because the server refusing a password is the only reason
    // this app enforces one at all.
    is AuthFailure.WeakOrInvalidPassword -> R.string.auth_error_password_short

    is AuthFailure.NetworkFailure -> R.string.auth_error_network

    is AuthFailure.RateLimited -> R.string.auth_error_rate_limited

    // A user was created or authenticated and this device holds no session for them.
    // The listener cannot fix it and must not be sent to check their mail - v1 has no
    // confirmation flow - so they are told what is true: it did not work, try again.
    is AuthFailure.SessionNotEstablished -> R.string.auth_error_session

    is AuthFailure.EmailNotConfirmed,
    is AuthFailure.PasswordUnchanged,
    is AuthFailure.InvalidRecoveryCode,
    is AuthFailure.RecoveryCodeExpired,
    is AuthFailure.Unknown,
    -> R.string.auth_error_unknown
}
