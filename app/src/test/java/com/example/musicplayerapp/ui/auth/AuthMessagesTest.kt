package com.example.musicplayerapp.ui.auth

import com.example.musicplayerapp.R
import com.example.musicplayerapp.data.supabase.AuthFailure
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every typed domain failure, and the message a listener gets for it.
 *
 * The table the owner specified, asserted as a table. A unit test rather than a UI
 * one because the mapping is a pure function of a sealed type: driving a fragment to
 * produce each of eleven failures would prove the same thing far more slowly and
 * would stop being exhaustive the moment a case was added.
 *
 * The screens' half of this - that the resource actually reaches the right inline
 * area - is `AuthFormTest`.
 */
class AuthMessagesTest {

    @Test
    fun `the owner's mapping table`() {
        val expected = listOf(
            AuthFailure.InvalidCredentials() to R.string.auth_error_invalid_credentials,
            AuthFailure.EmailAlreadyRegistered() to R.string.auth_error_email_taken,
            AuthFailure.InvalidEmail() to R.string.auth_error_email_format,
            AuthFailure.WeakOrInvalidPassword() to R.string.auth_error_password_short,
            AuthFailure.NetworkFailure() to R.string.auth_error_network,
            AuthFailure.RateLimited() to R.string.auth_error_rate_limited,
            AuthFailure.Unknown() to R.string.auth_error_unknown,
        )

        for ((failure, message) in expected) {
            assertEquals("$failure", message, authFailureMessage(failure))
        }
    }

    @Test
    fun `a user with no session is told the attempt failed, not to check their mail`() {
        // The listener cannot act on this and must not be sent to a mailbox: v1 has
        // no confirmation flow, so there would be nothing in it. Both reasons say the
        // same thing, because both mean the same thing to whoever is holding the phone.
        for (reason in AuthFailure.SessionNotEstablished.Reason.entries) {
            assertEquals(
                "$reason",
                R.string.auth_error_session,
                authFailureMessage(AuthFailure.SessionNotEstablished(reason)),
            )
        }
    }

    @Test
    fun `the cases no screen can reach still have a message`() {
        // Unreachable is a statement about today's wiring. A blank message is a
        // statement about nothing at all, and would be what shipped if one of these
        // ever became reachable.
        //
        // The two recovery-code cases left this list in G-A4c2: auth-recovery reaches
        // both, and they now have words of their own - see below.
        val unreachable = listOf(
            AuthFailure.EmailNotConfirmed(),
            AuthFailure.PasswordUnchanged(),
        )

        for (failure in unreachable) {
            assertEquals("$failure", R.string.auth_error_unknown, authFailureMessage(failure))
        }
    }

    /**
     * The two code failures are different problems and must not read alike.
     *
     * A wrong code is something to check and retype; an expired one is something no
     * amount of retyping fixes, and the listener has to ask for another. Folding them
     * back into the general message - which is where they sat until auth-recovery
     * existed to show them - would send somebody retyping a code that can never work.
     */
    @Test
    fun `a wrong recovery code and an expired one say different things`() {
        val invalid = authFailureMessage(AuthFailure.InvalidRecoveryCode())
        val expired = authFailureMessage(AuthFailure.RecoveryCodeExpired())

        assertEquals(R.string.auth_error_recovery_code_invalid, invalid)
        assertEquals(R.string.auth_error_recovery_code_expired, expired)
        assertNotEquals(invalid, expired)
        assertNotEquals(R.string.auth_error_unknown, invalid)
        assertNotEquals(R.string.auth_error_unknown, expired)
    }

    @Test
    fun `no failure resolves to nothing`() {
        val every = listOf(
            AuthFailure.InvalidCredentials(),
            AuthFailure.EmailAlreadyRegistered(),
            AuthFailure.WeakOrInvalidPassword(),
            AuthFailure.InvalidEmail(),
            AuthFailure.PasswordUnchanged(),
            AuthFailure.EmailNotConfirmed(),
            AuthFailure.NetworkFailure(),
            AuthFailure.RateLimited(),
            AuthFailure.InvalidRecoveryCode(),
            AuthFailure.RecoveryCodeExpired(),
            AuthFailure.SessionNotEstablished(AuthFailure.SessionNotEstablished.Reason.NO_SESSION),
            AuthFailure.Unknown(),
        )

        for (failure in every) {
            assertNotEquals("$failure resolved to no resource", 0, authFailureMessage(failure))
        }
    }

    @Test
    fun `nothing the server said can reach the message`() {
        // The detail and the cause exist for a log and a bug report. Server text is
        // English, written for developers, and on a bad day contains a column name or
        // a policy expression - none of which belongs on a sign-in form.
        val loud = AuthFailure.Unknown(
            status = 400,
            code = "validation_failed",
            detail = "new row violates row-level security policy for table \"reactions\"",
            cause = IOException("no route to host"),
        )

        // A resource id carries no text at all, which is the structural version of
        // this guarantee: there is no way for the detail to arrive on screen.
        assertEquals(R.string.auth_error_unknown, authFailureMessage(loud))
        assertTrue(authFailureMessage(loud) != 0)
    }
}
