package com.example.musicplayerapp

import androidx.test.core.app.ActivityScenario
import com.example.musicplayerapp.data.ReactionOutboxEntry
import com.example.musicplayerapp.data.TrackReaction
import com.example.musicplayerapp.data.supabase.AuthFailure
import com.example.musicplayerapp.data.supabase.AuthResult
import com.example.musicplayerapp.data.supabase.EmailAuthApi
import com.example.musicplayerapp.data.supabase.EmailAuthBackend
import com.example.musicplayerapp.data.supabase.IdentityState
import com.example.musicplayerapp.data.supabase.IdentityStore
import com.example.musicplayerapp.data.supabase.ListenerIdentity
import com.example.musicplayerapp.data.supabase.ReactionSyncApi
import com.example.musicplayerapp.data.supabase.ReactionSyncBackend
import com.example.musicplayerapp.data.supabase.RecoveryResult
import com.example.musicplayerapp.data.supabase.SyncOutcome

/**
 * The doubles the G-A4b2 suites share, and the one helper that puts the harness back.
 *
 * Everything here is deliberately a recorder rather than a mock. The questions these
 * suites ask are of the form "was X retired *before* Y adopted", "did anything write
 * an event as the destination", "was the identity boundary consulted at all" - and
 * those are answered by keeping a list and looking at it afterwards, not by
 * pre-declaring what should be called.
 */

/**
 * An auth backend with no server behind it.
 *
 * Scripted through two fields, and the second one is the interesting one.
 * [sessionDespiteFailure] models the case the frozen contract cares most about: the
 * remote switch happened, and the call still reported a failure. A device in that
 * state must **not** roll back, because the session it now holds belongs to the
 * destination and rebuilding the source under it would be refused by RLS.
 */
internal class FakeEmailAuthApi : EmailAuthApi {

    /** The uid handed out by the next successful authentication. */
    var uid: String = "22222222-2222-4222-8222-222222222222"

    /** When set, the next authentication fails with this instead of succeeding. */
    var failure: AuthFailure? = null

    /** Whether a *failed* authentication nonetheless leaves a live session behind. */
    var sessionDespiteFailure: Boolean = false

    /**
     * Whether a *successful* call leaves a session behind at all.
     *
     * False models the shape the guard exists for: `signUpWith` returns a `UserInfo`
     * because a row was created, and no token came with it because Confirm Email is
     * on. The call reports a user and this device is authenticated as nobody.
     */
    var establishesSession: Boolean = true

    /**
     * The uid the session ends up holding, when it differs from the reported one.
     *
     * Null means they agree, which is every ordinary case. Set, it models the other
     * half of the guard: a user is reported and the live session belongs to somebody
     * else - the anonymous identity the sign-up failed to replace, most plausibly.
     */
    var sessionUid: String? = null

    /** What `currentUid()` reports. Also settable directly, to model a restored session. */
    var session: String? = null

    /**
     * Holds an authentication open until a test lets it finish.
     *
     * The only way to observe a request that is *in flight* - which is what the
     * loading state, the double-submit guard and the survives-recreation rule are
     * all claims about. Without it every call returns before the assertion can look,
     * and a spinner nobody ever saw would pass every test.
     */
    var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    /** Incremented on entry to every authenticating call, before the gate. */
    var authCalls = 0
        private set

    /**
     * When set, the call throws this instead of returning a result.
     *
     * The shape every layer below the ViewModel is supposed to make impossible - a
     * Room open that failed, a preferences write that failed, a bug - and therefore
     * exactly the shape worth proving the ViewModel survives. An exception escaping
     * `viewModelScope` takes the process with it and leaves the button spinning on
     * the way out, which is a failure mode no amount of care in the layers below can
     * be relied on to prevent forever.
     */
    var throwOnCall: Throwable? = null

    val signUps = mutableListOf<SignUp>()
    val signIns = mutableListOf<Credentials>()
    val resetRequests = mutableListOf<String>()
    val verifications = mutableListOf<Credentials>()
    val passwordUpdates = mutableListOf<String>()

    var localSignOuts = 0
        private set

