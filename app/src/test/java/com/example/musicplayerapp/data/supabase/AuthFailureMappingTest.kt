package com.example.musicplayerapp.data.supabase

import io.github.jan.supabase.auth.exception.AuthErrorCode
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The auth failure taxonomy, asserted exhaustively.
 *
 * A unit test rather than an instrumentation one, and that is the point of
 * [classifyAuthError] existing separately from [classifyAuthFailure]: the mapping is
 * a pure function of a status, a code and which endpoint produced them, so proving it
 * needs no device, no network and - crucially - no way to build a Ktor `HttpResponse`
 * to wrap in an `AuthRestException`. The same split the reaction drain uses for
 * `classifyStatus`, for the same reason.
 *
 * The codes are [AuthErrorCode] entries, so a code that stopped existing in a library
 * upgrade fails to compile here rather than silently falling through to
 * [AuthFailure.Unknown] on a phone.
 */
class AuthFailureMappingTest {

    // ==================== credentials ====================

    @Test
    fun `an invalid credentials code is invalid credentials`() {
        val failure = classifyAuthError(
            status = 400,
            code = AuthErrorCode.InvalidCredentials,
            detail = "Invalid login credentials",
            operation = AuthOperation.SIGN_IN,
        )

        assertTrue("$failure", failure is AuthFailure.InvalidCredentials)
        assertEquals("the server's own words must survive for a bug report",
            "Invalid login credentials", failure.detail)
    }

    @Test
    fun `a refusal with no code on sign-in is invalid credentials`() {
        // GoTrue does not always attach an error code, and a sign-in is the one
        // endpoint where the bare refusal has an unambiguous reading.
        for (status in listOf(400, 401, 403, 422)) {
            val failure = classifyAuthError(status, null, "", AuthOperation.SIGN_IN)
            assertTrue("$status -> $failure", failure is AuthFailure.InvalidCredentials)
        }
    }

    @Test
    fun `a wrong password and an unknown address stay the same case`() {
        // Not a convenience. Supabase answers both identically on purpose, and a
        // taxonomy that split them would let anybody ask whether a given address has
        // an account here by watching which failure came back.
        val wrongPassword =
            classifyAuthError(400, AuthErrorCode.InvalidCredentials, "", AuthOperation.SIGN_IN)
        val noSuchUser = classifyAuthError(400, null, "", AuthOperation.SIGN_IN)

        assertEquals(wrongPassword::class, noSuchUser::class)
    }

    // ==================== registration ====================

    @Test
    fun `both duplicate-account codes are email already registered`() {
        for (code in listOf(AuthErrorCode.UserAlreadyExists, AuthErrorCode.EmailExists)) {
            val failure = classifyAuthError(422, code, "", AuthOperation.SIGN_UP)
            assertTrue("$code -> $failure", failure is AuthFailure.EmailAlreadyRegistered)
        }
    }

    @Test
    fun `a weak password is its own case`() {
        val failure =
            classifyAuthError(422, AuthErrorCode.WeakPassword, "too short", AuthOperation.SIGN_UP)

        assertTrue("$failure", failure is AuthFailure.WeakOrInvalidPassword)
    }

    @Test
    fun `an invalid address is a form problem, not an auth one`() {
        val failure = classifyAuthError(
            status = 400,
            code = AuthErrorCode.EmailAddressInvalid,
            detail = "Unable to validate email address",
            operation = AuthOperation.SIGN_UP,
        )

        assertTrue("$failure", failure is AuthFailure.InvalidEmail)
    }

    @Test
    fun `a refused sign-up with no code keeps its status rather than guessing`() {
        // Unlike a sign-in, a bare 400 on sign-up has several readings and no way to
        // choose between them. Admitting that beats inventing one.
        val failure = classifyAuthError(400, null, "whatever", AuthOperation.SIGN_UP)

        assertTrue("$failure", failure is AuthFailure.Unknown)
        assertEquals(400, (failure as AuthFailure.Unknown).status)
    }

    /**
     * The fingerprint of the one dashboard setting v1 depends on.
     *
     * Confirm Email is off, so nothing should ever produce this. It is mapped anyway,
     * because the day it appears is the day somebody needs to be told *which* setting
     * moved - and folded into [AuthFailure.Unknown] it would look like any other bug.
     */
    @Test
    fun `an unconfirmed-email refusal is diagnosable`() {
        val failure =
            classifyAuthError(400, AuthErrorCode.EmailNotConfirmed, "", AuthOperation.SIGN_IN)

        assertTrue("$failure", failure is AuthFailure.EmailNotConfirmed)
    }

    // ==================== recovery ====================

