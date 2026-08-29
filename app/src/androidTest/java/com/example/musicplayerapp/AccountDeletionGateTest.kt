package com.example.musicplayerapp

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayerapp.data.AppDatabase
import com.example.musicplayerapp.data.supabase.DeletionStage
import com.example.musicplayerapp.data.supabase.DrainResult
import com.example.musicplayerapp.data.supabase.EmailAuthBackend
import com.example.musicplayerapp.data.supabase.IdentityHandoff
import com.example.musicplayerapp.data.supabase.IdentityState
import com.example.musicplayerapp.data.supabase.IdentityStore
import com.example.musicplayerapp.data.supabase.LastSyncStore
import com.example.musicplayerapp.data.supabase.ListenerIdentity
import com.example.musicplayerapp.data.supabase.ListenerSession
import com.example.musicplayerapp.data.supabase.PullResult
import com.example.musicplayerapp.data.supabase.ReactionPull
import com.example.musicplayerapp.data.supabase.ReactionPullTrigger
import com.example.musicplayerapp.data.supabase.ReactionSyncBackend
import com.example.musicplayerapp.data.supabase.ReactionSyncEngine
import com.example.musicplayerapp.data.supabase.ReactionSyncScheduler
import com.example.musicplayerapp.ui.profile.ProfileRoute
import androidx.work.WorkManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The deletion marker, and everything it closes.
 *
 * G-A8b ships the client boundary and the gates - not the orchestrator, not the
 * cleanup, not a screen. So nothing in `src/main` writes the marker yet, and every
 * test here writes it directly. That is the point rather than a shortcut: the gates
 * have to hold for a marker that arrives from *anywhere*, including a process that
 * died half way through a deletion and left it on disk, and a test that could only
 * produce one by running the flow would be unable to express that case at all.
 *
 * ## The invariant the whole suite exists for
 *
 * **An install with an unresolved deletion is sync-dead, and above all it must never
 * mint.** `forgetDeletedAccount` returns the install to `IdentityState.None`, and
 * `None` is the one state `ListenerSession.identity` is allowed to sign in
 * anonymously from. Without the gate, a drain finding leftover outbox rows after a
 * deletion would create a brand-new listener and upload the deleted account's pending
 * reactions into it - the deleted data reappearing under a new uid, which is the
 * exact failure `docs/ACCOUNT-DELETION.md` is written to prevent.
 *
 * ## What is deliberately not asserted
 *
 * No test here calls `deleteAccount` or `checkDeletionStatus`. The gates must not
 * depend on either having been called - the marker alone closes them - so the fake's
 * call counters are asserted to stay at **zero** instead.
 */
