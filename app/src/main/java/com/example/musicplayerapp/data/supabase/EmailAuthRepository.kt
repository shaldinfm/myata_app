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

        return route(
            context = context,
            what = "register",
            attempt = AuthAttempt.REGISTER,
            api = api,
            call = call,
            // Registration from SIGNED_OUT is deliberately undefined: that install
            // already knows an account, and creating a second one for the same person
            // is the thing the identity model exists to prevent.
            mayAuthenticateDirectly = { it is IdentityState.None },
        )
    }

    // ------------------------------------------------------------- sign in --

    /** Authenticates an existing account, from wherever this install currently is. */
    suspend fun signIn(context: Context, email: String, password: String): AuthResult {
        val api = EmailAuthBackend.api(context)
        val call: suspend () -> AuthResult = { confirmed(api, api.signIn(email, password)) }

        return route(
            context = context,
            what = "sign in",
            attempt = AuthAttempt.SIGN_IN,
            api = api,
            call = call,
            mayAuthenticateDirectly = {
                it is IdentityState.None || it is IdentityState.SignedOut
            },
        )
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

        val routed = route(
            context = context,
            what = "verify a recovery code",
            attempt = AuthAttempt.SIGN_IN,
            api = api,
            call = call,
            mayAuthenticateDirectly = {
                it is IdentityState.None || it is IdentityState.SignedOut
            },
        )

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

    // -------------------------------------------------------------- sign out --

    /**
     * The frozen LOCAL logout, in the order the contract requires.
     *
     * ```
     * 1  commit SIGN_OUT                 durable, before anything can be lost
     * 2  api.signOutLocal()              LOCAL scope: this device, no other
     * 3  IdentityStore.signOut()         SIGNED_OUT(lastUid), one commit
     * 4  clear the marker                nothing is owed
     * ```
     *
     * Step 1 before step 2 is the whole point, exactly as `PREPARED` precedes the
     * handoff's first destructive call. Written afterwards, a death between clearing
     * the session and committing the state would leave `REGISTERED(uid)` with no
     * session - which is what an offline install looks like - and no way to tell the
     * two apart. [IdentityReconciler] finishes whatever this did not.
     *
     * **Nothing local is touched.** No Room write, no Collection change, no outbox
     * change, and emphatically no anonymous mint: signing out is not a route back to
     * [IdentityState.None], and the rows stay exactly where they are so that signing
     * back in resumes rather than restores.
     *
     * `LOCAL` scope means other devices keep their sessions. One person signing out
     * of their phone is not a statement about their tablet.
     */
    suspend fun signOut(context: Context): AuthResult {
        val state = IdentityStore.state(context)
        val uid = state.uid
            ?: return undefined("sign out", state)

        if (state !is IdentityState.Registered) return undefined("sign out", state)

        IdentityStore.markAuthAttempt(context, AuthAttempt.SIGN_OUT)

        // The call is allowed to fail. The token is cleared locally by the plugin
        // whether or not the server was reachable, and the persisted state is
        // authoritative regardless - so a listener on a train can still sign out.
        val cleared = EmailAuthBackend.api(context).signOutLocal()
        if (!cleared) Log.w(TAG, "the session did not clear cleanly; signing out locally anyway")

        IdentityStore.signOut(context)
        IdentityStore.clearAuthAttempt(context)
        Log.d(TAG, "signed out locally; cloud sync paused, local collection untouched")

        return AuthResult.Success(uid)
    }

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
     * Where one authentication goes, decided once and under [SyncLease].
     *
     * ## Why the lease, and not just a state read
     *
     * Two races live here, and they need the same answer.
     *
     * **The uid cannot change under a drain.** `apply_reaction_event_batch` takes its
     * identity from `auth.uid()` and accepts no `listener_id`, so the client checks
     * ownership itself just before the call - see `ownershipVerdict`. That check is
     * only worth something if nothing can authenticate as somebody else between it
     * and the request leaving. The drain holds the lease across its whole run,
     * including the call, so making direct authentication hold the lease too is what
     * turns "X's batch cannot execute as Y" from a hope into an invariant. Before
     * this, `direct` took no lease at all: a sign-out and a sign-in as another
     * account could land while a batch built for X was in flight, and the request
     * would carry the new account's token.
     *
     * **The route cannot be decided on stale state.** Reading the identity, then
     * waiting for the lease, then acting on what was read is the same bug one level
     * up: a drain that mints an anonymous identity while the sign-in waits would
     * leave that sign-in still believing it had nothing to preserve, and it would
     * authenticate directly and orphan the identity the drain had just created. So
     * the read happens **inside** the critical section, and it is the read that
     * decides.
     *
     * ## Why the handoff branch leaves the lease
     *
     * [IdentityHandoff.run] drains **before** it takes the lease - that is step one
     * of the frozen G-A4b1 ordering, and a drain cannot run while this holds the
     * lease. So the handoff branch cannot be executed here even in principle, and
     * calling it from inside would deadlock on a non-reentrant mutex rather than
     * merely being untidy.
     *
     * Only the decision needs the lease, and only the direct branch needs to *stay*
     * under it: the direct branch is the one that authenticates here. The handoff
     * branch carries a uid out to [viaHandoff], which re-establishes exclusion itself
     * and re-validates under it - the outbox emptiness check and the `PREPARED`
     * commit happen in one critical section it owns. Nothing is decided twice and
     * nothing is decided outside a lock.
     */
    private suspend fun route(
        context: Context,
        what: String,
        attempt: AuthAttempt,
        api: EmailAuthApi,
        call: suspend () -> AuthResult,
        mayAuthenticateDirectly: (IdentityState) -> Boolean,
    ): AuthResult {
        val decision = SyncLease.withExclusive {
            when (val fresh = IdentityStore.state(context)) {
                // An identity to preserve. Decided here, performed outside - see above.
                is IdentityState.Anonymous -> Route.ViaHandoff(fresh.uid)

                else ->
                    if (mayAuthenticateDirectly(fresh)) {
                        Route.Settled(directHoldingLease(context, attempt, call))
                    } else {
                        Route.Settled(undefined(what, fresh))
                    }
            }
        }

        val result = when (decision) {
            is Route.Settled -> decision.result
            is Route.ViaHandoff -> viaHandoff(context, decision.uid, api, call)
        }

        // The account is read back after an authentication settles, and only after.
        //
        // Here rather than inside the branches because both of them end the same way
        // when they end well - REGISTERED on disk, a session that agrees - and because
        // this is outside the lease. `directHoldingLease` runs inside it, and a pull
        // takes the same lease: triggering there would deadlock on a non-reentrant
        // mutex rather than merely being early. The handoff branch has already
        // released it too, and by this point has adopted the local rows into Y and
        // cleared its durable record.
        //
        // Fire and forget. A sign-in that worked is a sign-in that worked whether or
        // not the account could be read afterwards, and ReactionPull decides for
        // itself whether it may run at all - a failed authentication leaves the
        // identity untouched, so there is nothing for it to be eligible for.
        if (result is AuthResult.Success) {
            ReactionPullTrigger.requestInBackground(context, "after $what")
        }

        return result
    }

    /** What [route] concluded while it held the lease. */
    private sealed interface Route {
        data class Settled(val result: AuthResult) : Route
        data class ViaHandoff(val uid: String) : Route
    }

    /**
     * Authentication with no identity to preserve: [IdentityState.None] or
     * [IdentityState.SignedOut].
     *
     * **Runs with [SyncLease] held**, and must. See [route]: the lease is what stops a
     * drain's in-flight batch, built and ownership-checked as X, from being dispatched
     * on a session this call has just made Y.
     *
     * The durable attempt marker is committed **before** the remote call and cleared
     * after the identity is on disk, so the window between "the server says you are
     * Y" and "this device says it is Y" is recorded rather than inferred. See the
     * auth-attempt section of [IdentityStore] for why inference is not available.
     */
    private suspend fun directHoldingLease(
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
            deletionInFlight = { IdentityStore.deletionInFlight(context) },
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
                // An unresolved deletion joins the list for the same reason the others
                // are on it: it will not resolve inside a registration, and a handoff
                // that cannot drain must not start. IdentityHandoff.run refuses on the
                // same marker, so this is the earlier of two closed doors.
                is DrainResult.DeletionInProgress,
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
