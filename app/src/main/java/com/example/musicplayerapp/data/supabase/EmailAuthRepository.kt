package com.example.musicplayerapp.data.supabase

import android.content.Context
import android.util.Log
import com.example.musicplayerapp.data.AppDatabase

/**
 * Registering, signing in and recovering a password - and deciding, each time,
 * whether that is a plain authentication or an identity handoff.
 *
 * ## The routing is the whole job
 *
 * Every method here starts by reading [IdentityStore.state], and the answer decides
 * which of two completely different things happens:
 *
 * | state | what a sign-in or registration is |
 * |---|---|
 * | [IdentityState.None] | a plain authentication. Nothing exists to preserve |
 * | [IdentityState.SignedOut] | a plain authentication. Sync resumes as the new uid |
 * | [IdentityState.Anonymous] | an **identity handoff**: X is retired, Y adopts |
 *
 * The third row is the reason this file is not four lines long. Supabase will not
 * turn an anonymous user into a password account in place, so there is no
 * `updateUser(email, password)` upgrade here and there deliberately never will be:
 * that call returns the *same* uid with an email attached, which reads like exactly
 * what is wanted and quietly diverges from what the server will actually enforce.
 * The safe route is the one G-A4b1 built - drain X, retire X while X's session is
 * still live, authenticate Y, adopt the local rows into Y - and this file's job is to
 * hand it a destination and translate the outcome.
 *
 * ## Email is an identifier, not a permission
 *
 * Registration sends no confirmation mail, so an address here proves nothing about a
 * mailbox. Nothing in this package treats it as evidence of anything: it is the
 * string somebody signs in with. Password recovery is the single exception and it is
 * not really one - the recovery code proves control of the mailbox at the moment it
 * is used, which is what makes that flow safe under an unverified-email model and
 * what registration never had.
 *
 * ## What is deliberately refused
 *
 * Several state/action pairs have no defined answer yet and get a typed refusal
 * rather than a guess - see [undefined]. Registering from [IdentityState.SignedOut]
 * is the sharpest: this install owns `lastUid`, nothing records whether that uid was
 * anonymous or a real account, and the two want opposite handling. A handoff cannot
 * run there either, because retiring X's remote state needs X's session and a
 * signed-out install has none. Answering that needs an owner decision, not an
 * inference from the code.
 */
object EmailAuthRepository {

    private const val TAG = "SupabaseAuth"

    /**
     * How many batches the pre-handoff drain will push before giving up.
     *
     * [ReactionSyncEngine.BATCH_SIZE] rows each. Bounded because the drain runs while
     * somebody is waiting on a registration button, and unbounded here would mean a
     * listener who reacted a thousand times on a plane waits for a thousand round
     * trips; they get an abort, which changes nothing and can be retried.
     */
    private const val DRAIN_PAGES = 4

    // ------------------------------------------------------------ register --

    /**
     * Creates an account, from wherever this install currently is.
     *
     * [displayName] is the `Имя` field, stored as `user_metadata.display_name`.
     */
    suspend fun register(
        context: Context,
        email: String,
        password: String,
        displayName: String,
    ): AuthResult {
        val api = EmailAuthBackend.api(context)
        val call: suspend () -> AuthResult = { confirmed(api, api.signUp(email, password, displayName)) }

        return when (val state = IdentityStore.state(context)) {
            is IdentityState.None -> direct(context, AuthAttempt.REGISTER, call)
            is IdentityState.Anonymous -> viaHandoff(context, state.uid, api, call)
            else -> undefined("register", state)
        }
    }

    // ------------------------------------------------------------- sign in --

    /** Authenticates an existing account, from wherever this install currently is. */
    suspend fun signIn(context: Context, email: String, password: String): AuthResult {
        val api = EmailAuthBackend.api(context)
        val call: suspend () -> AuthResult = { confirmed(api, api.signIn(email, password)) }

        return when (val state = IdentityStore.state(context)) {
            is IdentityState.None,
            is IdentityState.SignedOut,
            -> direct(context, AuthAttempt.SIGN_IN, call)

            is IdentityState.Anonymous -> viaHandoff(context, state.uid, api, call)

            else -> undefined("sign in", state)
        }
    }