    data class SignUp(val email: String, val password: String, val displayName: String)

    data class Credentials(val email: String, val secret: String)

    override suspend fun signUp(email: String, password: String, displayName: String): AuthResult {
        signUps += SignUp(email, password, displayName)
        return authenticate()
    }

    override suspend fun signIn(email: String, password: String): AuthResult {
        signIns += Credentials(email, password)
        return authenticate()
    }

    /** Completes whatever is waiting on [gate], letting the call return. */
    fun release() {
        gate?.complete(Unit)
    }

    override suspend fun requestPasswordReset(email: String): RecoveryResult {
        resetRequests += email
        return failure?.let { RecoveryResult.Failed(it) } ?: RecoveryResult.Requested
    }

    override suspend fun verifyRecoveryCode(email: String, code: String): RecoveryResult {
        verifications += Credentials(email, code)
        val failed = failure
        if (failed != null) {
            if (sessionDespiteFailure) session = uid
            return RecoveryResult.Failed(failed)
        }
        if (establishesSession) session = sessionUid ?: uid
        return RecoveryResult.PasswordResetAuthorized(uid)
    }

    override suspend fun updatePassword(newPassword: String): RecoveryResult {
        passwordUpdates += newPassword
        val failed = failure
        return if (failed != null) {
            RecoveryResult.Failed(failed)
        } else {
            RecoveryResult.PasswordUpdated(session ?: uid)
        }
    }

    override suspend fun currentUid(): String? = session

    override suspend fun signOutLocal(): Boolean {
        localSignOuts++
        session = null
        return true
    }

    private suspend fun authenticate(): AuthResult {
        authCalls++
        gate?.await()
        throwOnCall?.let { throw it }

        val failed = failure
        if (failed != null) {
            if (sessionDespiteFailure) session = uid
            return AuthResult.Failed(failed)
        }
        if (establishesSession) session = sessionUid ?: uid
        return AuthResult.Success(uid)
    }
}

/**
 * A sync backend that records who did what to whom.
 *
 * The same shape as `IdentityHandoffTest`'s private one, with the listener id kept
 * alongside every event. That addition is what lets a suite driving the **real**
 * drain still assert the no-synthetic-events rule: the drain legitimately writes
 * events as the source identity, so "no events at all" is the wrong claim and "no
 * events as the destination" is the right one.
 */
internal class RecordingSyncApi : ReactionSyncApi {

    val retirements = mutableListOf<String>()
    val adoptedBy = linkedMapOf<String, MutableMap<String, String>>()
    val events = mutableListOf<Pair<ReactionOutboxEntry, String>>()

    var onRetire: (String) -> SyncOutcome = { SyncOutcome.Success }
    var onReconcile: (String) -> SyncOutcome = { SyncOutcome.Success }

    /** Every event written as [listenerId]. Asserted empty for a destination. */
    fun eventsBy(listenerId: String) = events.filter { it.second == listenerId }

    override suspend fun deliverEvent(entry: ReactionOutboxEntry, listenerId: String): SyncOutcome {
        events += entry to listenerId
        return SyncOutcome.Success
    }

    override suspend fun reconcileCurrentState(
        trackKey: String,
        current: TrackReaction?,
        listenerId: String,
    ): SyncOutcome {
        val outcome = onReconcile(trackKey)
        if (outcome is SyncOutcome.Success && current != null) {
            adoptedBy.getOrPut(listenerId) { linkedMapOf() }[trackKey] = current.reaction.name
        }
        return outcome
    }

    override suspend fun retireAllCurrentState(listenerId: String): SyncOutcome {
        val outcome = onRetire(listenerId)
        if (outcome is SyncOutcome.Success) {
            retirements += listenerId
            adoptedBy.remove(listenerId)
        }
        return outcome
    }
}

/**
 * A listener-identity boundary that counts how often it was asked.
 *
 * The count is the assertion for "password recovery must not touch the identity".
 * A recovery request that reached this at all would be one that could have started a
 * handoff, and no amount of it happening to return the right answer would make that
 * acceptable.
 */