    @Test
    fun `an expired one-time code is distinguished`() {
        val failure = classifyAuthError(
            status = 403,
            code = AuthErrorCode.OtpExpired,
            detail = "Token has expired or is invalid",
            operation = AuthOperation.RECOVERY_VERIFY,
        )

        assertTrue("$failure", failure is AuthFailure.RecoveryCodeExpired)
    }

    @Test
    fun `a refused code with no error code is an invalid recovery code`() {
        for (status in listOf(400, 401, 403, 422)) {
            val failure = classifyAuthError(status, null, "", AuthOperation.RECOVERY_VERIFY)
            assertTrue("$status -> $failure", failure is AuthFailure.InvalidRecoveryCode)
        }
    }

    @Test
    fun `the same status means different things on different endpoints`() {
        // The entire reason AuthOperation exists. A 403 from /token is a password; a
        // 403 from /verify is a code; nothing in the response separates them.
        val onSignIn = classifyAuthError(403, null, "", AuthOperation.SIGN_IN)
        val onVerify = classifyAuthError(403, null, "", AuthOperation.RECOVERY_VERIFY)

        assertTrue("$onSignIn", onSignIn is AuthFailure.InvalidCredentials)
        assertTrue("$onVerify", onVerify is AuthFailure.InvalidRecoveryCode)
    }

    @Test
    fun `retyping the existing password is not an error worth alarming anybody about`() {
        val failure =
            classifyAuthError(422, AuthErrorCode.SamePassword, "", AuthOperation.PASSWORD_UPDATE)

        assertTrue("$failure", failure is AuthFailure.PasswordUnchanged)
    }

    // ==================== quotas and the network ====================

    @Test
    fun `every rate limit is one case, because the response to all of them is to wait`() {
        val limits = listOf(
            AuthErrorCode.OverRequestRateLimit,
            AuthErrorCode.OverEmailSendRateLimit,
            AuthErrorCode.OverSmsSendRateLimit,
        )

        for (code in limits) {
            val failure = classifyAuthError(429, code, "", AuthOperation.RECOVERY_REQUEST)
            assertTrue("$code -> $failure", failure is AuthFailure.RateLimited)
        }

        // And by status alone, which is how a proxy in front of the project reports it.
        assertTrue(classifyAuthError(429, null, "", AuthOperation.SIGN_IN) is AuthFailure.RateLimited)
    }

    @Test
    fun `a server that is unwell reads as a network failure`() {
        for (status in listOf(500, 502, 503, 504)) {
            val failure = classifyAuthError(status, null, "", AuthOperation.SIGN_IN)
            assertTrue("$status -> $failure", failure is AuthFailure.NetworkFailure)
        }
    }

    @Test
    fun `an IO failure anywhere in the cause chain is a network failure`() {
        val causes = listOf(
            UnknownHostException("no dns"),
            SocketTimeoutException("timeout"),
            IOException("reset"),
        )

        for (cause in causes) {
            val wrapped = IllegalStateException("wrapped", cause)
            val failure = classifyAuthFailure(wrapped, AuthOperation.SIGN_IN)
            assertTrue("$cause -> $failure", failure is AuthFailure.NetworkFailure)
        }
    }

    /**
     * The one place this taxonomy deliberately disagrees with the reaction drain's.
     *
     * A drain guesses "transient" for anything it does not recognise, because being
     * wrong costs a few pointless retries. Being wrong here would tell somebody their
     * sign-in failed because of their connection when it did not, and send them off to
     * restart a router over a bug of ours.
     */
    @Test
    fun `an unrecognised throwable is unknown and not a network failure`() {
        val failure = classifyAuthFailure(IllegalStateException("something odd"), AuthOperation.SIGN_IN)

        assertTrue("$failure", failure is AuthFailure.Unknown)
        assertTrue("the class and message are what a bug report needs",
            failure.detail.contains("IllegalStateException") && failure.detail.contains("something odd"))
    }

    // ==================== diagnostics survive ====================

    @Test
    fun `the original throwable is preserved`() {
        val cause = IOException("no route to host")

        val failure = classifyAuthFailure(cause, AuthOperation.SIGN_UP)

        assertNotNull("a failure with no cause cannot be diagnosed later", failure.cause)
        assertEquals(cause, failure.cause)
    }

    @Test
    fun `a classification made from a status alone admits it has no cause`() {
        val failure = classifyAuthError(418, null, "teapot", AuthOperation.SIGN_IN)

        assertNull(failure.cause)
        assertEquals("teapot", failure.detail)
        assertEquals(418, (failure as AuthFailure.Unknown).status)
    }
}