    // ------------------------------------------------------------ recovery --

    /**
     * Asks for a recovery mail, and changes no identity state whatever.
     *
     * **No handoff is started here, and that is a rule rather than an optimisation.**
     * Requesting a mail is not authentication: nobody has proved anything yet, the
     * address may not even have an account, and starting a handoff would retire this
     * install's anonymous identity on the strength of somebody typing an address into
     * a form. The switch belongs to [verifyRecoveryCode], where a session is actually
     * established.
     *
     * This is also the only call in the app that spends mail quota.
     */
    suspend fun requestPasswordReset(context: Context, email: String): RecoveryResult =
        EmailAuthBackend.api(context).requestPasswordReset(email)

    /**
     * Exchanges a typed recovery code for a session - which *is* an authentication,
     * and therefore is routed exactly like [signIn].
     *
     * An anonymous install that recovers a password ends up as Y just as surely as one
     * that signs in, so it goes through the same handoff. Anything else would leave
     * the device holding Y's session while its own storage still said X, which is the
     * split this whole package exists to prevent.
     */
    suspend fun verifyRecoveryCode(context: Context, email: String, code: String): RecoveryResult {
        val api = EmailAuthBackend.api(context)

        // Bridged through AuthResult so the handoff and the direct path can be shared
        // with sign-in rather than reimplemented: everything above cares that a
        // session was established and by whom, not which endpoint established it.
        val call: suspend () -> AuthResult = {
            val verified = when (val outcome = api.verifyRecoveryCode(email, code)) {
                is RecoveryResult.PasswordResetAuthorized -> AuthResult.Success(outcome.uid)
                is RecoveryResult.Failed -> AuthResult.Failed(outcome.failure)
                else -> AuthResult.Failed(
                    AuthFailure.Unknown(detail = "unexpected recovery result: $outcome")
                )
            }
            // Verifying a RECOVERY OTP establishes a session, so it is held to the
            // same rule as any other authentication.
            confirmed(api, verified)
        }

        val routed = when (val state = IdentityStore.state(context)) {
            is IdentityState.None,
            is IdentityState.SignedOut,
            -> direct(context, AuthAttempt.SIGN_IN, call)

            is IdentityState.Anonymous -> viaHandoff(context, state.uid, api, call)

            else -> undefined("verify a recovery code", state)
        }

        return when (routed) {
            is AuthResult.Success -> RecoveryResult.PasswordResetAuthorized(routed.uid)
            is AuthResult.Failed -> RecoveryResult.Failed(routed.failure)
        }
    }

    /**
     * Sets a new password on the session [verifyRecoveryCode] established.
     *
     * No identity routing: the session already belongs to whoever this install now
     * is, and changing a password does not change who that is.
     */
    suspend fun updatePassword(context: Context, newPassword: String): RecoveryResult =
        EmailAuthBackend.api(context).updatePassword(newPassword)

    // --------------------------------------------------------------- paths --

