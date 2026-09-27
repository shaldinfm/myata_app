package com.example.musicplayerapp

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayerapp.data.AppDatabase
import com.example.musicplayerapp.data.Reaction
import com.example.musicplayerapp.data.ReactionDao
import com.example.musicplayerapp.data.ReactionMigration
import com.example.musicplayerapp.data.ReactionOutboxDao
import com.example.musicplayerapp.data.ReactionOutboxEntry
import com.example.musicplayerapp.data.TrackReaction
import com.example.musicplayerapp.data.supabase.AuthResult
import com.example.musicplayerapp.data.supabase.BatchOutcome
import com.example.musicplayerapp.data.supabase.CollectionScope
import com.example.musicplayerapp.data.supabase.CollectionScope.Scope
import com.example.musicplayerapp.data.supabase.DrainResult
import com.example.musicplayerapp.data.supabase.EmailAuthBackend
import com.example.musicplayerapp.data.supabase.EmailAuthRepository
import com.example.musicplayerapp.data.supabase.IdentityState
import com.example.musicplayerapp.data.supabase.IdentityStore
import com.example.musicplayerapp.data.supabase.InsertOutcome
import com.example.musicplayerapp.data.supabase.LastSyncStore
import com.example.musicplayerapp.data.supabase.ListenerIdentity
import com.example.musicplayerapp.data.supabase.PullPage
import com.example.musicplayerapp.data.supabase.PullResult
import com.example.musicplayerapp.data.supabase.ReactionPull
import com.example.musicplayerapp.data.supabase.ReactionPullTrigger
import com.example.musicplayerapp.data.supabase.ReactionSyncApi
import com.example.musicplayerapp.data.supabase.ReactionSyncBackend
import com.example.musicplayerapp.data.supabase.ReactionSyncEngine
import com.example.musicplayerapp.data.supabase.RemoteReaction
import com.example.musicplayerapp.data.supabase.SupabaseConfig
import com.example.musicplayerapp.data.supabase.SyncOutcome
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The account Collection is durable in the account, and only in the account.
 *
 * Every case runs the production paths - [EmailAuthRepository], [ReactionPull.run],
 * a [ReactionSyncEngine] asking the real [CollectionScope] - against an in-memory
 * Room database and [FakeServer], an in-memory stand-in for `public.reactions` that
 * keeps one map per account and assigns revisions from one sequence, as the real
 * trigger does. Nothing here reaches Supabase.
 *
 * Letters and numbers follow the two briefs: A upload and restore, B (= 6) the
 * server's newer state wins, C idempotence, 1 Y's unsynced rows survive a switch to Z
 * and come back for Y, 2 Y's pending acts never execute as Z and still execute as Y,
 * 3 unprovable pre-fix data is set aside rather than adopted, 4 an in-place upgrade
 * proven as Y stays Y's, 5 the device -> account adoption, F registered without a
 * session, I the ordinary Like path. Backup rules are the JVM `AccountBackupRulesTest`.
 */
