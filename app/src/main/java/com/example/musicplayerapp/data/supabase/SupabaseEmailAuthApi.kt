package com.example.musicplayerapp.data.supabase

import android.content.Context
import android.util.Log
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.SignOutScope
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The real auth calls, against supabase-kt 3.2.6.
 *
 * Every signature here was read off the resolved `auth-kt-android:3.2.6` artifact
 * rather than from documentation, because the ones that matter changed shape across
 * 2.x and 3.x: `signUpWith` returns the created `UserInfo`, `signInWith` returns
 * `Unit` and leaves the session on the plugin, and `verifyEmailOtp` has two
 * overloads whose difference is whether the second argument is an address or a token
 * hash. Picking the wrong one of those compiles and then fails on a device.
 *
 * ## No redirect URL anywhere in this file
 *
 * Not an omission. v1 registers no deep link, so a redirect would produce a mail
 * whose only working affordance is a link the app cannot receive. The client's flow
 * type is `IMPLICIT` - pinned in [SupabaseModule] and verified against the library's
 * own default - so no PKCE verifier is created either, and the recovery mail's
 * `{{ .Token }}` is a code the listener can simply read out and type.
 *
 * ## Registration sends no mail
 *
 * With Confirm Email off, `signUpWith(Email)` returns a session and the project
 * sends nothing. That is a v1 contract rather than an accident, and it is checked
 * rather than assumed.
 *
 * A returned `UserInfo` means a row exists in `auth.users`. It does not mean this
 * device can act as that user, and only the session can - so [sessionVerdict] is
 * asked on every path that ends in a persisted identity, with the reported uid where
 * there is one. A sign-up that comes back without a session, or with somebody else's,
 * is [AuthFailure.SessionNotEstablished] rather than a uid the caller cannot use.
 * Nothing here routes anybody into a confirmation flow; v1 does not have one.
 */
class SupabaseEmailAuthApi(private val context: Context) : EmailAuthApi {

    private val client get() = SupabaseModule.client(context)

    override suspend fun signUp(
        email: String,
        password: String,
        displayName: String,
    ): AuthResult {
        val auth = client?.auth ?: return noClient()

        return runCatching {
            auth.signUpWith(Email) {
                this.email = email
                this.password = password
                // user_metadata. Built by hand for the same reason every PostgREST
                // payload in this package is - see SupabaseReactionSyncApi - and
                // because one string does not need a compiler plugin.
                data = buildJsonObject { put(DISPLAY_NAME, displayName) }
            }
        }.fold(
            onSuccess = { created ->
                // A returned UserInfo says a row exists in auth.users. It does not say
                // this device can act as that user, and only the session can. Asked
                // here with the reported uid, because this is the only place that
                // value is ever visible.
                val session = auth.currentUserOrNull()?.id

                when (val verdict = sessionVerdict(reported = created?.id, session = session)) {
                    null -> {
                        Log.d(TAG, "registered; session established")
                        AuthResult.Success(session!!)
                    }

                    else -> {
                        Log.w(TAG, "sign-up did not leave a usable session: ${verdict.reason}")
                        AuthResult.Failed(verdict)
                    }
                }
            },
            onFailure = { AuthResult.Failed(classifyAuthFailure(it, AuthOperation.SIGN_UP)) },
        )
    }

    override suspend fun signIn(email: String, password: String): AuthResult {
        val auth = client?.auth ?: return noClient()

        return runCatching {
            auth.signInWith(Email) {
                this.email = email
                this.password = password
            }
            // signInWith returns Unit: the session it created is on the plugin, and
            // the plugin is the only thing that can be asked who we now are.
            auth.currentUserOrNull()?.id
        }.fold(
            onSuccess = { uid ->
                // No exception is not the same as a session. signInWith returns Unit,
                // so there is nothing to cross-check against - the session is the
                // entire result, and its absence is the failure.
                when (val verdict = sessionVerdict(reported = null, session = uid)) {
                    null -> {
                        Log.d(TAG, "signed in")
                        AuthResult.Success(uid!!)
                    }

                    else -> {
                        Log.w(TAG, "sign-in did not leave a usable session: ${verdict.reason}")
                        AuthResult.Failed(verdict)
                    }
                }
            },
            onFailure = { AuthResult.Failed(classifyAuthFailure(it, AuthOperation.SIGN_IN)) },
        )
    }