    /**
     * The guard every authentication passes before its uid is allowed anywhere near
     * [IdentityStore].
     *
     * **A reported success is a claim, not a session.** `signUpWith(Email)` returns a
     * `UserInfo` whenever a row was created in `auth.users`, whether or not a token
     * came with it, and whether or not one did depends on a dashboard setting this
     * code cannot see. Committing `REGISTERED(Y)` on the strength of that claim would
     * leave an install asserting an account it holds no token for - every write
     * refused by RLS, every screen saying it is signed in, and nothing able to explain
     * the contradiction.
     *
     * So the session is asked, every time, on every path that ends in a persisted
     * identity: registration, sign-in and recovery verification alike, because a
     * verified RECOVERY OTP establishes a session exactly as the other two do.
     *
     * [SupabaseEmailAuthApi] asks the same question with the value only it can see -
     * the uid the sign-up response reported. This asks it again of whatever came back
     * through [EmailAuthApi], which is what makes "a live session for this uid" part
     * of that interface's contract rather than a property of the one implementation
     * behind it today.
     *
     * Failing here **never** routes anybody into an OTP or confirmation flow.
     * Email-confirmation registration is not part of v1, and turning a misconfigured
     * dashboard into "check your mail" would quietly implement it.
     */
    private suspend fun confirmed(api: EmailAuthApi, result: AuthResult): AuthResult =
        when (result) {
            is AuthResult.Failed -> result

            is AuthResult.Success ->
                when (val verdict = sessionVerdict(result.uid, api.currentUid())) {
                    null -> result
                    else -> {
                        Log.w(TAG, "authentication left no usable session: ${verdict.reason}")
                        AuthResult.Failed(verdict)
                    }
                }
        }

    /**
     * Authentication with no identity to preserve: [IdentityState.None] or
     * [IdentityState.SignedOut].
     *
     * The durable attempt marker is committed **before** the remote call and cleared
     * after the identity is on disk, so the window between "the server says you are
     * Y" and "this device says it is Y" is recorded rather than inferred. See the
     * auth-attempt section of [IdentityStore] for why inference is not available.
     */
    private suspend fun direct(
        context: Context,
        attempt: AuthAttempt,
        call: suspend () -> AuthResult,
    ): AuthResult {
        IdentityStore.markAuthAttempt(context, attempt)

        return when (val result = call()) {
            is AuthResult.Success -> {
                IdentityStore.markRegistered(context, result.uid)
                IdentityStore.clearAuthAttempt(context)
                // Rows that accumulated while this install was signed out - or before
                // it had any identity at all - now have an owner. Nothing else would
                // wake them: onReactionCommitted refuses to enqueue while paused, so
                // without this they wait for the next tap or the next launch.
                ReactionSyncScheduler.onAppStart(context)
                Log.d(TAG, "authenticated directly; identity committed")
                result
            }

            is AuthResult.Failed -> {
                IdentityStore.clearAuthAttempt(context)
                result
            }
        }
    }

    /**
     * Authentication that is also an identity change: [IdentityState.Anonymous].
     *
     * Everything structural belongs to [IdentityHandoff]; this supplies the two
     * things it does not have - how to drain, and how to establish the destination -
     * and turns its three outcomes back into an [AuthResult].
     */
    private suspend fun viaHandoff(
        context: Context,
        from: String,
        api: EmailAuthApi,
        call: suspend () -> AuthResult,
    ): AuthResult {
        val database = AppDatabase.getDatabase(context)

        // The typed reason the destination could not be established. The handoff's
        // DestinationIdentity contract is "a uid, or null", which is all *it* needs
        // and nothing like enough for a form: "wrong password" and "you are offline"
        // are the same null. Captured on the way past instead.
        var failure: AuthFailure? = null

        val outcome = IdentityHandoff.run(
            context = context,
            from = from,
            reactions = database.reactionDao(),
            outbox = database.reactionOutboxDao(),
            api = ReactionSyncBackend.api(context),
            drain = { drainForHandoff(context, database) },
            destination = IdentityHandoff.DestinationIdentity {
                when (val result = call()) {
                    is AuthResult.Success -> result.uid

                    is AuthResult.Failed -> when {
                        // The session was already inspected, and found either absent
                        // or belonging to somebody who is not the destination. Asking
                        // again would be asking the question that just came back "no",
                        // and acting on the answer would commit an identity the
                        // listener did not ask to become. Roll back instead: X is
                        // retired at this point and must not stay that way.
                        result.failure is AuthFailure.SessionNotEstablished -> {
                            Log.w(TAG, "the destination established no usable session; rolling back")
                            failure = result.failure
                            null
                        }

                        else -> {
                            // Any other failure report is not proof that no session
                            // exists. If the remote switch actually happened, rolling
                            // back would try to rebuild X's state under Y's token -
                            // refused by RLS - and would leave a device whose storage
                            // and session disagree. So the session is asked directly,
                            // and it wins.
                            val live = api.currentUid()
                            if (live != null && live != from) {
                                Log.w(TAG, "auth reported failure but a new session exists; going forward")
                                live
                            } else {
                                failure = result.failure
                                null
                            }
                        }
                    }
                }
            },
        )

        return when (outcome) {
            is IdentityHandoff.Result.Switched -> {
                // Reactions committed after the ownership cutover belong to Y and are
                // sitting in the outbox behind a gate that has just opened.
                ReactionSyncScheduler.onAppStart(context)
                Log.d(TAG, "handoff complete; this install is now the destination identity")
                AuthResult.Success(outcome.uid)
            }

            // Both failures leave the install exactly as it was - still anonymous,
            // still owning its rows - so both are reported as the auth failure that
            // caused them, with the handoff's own reason only as a fallback for the
            // cases auth had no part in: an outbox that would not drain, a retirement
            // the server refused.
            is IdentityHandoff.Result.RolledBack ->
                AuthResult.Failed(failure ?: AuthFailure.Unknown(detail = outcome.why))

            is IdentityHandoff.Result.Aborted ->
                AuthResult.Failed(failure ?: AuthFailure.Unknown(detail = outcome.why))
        }
    }