@RunWith(AndroidJUnit4::class)
class CollectionDurabilityTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var db: AppDatabase
    private lateinit var dao: ReactionDao
    private lateinit var outbox: ReactionOutboxDao
    private lateinit var auth: FakeEmailAuthApi
    private lateinit var server: FakeServer

    private val y = "22222222-2222-4222-8222-222222222222"
    private val z = "33333333-3333-4333-8333-333333333333"

    private val a = key(1)
    private val b = key(2)
    private val c = key(3)

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = db.reactionDao()
        outbox = db.reactionOutboxDao()
        AppDatabase.overrideForInstrumentation(db)

        auth = FakeEmailAuthApi()
        EmailAuthBackend.overrideForInstrumentation { auth }

        // The app's own WorkManager drain may run beside a test (an authentication
        // schedules one). It reaches the same fake, but with an identity that can never
        // deliver, so the only drains that deliver are the ones a test runs itself.
        server = FakeServer()
        ReactionSyncBackend.overrideForInstrumentation(
            api = { server },
            identity = { ListenerIdentity.Unavailable("the background drain does not deliver here") },
        )

        // Pulls happen when a test says so, not when an authentication's trigger fires.
        ReactionPullTrigger.resetForTest()
        ReactionPullTrigger.runner = { PullResult.NotEligible("pulled explicitly by the test") }

        IdentityStore.clearForTest(context)
        LastSyncStore.clearForTest(context)
    }

    @After
    fun close() {
        IdentityStore.clearForTest(context)
        LastSyncStore.clearForTest(context)
        ReactionPullTrigger.resetForTest()
        CollectionScope.resetForTest()
        AppDatabase.overrideForInstrumentation(null)
        TestIsolation.restoreBackends()
        db.close()
    }

    // ==================== A. migrated local-only rows survive through the account ====================

    @Test
    fun a_migrated_local_only_rows_reach_the_account_and_return_after_clear_data() = runBlocking {
        legacy(a, likedAt = 1_000L)
        legacy(b, likedAt = 2_000L)
        legacy(c, likedAt = 3_000L)

        assertEquals(AuthResult.Success(y), signIn(y))
        assertEquals(Scope.Account(y), CollectionScope.activeScope(context))

        assertTrue(pull() is PullResult.Completed)
        assertEquals("the favourites reached the account", setOf(a, b, c), server.liked(y))
        assertTrue("and every local row is now confirmed", dao.allReactions().all { it.remoteRev != null })

        // Clear data: every local file goes, the account stays.
        clearLocalData()
        assertTrue(liked().isEmpty())

        assertEquals(AuthResult.Success(y), signIn(y))
        assertTrue(pull() is PullResult.Completed)

        assertEquals("the Collection came back from the account", setOf(a, b, c), liked().toSet())
        assertEquals(
            "with the moments the listener liked them, not the moment of the restore",
            listOf(3_000L, 2_000L, 1_000L),
            dao.allReactions().sortedByDescending { it.likedAt }.map { it.likedAt },
        )
    }

    // ==================== 4. in-place upgrade proven as Y ====================

    @Test
    fun t4_an_in_place_upgrade_proven_as_y_keeps_the_rows_as_ys() = runBlocking {
        // 3.6.6 before this fix: registered directly, favourites never uploaded.
        legacy(a)
        legacy(b)
        migratedFromV4(CollectionScope.Provenance.IN_PLACE_UPGRADE)
        registered(y)
        LastSyncStore.recordPullSuccess(context, y)

        assertEquals(Scope.Account(y), CollectionScope.activeScope(context))
        assertEquals(setOf(a, b), liked().toSet())

        assertTrue(pull() is PullResult.Completed)
        assertEquals("and they reach Y's account", setOf(a, b), server.liked(y))
    }

    // ==================== B. the server's newer state is never overwritten ====================

    @Test
    fun b_a_stale_local_row_never_overwrites_what_the_account_holds() = runBlocking {
        // The account withdrew A elsewhere; this phone still has A as an old favourite,
        // with a clock far ahead - the case an updated_at guard would get wrong.
        server.seed(y, a, Reaction.NEUTRAL)
        val serverRev = server.row(y, a)!!.rev
        legacy(a, likedAt = 1_000L, updatedAt = Long.MAX_VALUE / 2)
        legacy(b)
        registered(y)

        assertTrue(pull() is PullResult.Completed)

        assertEquals("the account's row is untouched", Reaction.NEUTRAL, server.row(y, a)!!.reaction)
        assertEquals("not even re-revisioned", serverRev, server.row(y, a)!!.rev)
        assertEquals("and the server's answer won locally", Reaction.NEUTRAL, dao.find(a)!!.reaction)
        assertEquals("while the row it did not hold was published", setOf(b), server.liked(y))
    }

    @Test
    fun b_a_restored_row_with_an_old_revision_yields_to_the_newer_one() = runBlocking {
        server.seed(y, a, Reaction.LIKED)
        val old = server.row(y, a)!!.rev
        server.seed(y, a, Reaction.NEUTRAL) // later, from another device
        confirmed(a, Reaction.LIKED, rev = old)
        registered(y)

        assertTrue(pull() is PullResult.Completed)

        assertEquals(Reaction.NEUTRAL, dao.find(a)!!.reaction)
        assertTrue("a confirmed row is never re-uploaded", server.inserts.isEmpty())
    }

    // ==================== C. idempotence ====================

    @Test
    fun c_reconciliation_twice_neither_duplicates_nor_overwrites() = runBlocking {
        legacy(a)
        legacy(b)
        registered(y)

        assertTrue(pull() is PullResult.Completed)
        val afterFirst = server.snapshot(y)
        assertTrue(pull() is PullResult.Completed)

        assertEquals("the second run changed nothing remotely", afterFirst, server.snapshot(y))
        assertEquals("and uploaded nothing", 2, server.inserts.size)
        assertEquals("one statement for the whole Collection", 1, server.statements)
    }

    @Test
    fun c_one_row_the_server_refuses_does_not_hold_back_the_rest() = runBlocking {
        legacy(a)
        legacy(b)
        legacy(c, artist = "") // a legacy row the server's length check refuses
        registered(y)

        assertTrue(pull() is PullResult.Completed)

        assertEquals(setOf(a, b), server.liked(y))
        assertNotNull("the refused favourite is kept locally, never deleted", dao.find(c))
        assertNull(dao.find(c)!!.remoteRev)
    }

    @Test
    fun c_a_pull_that_failed_after_uploading_is_repaired_by_the_next_one() = runBlocking {
        legacy(a)
        registered(y)

        server.failFetch = true
        assertTrue(pull() is PullResult.Transient)
        assertNull("nothing confirmed locally yet", dao.find(a)!!.remoteRev)
        val afterFirst = server.snapshot(y)

        server.failFetch = false
        assertTrue(pull() is PullResult.Completed)

        assertEquals("inserted once, then found already held", listOf(1, 0), server.insertedCounts)
        assertEquals(afterFirst, server.snapshot(y))
        assertNotNull(dao.find(a)!!.remoteRev)
    }

    // ==================== 1. Y's unsynced like survives a switch to Z ====================

    @Test
    fun t1_ys_unsynced_like_is_hidden_from_z_and_back_for_y() = runBlocking {
        registered(y)
        CollectionScope.enterAccount(context, y, adoptDevice = false)
        legacy(a) // Y's, never reached the server
        server.seed(z, c, Reaction.LIKED)

        EmailAuthRepository.signOut(context)
        assertTrue("signing out hides Y's Collection", liked().isEmpty())
        assertEquals("and keeps it for Y", 1, scopes().parkedReactionCount(Scope.Account(y).key))

        assertEquals(AuthResult.Success(z), signIn(z))
        assertTrue("Y's rows are not Z's", liked().isEmpty())
        assertEquals(Scope.Account(z), CollectionScope.activeScope(context))
        assertTrue(pull() is PullResult.Completed)
        assertEquals("Z hydrates from Z", listOf(c), liked())
        assertTrue("nothing of Y's went into Z", server.inserts.none { it.first == z })
        assertEquals(setOf(c), server.liked(z))
        assertEquals("and Y's like is kept, parked for Y", 1, scopes().parkedReactionCount(Scope.Account(y).key))

        EmailAuthRepository.signOut(context)
        assertEquals(AuthResult.Success(y), signIn(y))
        assertEquals("Y's like is back", listOf(a), liked())
        assertEquals("and Z's rows are parked for Z", 1, scopes().parkedReactionCount(Scope.Account(z).key))

        assertTrue(pull() is PullResult.Completed)
        assertEquals("and it syncs as Y", setOf(a), server.liked(y))
        assertEquals(setOf(c), server.liked(z))
    }

    // ==================== 2. Y's pending act never executes as Z ====================

    @Test
    fun t2_ys_pending_acts_wait_through_z_and_execute_as_y() = runBlocking {
        registered(y)
        CollectionScope.enterAccount(context, y, adoptDevice = false)
        LastSyncStore.markInitialRestoreComplete(context, y) // Y was read back here earlier

        // Acts made as Y, offline: Y's, and unsent when Y signs out.
        dao.like(a, "Artist", "Title", "myata", 1_000L, 1_000L)
        dao.like(b, "Artist", "Title", "myata", 2_000L, 2_000L)
        dao.unlike(b)
        val pending = outbox.pending().map { it.eventId }
        assertEquals(3, pending.size)
        EmailAuthRepository.signOut(context)

        assertEquals(AuthResult.Success(z), signIn(z))
        assertEquals("nothing of Y's is pending as Z", 0, outbox.count())
        assertEquals(DrainResult.Idle, drainAs(z))
        assertTrue(server.batches.isEmpty())

        EmailAuthRepository.signOut(context)
        assertEquals(AuthResult.Success(y), signIn(y))
        assertEquals("the same acts, in the same order", pending, outbox.pending().map { it.eventId })

        assertTrue(drainAs(y) is DrainResult.Drained)
        assertTrue("delivered as Y", server.batches.isNotEmpty() && server.batches.all { it.first == y })
        assertEquals(Reaction.LIKED, server.row(y, a)!!.reaction)
        assertEquals(Reaction.NEUTRAL, server.row(y, b)!!.reaction)
        assertEquals(0, outbox.count())
    }

    @Test
    fun t2_the_drain_refuses_a_session_that_is_not_the_collections_account() = runBlocking {
        registered(y)
        CollectionScope.enterAccount(context, y, adoptDevice = false)
        dao.like(a, "Artist", "Title", "myata", 1_000L, 1_000L)

        // The disk says Z without the Collection having followed - the state a death
        // between two writes could leave. The drain must not carry Y's act as Z.
        IdentityStore.markRegistered(context, z)
        var asked = 0
        val result = drain { asked++; ListenerIdentity.Available(z) }

        assertEquals(DrainResult.Paused, result)
        assertEquals("not even asked who it is", 0, asked)
        assertTrue(server.batches.isEmpty())
        assertEquals(1, outbox.count())
    }

    @Test
    fun t2_a_restored_account_collection_mints_nothing_while_signed_out() = runBlocking {
        // A backup restore: the database (and its scope) came back, the identity did not.
        registered(y)
        CollectionScope.enterAccount(context, y, adoptDevice = false)
        IdentityStore.clearForTest(context)
        dao.like(a, "Artist", "Title", "myata", 1_000L, 1_000L)

        var asked = 0
        assertEquals(DrainResult.Paused, drain { asked++; ListenerIdentity.Available("anonymous") })
        assertEquals("no anonymous identity for an account's rows", 0, asked)
    }

    // ==================== 3. restored pre-fix database ====================

    @Test
    fun t3_a_restored_pre_fix_database_is_never_adopted_by_the_first_account() = runBlocking {
        legacy(a)
        confirmed(b, Reaction.LIKED, rev = 7)
        dao.like(c, "Artist", "Title", "myata", 1_000L, 1_000L) // a pending act, owner unknown
        migratedFromV4(CollectionScope.Provenance.FRESH_INSTALL)

        assertEquals(AuthResult.Success(z), signIn(z))
        assertTrue("nothing restored is shown as Z's", liked().isEmpty())
        assertEquals(0, outbox.count())
        assertTrue(pull() is PullResult.Completed)
        assertEquals(DrainResult.Idle, drainAs(z))
        assertTrue("nothing restored is uploaded into Z", server.inserts.isEmpty() && server.batches.isEmpty())

        assertEquals("but it is all kept, unclaimed", 3, scopes().parkedReactionCount(Scope.Legacy.key))
        assertEquals(1, scopes().parkedOutboxCount(Scope.Legacy.key))
    }

    @Test
    fun t3_nor_shown_to_a_guest_on_the_restored_install() = runBlocking {
        legacy(a)
        migratedFromV4(CollectionScope.Provenance.FRESH_INSTALL)

        assertEquals(Scope.Device, CollectionScope.activeScope(context))
        assertTrue(liked().isEmpty())
        assertEquals(1, scopes().parkedReactionCount(Scope.Legacy.key))
    }

    @Test
    fun t3_an_install_that_read_two_accounts_sets_its_old_rows_aside() = runBlocking {
        IdentityStore.markRegistered(context, z)
        auth.session = z
        LastSyncStore.recordPullSuccess(context, y)
        LastSyncStore.recordPullSuccess(context, z)
        confirmed(a, Reaction.LIKED, rev = 3) // Y's or Z's: unknowable
        legacy(b)
        migratedFromV4(CollectionScope.Provenance.IN_PLACE_UPGRADE)

        assertEquals(Scope.Account(z), CollectionScope.activeScope(context))
        assertTrue(liked().isEmpty())
        assertEquals("kept, not deleted", 2, scopes().parkedReactionCount(Scope.Legacy.key))
    }

    // ==================== 5. device -> account adoption ====================

    @Test
    fun t5_an_anonymous_collection_is_adopted_by_the_handoff() = runBlocking {
        val x = "11111111-1111-4111-8111-111111111111"
        legacy(a)
        IdentityStore.adoptAnonymous(context, x)

        assertEquals(AuthResult.Success(y), signIn(y))

        assertEquals(Scope.Account(y), CollectionScope.activeScope(context))
        assertEquals(listOf(a), liked())
        assertEquals("adopted into the account", setOf(a), server.liked(y))
    }

    @Test
    fun t5_a_guest_collection_is_adopted_by_a_direct_sign_in() = runBlocking {
        legacy(a)

        assertEquals(AuthResult.Success(y), signIn(y))
        assertTrue(pull() is PullResult.Completed)

        assertEquals(listOf(a), liked())
        assertEquals(setOf(a), server.liked(y))
    }

    @Test
    fun t5_adoption_never_overwrites_the_accounts_own_rows() = runBlocking {
        // Y's parked Collection holds A; the device, since, withdrew A and liked B.
        confirmed(a, Reaction.LIKED, rev = 4)
        scopes().parkReactions(Scope.Account(y).key)
        dao.clearAll()
        scopes().setScope(Scope.Device.key)
        dao.like(a, "Artist", "Title", "myata", 1_000L, 1_000L)
        dao.unlike(a)
        dao.like(b, "Artist", "Title", "myata", 2_000L, 2_000L)

        assertEquals(AuthResult.Success(y), signIn(y))

        assertEquals("Y's own row for A stands", 4L, dao.find(a)!!.remoteRev)
        assertEquals(Reaction.LIKED, dao.find(a)!!.reaction)
        assertEquals("B is adopted", Reaction.LIKED, dao.find(b)!!.reaction)
        assertEquals("the device's A is kept aside, not lost", 1, scopes().parkedReactionCount(Scope.Device.key))
        assertEquals("with its two acts", 2, scopes().parkedOutboxCount(Scope.Device.key))
        assertTrue("none of which are pending for Y", outbox.pending().none { it.trackKey == a })
    }

    // ==================== LOGOUT: Y leaves with its session ====================

    @Test
    fun logout_hides_y_at_once_and_parks_it_for_y() = runBlocking {
        registered(y)
        CollectionScope.enterAccount(context, y, adoptDevice = false)
        legacy(a)
        confirmed(b, Reaction.DISLIKED, rev = 5)
        dao.like(c, "Artist", "Title", "myata", 3_000L, 3_000L) // Y's pending act

        EmailAuthRepository.signOut(context)

        assertEquals(Scope.Device, CollectionScope.activeScope(context))
        assertTrue("the guest sees none of Y's rows", dao.allReactions().isEmpty())
        assertEquals("and runs none of Y's acts", 0, outbox.count())
        assertEquals(3, scopes().parkedReactionCount(Scope.Account(y).key))
        assertEquals("Y's pending act stays Y's", 1, scopes().parkedOutboxCount(Scope.Account(y).key))
    }

    @Test
    fun logout_a_guests_like_is_the_devices_and_stays_apart_from_y() = runBlocking {
        registered(y)
        CollectionScope.enterAccount(context, y, adoptDevice = false)
        legacy(a)
        legacy(b)
        dao.like(d, "Artist", "Title", "myata", 4_000L, 4_000L) // Y's pending act
        val ysPending = outbox.pending().map { it.eventId }

        EmailAuthRepository.signOut(context)
        CollectionScope.prepareLocalWrite(context)
        dao.like(c, "Artist", "Title", "myata", 5_000L, 5_000L) // a guest's like
        assertEquals(Scope.Device, CollectionScope.activeScope(context))
        assertEquals(listOf(c), liked())

        assertEquals(AuthResult.Success(y), signIn(y))

        assertEquals("Y's rows are back", setOf(a, b, d), liked().toSet())
        assertEquals("with Y's own pending act, unchanged", ysPending, outbox.pending().map { it.eventId })
        assertEquals("the guest's like is the device's, not Y's", 1, scopes().parkedReactionCount(Scope.Device.key))
        assertEquals(1, scopes().parkedOutboxCount(Scope.Device.key))
    }

    @Test
    fun logout_a_restored_account_database_is_not_shown_to_a_guest() = runBlocking {
        registered(y)
        CollectionScope.enterAccount(context, y, adoptDevice = false)
        legacy(a)
        // A backup restore: the database and its scope came back, the identity and the
        // session did not.
        IdentityStore.clearForTest(context)
        auth.session = null

        assertFalse("hidden before anything is moved", CollectionScope.visibility(context).first())

        CollectionScope.ensureScope(context)
        assertEquals(Scope.Device, CollectionScope.activeScope(context))
        assertTrue(liked().isEmpty())
        assertTrue(CollectionScope.visibility(context).first())
        assertEquals("kept for Y", 1, scopes().parkedReactionCount(Scope.Account(y).key))
    }

    @Test
    fun logout_an_account_whose_stored_session_is_gone_is_parked_at_start() = runBlocking {
        registered(y)
        CollectionScope.enterAccount(context, y, adoptDevice = false)
        legacy(a)

        auth.session = y
        CollectionScope.ensureScope(context)
        assertEquals("a stored session keeps it", Scope.Account(y), CollectionScope.activeScope(context))

        auth.session = null // refused by the server, or signed out elsewhere
        CollectionScope.ensureScope(context)
        assertEquals(Scope.Device, CollectionScope.activeScope(context))
        assertTrue(liked().isEmpty())

        auth.session = y
        CollectionScope.ensureScope(context)
        assertEquals("and back when the session is", listOf(a), liked())
    }

    // ==================== CROSS-DEVICE: the server's newer state wins ====================

    @Test
    fun conflict_a_stale_like_cannot_overwrite_a_newer_dislike() = runBlocking {
        val base = syncedAccountTrack(Reaction.NEUTRAL)
        dao.like(a, "Artist", "Title", "myata", 1_000L, 1_000L) // offline, on this phone

        server.seed(y, a, Reaction.DISLIKED) // later, on another device or the web
        assertTrue(server.row(y, a)!!.rev > base)

        assertTrue(drainAs(y) is DrainResult.Drained)
        assertEquals("the newer server state stands", Reaction.DISLIKED, server.row(y, a)!!.reaction)
        assertEquals("and wins locally", Reaction.DISLIKED, dao.find(a)!!.reaction)
        assertEquals("resolved, not retried", 0, outbox.count())
        assertEquals(1, server.conflicts)
    }

    @Test
    fun conflict_a_stale_dislike_cannot_overwrite_a_newer_like() = runBlocking {
        syncedAccountTrack(Reaction.NEUTRAL)
        dao.dislike(a, "Artist", "Title", "myata")
        server.seed(y, a, Reaction.LIKED)

        assertTrue(drainAs(y) is DrainResult.Drained)
        assertEquals(Reaction.LIKED, server.row(y, a)!!.reaction)
        assertEquals(Reaction.LIKED, dao.find(a)!!.reaction)
    }

    @Test
    fun conflict_a_stale_unlike_cannot_erase_a_newer_like() = runBlocking {
        syncedAccountTrack(Reaction.LIKED)
        dao.unlike(a)
        server.seed(y, a, Reaction.NEUTRAL) // another device unliked...
        server.seed(y, a, Reaction.LIKED) // ...and liked it again

        assertTrue(drainAs(y) is DrainResult.Drained)
        assertEquals(Reaction.LIKED, server.row(y, a)!!.reaction)
        assertEquals(Reaction.LIKED, dao.find(a)!!.reaction)
    }

    @Test
    fun conflict_pending_state_nobody_else_touched_still_syncs() = runBlocking {
        syncedAccountTrack(Reaction.NEUTRAL)
        dao.like(a, "Artist", "Title", "myata", 1_000L, 1_000L)

        assertTrue(drainAs(y) is DrainResult.Drained)
        assertEquals(Reaction.LIKED, server.row(y, a)!!.reaction)
        assertEquals(0, server.conflicts)
    }

    @Test
    fun conflict_a_retry_of_an_accepted_batch_is_idempotent() = runBlocking {
        syncedAccountTrack(Reaction.NEUTRAL)
        dao.like(a, "Artist", "Title", "myata", 1_000L, 1_000L)

        server.loseNextAnswer = true // applied on the server, the answer never arrives
        assertTrue(drainAs(y) is DrainResult.RetryLater)
        val applied = server.row(y, a)!!
        assertEquals(1, outbox.count())

        makeDue()
        assertTrue(drainAs(y) is DrainResult.Drained)
        assertEquals("no second revision", applied, server.row(y, a))
        assertEquals(0, outbox.count())
        assertEquals(0, server.conflicts)
    }

    @Test
    fun conflict_a_lost_answer_does_not_make_the_next_act_conflict_with_its_own_chain() = runBlocking {
        syncedAccountTrack(Reaction.NEUTRAL)
        dao.like(a, "Artist", "Title", "myata", 1_000L, 1_000L)
        server.loseNextAnswer = true
        assertTrue(drainAs(y) is DrainResult.RetryLater)

        dao.unlike(a) // the chain continues, still on the old baseline

        makeDue()
        assertTrue(drainAs(y) is DrainResult.Drained)
        assertEquals(Reaction.NEUTRAL, server.row(y, a)!!.reaction)
        assertEquals(0, server.conflicts)
    }

    // ==================== MULTIPLE OFFLINE ACTS ON ONE TRACK ====================

    @Test
    fun offline_chain_like_unlike_like_syncs_its_final_state_without_conflict() = runBlocking {
        syncedAccountTrack(Reaction.NEUTRAL)
        dao.like(a, "Artist", "Title", "myata", 1_000L, 1_000L)
        dao.unlike(a)
        dao.like(a, "Artist", "Title", "myata", 3_000L, 3_000L)
        assertEquals(3, outbox.count())

        assertTrue(drainAs(y) is DrainResult.Drained)

        assertEquals("one batch, one baseline", 1, server.batches.size)
        assertEquals(Reaction.LIKED, server.row(y, a)!!.reaction)
        assertEquals(0, server.conflicts)
        assertEquals(0, outbox.count())
    }

    @Test
    fun offline_an_act_tapped_during_the_call_builds_on_this_chains_own_write() = runBlocking {
        syncedAccountTrack(Reaction.NEUTRAL)
        dao.like(a, "Artist", "Title", "myata", 1_000L, 1_000L)
        server.duringApply = { dao.unlike(a) }

        val first = drainAs(y)
        assertTrue("$first", first is DrainResult.MoreWorkDue || first is DrainResult.Drained)
        server.duringApply = null
        assertEquals("the next act is still owed", 1, outbox.count())

        assertTrue(drainAs(y) is DrainResult.Drained)
        assertEquals(Reaction.NEUTRAL, server.row(y, a)!!.reaction)
        assertEquals("never a conflict with its own write", 0, server.conflicts)
    }

    // ==================== DELETION ====================

    @Test
    fun deletion_in_flight_refuses_sign_in_and_recovery() = runBlocking {
        registered(y)
        auth.session = null
        IdentityStore.markDeletionRequested(context, "00000000-0000-4000-8000-000000000001", y)

        assertTrue(signIn(z) is AuthResult.Failed)
        assertTrue(signIn(y) is AuthResult.Failed)
        val recovery = EmailAuthRepository.verifyRecoveryCode(context, "listener@example.com", "123456")
        assertTrue("$recovery", recovery is com.example.musicplayerapp.data.supabase.RecoveryResult.Failed)

        assertTrue("nothing reached the server", auth.signIns.isEmpty() && auth.verifications.isEmpty())
        assertEquals(IdentityState.Registered(y), IdentityStore.state(context))
    }

    @Test
    fun deletion_of_y_leaves_z_active_rows_alone() = runBlocking {
        registered(y)
        CollectionScope.enterAccount(context, y, adoptDevice = false)
        legacy(a)
        IdentityStore.markRegistered(context, z)
        CollectionScope.enterAccount(context, z, adoptDevice = false) // Y parked, Z active
        legacy(b)

        com.example.musicplayerapp.data.supabase.SyncLease.withExclusive {
            com.example.musicplayerapp.data.supabase.AccountDeletionCleanup.run(context, y)
        }

        assertEquals("Z's rows are untouched", listOf(b), liked())
        assertEquals(Scope.Account(z).key, scopes().scope())
        assertEquals("Y's rows are gone", 0, scopes().parkedReactionCount(Scope.Account(y).key))
    }

    @Test
    fun deletion_of_y_leaves_the_device_collection_alone() = runBlocking {
        registered(y)
        CollectionScope.enterAccount(context, y, adoptDevice = false)
        legacy(a)
        EmailAuthRepository.signOut(context) // Y parked, device active
        dao.like(c, "Artist", "Title", "myata", 1_000L, 1_000L)

        com.example.musicplayerapp.data.supabase.SyncLease.withExclusive {
            com.example.musicplayerapp.data.supabase.AccountDeletionCleanup.run(context, y)
        }

        assertEquals("the device's rows are untouched", listOf(c), liked())
        assertEquals(1, outbox.count())
        assertEquals(0, scopes().parkedReactionCount(Scope.Account(y).key))
    }

    @Test
    fun deletion_of_the_active_account_brings_the_device_collection_back() = runBlocking {
        dao.like(c, "Artist", "Title", "myata", 1_000L, 1_000L) // a guest's
        assertEquals(Scope.Device, CollectionScope.activeScope(context)) // settled, as at start
        IdentityStore.markRegistered(context, y)
        auth.session = y
        CollectionScope.enterAccount(context, y, adoptDevice = false) // device parked, not adopted
        legacy(a)

        com.example.musicplayerapp.data.supabase.SyncLease.withExclusive {
            com.example.musicplayerapp.data.supabase.AccountDeletionCleanup.run(context, y)
        }

        assertEquals(Scope.Device.key, scopes().scope())
        assertEquals(listOf(c), liked())
    }

    // ==================== RESTORE: taps before the initial pull ====================

    @Test
    fun restore_a_tap_before_the_initial_pull_survives_is_rebased_and_syncs() = runBlocking {
        server.seed(y, a, Reaction.DISLIKED)
        val rev10 = server.row(y, a)!!.rev

        assertEquals(AuthResult.Success(y), signIn(y)) // fresh install, nothing read back yet
        dao.like(a, "Artist", "Title", "myata", 1_000L, 1_000L) // tapped before the pull

        assertTrue("held, not sent on an unknown baseline", drainAs(y) is DrainResult.AwaitingRestore)
        assertTrue(server.batches.isEmpty())

        assertTrue(pull() is PullResult.Completed)
        assertEquals("the tap is still what the listener sees", Reaction.LIKED, dao.find(a)!!.reaction)
        assertEquals("rebased onto the revision the restore learned", rev10, dao.find(a)!!.remoteRev)
        assertEquals(1, outbox.count())

        assertTrue(drainAs(y) is DrainResult.Drained)
        assertEquals("the fresh act wins", Reaction.LIKED, server.row(y, a)!!.reaction)
        assertTrue("at a newer revision", server.row(y, a)!!.rev > rev10)
        assertEquals(0, server.conflicts)
        assertEquals(Reaction.LIKED, dao.find(a)!!.reaction)
    }

    @Test
    fun restore_a_tap_on_a_track_the_account_never_had_syncs_after_the_restore() = runBlocking {
        assertEquals(AuthResult.Success(y), signIn(y))
        dao.like(a, "Artist", "Title", "myata", 1_000L, 1_000L)
        assertTrue(drainAs(y) is DrainResult.AwaitingRestore)

        assertTrue(pull() is PullResult.Completed)
        assertTrue(drainAs(y) is DrainResult.Drained)

        assertEquals(Reaction.LIKED, server.row(y, a)!!.reaction)
        assertEquals(0, server.conflicts)
    }

    @Test
    fun restore_several_taps_before_the_pull_sync_their_final_state() = runBlocking {
        server.seed(y, a, Reaction.DISLIKED)
        assertEquals(AuthResult.Success(y), signIn(y))
        dao.like(a, "Artist", "Title", "myata", 1_000L, 1_000L)
        dao.dislike(a, "Artist", "Title", "myata")
        dao.like(a, "Artist", "Title", "myata", 3_000L, 3_000L)

        assertTrue(pull() is PullResult.Completed)
        assertTrue(drainAs(y) is DrainResult.Drained)

        assertEquals(Reaction.LIKED, server.row(y, a)!!.reaction)
        assertEquals("one batch", 1, server.batches.size)
        assertEquals("never a false conflict", 0, server.conflicts)
        assertEquals(0, outbox.count())
    }

    @Test
    fun restore_a_failed_pull_keeps_the_tap_waiting_and_a_later_pull_releases_it() = runBlocking {
        server.seed(y, a, Reaction.DISLIKED)
        assertEquals(AuthResult.Success(y), signIn(y))
        dao.like(a, "Artist", "Title", "myata", 1_000L, 1_000L)

        server.failFetch = true
        assertTrue(pull() is PullResult.Transient)
        assertFalse(LastSyncStore.isInitialRestoreComplete(context, y))
        assertTrue("still held", drainAs(y) is DrainResult.AwaitingRestore)
        assertEquals("the tap is kept and visible", Reaction.LIKED, dao.find(a)!!.reaction)
        assertEquals(1, outbox.count())

        server.failFetch = false
        assertTrue(pull() is PullResult.Completed)
        assertTrue(drainAs(y) is DrainResult.Drained)
        assertEquals(Reaction.LIKED, server.row(y, a)!!.reaction)
        assertEquals(0, server.conflicts)
    }

    @Test
    fun restore_a_waiting_act_stays_with_its_account_across_a_switch() = runBlocking {
        server.seed(y, a, Reaction.DISLIKED)
        assertEquals(AuthResult.Success(y), signIn(y))
        dao.like(a, "Artist", "Title", "myata", 1_000L, 1_000L)
        val waiting = outbox.pending().map { it.eventId }

        EmailAuthRepository.signOut(context)
        assertEquals(AuthResult.Success(z), signIn(z))
        assertEquals("Y's waiting act is not Z's", 0, outbox.count())
        assertEquals(DrainResult.Idle, drainAs(z))

        EmailAuthRepository.signOut(context)
        assertEquals(AuthResult.Success(y), signIn(y))
        assertEquals("back, unchanged", waiting, outbox.pending().map { it.eventId })

        assertTrue(pull() is PullResult.Completed)
        assertTrue(drainAs(y) is DrainResult.Drained)
        assertEquals(Reaction.LIKED, server.row(y, a)!!.reaction)
        assertTrue("nothing reached Z", server.batches.none { it.first == z })
    }

    @Test
    fun restore_once_complete_nothing_is_held_or_rebased() = runBlocking {
        registered(y)
        CollectionScope.enterAccount(context, y, adoptDevice = false)
        LastSyncStore.markInitialRestoreComplete(context, y)
        server.seed(y, a, Reaction.DISLIKED) // appeared after the restore, from elsewhere
        dao.like(a, "Artist", "Title", "myata", 1_000L, 1_000L) // no baseline for it here

        assertTrue(pull() is PullResult.Completed)
        assertNull("a later pull does not rebase", dao.find(a)!!.remoteRev)

        assertTrue(drainAs(y) is DrainResult.Drained)
        assertEquals("the guard judges it as before", 1, server.conflicts)
        assertEquals(Reaction.DISLIKED, server.row(y, a)!!.reaction)
    }

    // ==================== GUEST: pre-sign-in state never overwrites the account ====================

    @Test
    fun guest_a_like_the_account_lacks_is_adopted() = runBlocking {
        dao.like(a, "Artist", "Title", "myata", 1_000L, 1_000L) // as a guest, offline

        assertEquals(AuthResult.Success(y), signIn(y))
        assertEquals("adopted with the expected-absent baseline", CollectionScope.ADOPTED_BASELINE, dao.find(a)!!.remoteRev)
        assertTrue(pull() is PullResult.Completed)
        assertTrue(drainAs(y) is DrainResult.Drained)

        assertEquals(Reaction.LIKED, server.row(y, a)!!.reaction)
        assertEquals(Reaction.LIKED, dao.find(a)!!.reaction)
    }

    @Test
    fun guest_a_pending_like_never_overwrites_the_accounts_dislike() = runBlocking {
        server.seed(y, a, Reaction.DISLIKED)
        val held = server.row(y, a)!!
        dao.like(a, "Artist", "Title", "myata", 1_000L, 1_000L) // as a guest, offline

        assertEquals(AuthResult.Success(y), signIn(y))
        assertTrue(pull() is PullResult.Completed)
        assertEquals("the initial restore does not rebase guest state", CollectionScope.ADOPTED_BASELINE, dao.find(a)!!.remoteRev)
        assertTrue(drainAs(y) is DrainResult.Drained)

        assertEquals("the account's reaction stands", held, server.row(y, a))
        assertEquals("and wins locally", Reaction.DISLIKED, dao.find(a)!!.reaction)
        assertEquals(0, outbox.count())
    }

    @Test
    fun guest_a_saved_favourite_never_overwrites_the_accounts_dislike() = runBlocking {
        server.seed(y, a, Reaction.DISLIKED)
        val held = server.row(y, a)!!
        legacy(a) // a guest's favourite with no pending act
        legacy(b) // and one the account lacks

        assertEquals(AuthResult.Success(y), signIn(y))
        assertTrue(pull() is PullResult.Completed)

        assertEquals(held, server.row(y, a))
        assertEquals(Reaction.DISLIKED, dao.find(a)!!.reaction)
        assertEquals("the absent one is adopted", Reaction.LIKED, server.row(y, b)!!.reaction)
    }

    @Test
    fun guest_an_anonymous_handoff_never_overwrites_the_accounts_dislike() = runBlocking {
        val x = "11111111-1111-4111-8111-111111111111"
        server.seed(y, a, Reaction.DISLIKED)
        val held = server.row(y, a)!!
        legacy(a)
        legacy(b)
        IdentityStore.adoptAnonymous(context, x)

        assertEquals(AuthResult.Success(y), signIn(y))

        assertEquals("the account's reaction stands", held, server.row(y, a))
        assertEquals("the absent one is adopted", Reaction.LIKED, server.row(y, b)!!.reaction)
        assertTrue(pull() is PullResult.Completed)
        assertEquals(Reaction.DISLIKED, dao.find(a)!!.reaction)
    }

    // ==================== F. registered without a session ====================

    @Test
    fun f_a_registered_install_with_no_session_can_sign_in_again() = runBlocking {
        legacy(a)
        IdentityStore.markRegistered(context, y)
        CollectionScope.enterAccount(context, y, adoptDevice = false)
        auth.session = null // the token is gone

        assertEquals(AuthResult.Success(y), signIn(y))

        assertEquals(IdentityState.Registered(y), IdentityStore.state(context))
        assertEquals("the same account resumes its Collection", listOf(a), liked())
    }

    @Test
    fun f_and_another_account_from_there_parks_ys_rows_like_any_other_switch() = runBlocking {
        legacy(a)
        IdentityStore.markRegistered(context, y)
        CollectionScope.enterAccount(context, y, adoptDevice = false)
        auth.session = null

        assertEquals(AuthResult.Success(z), signIn(z))

        assertEquals(IdentityState.Registered(z), IdentityStore.state(context))
        assertTrue(liked().isEmpty())
        assertEquals(1, scopes().parkedReactionCount(Scope.Account(y).key))
    }

    @Test
    fun f_a_registered_install_with_its_session_is_still_refused() = runBlocking {
        registered(y)

        assertTrue(signIn(z) is AuthResult.Failed)
        assertEquals(IdentityState.Registered(y), IdentityStore.state(context))
        assertTrue("no authentication was attempted", auth.signIns.isEmpty())
    }

    // ==================== I. the ordinary Like path ====================

    @Test
    fun i_like_and_unlike_still_travel_through_the_outbox() = runBlocking {
        registered(y)
        CollectionScope.enterAccount(context, y, adoptDevice = false)
        LastSyncStore.markInitialRestoreComplete(context, y)

        dao.like(a, "Artist", "Title", "myata", 1_000L, 1_000L)
        assertEquals(1, outbox.count())
        assertTrue(drainAs(y) is DrainResult.Drained)
        assertEquals(listOf(y to a), server.batches)
        assertEquals(Reaction.LIKED, server.row(y, a)!!.reaction)
        assertEquals(server.row(y, a)!!.rev, dao.find(a)!!.remoteRev)

        dao.unlike(a)
        assertTrue(drainAs(y) is DrainResult.Drained)
        assertEquals(Reaction.NEUTRAL, server.row(y, a)!!.reaction)
        assertTrue("the ordinary path never uses insert-if-absent", server.inserts.isEmpty())
    }

    @Test
    fun i_a_guest_collection_still_drains_as_whatever_identity_the_boundary_resolves() = runBlocking {
        dao.like(a, "Artist", "Title", "myata", 1_000L, 1_000L)

        assertTrue(drain { ListenerIdentity.Available("anonymous-x") } is DrainResult.Drained)
        assertEquals(listOf("anonymous-x" to a), server.batches)
        assertEquals(Scope.Device, CollectionScope.activeScope(context))
    }

    // ==================== migration ====================

    @Test
    fun a_migration_from_v4_keeps_every_row_and_attributes_nothing() = runBlocking {
        val name = "collection_scope_migration_test.db"
        context.deleteDatabase(name)
        val raw = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null)
        raw.execSQL(
            "CREATE TABLE IF NOT EXISTS `track_reaction` (`track_key` TEXT NOT NULL, `artist` TEXT NOT NULL, " +
                "`title` TEXT NOT NULL, `stream` TEXT NOT NULL, `reaction` TEXT NOT NULL, `liked_at` INTEGER, " +
                "`updated_at` INTEGER NOT NULL, `remote_rev` INTEGER, PRIMARY KEY(`track_key`))"
        )
        raw.execSQL(
            "CREATE TABLE IF NOT EXISTS `reaction_outbox` (`event_id` TEXT NOT NULL, `track_key` TEXT NOT NULL, " +
                "`artist` TEXT NOT NULL, `title` TEXT NOT NULL, `stream` TEXT NOT NULL, `event_type` TEXT NOT NULL, " +
                "`occurred_at` INTEGER NOT NULL, `attempts` INTEGER NOT NULL, `next_attempt_at` INTEGER NOT NULL, " +
                "`sync_protocol` TEXT NOT NULL DEFAULT 'LEGACY', PRIMARY KEY(`event_id`))"
        )
        raw.execSQL(ReactionMigration.CREATE_REACTION_OUTBOX_INDEX)
        raw.execSQL(
            "INSERT INTO track_reaction VALUES (?, 'Artist', 'Title', 'myata', 'LIKED', 1000, 1000, NULL)",
            arrayOf<Any?>(a),
        )
        raw.version = 4
        raw.close()

        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(ReactionMigration.MIGRATION_4_5)
            .build()
        try {
            assertEquals(listOf(a), migrated.reactionDao().allReactions().map { it.trackKey })
            assertEquals("marked, not attributed", CollectionScope.MIGRATED, migrated.collectionScopeDao().scope())
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    // ==================== helpers ====================

    private suspend fun signIn(uid: String): AuthResult {
        auth.uid = uid
        return EmailAuthRepository.signIn(context, "listener@example.com", "s3cret!")
    }

    /** A REGISTERED install with a live session for [uid]. */
    private fun registered(uid: String) {
        IdentityStore.markRegistered(context, uid)
        auth.session = uid
    }

    /** The production pull, retried past a background drain holding the lease. */
    private suspend fun pull(): PullResult {
        assumeTrue("the production pull needs a configured Supabase build", SupabaseConfig.isConfigured)
        repeat(20) {
            val result = ReactionPull.run(context)
            if (result !is PullResult.Busy) return result
            delay(100)
        }
        error("the lease never came free")
    }

    private suspend fun drainAs(uid: String) = drain { ListenerIdentity.Available(uid) }

    /** A drain asking the real [CollectionScope], retried past a background one. */
    private suspend fun drain(identity: suspend () -> ListenerIdentity): DrainResult {
        repeat(20) {
            val result = ReactionSyncEngine(
                reactions = dao,
                outbox = outbox,
                api = server,
                identity = identity,
                deletionInFlight = { false },
                delivery = { CollectionScope.deliveryHoldingLease(context) },
            ).drain()
            if (result !is DrainResult.HandoffInProgress) return result
            delay(100)
        }
        error("the lease never came free")
    }

    private fun scopes() = db.collectionScopeDao()

    /** Ends every backoff, so the next drain may send what a failed one parked. */
    private fun makeDue() {
        db.openHelper.writableDatabase.execSQL("UPDATE reaction_outbox SET next_attempt_at = 0")
    }

    private val d = key(4)

    /**
     * Track A as an account's device knows it after a sync: the server holds [state]
     * at some revision, and this device holds the same state at that revision - the
     * baseline its next offline acts will be judged against. Registered as Y.
     */
    private suspend fun syncedAccountTrack(state: Reaction): Long {
        registered(y)
        CollectionScope.enterAccount(context, y, adoptDevice = false)
        LastSyncStore.markInitialRestoreComplete(context, y)
        server.seed(y, a, state)
        val rev = server.row(y, a)!!.rev
        confirmed(a, state, rev)
        return rev
    }

    /** The database as `MIGRATION_4_5` leaves it, on an install of the given provenance. */
    private suspend fun migratedFromV4(provenance: CollectionScope.Provenance) {
        scopes().setScope(CollectionScope.MIGRATED)
        CollectionScope.provenance = { provenance }
    }

    /** What clearing the app's data leaves: nothing local at all. */
    private fun clearLocalData() {
        db.clearAllTables()
        IdentityStore.clearForTest(context)
        LastSyncStore.clearForTest(context)
        auth.session = null
    }

    /** A row as `MIGRATION_1_2` leaves it: LIKED, no revision, no outbox event. */
    private fun legacy(key: String, likedAt: Long = 1_000L, updatedAt: Long = likedAt, artist: String = "Artist") {
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO track_reaction (track_key, artist, title, stream, reaction, liked_at, updated_at, remote_rev) " +
                "VALUES (?, ?, 'Title', 'myata', 'LIKED', ?, ?, NULL)",
            arrayOf<Any?>(key, artist, likedAt, updatedAt),
        )
    }

    /** A row some server has confirmed at [rev]. */
    private fun confirmed(key: String, reaction: Reaction, rev: Long) {
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO track_reaction (track_key, artist, title, stream, reaction, liked_at, updated_at, remote_rev) " +
                "VALUES (?, 'Artist', 'Title', 'myata', ?, ?, 1000, ?)",
            arrayOf<Any?>(key, reaction.name, if (reaction == Reaction.LIKED) 1_000L else null, rev),
        )
    }

    private suspend fun liked(): List<String> =
        dao.allReactions().filter { it.reaction == Reaction.LIKED }.map { it.trackKey }

    private fun key(index: Int) = "%064x".format(index)
}

