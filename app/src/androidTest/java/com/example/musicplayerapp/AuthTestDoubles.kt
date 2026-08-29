package com.example.musicplayerapp

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.example.musicplayerapp.data.Reaction
import com.example.musicplayerapp.data.ReactionOutboxEntry
import com.example.musicplayerapp.data.TrackReaction
import com.example.musicplayerapp.data.supabase.AccountInfo
import com.example.musicplayerapp.data.supabase.AuthFailure
import com.example.musicplayerapp.data.supabase.AuthResult
import com.example.musicplayerapp.data.supabase.DeleteAccountOutcome
import com.example.musicplayerapp.data.supabase.DeletionStatusOutcome
import com.example.musicplayerapp.data.supabase.EmailAuthApi
import com.example.musicplayerapp.data.supabase.EmailAuthBackend
import com.example.musicplayerapp.data.supabase.IdentityState
import com.example.musicplayerapp.data.supabase.IdentityStore
import com.example.musicplayerapp.data.supabase.ListenerIdentity
import com.example.musicplayerapp.data.supabase.BatchOutcome
import com.example.musicplayerapp.data.supabase.PullPage
import com.example.musicplayerapp.data.supabase.ReactionSyncApi
import com.example.musicplayerapp.data.supabase.RemoteReaction
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

    /** `user_metadata.display_name` on the live session. Null models an account without one. */
    var accountName: String? = "Денис"

    /** The address on the live session. Null models one that has none to report. */
    var accountEmail: String? = "name@example.com"

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

    /** What the account card reads. Null session means null account, as in life. */
    override suspend fun currentAccount(): AccountInfo? =
        session?.let { AccountInfo(it, accountName, accountEmail) }

    /**
     * How many times the session was asked for.
     *
     * Zero is an assertion in its own right: a gate that must run *before* any session
     * lookup - `ProfileRoute` under a deletion marker, which must not reconcile - can
     * only be proved by nobody having asked.
     */
    var currentUidCalls: Int = 0

    override suspend fun currentUid(): String? {
        currentUidCalls++
        return session
    }

    override suspend fun signOutLocal(): Boolean {
        localSignOuts++
        session = null
        return true
    }

    // ------------------------------------------------ account deletion --
    //
    // G-A8b ships the boundary, not the orchestrator, so nothing in `src/main` calls
    // either of these yet. They are here so the interface is implementable and so a
    // test can assert the one property that matters today: **the gates do not depend
    // on them.** A deletion marker makes this install sync-dead whether or not either
    // call was ever made, which is why `deleteCalls` starts at zero and is expected to
    // stay there in every G-A8b test.

    /** What the next [deleteAccount] returns. */
    var deleteOutcome: DeleteAccountOutcome = DeleteAccountOutcome.AlreadyDeleted

    /** What the next [checkDeletionStatus] returns. */
    var statusOutcome: DeletionStatusOutcome = DeletionStatusOutcome.Unknown

    /** How many times each was called. Asserted to be zero by the gate tests. */
    var deleteCalls: Int = 0
    var statusCalls: Int = 0

    /** The arguments the last call carried, so a test can prove the token round-trips. */
    var lastDeleteRequestId: String? = null
    var lastStatusPair: Pair<String, String>? = null

    override suspend fun deleteAccount(requestId: String): DeleteAccountOutcome {
        deleteCalls++
        lastDeleteRequestId = requestId
        return deleteOutcome
    }

    override suspend fun checkDeletionStatus(
        requestId: String,
        deletedUid: String,
    ): DeletionStatusOutcome {
        statusCalls++
        lastStatusPair = requestId to deletedUid
        return statusOutcome
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

    /**
     * The atomic path, recorded exactly as the legacy one is.
     *
     * Every represented event lands in [events] attributed to [listenerId], so the
     * "no synthetic events as the destination" assertions keep their meaning after
     * the cutover: the two protocols write history through different calls, and a
     * fake that only watched one of them would stop being evidence.
     */
    override suspend fun applyBatch(
        trackKey: String,
        events: List<ReactionOutboxEntry>,
        current: TrackReaction,
        listenerId: String,
    ): BatchOutcome {
        for (event in events) this.events += event to listenerId
        val outcome = onReconcile(trackKey)
        if (outcome !is SyncOutcome.Success) return BatchOutcome.Failed(outcome)
        adoptedBy.getOrPut(listenerId) { linkedMapOf() }[trackKey] = current.reaction.name
        return BatchOutcome.Applied(current.asRemote(++rev))
    }

    private var rev = 0L

    val retirements = mutableListOf<String>()
    val adoptedBy = linkedMapOf<String, MutableMap<String, String>>()
    val events = mutableListOf<Pair<ReactionOutboxEntry, String>>()

    var onRetire: (String) -> SyncOutcome = { SyncOutcome.Success }
    var onReconcile: (String) -> SyncOutcome = { SyncOutcome.Success }

    /** Every event written as [listenerId]. Asserted empty for a destination. */
    fun eventsBy(listenerId: String) = events.filter { it.second == listenerId }


    /**
     * The account this fake will hand back, or null to refuse being read at all.
     *
     * Null is the default and the point: a suite that does not exercise pull must not
     * be able to reach a pull silently. An empty page would be a plausible-looking lie
     * - an accidental read would look like "the account has nothing" and pass - which
     * is exactly how a regression in the trigger wiring would hide.
     *
     * A suite that genuinely drives a pull sets this, and says so by setting it.
     */
    var pullPages: List<RemoteReaction>? = null

    /** Set to make the read fail, for the partial-scan cases. */
    var pullFailure: SyncOutcome? = null

    /** How many pages were asked for. Distinguishes "scanned again" from "was skipped". */
    var pullRequests = 0
        private set

    override suspend fun fetchReactionsPage(
        listenerId: String,
        afterRev: Long,
        limit: Int,
    ): PullPage {
        val account = pullPages
            ?: throw AssertionError("this suite must not pull; fetchReactionsPage was called")
        pullRequests++
        pullFailure?.let { return PullPage.Failed(it) }
        return PullPage.Rows(account.filter { it.rev > afterRev })
    }

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
        override suspend fun currentAccount(): AccountInfo? = null
        override suspend fun currentUid(): String? = null
        override suspend fun signOutLocal(): Boolean = true

        // Refused like every other remote call, and this pair matters more than the
        // rest: `delete_my_account` against the live project would destroy a real
        // `auth.users` row and everything it owns, irreversibly, from a test run that
        // merely forgot an opt-in. That is precisely the hole EmailAuthBackend exists
        // to close, which is why account deletion was put behind this seam rather than
        // given one of its own.
        override suspend fun deleteAccount(requestId: String): DeleteAccountOutcome =
            DeleteAccountOutcome.Failed(AuthFailure.NetworkFailure(WHY))

        override suspend fun checkDeletionStatus(
            requestId: String,
            deletedUid: String,
        ): DeletionStatusOutcome = DeletionStatusOutcome.Failed(AuthFailure.NetworkFailure(WHY))
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

        override suspend fun fetchReactionsPage(listenerId: String, afterRev: Long, limit: Int) =
            PullPage.Failed(SyncOutcome.AuthUnavailable(WHY))
        override suspend fun applyBatch(
            trackKey: String,
            events: List<ReactionOutboxEntry>,
            current: TrackReaction,
            listenerId: String,
        ) = BatchOutcome.Failed(SyncOutcome.AuthUnavailable(WHY))
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

/**
 * Taps the profile control and waits for the route to resolve.
 *
 * Opening a profile is asynchronous, and deliberately so: `ProfileRoute` proves a
 * matching session before it navigates, rather than entering the authenticated screen
 * and letting it discover it should not have. That check is local and quick, but it is
 * not the same main-thread frame as the tap - so a suite that taps and asserts in one
 * breath reads the destination it started on.
 *
 * This waits for *either* profile destination and asserts neither. Which one it landed
 * on is what the calling test is for, and one case ([ProfileAuthenticatedTest] B) exists
 * precisely to prove one of them is never entered at all.
 */
internal fun openProfileAndSettle(timeoutMs: Long = 15_000) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.runOnMainSync {
        resumedMainActivity().findViewById<android.view.View>(R.id.profile_entry).performClick()
    }

    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        var landed = false
        runCatching {
            instrumentation.runOnMainSync {
                landed = resumedMainActivity().profileDestination() != null
            }
        }
        if (landed) return
        Thread.sleep(25)
    }
    throw AssertionError("timed out after ${timeoutMs}ms opening the profile")
}