    /**
     * The drain the handoff runs before it will touch anything.
     *
     * Paged, because [ReactionSyncEngine] deliberately bounds a run to
     * [ReactionSyncEngine.BATCH_SIZE] rows and reports [DrainResult.MoreWorkDue]
     * rather than looping forever inside one worker. Doing the paging here rather
     * than letting the handoff's own retry loop do it keeps those three attempts for
     * what they are for - an outbox that is being *refilled* by a listener tapping
     * during a registration - instead of spending them on pagination.
     *
     * @return whether it is worth proceeding. False abandons the handoff and writes
     *   nothing at all, which is rule 2 of the frozen contract.
     */
    private suspend fun drainForHandoff(context: Context, database: AppDatabase): Boolean {
        val engine = ReactionSyncEngine(
            reactions = database.reactionDao(),
            outbox = database.reactionOutboxDao(),
            api = ReactionSyncBackend.api(context),
            identity = { ReactionSyncBackend.identity(context) },
        )

        repeat(DRAIN_PAGES) {
            when (val result = engine.drain()) {
                is DrainResult.Idle, is DrainResult.Drained -> return true

                // The batch filled. Another page, and the cutover's own emptiness
                // check is what decides whether it was enough.
                is DrainResult.MoreWorkDue -> Unit

                // A drain that started before this one holds the lease. Proceeding is
                // correct: the handoff takes the lease with a *wait*, so it queues
                // behind that drain and re-counts the outbox once it has finished.
                is DrainResult.HandoffInProgress -> return true

                // Every row is parked on a backoff, there is no session, or this
                // install is signed out. None of them will resolve inside a
                // registration, and a handoff that cannot drain must not start.
                is DrainResult.Waiting,
                is DrainResult.RetryLater,
                is DrainResult.Paused,
                -> return false
            }
        }

        return false
    }

    /**
     * A state and an action with no defined transition between them.
     *
     * Typed rather than thrown, and refused rather than guessed. Each of these is an
     * owner decision that G-A4b2 deliberately does not take: what registering from a
     * signed-out install means when nothing records whether the last uid was
     * anonymous, and what signing into a second account from a first one should do
     * with the first one's remote state - which is emphatically *not* "retire it",
     * because that state may belong to an account the person still uses elsewhere.
     */
    private fun undefined(action: String, state: IdentityState): AuthResult {
        val why = "$action is not a defined transition from ${state.javaClass.simpleName}"
        Log.w(TAG, why)
        return AuthResult.Failed(AuthFailure.Unknown(detail = why))
    }
}