/**
 * `public.reactions` in memory: one map per account, revisions from one sequence.
 *
 * Insert-if-absent inserts only when the account holds no row for the track, and an
 * applied batch writes current state at a fresh revision - the two server behaviours
 * the Collection's durability rests on.
 */
private class FakeServer : ReactionSyncApi {

    private val lock = Any()
    private var rev = 0L
    private val accounts = linkedMapOf<String, MutableMap<String, RemoteReaction>>()

    val inserts = mutableListOf<Pair<String, String>>()

    /** Rows each statement actually inserted, in order. */
    val insertedCounts = mutableListOf<Int>()
    var statements = 0
    val batches = mutableListOf<Pair<String, String>>()

    @Volatile
    var failFetch = false

    fun row(listener: String, key: String): RemoteReaction? = synchronized(lock) { accounts[listener]?.get(key) }

    fun liked(listener: String): Set<String> = synchronized(lock) {
        accounts[listener].orEmpty().values.filter { it.reaction == Reaction.LIKED }.map { it.trackKey }.toSet()
    }

    fun snapshot(listener: String): Map<String, RemoteReaction> = synchronized(lock) { accounts[listener].orEmpty().toMap() }

    fun seed(listener: String, key: String, reaction: Reaction) = synchronized(lock) {
        accounts.getOrPut(listener) { linkedMapOf() }[key] = RemoteReaction(
            trackKey = key,
            reaction = reaction,
            likedAt = if (reaction == Reaction.LIKED) 500L else null,
            artist = "Artist",
            title = "Title",
            stream = "myata",
            updatedAt = 500L,
            rev = ++rev,
        )
    }

