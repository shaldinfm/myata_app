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
import org.junit.Assert.assertNotNull
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

    /** One completed full scan marks the account restored. */
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
     * A scan that failed part-way leaves it false.
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
        assertNull("nor a synchronisation", LastSyncStore.lastPullAt(context, x))
    }

    /** Nor does anything that never read the account at all. */
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

        assertNull(LastSyncStore.lastPullAt(context, x))
    }

    /** **B.** A completed scan for X records the restore under X, and only X. */
    @Test
    fun b_a_completed_scan_records_the_restore_for_that_account() = runBlocking {
        IdentityStore.markRegistered(context, x)
        auth.session = x

        ReactionPull.run(context)

        assertNotNull(LastSyncStore.lastPullAt(context, x))
        assertNull("and not under anybody else", LastSyncStore.lastPullAt(context, y))
    }

    /** Completing X says nothing whatever about Y. */
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
     * **M.** A marked account still full-scans on the next opportunity.
     *
     * This is the property that keeps the marker from quietly becoming a cursor. It
     * is durable, it is per account, and nothing in the pull or the trigger reads it -
     * so the second run reads the account exactly as the first did.
     */
    @Test
    fun m_a_marked_account_still_scans_again() = runBlocking {
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

    /** Signing out marks nobody complete. */
    @Test
    fun signing_out_marks_no_account_complete() = runBlocking {
        IdentityStore.markRegistered(context, x)
        auth.session = x
        ReactionPull.run(context)

        IdentityStore.signOut(context)

        assertTrue("X's own completion is a fact and stays one", LastSyncStore.isInitialRestoreComplete(context, x))
        assertFalse(LastSyncStore.isInitialRestoreComplete(context, y))
    }

    // ==================== whose timestamps they are ====================

    /**
     * **A and J.** An upload is filed under the identity that owned the drain, even
     * when the account changes immediately afterwards.
     *
     * The race this closes: a drain delivers for X and releases [SyncLease]; a
     * sign-out and a sign-in as Y land in the gap; the worker then does its
     * bookkeeping. If it asked "who am I now" it would file X's delivery under Y.
     *
     * So the uid travels out **on the result** - `DrainResult.Drained.listenerId` -
     * and `ReactionSyncWorker` uses exactly that. This drives the same sequence and
     * asserts the value the worker is handed is still X after the switch.
     */
    @Test
    fun a_and_j_an_upload_belongs_to_the_identity_that_drained() = runBlocking {
        val track = "a".repeat(64)
        db.reactionDao().like(track, "Artist", "Title", "myata", likedAt = 1_000L)

        IdentityStore.markRegistered(context, x)
        val result = com.example.musicplayerapp.data.supabase.ReactionSyncEngine(
            reactions = db.reactionDao(),
            outbox = db.reactionOutboxDao(),
            api = sync,
            identity = { com.example.musicplayerapp.data.supabase.ListenerIdentity.Available(x) },
            deletionInFlight = { false },
        ).drain()

        assertTrue("$result", result is com.example.musicplayerapp.data.supabase.DrainResult.Drained)
        val drained = result as com.example.musicplayerapp.data.supabase.DrainResult.Drained

        // The account changes before the worker gets round to its bookkeeping.
        IdentityStore.markRegistered(context, y)

        assertEquals(
            "the result must still name the identity that actually delivered",
            x,
            drained.listenerId,
        )

        // Exactly what ReactionSyncWorker does with it.
        LastSyncStore.recordUploadSuccess(context, drained.listenerId)

        assertNotNull("X delivered, so X synchronised", LastSyncStore.lastUploadAt(context, x))
        assertNull(
            "and Y, which happened to be current, did not",
            LastSyncStore.lastUploadAt(context, y),
        )
    }

    /**
     * **K.** Both settlement outcomes feed the same upload success.
     *
     * `APPLIED` and `ALREADY_APPLIED` both report their rows as delivered, so a drain
     * reports `Drained` either way. The second matters most: those events *did* reach
     * the cloud, and not counting them would empty the outbox while the profile still
     * claimed the account had never synchronised.
     */
    @Test
    fun k_applied_and_already_applied_both_count_for_the_right_account() = runBlocking {
        val track = "a".repeat(64)
        db.reactionDao().like(track, "Artist", "Title", "myata", likedAt = 1_000L)
        IdentityStore.markRegistered(context, x)

        fun engine() = com.example.musicplayerapp.data.supabase.ReactionSyncEngine(
            reactions = db.reactionDao(),
            outbox = db.reactionOutboxDao(),
            api = sync,
            identity = { com.example.musicplayerapp.data.supabase.ListenerIdentity.Available(x) },
            deletionInFlight = { false },
        )

        val applied = engine().drain()
        assertTrue("$applied", applied is com.example.musicplayerapp.data.supabase.DrainResult.Drained)
        assertEquals(x, (applied as com.example.musicplayerapp.data.supabase.DrainResult.Drained).listenerId)

        // A second act, settled through the same path.
        db.reactionDao().unlike(track)
        val again = engine().drain()
        assertTrue("$again", again is com.example.musicplayerapp.data.supabase.DrainResult.Drained)
        assertEquals(x, (again as com.example.musicplayerapp.data.supabase.DrainResult.Drained).listenerId)
    }

    // ==================== which timestamp the row shows ====================

    /** **I.** Neither, for this account: there is nothing to report. */
    @Test
    fun i_neither_timestamp_means_never() {
        assertNull(LastSyncStore.lastSyncAt(context, x))
    }

    /** **F.** Only an upload. */
    @Test
    fun f_upload_only() {
        LastSyncStore.recordUploadSuccess(context, x, at = 5_000L)
        assertEquals(5_000L, LastSyncStore.lastSyncAt(context, x))
    }

    /** Only a restore - which is a synchronisation, and used to read as "never". */
    @Test
    fun pull_only() {
        LastSyncStore.recordPullSuccess(context, x, at = 7_000L)
        assertEquals(7_000L, LastSyncStore.lastSyncAt(context, x))
    }

    /** **G.** Both, upload newer. */
    @Test
    fun g_the_newer_of_the_two_wins_when_it_is_the_upload() {
        LastSyncStore.recordPullSuccess(context, x, at = 5_000L)
        LastSyncStore.recordUploadSuccess(context, x, at = 9_000L)

        assertEquals(9_000L, LastSyncStore.lastSyncAt(context, x))
        assertEquals("and neither is overwritten", 5_000L, LastSyncStore.lastPullAt(context, x))
        assertEquals(9_000L, LastSyncStore.lastUploadAt(context, x))
    }

    /** **H.** Both, restore newer. */
    @Test
    fun h_the_newer_of_the_two_wins_when_it_is_the_pull() {
        LastSyncStore.recordUploadSuccess(context, x, at = 5_000L)
        LastSyncStore.recordPullSuccess(context, x, at = 9_000L)

        assertEquals(9_000L, LastSyncStore.lastSyncAt(context, x))
        assertEquals(5_000L, LastSyncStore.lastUploadAt(context, x))
        assertEquals(9_000L, LastSyncStore.lastPullAt(context, x))
    }

    /**
     * **C, D and E.** X's history is invisible to Y, and surviving a switch back.
     *
     * The three questions in one sequence, because they are one property: a timestamp
     * is a statement about an account. Y starting from nothing is the correct answer
     * rather than a gap, Y syncing must not touch X, and coming back to X must find
     * X's own history where it was - clearing it because somebody else signed in
     * would be discarding a fact that is still true.
     */
    @Test
    fun cde_timestamps_follow_the_account_through_a_switch() {
        LastSyncStore.recordUploadSuccess(context, x, at = 5_000L)
        LastSyncStore.recordPullSuccess(context, x, at = 6_000L)

        // Y has synchronised nothing.
        assertNull("Y starts with nothing of its own", LastSyncStore.lastSyncAt(context, y))

        // Y syncs. X is untouched.
        LastSyncStore.recordPullSuccess(context, y, at = 8_000L)
        assertEquals(8_000L, LastSyncStore.lastSyncAt(context, y))
        assertEquals("Y's sync must not overwrite X", 6_000L, LastSyncStore.lastSyncAt(context, x))

        // Back to X: its own history is still there.
        assertEquals(5_000L, LastSyncStore.lastUploadAt(context, x))
        assertEquals(6_000L, LastSyncStore.lastPullAt(context, x))
    }

    /** **L.** The restore marker is scoped independently of the timestamps. */
    @Test
    fun l_the_restore_marker_is_independently_scoped() {
        LastSyncStore.markInitialRestoreComplete(context, x)

        assertTrue(LastSyncStore.isInitialRestoreComplete(context, x))
        assertFalse(LastSyncStore.isInitialRestoreComplete(context, y))
        assertNull("and it is not a timestamp", LastSyncStore.lastSyncAt(context, x))
    }

    /**
     * The debounce is never the answer.
     *
     * `ReactionPullTrigger`'s sixty-second window is process-local, measured on
     * `elapsedRealtime`, and exists only to stop two lifecycle triggers stacking. A
     * screen that showed it would be reporting when the app last *considered* syncing.
     */
    @Test
    fun the_throttle_window_is_never_reported_as_a_sync() = runBlocking {
        IdentityStore.markRegistered(context, x)
        auth.session = null   // the attempt reads nothing

        ReactionPullTrigger.request(context, "app start")

        assertNull(
            "a trigger that read nothing is not a synchronisation",
            LastSyncStore.lastSyncAt(context, x),
        )
    }
}
