package com.example.musicplayerapp

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayerapp.data.AppDatabase
import com.example.musicplayerapp.data.supabase.AccountDeletion
import com.example.musicplayerapp.data.supabase.AccountDeletionResult
import com.example.musicplayerapp.data.supabase.DeleteAccountOutcome
import com.example.musicplayerapp.data.supabase.DeletionStage
import com.example.musicplayerapp.data.supabase.DeletionStatusOutcome
import com.example.musicplayerapp.data.supabase.DrainResult
import com.example.musicplayerapp.data.supabase.EmailAuthBackend
import com.example.musicplayerapp.data.supabase.IdentityReconciler
import com.example.musicplayerapp.data.supabase.IdentityState
import com.example.musicplayerapp.data.supabase.IdentityStore
import com.example.musicplayerapp.data.supabase.LastSyncStore
import com.example.musicplayerapp.data.supabase.ListenerIdentity
import com.example.musicplayerapp.data.supabase.ReactionPullTrigger
import com.example.musicplayerapp.data.supabase.ReactionSyncBackend
import com.example.musicplayerapp.data.supabase.ReactionSyncEngine
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
 * The deletion flow end to end, without a server.
 *
 * G-A8b proved the gates hold for a marker that arrives from anywhere. This proves the
 * thing that *writes* the marker, and - more importantly - the two rules that decide
 * whether it may ever be taken back:
 *
 *  * a **definitive refusal** is the only outcome that retracts a request, and it is
 *    granted only on a SQLSTATE `delete_my_account` raises itself. Getting that line
 *    wrong in the permissive direction is unrecoverable: clearing the marker for a
 *    deletion that did commit leaves an install believing it owns an account that no
 *    longer exists, with no way left to find out;
 *  * a **completed** result is returned only once `forgetDeletedAccount` has actually
 *    run. A confirmed server deletion whose local cleanup could not finish is not a
 *    finished deletion, and must not be reported as one.
 *
 * Every server answer here comes from the fake behind `EmailAuthBackend`. No test in
 * this file calls a real project, and `deleteCalls` / `statusCalls` are asserted
 * wherever "did it ask the server at all" is the property under test.
 */