    /** Refused like the server's length check refuses an empty artist. */
    @Volatile
    var refuseEmptyArtist = true

    override suspend fun insertIfAbsent(rows: List<TrackReaction>, listenerId: String): InsertOutcome =
        synchronized(lock) {
            statements++
            if (refuseEmptyArtist && rows.any { it.artist.isEmpty() }) {
                return InsertOutcome.Failed(SyncOutcome.Permanent(400, "23514"))
            }
            val account = accounts.getOrPut(listenerId) { linkedMapOf() }
            val inserted = mutableSetOf<String>()
            for (row in rows) {
                inserts += listenerId to row.trackKey
                if (row.trackKey !in account) {
                    account[row.trackKey] = row.asRemote(++rev)
                    inserted += row.trackKey
                }
            }
            insertedCounts += inserted.size
            InsertOutcome.Written(inserted)
        }

    override suspend fun fetchReactionsPage(listenerId: String, afterRev: Long, limit: Int): PullPage =
        synchronized(lock) {
            if (failFetch) return PullPage.Failed(SyncOutcome.Transient("network"))
            PullPage.Rows(
                accounts[listenerId].orEmpty().values.filter { it.rev > afterRev }.sortedBy { it.rev }.take(limit)
            )
        }

    /** event id -> (revision, whether its effect is that revision's state), as 0005 marks them. */
    private val applied = mutableMapOf<String, Pair<Long, Boolean>>()