/**
 * The resumed activity, rather than `ActivityScenario.onActivity`.
 *
 * `onActivity` waits for an idle looper, and on API 24 a screen that is animating or
 * spinning may never give it one - see the harness note in `AuthFormTest`.
 */
internal fun resumedMainActivity(): MainActivity =
    ActivityLifecycleMonitorRegistry.getInstance()
        .getActivitiesInStage(Stage.RESUMED)
        .filterIsInstance<MainActivity>()
        .firstOrNull() ?: error("no resumed MainActivity")

/** Which profile is showing, or `null` if the route has not resolved yet. */
internal fun MainActivity.profileDestination(): Int? {
    val host = supportFragmentManager.findFragmentById(R.id.navHostFragment)
        as androidx.navigation.fragment.NavHostFragment
    return host.navController.currentDestination?.id
        ?.takeIf { it == R.id.profile || it == R.id.profile_authenticated }
}

/**
 * A local row as the server would hand it back, for fakes that answer the atomic RPC.
 *
 * The production function returns the `reactions` row it just wrote, so a fake that
 * returned nothing would let a settlement bug through: the drain only records a
 * revision, or adopts remote state, when a row comes back.
 */
internal fun TrackReaction.asRemote(rev: Long) = RemoteReaction(
    trackKey = trackKey,
    reaction = reaction,
    likedAt = if (reaction == Reaction.LIKED) (likedAt ?: updatedAt) else null,
    artist = artist,
    title = title,
    stream = stream,
    updatedAt = updatedAt,
    rev = rev,
)