@RunWith(AndroidJUnit4::class)
class AccountDeletionFlowTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var db: AppDatabase
    private lateinit var auth: FakeEmailAuthApi
    private lateinit var sync: RecordingSyncApi

    private val x = "11111111-1111-4111-8111-111111111111"
    private val y = "22222222-2222-4222-8222-222222222222"
    private val track = "a".repeat(64)

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

    /** A registered install with a live session, a Collection and per-account facts. */
    private fun anAccountWithData() = runBlocking {
        IdentityStore.markRegistered(context, x)
        auth.session = x
        db.reactionDao().like(track, "Artist", "Title", "myata", likedAt = 1_000L)
        LastSyncStore.recordUploadSuccess(context, x, 1_000L)
        LastSyncStore.markInitialRestoreComplete(context, x)
        // A second account's facts, which must survive.
        LastSyncStore.recordUploadSuccess(context, y, 2_000L)
    }

    private fun assertFullyCleanedUp() = runBlocking {
        assertEquals(IdentityState.None, IdentityStore.state(context))
        assertNull(IdentityStore.deletion(context))
        assertEquals(0, db.reactionDao().allReactions().size)
        assertEquals(0, db.reactionOutboxDao().count())
        assertNull(LastSyncStore.lastUploadAt(context, x))
        assertFalse(LastSyncStore.isInitialRestoreComplete(context, x))
        assertNull(auth.session)
        // Another account's history is untouched.
        assertEquals(2_000L, LastSyncStore.lastUploadAt(context, y))
    }

    // ==================== success ====================

    /** DELETED runs the whole cleanup and reports a completed deletion. */
    @Test
    fun a_deleted_cleans_up_completely() = runBlocking {
        anAccountWithData()
        auth.deleteOutcome = DeleteAccountOutcome.Deleted(1, 1, 1)

        val result = AccountDeletion.request(context)

        assertEquals(AccountDeletionResult.Deleted, result)
        assertFullyCleanedUp()
    }

    /** ALREADY_DELETED is not a lesser success - the outcome is identical. */
    @Test
    fun b_already_deleted_is_treated_exactly_as_deleted() = runBlocking {
        anAccountWithData()
        auth.deleteOutcome = DeleteAccountOutcome.AlreadyDeleted

        val result = AccountDeletion.request(context)

        assertEquals(AccountDeletionResult.Deleted, result)
        assertFullyCleanedUp()
    }

    /** The token is minted once and is what the server was asked with. */
    @Test
    fun c_the_request_carries_a_minted_token() = runBlocking {
        anAccountWithData()
        auth.deleteOutcome = DeleteAccountOutcome.Deleted(0, 0, 0)

        AccountDeletion.request(context)

        assertNotNull(auth.lastDeleteRequestId)
        assertEquals(1, auth.deleteCalls)
    }

    // ==================== definitive refusals ====================

    /**
     * Each SQLSTATE the function raises retracts the request and leaves everything.
     *
     * A `RAISE` aborts the transaction, so none of these can have deleted anything -
     * which is what makes clearing the marker safe here and nowhere else.
     */
    @Test
    fun d_definitive_sqlstates_retract_the_request() = runBlocking {
        for (code in listOf("28000", "42501", "22023", "XX000")) {
            IdentityStore.clearForTest(context)
            LastSyncStore.clearForTest(context)
            db.reactionDao().clearAll()
            db.reactionOutboxDao().clearAll()
            anAccountWithData()
            auth.deleteOutcome = DeleteAccountOutcome.Refused(code)

            val result = AccountDeletion.request(context)

            assertEquals("code $code", AccountDeletionResult.Refused(code), result)
            assertNull("code $code: marker must be cleared", IdentityStore.deletion(context))
            assertFalse("code $code", IdentityStore.deletionInFlight(context))

            val state = IdentityStore.state(context)
            assertTrue("code $code: $state", state is IdentityState.Registered)
            assertEquals("code $code", x, state.uid)

            // Local data untouched.
            assertEquals("code $code", 1, db.reactionDao().allReactions().size)
            assertEquals("code $code", 1_000L, LastSyncStore.lastUploadAt(context, x))
        }
    }

    // ==================== inconclusive ====================

    /**
     * Every other failure family leaves REQUESTED standing and the install sync-dead.
     *
     * The important half is what is *not* asserted anywhere: none of these clears the
     * marker. An inconclusive answer may be a deletion that committed and lost its
     * response, and treating it as a failure would strand the account.
     */
    @Test
    fun e_inconclusive_failures_leave_requested() = runBlocking {
        val families = listOf(
            "network" to com.example.musicplayerapp.data.supabase.AuthFailure.NetworkFailure("IOException"),
            "gateway" to com.example.musicplayerapp.data.supabase.AuthFailure.Unknown(detail = "502 bad gateway"),
            "unauthorised" to com.example.musicplayerapp.data.supabase.AuthFailure.Unknown(detail = "401 PGRST301"),
            "parse" to com.example.musicplayerapp.data.supabase.AuthFailure.Unknown(detail = "unrecognised outcome"),
            "unknown sqlstate" to com.example.musicplayerapp.data.supabase.AuthFailure.Unknown(detail = "23514"),
        )

        for ((name, failure) in families) {
            IdentityStore.clearForTest(context)
            LastSyncStore.clearForTest(context)
            db.reactionDao().clearAll()
            db.reactionOutboxDao().clearAll()
            anAccountWithData()
            auth.deleteOutcome = DeleteAccountOutcome.Failed(failure)

            val result = AccountDeletion.request(context)

            assertTrue("$name: $result", result is AccountDeletionResult.Unresolved)

            val record = IdentityStore.deletion(context)
            assertNotNull("$name: the marker must stand", record)
            assertEquals("$name", DeletionStage.REQUESTED, record!!.stage)
            assertTrue("$name", IdentityStore.deletionInFlight(context))

            // Nothing local was touched.
            assertEquals("$name", 1, db.reactionDao().allReactions().size)
            assertEquals("$name", x, IdentityStore.state(context).uid)
            assertEquals("$name", x, auth.session)
        }
    }

    // ==================== preconditions ====================

    /** A precondition refusal writes no marker at all, so nothing is owed later. */
    @Test
    fun f_preconditions_write_no_marker() = runBlocking {
        // Not an account.
        IdentityStore.adoptAnonymous(context, x)
        auth.session = x
        assertTrue(AccountDeletion.request(context) is AccountDeletionResult.NotEligible)
        assertNull(IdentityStore.deletion(context))
        assertEquals(0, auth.deleteCalls)

        // An account, but the live session belongs to somebody else.
        IdentityStore.clearForTest(context)
        IdentityStore.markRegistered(context, x)
        auth.session = y
        assertTrue(AccountDeletion.request(context) is AccountDeletionResult.NotEligible)
        assertNull(IdentityStore.deletion(context))

        // An account with no session at all.
        auth.session = null
        assertTrue(AccountDeletion.request(context) is AccountDeletionResult.NotEligible)
        assertNull(IdentityStore.deletion(context))

        // An unresolved handoff.
        auth.session = x
        IdentityStore.markHandoffPrepared(context, x)
        assertTrue(AccountDeletion.request(context) is AccountDeletionResult.NotEligible)
        assertNull(IdentityStore.deletion(context))
        IdentityStore.clearHandoff(context)

        // A deletion already in flight.
        IdentityStore.markDeletionRequested(context, "R", x)
        assertTrue(AccountDeletion.request(context) is AccountDeletionResult.NotEligible)
        assertEquals("R", IdentityStore.deletion(context)!!.requestId)

        assertEquals("no precondition path may call the server", 0, auth.deleteCalls)
    }

    // ==================== cleanup that cannot finish ====================

    /**
     * A failed local sign-out stops the cleanup and keeps CONFIRMED.
     *
     * Carrying on would write `None` over an install that can still present a token,
     * and take the marker saying cleanup is owed away with it - so nothing would ever
     * come back to finish. The result is deliberately not a completed deletion.
     */
    @Test
    fun g_failed_sign_out_keeps_confirmed_and_writes_no_none() = runBlocking {
        anAccountWithData()
        auth.deleteOutcome = DeleteAccountOutcome.Deleted(1, 1, 1)
        auth.signOutSucceeds = false

        val result = AccountDeletion.request(context)

        assertTrue("$result", result is AccountDeletionResult.CleanupDeferred)

        val record = IdentityStore.deletion(context)
        assertNotNull(record)
        assertEquals(DeletionStage.CONFIRMED, record!!.stage)
        // None must NOT have been written.
        assertTrue(IdentityStore.state(context) is IdentityState.Registered)
        // The rows before the sign-out did go - that half is idempotent and already done.
        assertEquals(0, db.reactionDao().allReactions().size)
        // The per-account facts must NOT have been removed yet.
        assertEquals(1_000L, LastSyncStore.lastUploadAt(context, x))
    }

    /** And the next attempt converges once the sign-out works. */
    @Test
    fun h_retry_after_a_failed_sign_out_converges() = runBlocking {
        anAccountWithData()
        auth.deleteOutcome = DeleteAccountOutcome.Deleted(1, 1, 1)
        auth.signOutSucceeds = false
        AccountDeletion.request(context)
        assertEquals(DeletionStage.CONFIRMED, IdentityStore.deletion(context)!!.stage)

        // A later start, with the session now clearable.
        auth.signOutSucceeds = true
        val outcome = IdentityReconciler.reconcile(context, sessionUid = null)

        assertNotNull(outcome)
        assertFullyCleanedUp()
        // The server was asked exactly once, by the original request.
        assertEquals(1, auth.deleteCalls)
        assertEquals(0, auth.statusCalls)
    }

    /** Cleanup runs twice with the same result - the process-death repair. */
    @Test
    fun i_cleanup_is_idempotent() = runBlocking {
        anAccountWithData()
        auth.deleteOutcome = DeleteAccountOutcome.AlreadyDeleted

        assertEquals(AccountDeletionResult.Deleted, AccountDeletion.request(context))
        assertFullyCleanedUp()

        // Re-running the whole resolution finds nothing owed and changes nothing.
        IdentityReconciler.reconcile(context, sessionUid = null)
        assertFullyCleanedUp()
    }

    // ==================== the outbox never escapes ====================

    /**
     * Pending events cannot drain once REQUESTED is on disk, and are gone afterwards.
     *
     * The failure this is guarding: rows carry no `listener_id`, so a later anonymous
     * identity would happily adopt them and upload the deleted account's reactions
     * under a new uid.
     */
    @Test
    fun j_pending_events_never_drain_after_requested() = runBlocking {
        anAccountWithData()
        assertEquals(1, db.reactionOutboxDao().count())
        auth.deleteOutcome = DeleteAccountOutcome.Failed(
            com.example.musicplayerapp.data.supabase.AuthFailure.NetworkFailure("offline")
        )

        AccountDeletion.request(context)
        assertTrue(IdentityStore.deletionInFlight(context))

        val drain = ReactionSyncEngine(
            reactions = db.reactionDao(),
            outbox = db.reactionOutboxDao(),
            api = sync,
            identity = { ListenerIdentity.Available(x) },
            deletionInFlight = { IdentityStore.deletionInFlight(context) },
        ).drain()

        assertTrue("$drain", drain is DrainResult.DeletionInProgress)
        assertEquals(1, db.reactionOutboxDao().count())
        assertTrue(sync.events.isEmpty())

        // And when the deletion finally settles, the rows are discarded rather than sent.
        auth.deleteOutcome = DeleteAccountOutcome.AlreadyDeleted
        IdentityReconciler.reconcile(context, sessionUid = x)
        assertEquals(0, db.reactionOutboxDao().count())
        assertTrue("nothing may have been delivered", sync.events.isEmpty())
    }

    // ==================== the status route is never called on the happy path ====================

    /** A deletion that resolves against a live session never consults the receipt. */
    @Test
    fun k_the_receipt_is_not_consulted_when_the_server_answered() = runBlocking {
        anAccountWithData()
        auth.deleteOutcome = DeleteAccountOutcome.Deleted(1, 1, 1)
        auth.statusOutcome = DeletionStatusOutcome.Unknown

        AccountDeletion.request(context)

        assertEquals(0, auth.statusCalls)
    }
}