    override suspend fun requestPasswordReset(email: String): RecoveryResult {
        val auth = client?.auth ?: return RecoveryResult.Failed(noClientFailure())

        return runCatching { auth.resetPasswordForEmail(email) }.fold(
            onSuccess = {
                // 200 whether or not the address has an account. See
                // RecoveryResult.Requested - the ambiguity is the point.
                Log.d(TAG, "password recovery requested")
                RecoveryResult.Requested
            },
            onFailure = {
                RecoveryResult.Failed(classifyAuthFailure(it, AuthOperation.RECOVERY_REQUEST))
            },
        )
    }

    override suspend fun verifyRecoveryCode(email: String, code: String): RecoveryResult {
        val auth = client?.auth ?: return RecoveryResult.Failed(noClientFailure())

        return runCatching {
            // The (type, email, token) overload. The other one takes a token *hash*
            // out of a confirmation link, which is the deep-link flow v1 does not have.
            auth.verifyEmailOtp(
                type = RECOVERY_OTP,
                email = email,
                token = code,
            )
            auth.currentUserOrNull()?.id
        }.fold(
            onSuccess = { uid ->
                // A verified RECOVERY OTP establishes a session, which makes this an
                // authentication and subject to exactly the same rule as one. A
                // verification that reported success without leaving a session must
                // never reach IdentityState.
                when (val verdict = sessionVerdict(reported = null, session = uid)) {
                    null -> {
                        Log.d(TAG, "recovery code accepted; password may be reset")
                        RecoveryResult.PasswordResetAuthorized(uid!!)
                    }

                    else -> {
                        Log.w(TAG, "recovery left no usable session: ${verdict.reason}")
                        RecoveryResult.Failed(verdict)
                    }
                }
            },
            onFailure = {
                RecoveryResult.Failed(classifyAuthFailure(it, AuthOperation.RECOVERY_VERIFY))
            },
        )
    }

    override suspend fun updatePassword(newPassword: String): RecoveryResult {
        val auth = client?.auth ?: return RecoveryResult.Failed(noClientFailure())

        return runCatching { auth.updateUser { password = newPassword } }.fold(
            onSuccess = { user ->
                Log.d(TAG, "password updated")
                RecoveryResult.PasswordUpdated(user.id)
            },
            onFailure = {
                RecoveryResult.Failed(classifyAuthFailure(it, AuthOperation.PASSWORD_UPDATE))
            },
        )
    }

    override suspend fun currentUid(): String? =
        runCatching { client?.auth?.currentUserOrNull()?.id }.getOrNull()

    override suspend fun signOutLocal(): Boolean {
        val auth = client?.auth ?: return false

        runCatching { auth.signOut(SignOutScope.LOCAL) }
            .onFailure {
                // The token is cleared locally by the plugin whether or not the
                // server was reachable, so a failure here is worth a line and no
                // more. The persisted state is authoritative regardless.
                Log.w(TAG, "local sign-out call failed: ${it.message}")
            }

        return auth.currentUserOrNull() == null
    }

    private fun noClient(): AuthResult = AuthResult.Failed(noClientFailure())

    private fun noClientFailure(): AuthFailure =
        AuthFailure.Unknown(detail = "no supabase client configured for this build")

    companion object {

        private const val TAG = "SupabaseAuth"

        /**
         * The `user_metadata` key the create-account form's name lands in.
         *
         * A constant because two places have to agree on it - this file writes it and
         * whatever later reads a display name has to look it up - and because
         * `display_name` is also the key the Supabase dashboard shows in its user
         * list, which is worth not drifting from.
         */
        const val DISPLAY_NAME = "display_name"

        /**
         * The OTP type a recovery code is verified as.
         *
         * Named rather than written inline for the same reason [DISPLAY_NAME] is: it
         * is a wire value, `OtpType.Email` has six of them, and picking the wrong one
         * compiles perfectly and then rejects every code a listener types. Pinned by a
         * test against the string GoTrue actually expects.
         */
        val RECOVERY_OTP: OtpType.Email = OtpType.Email.RECOVERY
    }
}
