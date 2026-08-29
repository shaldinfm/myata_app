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
 * Resolving a deletion the first attempt did not finish.
 *
 * This is the half the receipt exists for. Deleting `auth.users` invalidates the
 * refresh credentials at once, so a device whose response was lost may never be able
 * to authenticate again - and absence of a session is not evidence that the deletion
 * happened. Every test here starts from a marker on disk, which is exactly what a
 * process death leaves behind.
 *
 * Two rules are load-bearing throughout:
 *
 *  * **`CONFIRMED` never asks the server again.** The answer is already known; asking
 *    could only reintroduce doubt.
 *  * **A session for Y is not a session for X.** `auth.uid()` decides whose account
 *    dies, so retrying `deleteAccount` while signed in as somebody else would ask the
 *    server to delete the wrong account. Those cases take the session-less receipt
 *    route instead, and Y is never adopted.
 */
@RunWith(AndroidJUnit4::class)
class AccountDeletionRecoveryTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var db: AppDatabase
    private lateinit var auth: FakeEmailAuthApi
    private lateinit var sync: RecordingSyncApi

    private val x = "11111111-1111-4111-8111-111111111111"
    private val y = "22222222-2222-4222-8222-222222222222"
    private val request = "99999999-9999-4999-8999-999999999999"
    private val track = "b".repeat(64)

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

    /** An install that asked to be deleted and never learned the answer. */
    private fun interruptedAt(stage: DeletionStage) = runBlocking {
        IdentityStore.markRegistered(context, x)
        db.reactionDao().like(track, "Artist", "Title", "myata", likedAt = 1_000L)
        LastSyncStore.recordUploadSuccess(context, x, 1_000L)
        when (stage) {
            DeletionStage.REQUESTED -> IdentityStore.markDeletionRequested(context, request, x)
            DeletionStage.CONFIRMED -> IdentityStore.markDeletionConfirmed(context, request, x)
        }
    }

    private fun assertCleanedUp() = runBlocking {
        assertEquals(IdentityState.None, IdentityStore.state(context))
        assertNull(IdentityStore.deletion(context))
        assertEquals(0, db.reactionDao().allReactions().size)
        assertEquals(0, db.reactionOutboxDao().count())
        assertNull(LastSyncStore.lastUploadAt(context, x))
        assertFalse(IdentityStore.deletionInFlight(context))
    }

    // ==================== CONFIRMED: local only ====================

    /** A CONFIRMED marker finishes locally and never touches the network. */
    @Test
    fun a_confirmed_restart_is_local_only() = runBlocking {
        interruptedAt(DeletionStage.CONFIRMED)
        auth.session = null

        IdentityReconciler.reconcile(context, sessionUid = null)

        assertCleanedUp()
        assertEquals("CONFIRMED must never call the server", 0, auth.deleteCalls)
        assertEquals(0, auth.statusCalls)
    }

    /** Even with a live session for X, CONFIRMED still asks nothing. */
    @Test
    fun b_confirmed_asks_nothing_even_with_a_session() = runBlocking {
        interruptedAt(DeletionStage.CONFIRMED)
        auth.session = x

        IdentityReconciler.reconcile(context, sessionUid = x)

        assertCleanedUp()
        assertEquals(0, auth.deleteCalls)
        assertEquals(0, auth.statusCalls)
    }

    // ==================== REQUESTED with a usable session ====================

    /** A restored session for X retries the delete with the **same** token. */
    @Test
    fun c_requested_with_session_x_retries_the_same_request_id() = runBlocking {
        interruptedAt(DeletionStage.REQUESTED)
        auth.session = x
        auth.deleteOutcome = DeleteAccountOutcome.AlreadyDeleted

        IdentityReconciler.reconcile(context, sessionUid = x)

        assertEquals(1, auth.deleteCalls)
        assertEquals("the token must be reused, never re-minted", request, auth.lastDeleteRequestId)
        assertEquals("the receipt route is not needed here", 0, auth.statusCalls)
        assertCleanedUp()
    }

    /** A retry that is still inconclusive leaves everything exactly as it was. */
    @Test
    fun d_an_inconclusive_retry_leaves_requested() = runBlocking {
        interruptedAt(DeletionStage.REQUESTED)
        auth.session = x
        auth.deleteOutcome = DeleteAccountOutcome.Failed(
            com.example.musicplayerapp.data.supabase.AuthFailure.NetworkFailure("still offline")
        )

        IdentityReconciler.reconcile(context, sessionUid = x)

        val record = IdentityStore.deletion(context)
        assertNotNull(record)
        assertEquals(DeletionStage.REQUESTED, record!!.stage)
        assertEquals(request, record.requestId)
        assertTrue(IdentityStore.deletionInFlight(context))
        assertEquals(1, db.reactionDao().allReactions().size)
    }

    // ==================== REQUESTED without a usable session ====================

    /** No session at all, and the receipt says the deletion completed. */
    @Test
    fun e_no_session_and_a_receipt_completes_the_deletion() = runBlocking {
        interruptedAt(DeletionStage.REQUESTED)
        auth.session = null
        auth.statusOutcome = DeletionStatusOutcome.Completed

        IdentityReconciler.reconcile(context, sessionUid = null)

        assertEquals("the delete must not be retried without a session", 0, auth.deleteCalls)
        assertEquals(1, auth.statusCalls)
        assertEquals(request to x, auth.lastStatusPair)
        assertCleanedUp()
    }

    /** No receipt is not evidence. The marker stands and the install stays sync-dead. */
    @Test
    fun f_unknown_leaves_the_device_sync_dead() = runBlocking {
        interruptedAt(DeletionStage.REQUESTED)
        auth.session = null
        auth.statusOutcome = DeletionStatusOutcome.Unknown

        IdentityReconciler.reconcile(context, sessionUid = null)

        val record = IdentityStore.deletion(context)
        assertNotNull(record)
        assertEquals(DeletionStage.REQUESTED, record!!.stage)
        assertTrue(IdentityStore.deletionInFlight(context))
        assertEquals(0, auth.deleteCalls)
        // Nothing local was touched.
        assertEquals(1, db.reactionDao().allReactions().size)
        assertEquals(1_000L, LastSyncStore.lastUploadAt(context, x))
    }

    /** A receipt that could not be reached is equally not evidence. */
    @Test
    fun g_a_failed_status_call_leaves_the_marker() = runBlocking {
        interruptedAt(DeletionStage.REQUESTED)
        auth.session = null
        auth.statusOutcome = DeletionStatusOutcome.Failed(
            com.example.musicplayerapp.data.supabase.AuthFailure.NetworkFailure("offline")
        )

        IdentityReconciler.reconcile(context, sessionUid = null)

        assertEquals(DeletionStage.REQUESTED, IdentityStore.deletion(context)!!.stage)
        assertEquals(0, auth.deleteCalls)
    }

    // ==================== a session for somebody else ====================

    /**
     * A live session for Y takes the receipt route, and Y is never adopted.
     *
     * The failure being guarded is severe: retrying `deleteAccount` here would run as
     * Y, and `auth.uid()` inside the function decides whose account dies - so the
     * device would ask the server to delete an account it was never asked to touch.
     */
    @Test
    fun h_a_session_for_y_uses_the_receipt_and_never_deletes_as_y() = runBlocking {
        interruptedAt(DeletionStage.REQUESTED)
        auth.session = y
        auth.statusOutcome = DeletionStatusOutcome.Unknown

        IdentityReconciler.reconcile(context, sessionUid = y)

        assertEquals("deleteAccount must never run as Y", 0, auth.deleteCalls)
        assertEquals(1, auth.statusCalls)
        assertEquals("the receipt is asked about X, not Y", request to x, auth.lastStatusPair)

        // Y was not adopted and the marker still names X.
        val state = IdentityStore.state(context)
        assertTrue("$state", state is IdentityState.Registered)
        assertEquals(x, state.uid)
        assertEquals(x, IdentityStore.deletion(context)!!.deletedUid)
    }

    /** The same, when the receipt does answer: it still resolves X's deletion. */
    @Test
    fun i_a_session_for_y_can_still_complete_x_via_the_receipt() = runBlocking {
        interruptedAt(DeletionStage.REQUESTED)
        auth.session = y
        auth.statusOutcome = DeletionStatusOutcome.Completed

        IdentityReconciler.reconcile(context, sessionUid = y)

        assertEquals(0, auth.deleteCalls)
        assertNull(IdentityStore.deletion(context))
        assertEquals(IdentityState.None, IdentityStore.state(context))
    }

    // ==================== crash points inside cleanup ====================

    /**
     * A death after the rows went but before the identity was cleared.
     *
     * Modelled by leaving CONFIRMED on disk with the Room tables already empty, which
     * is exactly what such a death leaves. The repair is to run the same sequence
     * again - there is deliberately no progress marker inside the cleanup.
     */
    @Test
    fun j_a_death_between_cleanup_steps_is_repaired_by_rerunning() = runBlocking {
        interruptedAt(DeletionStage.CONFIRMED)
        db.reactionOutboxDao().clearAll()
        db.reactionDao().clearAll()

        IdentityReconciler.reconcile(context, sessionUid = null)

        assertCleanedUp()
        assertEquals(0, auth.deleteCalls)
        assertEquals(0, auth.statusCalls)
    }

    /** A death after the sign-out but before the identity was cleared. */
    @Test
    fun k_a_death_after_sign_out_is_repaired() = runBlocking {
        interruptedAt(DeletionStage.CONFIRMED)
        db.reactionOutboxDao().clearAll()
        db.reactionDao().clearAll()
        auth.session = null

        IdentityReconciler.reconcile(context, sessionUid = null)

        assertCleanedUp()
    }

    /** Resolution runs before handoff recovery, and a stale handoff does not divert it. */
    @Test
    fun l_deletion_resolution_precedes_handoff_recovery() = runBlocking {
        interruptedAt(DeletionStage.CONFIRMED)
        // A handoff record left over from an earlier interrupted switch.
        IdentityStore.markHandoffPrepared(context, x)

        IdentityReconciler.reconcile(context, sessionUid = null)

        // The deletion won: identity is gone, and the handoff record went with it in
        // forgetDeletedAccount's single commit.
        assertEquals(IdentityState.None, IdentityStore.state(context))
        assertNull(IdentityStore.deletion(context))
        assertFalse(IdentityStore.handoffInProgress(context))
        assertTrue("no retirement may have run", sync.retirements.isEmpty())
    }
}
