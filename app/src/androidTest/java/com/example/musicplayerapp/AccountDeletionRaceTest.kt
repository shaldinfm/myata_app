package com.example.musicplayerapp

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayerapp.data.AppDatabase
import com.example.musicplayerapp.data.supabase.DeleteAccountOutcome
import com.example.musicplayerapp.data.supabase.DeletionStage
import com.example.musicplayerapp.data.supabase.DeletionStatusOutcome
import com.example.musicplayerapp.data.supabase.EmailAuthBackend
import com.example.musicplayerapp.data.supabase.IdentityReconciler
import com.example.musicplayerapp.data.supabase.IdentityState
import com.example.musicplayerapp.data.supabase.IdentityStore
import com.example.musicplayerapp.data.supabase.LastSyncStore
import com.example.musicplayerapp.data.supabase.ReactionPullTrigger
import com.example.musicplayerapp.data.supabase.ReactionSyncBackend
import com.example.musicplayerapp.data.supabase.SyncLease
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What a deletion resolution is allowed to act on, when the world moved while it waited.
 *
 * `IdentityReconciler` decides whether to *look* at a deletion before it holds
 * [SyncLease], and waiting for that lease is a suspension of unbounded length - a drain
 * or another resolution may hold it across a network round trip. Everything the
 * resolution then *acts* on has to be re-read on the other side of that wait, because
 * a retry here is a destructive, irreversible request.
 *
 * Two facts can go stale in that window, and the consequences are different:
 *
 *  * **the marker.** Another resolution can complete the deletion, or a definitive
 *    refusal can retract it. Acting on the pre-lock copy re-sends a deletion for a
 *    request that is already settled;
 *  * **the session.** `auth.uid()` inside `delete_my_account` decides whose account
 *    dies. A uid captured before the lease can belong to an account this device has
 *    since signed out of - so retrying on it would ask the server to delete whoever is
 *    live now, rather than the account the listener named.
 *
 * These tests drive the interleaving deterministically rather than hoping for it.
 * `runBlocking` gives a single-threaded event loop, so a coroutine runs until its first
 * real suspension - which for the reconciler is the lease it cannot have. One `yield()`
 * is therefore enough to prove it has read the marker and parked.
 */
@RunWith(AndroidJUnit4::class)
class AccountDeletionRaceTest {

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

        IdentityStore.markRegistered(context, x)
        IdentityStore.markDeletionRequested(context, request, x)
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

    /**
     * **A. The marker is resolved while the reconciler waits for the lease.**
     *
     * The reconciler observes a deletion, parks on a lease somebody else holds, and by
     * the time it gets in the deletion has already been settled - by another
     * resolution, or by a refusal that retracted it. It must notice, and ask the server
     * nothing at all.
     *
     * Before the fix this re-sent `deleteAccount` for a request that no longer existed.
     */
    @Test
    fun a_a_marker_resolved_while_waiting_produces_no_server_call() = runBlocking {
        val leaseHeld = CompletableDeferred<Unit>()
        val releaseLease = CompletableDeferred<Unit>()

        // Somebody else owns the lease - a drain mid-round-trip, in production.
        val holder = launch {
            SyncLease.withExclusive {
                leaseHeld.complete(Unit)
                releaseLease.await()
            }
        }
        leaseHeld.await()

        // The reconciler starts, reads the marker, and parks on the lease.
        val reconciliation = async { IdentityReconciler.reconcile(context, sessionUid = x) }
        yield()

        // It is definitely past its pre-lock read and definitely inside the wait: the
        // event loop is single-threaded, so it ran until its first suspension, and the
        // only suspension between the read and here is the lease it cannot have.
        assertEquals("nothing may have been sent yet", 0, auth.deleteCalls)

        // Meanwhile the deletion is settled by somebody else and the marker goes.
        IdentityStore.clearDeletionMarker(context)

        releaseLease.complete(Unit)
        holder.join()
        val outcome = reconciliation.await()

        assertNotNull(outcome)
        assertEquals("a stale marker must not send a deletion", 0, auth.deleteCalls)
        assertEquals("nor consult a receipt", 0, auth.statusCalls)
        assertNull(IdentityStore.deletion(context))
    }

