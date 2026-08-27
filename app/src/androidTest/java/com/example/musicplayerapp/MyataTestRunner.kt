package com.example.musicplayerapp

import android.app.Application
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import com.example.musicplayerapp.data.ReactionOutboxEntry
import com.example.musicplayerapp.data.TrackReaction
import com.example.musicplayerapp.data.supabase.AccountInfo
import com.example.musicplayerapp.data.supabase.AuthFailure
import com.example.musicplayerapp.data.supabase.AuthResult
import com.example.musicplayerapp.data.supabase.EmailAuthApi
import com.example.musicplayerapp.data.supabase.EmailAuthBackend
import com.example.musicplayerapp.data.supabase.IdentityState
import com.example.musicplayerapp.data.supabase.IdentityStore
import com.example.musicplayerapp.data.supabase.ListenerIdentity
import com.example.musicplayerapp.data.supabase.BatchOutcome
import com.example.musicplayerapp.data.supabase.ReactionSyncApi
import com.example.musicplayerapp.data.supabase.ReactionSyncBackend
import com.example.musicplayerapp.data.supabase.RecoveryResult
import com.example.musicplayerapp.data.supabase.SyncOutcome

/**
 * The runner every instrumentation run goes through, and the only place the live
 * project can be switched on.
 *
 * [onCreate] is called after `newApplication` but before `callApplicationOnCreate`,
 * and therefore before `MyataApplication.onCreate`, which is the whole point: the app
 * schedules a reaction
 * drain at startup, so by the time any `@Before` could run, a pending outbox row is
 * already on its way to production. Installing the gate here is the only position
 * early enough to stop it.
 *
 * Registered in `app/build.gradle` as the `testInstrumentationRunner`, so there is no
 * way to run the suite and bypass it - which is the difference between a rule and a
 * convention somebody has to remember.
 */
class MyataTestRunner : AndroidJUnitRunner() {

    /** What [onCreate] decided. The runner's own `arguments` field is private. */
    private var optedIn = false

    override fun onCreate(arguments: Bundle) {
        optedIn = LiveSupabase.optedIn(arguments)
        if (optedIn) {
            // Explicitly asked for. The real backend stays in place and the live
            // suites run for real.
            ReactionSyncBackend.overrideForInstrumentation(null, null)
            EmailAuthBackend.overrideForInstrumentation(null)
        } else {
            // The auth boundary matters more than the sync one, not less. A stray
            // reaction row can be deleted with the key the app already ships; a stray
            // auth.users row cannot be deleted with it at all, so a suite that
            // registered against production would leave rows nothing in this repo can
            // clean up.
            EmailAuthBackend.overrideForInstrumentation { OfflineEmailAuthApi }

            ReactionSyncBackend.overrideForInstrumentation(
                api = { OfflineReactionSyncApi },
                // The identity half is the important one, and it is not a constant.
                //
                // The harness must remove the *network*, not the product's state
                // machine. Returning a flat Unavailable would do both: a genuinely
                // signed-out install would report "temporarily broken" instead of
                // "paused", the worker would retry it forever, and the distinction
                // this whole state machine exists to draw would be invisible to every
                // test. So the persisted state still decides - reading it is a
                // SharedPreferences lookup and reaches nothing - and only the ability
                // to mint or refresh is taken away.
                identity = { ctx ->
                    when (val state = IdentityStore.state(ctx)) {
                        is IdentityState.SignedOut -> ListenerIdentity.Paused(state.lastUid)
                        else -> ListenerIdentity.Unavailable("instrumentation")
                    }
                },
            )
        }
        super.onCreate(arguments)
    }

    override fun callApplicationOnCreate(app: Application) {
        // The last instant before MyataApplication.onCreate runs, and therefore
        // before it schedules a drain. Asserting here rather than in newApplication
        // is not a detail: ActivityThread calls newApplication *before* onCreate, so
        // a check there runs before the gate is installed and fails every run. This
        // is the hook that is genuinely last.
        check(optedIn || (ReactionSyncBackend.isOverridden && EmailAuthBackend.isOverridden)) {
            "the live Supabase gate was not installed before the Application started"
        }
        super.callApplicationOnCreate(app)
    }
}

/**
 * A backend that is not a backend: every call reports that there is no session.
 *
 * [SyncOutcome.AuthUnavailable] rather than a failure on purpose. It is the one
 * outcome the drain treats as nobody's fault - the run stops, the row keeps its
 * attempt count and its backoff, and nothing is parked or discarded. So a suite that
 * happens to leave a pending row behind finds it exactly as it left it, and the row
 * syncs normally the next time the real app runs.
 */
private object OfflineReactionSyncApi : ReactionSyncApi {

    private const val WHY = "live Supabase is disabled for this instrumentation run"

    override suspend fun deliverEvent(entry: ReactionOutboxEntry, listenerId: String): SyncOutcome =
        SyncOutcome.AuthUnavailable(WHY)

    override suspend fun reconcileCurrentState(
        trackKey: String,
        current: TrackReaction?,
        listenerId: String,
    ): SyncOutcome = SyncOutcome.AuthUnavailable(WHY)

    override suspend fun retireAllCurrentState(listenerId: String): SyncOutcome =
        SyncOutcome.AuthUnavailable(WHY)

    override suspend fun applyBatch(
        trackKey: String,
        events: List<ReactionOutboxEntry>,
        current: TrackReaction,
        listenerId: String,
    ): BatchOutcome = BatchOutcome.Failed(SyncOutcome.AuthUnavailable(WHY))
}

/**
 * An auth backend that authenticates nobody.
 *
 * Every call reports the same refusal, and the refusal is deliberately
 * [AuthFailure.NetworkFailure] rather than something that reads like a rejected
 * password: a test that accidentally exercises this gets "there is no network here",
 * which is true, instead of "those credentials are wrong", which would be a lie the
 * assertion might well accept.
 *
 * A suite that means to drive the repository installs its own fake over this one and
 * puts this back afterwards. What it must never do is remove the override and leave
 * the real backend in place - see `EmailAuthBackend`.
 */
private object OfflineEmailAuthApi : EmailAuthApi {

    private const val WHY = "live Supabase is disabled for this instrumentation run"

    private val refused = AuthResult.Failed(AuthFailure.NetworkFailure(WHY))

    override suspend fun signUp(email: String, password: String, displayName: String) = refused

    override suspend fun signIn(email: String, password: String) = refused

    override suspend fun requestPasswordReset(email: String): RecoveryResult =
        RecoveryResult.Failed(AuthFailure.NetworkFailure(WHY))

    override suspend fun verifyRecoveryCode(email: String, code: String): RecoveryResult =
        RecoveryResult.Failed(AuthFailure.NetworkFailure(WHY))

    override suspend fun updatePassword(newPassword: String): RecoveryResult =
        RecoveryResult.Failed(AuthFailure.NetworkFailure(WHY))

    override suspend fun currentAccount(): AccountInfo? = null

    override suspend fun currentUid(): String? = null

    override suspend fun signOutLocal(): Boolean = true
}
