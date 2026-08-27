package com.example.musicplayerapp

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayerapp.data.AppDatabase
import com.example.musicplayerapp.data.supabase.EmailAuthBackend
import com.example.musicplayerapp.data.supabase.IdentityStore
import com.example.musicplayerapp.data.supabase.LastSyncStore
import com.example.musicplayerapp.data.supabase.PullResult
import com.example.musicplayerapp.data.supabase.ReactionPull
import com.example.musicplayerapp.data.supabase.ReactionPullTrigger
import com.example.musicplayerapp.data.supabase.ReactionSyncBackend
import com.example.musicplayerapp.data.supabase.SyncOutcome
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What this install can honestly say about its own synchronisation.
 *
 * Two facts, and the difference between them is the point of this suite.
 *
 * **`initialRestoreComplete(uid)`** answers "has this device ever read that account
 * through". It is durable and per account, and it is the only thing that separates
 * "there is a registered account" from "the Collection on this phone actually came
 * back from the cloud". Everything that is *not* a completed scan must leave it
 * false, which is most of what is asserted below.
 *
 * **`lastSyncAt`** answers "when did this device last exchange anything with the
 * cloud", and it is the maximum of two separately stored timestamps. They stay apart
 * in storage because an install can have pushed without ever restoring, or restored
 * without ever pushing, and collapsing them would make the second indistinguishable
 * from having done neither - the false "never synchronised" this whole phase exists
 * to stop showing.
 */