    /**
     * **B. The pre-lock session snapshot is not authority.**
     *
     * The caller hands in `sessionUid = X` - true when it was captured at startup - and
     * by the time the lease is held the device's live session is somebody else's. The
     * retry must not run: `auth.uid()` would be Y, and the server would delete Y's
     * account rather than X's.
     *
     * The receipt route is taken instead, and it asks about `(R, X)` - the account that
     * was actually named - not about whoever is signed in now.
     */
    @Test
    fun b_a_stale_session_snapshot_never_authorises_a_delete() = runBlocking {
        // The snapshot says X. The live session says Y.
        auth.session = y
        auth.statusOutcome = DeletionStatusOutcome.Unknown

        IdentityReconciler.reconcile(context, sessionUid = x)

        assertEquals("deleteAccount must never run on a stale snapshot", 0, auth.deleteCalls)
        assertEquals(1, auth.statusCalls)
        assertEquals("the receipt is asked about X", request to x, auth.lastStatusPair)

        // Y was not adopted and the marker still names X.
        val state = IdentityStore.state(context)
        assertTrue("$state", state is IdentityState.Registered)
        assertEquals(x, state.uid)
        assertEquals(x, IdentityStore.deletion(context)!!.deletedUid)
    }

    /** The same when the live session has gone entirely. */
    @Test
    fun c_a_stale_snapshot_with_no_live_session_uses_the_receipt() = runBlocking {
        auth.session = null
        auth.statusOutcome = DeletionStatusOutcome.Unknown

        IdentityReconciler.reconcile(context, sessionUid = x)

        assertEquals(0, auth.deleteCalls)
        assertEquals(1, auth.statusCalls)
        assertEquals(DeletionStage.REQUESTED, IdentityStore.deletion(context)!!.stage)
    }

    /**
     * **C. The live session is authority, even when the caller's hint is wrong.**
     *
     * The mirror of B, and it is what stops the fix from being "never retry": the
     * caller passes null - no session at startup - but the device does hold one for X
     * by the time the lease is granted. The retry runs, with the same token.
     */
    @Test
    fun d_the_live_session_authorises_the_retry_whatever_the_hint_said() = runBlocking {
        auth.session = x
        auth.deleteOutcome = DeleteAccountOutcome.AlreadyDeleted

        IdentityReconciler.reconcile(context, sessionUid = null)

        assertEquals(1, auth.deleteCalls)
        assertEquals("the token must be reused", request, auth.lastDeleteRequestId)
        assertEquals("the receipt was not needed", 0, auth.statusCalls)
        assertEquals(IdentityState.None, IdentityStore.state(context))
    }

    /** A hint naming somebody else does not stop a genuine retry either. */
    @Test
    fun e_a_wrong_hint_does_not_block_a_genuine_retry() = runBlocking {
        auth.session = x
        auth.deleteOutcome = DeleteAccountOutcome.AlreadyDeleted

        IdentityReconciler.reconcile(context, sessionUid = y)

        assertEquals(1, auth.deleteCalls)
        assertEquals(request, auth.lastDeleteRequestId)
    }

    /**
     * A CONFIRMED marker that is cleared while the reconciler waits asks nothing either.
     *
     * The same stale-marker window, on the stage where the answer is already known -
     * so the failure mode is a redundant cleanup rather than a redundant request, but
     * the check that prevents it is the same one.
     */
    @Test
    fun f_a_confirmed_marker_cleared_while_waiting_is_a_no_op() = runBlocking {
        IdentityStore.markDeletionConfirmed(context, request, x)

        val leaseHeld = CompletableDeferred<Unit>()
        val releaseLease = CompletableDeferred<Unit>()
        val holder = launch {
            SyncLease.withExclusive {
                leaseHeld.complete(Unit)
                releaseLease.await()
            }
        }
        leaseHeld.await()

        val reconciliation = async { IdentityReconciler.reconcile(context, sessionUid = null) }
        yield()

        // Another resolution finished the whole thing while this one waited.
        IdentityStore.forgetDeletedAccount(context)

        releaseLease.complete(Unit)
        holder.join()
        reconciliation.await()

        assertEquals(0, auth.deleteCalls)
        assertEquals(0, auth.statusCalls)
        assertEquals(IdentityState.None, IdentityStore.state(context))
    }
}