internal class CountingIdentity(private val uid: String?) {

    var calls = 0
        private set

    fun asProvider(): suspend (android.content.Context) -> ListenerIdentity = {
        calls++
        uid?.let { ListenerIdentity.Available(it) } ?: ListenerIdentity.Unavailable("test")
    }
}

/**
 * Puts the process-wide harness back the way `MyataTestRunner` left it.
 *
 * Called from every `@After` in these suites, and it is not optional: a suite that
 * installed its own auth double and then simply cleared the override would hand the
 * *next* test class the real backend, which is a suite that can create accounts in
 * production. Restoring the offline doubles is the safe direction; on an opted-in
 * live run it restores the real ones, which is what that run asked for.
 */
internal object TestIsolation {

    fun restoreBackends() {
        if (LiveSupabase.isOptedIn) {
            ReactionSyncBackend.overrideForInstrumentation(null, null)
            EmailAuthBackend.overrideForInstrumentation(null)
            return
        }

        EmailAuthBackend.overrideForInstrumentation { OfflineAuth }
        ReactionSyncBackend.overrideForInstrumentation(
            api = { OfflineSync },
            identity = { context ->
                when (val state = IdentityStore.state(context)) {
                    is IdentityState.SignedOut -> ListenerIdentity.Paused(state.lastUid)
                    else -> ListenerIdentity.Unavailable("instrumentation")
                }
            },
        )
    }

    private const val WHY = "live Supabase is disabled for this instrumentation run"

    private object OfflineAuth : EmailAuthApi {
        private val refused = AuthResult.Failed(AuthFailure.NetworkFailure(WHY))
        override suspend fun signUp(email: String, password: String, displayName: String) = refused
        override suspend fun signIn(email: String, password: String) = refused
        override suspend fun requestPasswordReset(email: String): RecoveryResult =
            RecoveryResult.Failed(AuthFailure.NetworkFailure(WHY))
        override suspend fun verifyRecoveryCode(email: String, code: String): RecoveryResult =
            RecoveryResult.Failed(AuthFailure.NetworkFailure(WHY))
        override suspend fun updatePassword(newPassword: String): RecoveryResult =
            RecoveryResult.Failed(AuthFailure.NetworkFailure(WHY))
        override suspend fun currentUid(): String? = null
        override suspend fun signOutLocal(): Boolean = true
    }

    private object OfflineSync : ReactionSyncApi {
        override suspend fun deliverEvent(entry: ReactionOutboxEntry, listenerId: String) =
            SyncOutcome.AuthUnavailable(WHY)
        override suspend fun reconcileCurrentState(
            trackKey: String,
            current: TrackReaction?,
            listenerId: String,
        ) = SyncOutcome.AuthUnavailable(WHY)
        override suspend fun retireAllCurrentState(listenerId: String) =
            SyncOutcome.AuthUnavailable(WHY)
    }
}

/**
 * Launches MainActivity and tears it down the way this repository's other suites do.
 *
 * **Not `use {}`.** `ActivityScenario.close()` times out on the API 24 image with
 * `Activity never becomes requested state "[DESTROYED]"` - a known, recorded
 * property of that emulator rather than of any test - and `use {}` turns that into
 * a hard failure *outside* the test body, where the message does not survive into
 * the result XML and the run destabilises around it. Eight of this project's nine
 * ActivityScenario suites already close inside a try/catch for exactly this reason;
 * these two were the exception, and they were the two that failed.
 *
 * It bites hardest on a test that leaves the activity mid-request with a spinner up,
 * because that is the activity that takes longest to reach DESTROYED - which is why
 * the visible symptom was a stranded loading screen and the failing test was always
 * the one holding a deferred open.
 */
internal fun withMainActivity(body: (ActivityScenario<MainActivity>) -> Unit) {
    val scenario = ActivityScenario.launch(MainActivity::class.java)
    try {
        body(scenario)
    } finally {
        try {
            scenario.close()
        } catch (e: Throwable) {
            android.util.Log.w("AuthQA", "activity close timed out; checks already complete", e)
        }
    }
}