@RunWith(AndroidJUnit4::class)
class AccountDeletionGateTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var db: AppDatabase
    private lateinit var auth: FakeEmailAuthApi
    private lateinit var sync: RecordingSyncApi

    private val x = "11111111-1111-4111-8111-111111111111"
    private val y = "22222222-2222-4222-8222-222222222222"
    private val request = "99999999-9999-4999-8999-999999999999"

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        AppDatabase.overrideForInstrumentation(db)

        auth = FakeEmailAuthApi().also { it.uid = x }
        EmailAuthBackend.overrideForInstrumentation { auth }

        sync = RecordingSyncApi().also { it.pullPages = emptyList() }
        ReactionSyncBackend.overrideForInstrumentation({ sync }, CountingIdentity(x).asProvider())

        IdentityStore.clearForTest(context)
        LastSyncStore.clearForTest(context)
        ReactionPullTrigger.resetForTest()
    }

    @After
    fun close() {
        ReactionPullTrigger.resetForTest()
        IdentityStore.clearForTest(context)
        LastSyncStore.clearForTest(context)
        AppDatabase.overrideForInstrumentation(null)
        TestIsolation.restoreBackends()
        if (::db.isInitialized) db.close()
    }

    // ==================== the durable marker ====================

    /** REQUESTED round-trips whole: stage, token and uid. */
    @Test
    fun a_requested_round_trips() {
        IdentityStore.markDeletionRequested(context, request, x)

        val record = IdentityStore.deletion(context)
        assertNotNull(record)
        assertEquals(DeletionStage.REQUESTED, record!!.stage)
        assertEquals(request, record.requestId)
        assertEquals(x, record.deletedUid)
        assertTrue(IdentityStore.deletionInFlight(context))
    }

    /** CONFIRMED replaces the stage and keeps the pair. */
    @Test
    fun b_confirmed_replaces_the_stage_and_keeps_the_pair() {
        IdentityStore.markDeletionRequested(context, request, x)
        IdentityStore.markDeletionConfirmed(context, request, x)

        val record = IdentityStore.deletion(context)!!
        assertEquals(DeletionStage.CONFIRMED, record.stage)
        assertEquals(request, record.requestId)
        assertEquals(x, record.deletedUid)
    }

    /**
     * The marker survives a re-read of the store.
     *
     * `IdentityStore` is an object over `SharedPreferences`, so re-reading it after a
     * write is the only thing a test can do to model a restart - and it is the thing
     * that matters, because every write here is `commit()` precisely so a process
     * death cannot lose it. A cached value would pass a naive assertion and fail on a
     * real device.
     */
    @Test
    fun c_the_marker_survives_a_re_read() {
        IdentityStore.markDeletionRequested(context, request, x)

        // A different Context object onto the same preferences file, which is as close
        // to "a new process read this" as an in-process test can get.
        val fresh = context.applicationContext
        val record = IdentityStore.deletion(fresh)

        assertNotNull(record)
        assertEquals(DeletionStage.REQUESTED, record!!.stage)
        assertEquals(request, record.requestId)
        assertTrue(IdentityStore.deletionInFlight(fresh))
    }

    /** No marker, no gate. The default state of every install. */
    @Test
    fun d_no_marker_means_not_in_flight() {
        assertNull(IdentityStore.deletion(context))
        assertFalse(IdentityStore.deletionInFlight(context))
    }

    /** Clearing abandons the deletion and leaves the identity exactly as it was. */
    @Test
    fun e_clearing_the_marker_leaves_the_identity_alone() {
        IdentityStore.markRegistered(context, x)
        IdentityStore.markDeletionRequested(context, request, x)

        IdentityStore.clearDeletionMarker(context)

        assertFalse(IdentityStore.deletionInFlight(context))
        val state = IdentityStore.state(context)
        assertTrue("$state", state is IdentityState.Registered)
        assertEquals(x, state.uid)
    }

    /**
     * `forgetDeletedAccount` returns the install to None and takes the marker with it.
     *
     * The one route back to `None` in the whole store, and the assertion below is why
     * it is allowed to exist: after a confirmed deletion there is no identity left to
     * split, so the rule that forbids the transition has nothing to protect.
     */
    @Test
    fun f_forgetting_a_deleted_account_returns_to_none() {
        IdentityStore.markRegistered(context, x)
        IdentityStore.markDeletionConfirmed(context, request, x)

        IdentityStore.forgetDeletedAccount(context)

        assertEquals(IdentityState.None, IdentityStore.state(context))
        assertNull(IdentityStore.state(context).uid)
        assertNull(IdentityStore.deletion(context))
        assertFalse(IdentityStore.deletionInFlight(context))
        // The legacy marker goes too, or a downgraded build would still refuse to mint
        // for an identity that no longer exists.
        assertFalse(IdentityStore.isSignedOut(context))
    }

    // ==================== the mint gate ====================

    /**
     * **The one that matters.** A marker stops the only function that can create a uid.
     *
     * Run from `None`, which is exactly the state `forgetDeletedAccount` leaves behind
     * and the only one a mint is permitted from. Without the gate this signs in
     * anonymously and the install becomes a new listener.
     */
    @Test
    fun g_requested_prevents_an_anonymous_mint() = runBlocking {
        IdentityStore.markDeletionRequested(context, request, x)

        val identity = ListenerSession.identity(context)

        assertTrue("$identity", identity is ListenerIdentity.Unavailable)
        assertEquals(IdentityState.None, IdentityStore.state(context))
        assertNull(ListenerSession.knownUid(context))
    }

    /** CONFIRMED closes it too: cleanup owed is still sync-dead. */
    @Test
    fun h_confirmed_prevents_an_anonymous_mint() = runBlocking {
        IdentityStore.markDeletionConfirmed(context, request, x)

        val identity = ListenerSession.identity(context)

        assertTrue("$identity", identity is ListenerIdentity.Unavailable)
        assertEquals(IdentityState.None, IdentityStore.state(context))
    }

    // ==================== the sync gates ====================

    /** A direct drain refuses before it reads a single row. */
    @Test
    fun i_the_engine_refuses_to_drain() = runBlocking {
        val track = "a".repeat(64)
        db.reactionDao().like(track, "Artist", "Title", "myata", likedAt = 1_000L)
        assertEquals(1, db.reactionOutboxDao().count())

        val result = ReactionSyncEngine(
            reactions = db.reactionDao(),
            outbox = db.reactionOutboxDao(),
            api = sync,
            identity = { ListenerIdentity.Available(x) },
            deletionInFlight = { true },
        ).drain()

        assertTrue("$result", result is DrainResult.DeletionInProgress)
        // Untouched: the row is still owed and nothing was sent.
        assertEquals(1, db.reactionOutboxDao().count())
        assertTrue("no event should have been sent", sync.events.isEmpty())
    }

    /** The scheduler enqueues nothing while a deletion is unresolved. */
    @Test
    fun j_the_scheduler_enqueues_nothing() {
        IdentityStore.markRegistered(context, x)
        IdentityStore.markDeletionRequested(context, request, x)

        ReactionSyncScheduler.onReactionCommitted(context)

        val work = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(ReactionSyncScheduler.UNIQUE_WORK)
            .get()
        assertTrue("$work", work.none { !it.state.isFinished })
    }

    /** The pull is not eligible, and says why. */
    @Test
    fun k_the_pull_refuses() = runBlocking {
        IdentityStore.markRegistered(context, x)
        auth.session = x
        IdentityStore.markDeletionRequested(context, request, x)

        val result = ReactionPull.run(context)

        assertTrue("$result", result is PullResult.NotEligible)
        assertEquals(0, sync.pullRequests)
    }

    /** The trigger does not even claim its debounce window. */
    @Test
    fun l_the_pull_trigger_refuses() = runBlocking {
        IdentityStore.markRegistered(context, x)
        auth.session = x
        IdentityStore.markDeletionRequested(context, request, x)

        val result = ReactionPullTrigger.request(context, "test")

        assertNull("$result", result)
        assertEquals(0, sync.pullRequests)
    }

    /** A handoff aborts without writing anything. */
    @Test
    fun m_the_handoff_aborts() = runBlocking {
        IdentityStore.adoptAnonymous(context, x)
        IdentityStore.markDeletionRequested(context, request, x)

        val result = IdentityHandoff.run(
            context = context,
            from = x,
            reactions = db.reactionDao(),
            outbox = db.reactionOutboxDao(),
            api = sync,
            drain = { error("a handoff must not drain while a deletion is unresolved") },
            destination = { error("a handoff must not authenticate while a deletion is unresolved") },
        )

        assertTrue("$result", result is IdentityHandoff.Result.Aborted)
        // Nothing remote was retired and no handoff record was written.
        assertTrue("nothing retired", sync.retirements.isEmpty())
        assertFalse(IdentityStore.handoffInProgress(context))
    }

    /** The profile routes to guest, without reconciling on the way. */
    @Test
    fun n_profile_routes_to_guest_without_reconciling() = runBlocking {
        IdentityStore.markRegistered(context, x)
        auth.session = x
        IdentityStore.markDeletionRequested(context, request, x)

        val destination = ProfileRoute.destination(context)

        assertEquals(R.id.profile, destination)
        // The gate is before the session read, so nothing asked the auth boundary
        // anything - which is what stops reconciliation running underneath a deletion.
        assertEquals(0, auth.currentUidCalls)
    }

    // ==================== local writes still work ====================

    /**
     * A tap still lands locally, and still cannot leave.
     *
     * `ReactionWriteGate` is deliberately untouched by this phase: the Collection is
     * a local feature and blocking it would need a screen that does not exist. What
     * makes that safe is that every path *out* is closed, so the row simply waits -
     * and is the outbox row the eventual cleanup will discard.
     */
    @Test
    fun o_a_local_reaction_still_commits_but_cannot_drain() = runBlocking {
        IdentityStore.markRegistered(context, x)
        IdentityStore.markDeletionRequested(context, request, x)

        val track = "b".repeat(64)
        db.reactionDao().like(track, "Artist", "Title", "myata", likedAt = 2_000L)

        // The local write happened.
        assertNotNull(db.reactionDao().find(track))
        assertEquals(1, db.reactionOutboxDao().count())

        // And it goes nowhere.
        val result = ReactionSyncEngine(
            reactions = db.reactionDao(),
            outbox = db.reactionOutboxDao(),
            api = sync,
            identity = { ListenerIdentity.Available(x) },
            deletionInFlight = { IdentityStore.deletionInFlight(context) },
        ).drain()

        assertTrue("$result", result is DrainResult.DeletionInProgress)
        assertEquals(1, db.reactionOutboxDao().count())
        assertTrue("no event should have been sent", sync.events.isEmpty())
    }

    // ==================== the boundary is not what gates ====================

    /**
     * No gate called either deletion method.
     *
     * The marker is what closes the doors, not a round trip. If this ever fails, a
     * gate has started depending on the network - which would mean an offline device
     * mid-deletion could drain.
     */
    @Test
    fun p_the_gates_never_call_the_deletion_api() = runBlocking {
        IdentityStore.markRegistered(context, x)
        auth.session = x
        IdentityStore.markDeletionRequested(context, request, x)

        ReactionPull.run(context)
        ReactionPullTrigger.request(context, "test")
        ProfileRoute.destination(context)
        ListenerSession.identity(context)

        assertEquals(0, auth.deleteCalls)
        assertEquals(0, auth.statusCalls)
    }

    // ==================== per-uid sync facts ====================

    /** `forget` removes one account's three facts and leaves the other's alone. */
    @Test
    fun q_forget_removes_only_that_uids_facts() {
        LastSyncStore.recordUploadSuccess(context, x, 1_000L)
        LastSyncStore.recordPullSuccess(context, x, 2_000L)
        LastSyncStore.markInitialRestoreComplete(context, x)

        LastSyncStore.recordUploadSuccess(context, y, 3_000L)
        LastSyncStore.recordPullSuccess(context, y, 4_000L)
        LastSyncStore.markInitialRestoreComplete(context, y)

        LastSyncStore.forget(context, x)

        assertNull(LastSyncStore.lastUploadAt(context, x))
        assertNull(LastSyncStore.lastPullAt(context, x))
        assertNull(LastSyncStore.lastSyncAt(context, x))
        assertFalse(LastSyncStore.isInitialRestoreComplete(context, x))

        assertEquals(3_000L, LastSyncStore.lastUploadAt(context, y))
        assertEquals(4_000L, LastSyncStore.lastPullAt(context, y))
        assertEquals(4_000L, LastSyncStore.lastSyncAt(context, y))
        assertTrue(LastSyncStore.isInitialRestoreComplete(context, y))
    }

    /** Forgetting an account that has no facts is a no-op, so cleanup can be re-run. */
    @Test
    fun r_forget_is_idempotent() {
        LastSyncStore.recordUploadSuccess(context, x, 1_000L)

        LastSyncStore.forget(context, x)
        LastSyncStore.forget(context, x)

        assertNull(LastSyncStore.lastUploadAt(context, x))
    }
}
