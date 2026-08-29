package com.example.musicplayerapp.data.supabase

import android.content.Context
import android.util.Log
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.SignOutScope
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.postgrest
import com.example.musicplayerapp.SecureNetModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

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
class SupabaseEmailAuthApi(
    private val context: Context,
    /**
     * The client the unauthenticated status call travels on.
     *
     * The app's own shared, fully validating OkHttp client - the same one
     * [SupabaseModule] hands to the Ktor engine, so this request gets the identical
     * TLS configuration and the bundled trust anchors that make Supabase reachable on
     * API 24. A parameter only so a test can capture the request without a network.
     */
    private val statusHttpClient: OkHttpClient = SecureNetModule.getOkHttpClient(context),
    /** The project origin. A parameter only so a test never needs the real one. */
    private val statusBaseUrl: String = SupabaseConfig.url,
    /** The publishable key, sent as `apikey`. Never logged, never returned. */
    private val statusApiKey: String = SupabaseConfig.publishableKey,
) : EmailAuthApi {

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

    /**
     * `delete_my_account(p_request_id)`.
     *
     * Sent as `authenticated`: the session's token is what `auth.uid()` reads inside
     * the function, and it is the *only* thing that decides whose account is deleted.
     * The payload carries the request token and nothing else - there is no uid field
     * in the signature to fill in.
     *
     * The payload is built by hand for the same reason every PostgREST body in this
     * package is - see [SupabaseReactionSyncApi] - and because one string does not
     * need a compiler plugin.
     */
    override suspend fun deleteAccount(requestId: String): DeleteAccountOutcome {
        val db = client?.postgrest ?: return DeleteAccountOutcome.Failed(noClientFailure())

        val payload = buildJsonObject { put(PARAM_REQUEST_ID, requestId) }

        return runCatching {
            val answer = db.rpc(RPC_DELETE_ACCOUNT, payload).decodeAs<JsonObject>()
            when (answer[OUTCOME]?.jsonPrimitive?.contentOrNull) {
                OUTCOME_DELETED -> {
                    Log.d(TAG, "account deleted")
                    DeleteAccountOutcome.Deleted(
                        reactions = answer.count("reactions"),
                        events = answer.count("events"),
                        applications = answer.count("applications"),
                    )
                }

                // The second of two devices, or a retry whose original answer was
                // lost. A receipt for this request exists either way, which is the
                // only thing that separates this from an error.
                OUTCOME_ALREADY_DELETED -> {
                    Log.d(TAG, "account was already deleted; receipt recorded for this request")
                    DeleteAccountOutcome.AlreadyDeleted
                }

                // The call reached the server and the server replied with something
                // this build does not know. Guessing at the shape is how a deletion
                // gets reported as done when it was not.
                else -> {
                    Log.w(TAG, "unrecognised deletion outcome")
                    DeleteAccountOutcome.Failed(
                        AuthFailure.Unknown(detail = "unrecognised delete_my_account outcome")
                    )
                }
            }
        }.getOrElse { thrown ->
            definitiveRefusal(thrown)
                ?.let { DeleteAccountOutcome.Refused(it) }
                ?: DeleteAccountOutcome.Failed(
                    classifyAuthFailure(thrown, AuthOperation.ACCOUNT_DELETE)
                )
        }
    }

    /**
     * The SQLSTATE, when it is one that proves the deletion transaction did not commit.
     *
     * ## Why the code and not the HTTP status
     *
     * A status says how PostgREST rendered a failure; it does not say whether any SQL
     * ran. `403` covers both "the function raised 42501" and "a policy refused
     * something else"; `400` covers a malformed request and a `RAISE ... 22023` alike.
     * Deciding on the status would mean clearing a deletion marker on evidence that
     * does not distinguish "nothing happened" from "something happened".
     *
     * The SQLSTATE does distinguish it. Each code below is raised by
     * `delete_my_account` itself, and a plpgsql `RAISE` aborts the enclosing
     * transaction - which for PostgREST is the whole request. So one of these coming
     * back is proof that no row and no receipt were committed:
     *
     * | code | raised where | why nothing committed |
     * |---|---|---|
     * | `28000` | `auth.uid()` is null | before any DELETE |
     * | `42501` | anonymous caller - or no EXECUTE on the function | before any DELETE, or the function never ran |
     * | `22023` | `p_request_id` is null | before any DELETE |
     * | `XX000` | the defensive row-count check | after the DELETEs, and the RAISE rolls them back with the receipt |
     *
     * Everything else is `null` here and becomes [DeleteAccountOutcome.Failed]: an
     * `IOException`, a gateway's own error page, a `PGRST301`, a 5xx, an exception
     * carrying no code at all, and - deliberately - **any SQLSTATE this build does not
     * recognise**. A code added to the function by a later migration must not be read
     * as a refusal by an older client that has never heard of it.
     */
    private fun definitiveRefusal(t: Throwable): String? =
        generateSequence(t) { it.cause }
            .filterIsInstance<PostgrestRestException>()
            .firstOrNull()
            ?.code
            ?.takeIf { it in DEFINITIVE_REFUSALS }

    /**
     * `account_deletion_status(p_request_id, p_deleted_uid)`, sent **without** an
     * `Authorization` header.
     *
     * ## Why this one call does not go through supabase-kt
     *
     * It is the only call in the app that must work with **no session**, and
     * supabase-kt 3.2.6 cannot express that request correctly for this project's key.
     *
     * Its `AuthenticatedSupabaseApi` resolves a bearer token as `jwtToken ?:
     * client.accessToken ?: session token ?: supabaseKey`, with the key fallback on by
     * default. With no session that puts the **publishable key** in
     * `Authorization: Bearer`, alongside the same value in `apikey`. Supabase
     * documents exactly that shape as failing: a `sb_publishable_...` key is not a
     * JWT, and a request carrying one as a bearer token "will be forwarded down to
     * your project's database, but will be rejected as the value is not a JWT".
     *
     * This is a known defect rather than a reading of the docs: supabase-kt 3.7.0
     * added `SupabaseClient.checkIsNewApiKey` and an opt-in `useNewApiKeyAsFallback`
     * precisely so a new-format key is *not* used as a bearer token. 3.2.6 predates
     * the concept - the string `sb_publishable_` appears nowhere in it - and the
     * version is pinned by a Kotlin-toolchain constraint recorded in `build.gradle`.
     *
     * So this one request is built directly, against the same
     * [SecureNetModule] client every other Supabase call already travels on - the same
     * TLS configuration, the same `network_security_config` trust anchors that make
     * API 24 work, the same pool and timeouts. **No second Supabase abstraction is
     * introduced**, and nothing else in this file changes: [deleteAccount] runs with a
     * live session, so its bearer token is a real JWT and supabase-kt handles it
     * correctly.
     *
     * ## The header shape, which is the whole point
     *
     * ```
     * POST <project>/rest/v1/rpc/account_deletion_status
     * apikey: <publishable key>
     * Accept: application/json
     * Content-Type: application/json
     * (no Authorization header at all)
     *
     * {"p_request_id": "...", "p_deleted_uid": "..."}
     * ```
     *
     * The absence of `Authorization` is asserted by a test, not assumed: adding one
     * back - or letting some future refactor route this through the authenticated
     * path - would break the call in a way no offline suite would otherwise notice.
     *
     * The key is written into a header and never logged, never included in a failure
     * message, and never part of the returned value.
     */
    override suspend fun checkDeletionStatus(
        requestId: String,
        deletedUid: String,
    ): DeletionStatusOutcome {
        if (statusBaseUrl.isBlank() || statusApiKey.isBlank()) {
            return DeletionStatusOutcome.Failed(noClientFailure())
        }

        val payload = buildJsonObject {
            put(PARAM_REQUEST_ID, requestId)
            put(PARAM_DELETED_UID, deletedUid)
        }

        val request = Request.Builder()
            .url(statusBaseUrl.trimEnd('/') + STATUS_PATH)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header(HEADER_APIKEY, statusApiKey)
            .header("Accept", "application/json")
            // Deliberately no Authorization header. See the KDoc above.
            .build()

        return runCatching {
            // OkHttp's execute() blocks, and this is a suspend function that callers
            // reach from arbitrary dispatchers.
            withContext(Dispatchers.IO) {
                statusHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        // The status code only. A body could echo request content, and
                        // this failure is read by code that may go on to log it.
                        return@use DeletionStatusOutcome.Failed(
                            AuthFailure.Unknown(
                                detail = "account_deletion_status returned ${response.code}"
                            )
                        )
                    }
                    readStatus(response.body?.string().orEmpty())
                }
            }
        }.getOrElse {
            DeletionStatusOutcome.Failed(classifyAuthFailure(it, AuthOperation.DELETION_STATUS))
        }
    }

    /**
     * The one word the status route returns.
     *
     * `UNKNOWN` is an answer, not a failure: the pair has no receipt. It is
     * deliberately indistinguishable from a malformed request, so it is never read as
     * evidence that the deletion did not happen.
     */
    private fun readStatus(body: String): DeletionStatusOutcome {
        val outcome = runCatching {
            Json.parseToJsonElement(body).jsonObject[OUTCOME]?.jsonPrimitive?.contentOrNull
        }.getOrNull()

        return when (outcome) {
            OUTCOME_COMPLETED -> DeletionStatusOutcome.Completed
            OUTCOME_UNKNOWN -> DeletionStatusOutcome.Unknown
            else -> DeletionStatusOutcome.Failed(
                AuthFailure.Unknown(detail = "unrecognised account_deletion_status outcome")
            )
        }
    }

    /**
     * One count out of the deletion's receipt, defaulting to zero.
     *
     * A missing or non-numeric field is reported as zero rather than failing the
     * whole call: the deletion has already committed by the time this is read, and
     * refusing to acknowledge it over a malformed statistic would turn a success into
     * a retry of something that cannot be repeated.
     */
    private fun JsonObject.count(name: String): Long =
        this[name]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L

    override suspend fun currentAccount(): AccountInfo? = runCatching {
        val user = client?.auth?.currentUserOrNull() ?: return null
        AccountInfo(
            uid = user.id,
            // The key the create-account form wrote and the key the Supabase
            // dashboard displays. `jsonPrimitive.content` rather than `toString()`,
            // which would hand the screen a quoted JSON string to draw.
            displayName = user.userMetadata?.get(DISPLAY_NAME)?.jsonPrimitive?.contentOrNull,
            email = user.email,
        )
    }.getOrNull()

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

        // ------------------------------------------------ account deletion --
        //
        // Wire names for migration 0004. Constants for the same reason DISPLAY_NAME
        // is: they are strings two sides have to agree on, and a typo in one of them
        // compiles perfectly and fails only against a live project.

        const val RPC_DELETE_ACCOUNT = "delete_my_account"
        const val RPC_DELETION_STATUS = "account_deletion_status"

        /**
         * The status route's own path, because it does not go through supabase-kt.
         *
         * PostgREST's RPC convention, the same one `db.rpc(name, …)` builds for every
         * other call in this package - written out here only because this request is
         * constructed directly. See [checkDeletionStatus] for why.
         */
        const val STATUS_PATH = "/rest/v1/rpc/$RPC_DELETION_STATUS"

        /** The header a publishable key belongs in, and the only one it is sent in. */
        const val HEADER_APIKEY = "apikey"

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        const val PARAM_REQUEST_ID = "p_request_id"
        const val PARAM_DELETED_UID = "p_deleted_uid"

        private const val OUTCOME = "outcome"
        private const val OUTCOME_DELETED = "DELETED"
        private const val OUTCOME_ALREADY_DELETED = "ALREADY_DELETED"
        private const val OUTCOME_COMPLETED = "COMPLETED"
        private const val OUTCOME_UNKNOWN = "UNKNOWN"

        /**
         * The SQLSTATEs `delete_my_account` raises itself, and the only codes that may
         * be read as a definitive refusal.
         *
         * Written out as a closed set rather than a range or a prefix: the point is
         * that an unrecognised code is *not* a refusal, and a set is the only shape
         * that keeps saying so when the function grows a new one.
         */
        private val DEFINITIVE_REFUSALS = setOf("28000", "42501", "22023", "XX000")
    }
}