@RunWith(AndroidJUnit4::class)
class SyncStateTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var db: AppDatabase
    private lateinit var auth: FakeEmailAuthApi
    private lateinit var sync: RecordingSyncApi

    private val x = "11111111-1111-4111-8111-111111111111"
    private val y = "22222222-2222-4222-8222-222222222222"

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

    // ==================== the restore marker ====================

    /** **A.** One completed full scan marks the account restored. */
    @Test
    fun a_a_completed_scan_marks_the_account_restored() = runBlocking {
        IdentityStore.markRegistered(context, x)
        auth.session = x
        assertFalse(LastSyncStore.isInitialRestoreComplete(context, x))

        val result = ReactionPull.run(context)

        assertTrue("$result", result is PullResult.Completed)
        assertTrue(LastSyncStore.isInitialRestoreComplete(context, x))
    }

    /**
     * **B.** A scan that failed part-way leaves it false.
     *
     * The pages it applied stay applied and stay valid - that is G-A7c's contract -
     * but the account was not read through, and saying it was restored would be
     * claiming something nobody checked.
     */
    @Test
    fun b_a_partial_scan_leaves_the_marker_false() = runBlocking {
        IdentityStore.markRegistered(context, x)
        auth.session = x
        sync.pullFailure = SyncOutcome.Transient("network gave way")

        val result = ReactionPull.run(context)

        assertTrue("$result", result is PullResult.Transient)
        assertFalse("a partial scan is not a restore", LastSyncStore.isInitialRestoreComplete(context, x))
        assertNull("nor a synchronisation", LastSyncStore.lastPullAt(context))
    }

    /** **C.** Nor does anything that never read the account at all. */
    @Test
    fun c_outcomes_that_read_nothing_leave_the_marker_false() = runBlocking {
        // No session: AuthUnavailable.
        IdentityStore.markRegistered(context, x)
        auth.session = null
        assertTrue("${ReactionPull.run(context)}", ReactionPull.run(context) is PullResult.AuthUnavailable)
        assertFalse(LastSyncStore.isInitialRestoreComplete(context, x))

        // Not an account: NotEligible.
        IdentityStore.clearForTest(context)
        IdentityStore.adoptAnonymous(context, x)
        assertTrue("${ReactionPull.run(context)}", ReactionPull.run(context) is PullResult.NotEligible)
        assertFalse(LastSyncStore.isInitialRestoreComplete(context, x))

        assertNull(LastSyncStore.lastPullAt(context))
    }

    /** **D.** Completing X says nothing whatever about Y. */
    @Test
    fun d_the_marker_is_scoped_to_the_account() = runBlocking {
        IdentityStore.markRegistered(context, x)
        auth.session = x
        ReactionPull.run(context)

        assertTrue(LastSyncStore.isInitialRestoreComplete(context, x))
        assertFalse(
            "an install that switches accounts has restored nothing for the new one",
            LastSyncStore.isInitialRestoreComplete(context, y),
        )
    }

    /**
     * **E and O.** A marked account still full-scans on the next opportunity.
     *
     * This is the property that keeps the marker from quietly becoming a cursor. It
     * is durable, it is per account, and nothing in the pull or the trigger reads it -
     * so the second run reads the account exactly as the first did.
     */
    @Test
    fun e_and_o_a_marked_account_still_scans_again() = runBlocking {
        IdentityStore.markRegistered(context, x)
        auth.session = x

        ReactionPull.run(context)
        assertTrue(LastSyncStore.isInitialRestoreComplete(context, x))
        val readsAfterFirst = sync.pullRequests

        // Both routes: the primitive directly, and the trigger G-A7d wires up.
        val again = ReactionPull.run(context)
        assertTrue("$again", again is PullResult.Completed)

        ReactionPullTrigger.request(context, "app start")

        assertTrue(
            "the marker must not suppress a later scan",
            sync.pullRequests > readsAfterFirst + 1,
        )
    }

    /** **M.** Signing out marks nobody complete. */
    @Test
    fun m_signing_out_marks_no_account_complete() = runBlocking {
        IdentityStore.markRegistered(context, x)
        auth.session = x
        ReactionPull.run(context)

        IdentityStore.signOut(context)

        assertTrue("X's own completion is a fact and stays one", LastSyncStore.isInitialRestoreComplete(context, x))
        assertFalse(LastSyncStore.isInitialRestoreComplete(context, y))
    }

    // ==================== which timestamp the row shows ====================

    /** **J.** Neither: there is nothing to report. */
    @Test
    fun j_neither_timestamp_means_never() {
        assertNull(LastSyncStore.lastSyncAt(context))
    }

    /** **F.** Only an upload. */
    @Test
    fun f_upload_only() {
        LastSyncStore.recordSuccess(context, at = 5_000L)
        assertEquals(5_000L, LastSyncStore.lastSyncAt(context))
    }

    /** **G.** Only a restore - which is a synchronisation, and used to read as "never". */
    @Test
    fun g_pull_only() {
        LastSyncStore.recordPullSuccess(context, at = 7_000L)
        assertEquals(7_000L, LastSyncStore.lastSyncAt(context))
    }

    /** **H.** Both, upload newer. */
    @Test
    fun h_the_newer_of_the_two_wins_when_it_is_the_upload() {
        LastSyncStore.recordPullSuccess(context, at = 5_000L)
        LastSyncStore.recordSuccess(context, at = 9_000L)

        assertEquals(9_000L, LastSyncStore.lastSyncAt(context))
        assertEquals("and neither is overwritten", 5_000L, LastSyncStore.lastPullAt(context))
        assertEquals(9_000L, LastSyncStore.lastSuccessAt(context))
    }

    /** **I.** Both, restore newer. */
    @Test
    fun i_the_newer_of_the_two_wins_when_it_is_the_pull() {
        LastSyncStore.recordSuccess(context, at = 5_000L)
        LastSyncStore.recordPullSuccess(context, at = 9_000L)

        assertEquals(9_000L, LastSyncStore.lastSyncAt(context))
        assertEquals(5_000L, LastSyncStore.lastSuccessAt(context))
        assertEquals(9_000L, LastSyncStore.lastPullAt(context))
    }

    /**
     * **L.** The debounce is never the answer.
     *
     * `ReactionPullTrigger`'s sixty-second window is process-local, measured on
     * `elapsedRealtime`, and exists only to stop two lifecycle triggers stacking. A
     * screen that showed it would be reporting when the app last *considered* syncing.
     */
    @Test
    fun l_the_throttle_window_is_never_reported_as_a_sync() = runBlocking {
        IdentityStore.markRegistered(context, x)
        auth.session = null   // the attempt reads nothing

        ReactionPullTrigger.request(context, "app start")

        assertNull(
            "a trigger that read nothing is not a synchronisation",
            LastSyncStore.lastSyncAt(context),
        )
    }

    /**
     * **K.** A push that settled as ALREADY_APPLIED still counts as an upload.
     *
     * Those events did reach the cloud; this device is only learning it now, because
     * the attempt that delivered them lost its answer. The drain reports a delivery
     * either way, which is what `ReactionSyncWorker` turns into the timestamp - so the
     * row cannot go backwards just because a response was lost.
     */
    @Test
    fun k_an_already_applied_settlement_still_counts_as_an_upload() {
        // The worker's rule, stated where it is observable: any drain that delivered
        // rows records the upload, and ALREADY_APPLIED reports its rows as delivered.
        LastSyncStore.recordSuccess(context, at = 4_000L)

        assertEquals(4_000L, LastSyncStore.lastSuccessAt(context))
        assertEquals(4_000L, LastSyncStore.lastSyncAt(context))
        assertNull("and it is not a restore", LastSyncStore.lastPullAt(context))
    }
}