    var conflicts = 0

    /** Applies the next batch and then answers as a dropped connection would. */
    @Volatile
    var loseNextAnswer = false

    /** Runs inside the call, after the snapshot was taken - a tap mid-flight. */
    @Volatile
    var duringApply: (suspend () -> Unit)? = null

    /**
     * Migration 0005's apply, in memory: already applied -> ALREADY_APPLIED; the row
     * absent, at the batch's baseline, or at a revision this batch's own events
     * produced -> APPLIED; anything else -> CONFLICT, events marked, state untouched.
     */
    override suspend fun applyBatch(
        trackKey: String,
        events: List<ReactionOutboxEntry>,
        current: TrackReaction,
        listenerId: String,
    ): BatchOutcome {
        duringApply?.invoke()
        val outcome = synchronized(lock) {
            batches += listenerId to trackKey
            val account = accounts.getOrPut(listenerId) { linkedMapOf() }
            val row = account[trackKey]

            if (events.all { it.eventId in applied }) return@synchronized BatchOutcome.AlreadyApplied(row)

            val ownRev = events.mapNotNull { applied[it.eventId]?.takeIf { mark -> mark.second }?.first }.maxOrNull()
            if (row != null && row.rev != current.remoteRev && row.rev != ownRev) {
                conflicts++
                for (event in events) applied.putIfAbsent(event.eventId, row.rev to false)
                return@synchronized BatchOutcome.Conflict(row)
            }

            val written = current.asRemote(++rev)
            account[trackKey] = written
            for (event in events) applied.putIfAbsent(event.eventId, written.rev to true)
            BatchOutcome.Applied(written)
        }
        if (loseNextAnswer) {
            loseNextAnswer = false
            return BatchOutcome.Failed(SyncOutcome.Transient("the answer was lost"))
        }
        return outcome
    }

    override suspend fun deliverEvent(entry: ReactionOutboxEntry, listenerId: String): SyncOutcome =
        SyncOutcome.Success

    override suspend fun reconcileCurrentState(
        trackKey: String,
        current: TrackReaction?,
        listenerId: String,
    ): SyncOutcome = synchronized(lock) {
        val account = accounts.getOrPut(listenerId) { linkedMapOf() }
        if (current == null) account.remove(trackKey) else account[trackKey] = current.asRemote(++rev)
        SyncOutcome.Success
    }

    override suspend fun retireAllCurrentState(listenerId: String): SyncOutcome = synchronized(lock) {
        accounts.remove(listenerId)
        SyncOutcome.Success
    }
}
