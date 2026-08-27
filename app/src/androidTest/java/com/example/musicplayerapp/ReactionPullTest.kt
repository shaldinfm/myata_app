package com.example.musicplayerapp

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayerapp.data.AppDatabase
import com.example.musicplayerapp.data.Reaction
import com.example.musicplayerapp.data.ReactionDao
import com.example.musicplayerapp.data.ReactionOutboxDao
import com.example.musicplayerapp.data.ReactionOutboxEntry
import com.example.musicplayerapp.data.Streams
import com.example.musicplayerapp.data.SyncProtocol
import com.example.musicplayerapp.data.TrackReaction
import com.example.musicplayerapp.data.supabase.BatchOutcome
import com.example.musicplayerapp.data.supabase.LastSyncStore
import com.example.musicplayerapp.data.supabase.PullIdentity
import com.example.musicplayerapp.data.supabase.PullPage
import com.example.musicplayerapp.data.supabase.PullResult
import com.example.musicplayerapp.data.supabase.ReactionPullEngine
import com.example.musicplayerapp.data.supabase.ReactionSyncApi
import com.example.musicplayerapp.data.supabase.ReactionSyncEngine
import com.example.musicplayerapp.data.supabase.RemoteReaction
import com.example.musicplayerapp.data.supabase.SyncLease
import com.example.musicplayerapp.data.supabase.SyncOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reading the account back, on a real database.
 *
 * The pull is the half of G-A7 that makes an account mean something on a second
 * device, and almost everything that can go wrong with it is a question of
 * precedence: what happens when the server and this phone disagree, and which of them
 * was speaking more recently.
 *
 * Two rules answer nearly all of it, and both are asserted here rather than argued:
 *
 * **A pending local act always wins.** An outbox row means the listener did something
 * the server has not been told, so the remote row predates it by definition.
 *
 * **Absence is not an opinion.** A track the server never mentions is left alone. Only
 * a stored NEUTRAL - a row with its own revision - clears a stale local LIKED.
 */
@RunWith(AndroidJUnit4::class)
class ReactionPullTest {

    private lateinit var db: AppDatabase
    private lateinit var reactions: ReactionDao
    private lateinit var outbox: ReactionOutboxDao

    private val listener = "11111111-1111-4111-8111-111111111111"
    private val stranger = "22222222-2222-4222-8222-222222222222"
    private val trackA = "a".repeat(64)
    private val trackB = "b".repeat(64)
    private val trackC = "c".repeat(64)

    @Before
    fun open() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        reactions = db.reactionDao()
        outbox = db.reactionOutboxDao()
    }

    @After
    fun close() {
        if (::db.isInitialized) db.close()
    }

    // ==================== eligibility ====================

    /** **A.** REGISTERED(X) with a session for X reads the account. */
    @Test
    fun a_a_matching_session_is_eligible() = runBlocking {
        val api = FakeRemote(remoteLiked(trackA, rev = 10))

        val result = pull(api)

        assertTrue("$result", result is PullResult.Completed)
        assertEquals(1, api.requests.size)
        assertEquals(Reaction.LIKED, reactions.find(trackA)!!.reaction)
    }

    /** **B.** No session: nothing is fetched at all. */
    @Test
    fun b_no_session_reads_nothing() = runBlocking {
        val api = FakeRemote(remoteLiked(trackA, rev = 10))

        val result = pull(api, identity = PullIdentity.Unavailable("no restored session"))

        assertTrue("$result", result is PullResult.AuthUnavailable)
        assertEquals("the network must not be touched", 0, api.requests.size)
        assertNull(reactions.find(trackA))
    }

    /** **C.** A session for somebody else fails closed, before any read. */
    @Test
    fun c_a_foreign_session_fails_closed() = runBlocking {
        val api = FakeRemote(remoteLiked(trackA, rev = 10))

        val result = pull(
            api,
            identity = PullIdentity.Unavailable("the restored session is not this install's account"),
        )

        assertTrue("$result", result is PullResult.AuthUnavailable)
        assertEquals(0, api.requests.size)
        assertNull("no stranger's row may reach this Collection", reactions.find(trackA))
    }

    /** **D.** An install that is not an account has nothing to read back. */
    @Test
    fun d_a_non_registered_identity_is_not_eligible() = runBlocking {
        val api = FakeRemote(remoteLiked(trackA, rev = 10))

        val result = pull(api, identity = PullIdentity.NotEligible("identity is Anonymous"))

        assertTrue("$result", result is PullResult.NotEligible)
        assertEquals(0, api.requests.size)
    }

    // ==================== restore ====================

    /** **E.** An empty account completes and changes nothing. */
    @Test
    fun e_an_empty_account_completes_and_changes_nothing() = runBlocking {
        reactions.like(trackA, "A", "T", "myata", likedAt = 500L)
        val before = reactions.find(trackA)!!

        val result = pull(FakeRemote())

        assertTrue("$result", result is PullResult.Completed)
        assertEquals(0, (result as PullResult.Completed).applied)
        assertEquals(before, reactions.find(trackA))
    }

    /** **F and AF.** A LIKED row restores onto a device that has never seen the track. */
    @Test
    fun f_a_liked_row_restores_with_the_servers_own_liked_at() = runBlocking {
        val api = FakeRemote(
            RemoteReaction(trackA, Reaction.LIKED, likedAt = 1_700_000L, "Artist", "Title", "gold",
                updatedAt = 9_900_000L, rev = 42),
        )

        pull(api)

        val row = reactions.find(trackA)!!
        assertEquals(Reaction.LIKED, row.reaction)
        assertEquals(
            "liked_at is what orders a restored Collection and must be the server's own",
            1_700_000L,
            row.likedAt,
        )
        assertEquals("Artist", row.artist)
        assertEquals("Title", row.title)
        assertEquals("gold", row.stream)
        assertEquals(42L, row.remoteRev)
        // Proof it is not derived: updated_at is a different value and is not reused.
        assertFalse("liked_at must not come from updated_at", row.likedAt == row.updatedAt)
    }

    /** **G.** DISLIKED restores, and carries no liked_at. */
    @Test
    fun g_a_disliked_row_restores_without_a_liked_at() = runBlocking {
        pull(FakeRemote(remote(trackA, Reaction.DISLIKED, rev = 7)))

        val row = reactions.find(trackA)!!
        assertEquals(Reaction.DISLIKED, row.reaction)
        assertNull(row.likedAt)
        assertEquals(7L, row.remoteRev)
    }

    /**
     * **H.** A stored NEUTRAL clears a stale local LIKED - and with it the Collection
     * membership, which is derived from the reaction rather than kept separately.
     */
    @Test
    fun h_a_remote_neutral_clears_a_stale_local_liked() = runBlocking {
        reactions.like(trackA, "A", "T", "myata", likedAt = 500L)
        settle()
        assertTrue(collection().contains(trackA))

        pull(FakeRemote(remote(trackA, Reaction.NEUTRAL, rev = 8)))

        val row = reactions.find(trackA)!!
        assertEquals(Reaction.NEUTRAL, row.reaction)
        assertNull(row.likedAt)
        assertFalse("the Collection follows the reaction, with no second table", collection().contains(trackA))
    }

    /**
     * **I.** A track the scan never mentions is left exactly as it is.
     *
     * Absence is not NEUTRAL. The server having no row means it has no opinion, which
     * is a different thing from holding the opinion "no reaction" - and inferring a
     * deletion from silence would empty a Collection the moment a scan came back
     * short for any reason at all.
     */
    @Test
    fun i_absence_is_never_read_as_a_withdrawal() = runBlocking {
        reactions.like(trackA, "A", "T", "myata", likedAt = 500L)
        settle()
        val before = reactions.find(trackA)!!

        val result = pull(FakeRemote(remote(trackB, Reaction.LIKED, rev = 3)))

        assertTrue("$result", result is PullResult.Completed)
        assertEquals("the untouched track is untouched", before, reactions.find(trackA))
        assertTrue(collection().contains(trackA))
    }

    // ==================== pending local work wins ====================

    /** **J.** A pending LEGACY row holds its track against the server. */
    @Test
    fun j_a_pending_legacy_row_holds_the_track() = runBlocking {
        reactions.like(trackA, "A", "T", "myata", likedAt = 500L)
        makeLegacy()

        val result = pull(FakeRemote(remote(trackA, Reaction.NEUTRAL, rev = 99)))

        assertEquals(Reaction.LIKED, reactions.find(trackA)!!.reaction)
        assertNull("no watermark may be recorded either", reactions.find(trackA)!!.remoteRev)
        assertEquals(1, (result as PullResult.Completed).skippedPending)
    }

    /** **K.** So does a pending ATOMIC_RPC row. */
    @Test
    fun k_a_pending_atomic_row_holds_the_track() = runBlocking {
        reactions.like(trackA, "A", "T", "myata", likedAt = 500L)
        assertEquals(SyncProtocol.ATOMIC_RPC, outbox.pendingForTrack(trackA).single().syncProtocol)

        val result = pull(FakeRemote(remote(trackA, Reaction.NEUTRAL, rev = 99)))

        assertEquals(Reaction.LIKED, reactions.find(trackA)!!.reaction)
        assertEquals(1, (result as PullResult.Completed).skippedPending)
    }

    /** **L.** A parked row is still a pending act. Backoff is not consent. */
    @Test
    fun l_a_parked_row_still_holds_the_track() = runBlocking {
        reactions.like(trackA, "A", "T", "myata", likedAt = 500L)
        outbox.recordFailedAttempt(outbox.pendingForTrack(trackA).single().eventId, Long.MAX_VALUE)

        pull(FakeRemote(remote(trackA, Reaction.NEUTRAL, rev = 99)))

        assertEquals(Reaction.LIKED, reactions.find(trackA)!!.reaction)
    }

    /** **M.** ...and a track with nothing pending is unaffected by another's hold. */
    @Test
    fun m_an_unrelated_track_still_applies() = runBlocking {
        reactions.like(trackA, "A", "T", "myata", likedAt = 500L)   // pending
        reactions.like(trackB, "B", "U", "myata", likedAt = 500L)
        settle(trackB)                                               // nothing pending

        val result = pull(
            FakeRemote(
                remote(trackA, Reaction.NEUTRAL, rev = 98),
                remote(trackB, Reaction.DISLIKED, rev = 99),
            )
        )

        assertEquals(Reaction.LIKED, reactions.find(trackA)!!.reaction)
        assertEquals(Reaction.DISLIKED, reactions.find(trackB)!!.reaction)
        val completed = result as PullResult.Completed
        assertEquals(1, completed.applied)
        assertEquals(1, completed.skippedPending)
    }

    // ==================== the watermark ====================

    /** **N.** A higher revision applies. */
    @Test
    fun n_a_higher_revision_applies() = runBlocking {
        pull(FakeRemote(remote(trackA, Reaction.LIKED, rev = 10)))
        pull(FakeRemote(remote(trackA, Reaction.DISLIKED, rev = 11)))

        assertEquals(Reaction.DISLIKED, reactions.find(trackA)!!.reaction)
        assertEquals(11L, reactions.find(trackA)!!.remoteRev)
    }

    /** **O.** An equal revision is a no-op. */
    @Test
    fun o_an_equal_revision_is_a_no_op() = runBlocking {
        pull(FakeRemote(remote(trackA, Reaction.LIKED, rev = 10)))

        val result = pull(FakeRemote(remote(trackA, Reaction.DISLIKED, rev = 10)))

        assertEquals(Reaction.LIKED, reactions.find(trackA)!!.reaction)
        assertEquals(1, (result as PullResult.Completed).skippedStale)
    }

    /** **P.** A lower revision is a no-op - a stale page cannot regress local state. */
    @Test
    fun p_a_lower_revision_is_a_no_op() = runBlocking {
        pull(FakeRemote(remote(trackA, Reaction.LIKED, rev = 10)))

        val result = pull(FakeRemote(remote(trackA, Reaction.DISLIKED, rev = 4)))

        assertEquals(Reaction.LIKED, reactions.find(trackA)!!.reaction)
        assertEquals(10L, reactions.find(trackA)!!.remoteRev)
        assertEquals(1, (result as PullResult.Completed).skippedStale)
    }

    /**
     * **Q and R.** A row updated mid-scan is legitimately seen twice, and the higher
     * revision is what survives.
     *
     * This is not a defect to design around: an update moves a row *ahead* of the
     * cursor, which is exactly what stops a mid-scan change being missed. The
     * watermark is what makes the second visit safe.
     */
    @Test
    fun q_and_r_the_same_track_twice_in_one_scan_keeps_the_higher_revision() = runBlocking {
        val page1 = List(500) { remote(key(it), Reaction.LIKED, rev = (it + 1).toLong()) }
        // The first track, updated remotely while the scan was in flight.
        val page2 = listOf(remote(key(0), Reaction.DISLIKED, rev = 900))

        val result = pull(FakePagedRemote(listOf(page1, page2)))

        assertEquals(Reaction.DISLIKED, reactions.find(key(0))!!.reaction)
        assertEquals(900L, reactions.find(key(0))!!.remoteRev)
        assertEquals(2, (result as PullResult.Completed).pages)
    }

    // ==================== pagination ====================

    /** **S.** A full page means there may be more. */
    @Test
    fun s_a_full_page_asks_for_another() = runBlocking {
        val api = FakePagedRemote(listOf(List(500) { remote(key(it), Reaction.LIKED, rev = (it + 1).toLong()) }))

        val result = pull(api)

        assertEquals("a full page is never the last word", 2, api.requests.size)
        assertEquals(2, (result as PullResult.Completed).pages)
    }

    /** **T and U.** 501 rows are two pages, and the second asks by keyset. */
    @Test
    fun t_and_u_pagination_is_keyset_on_rev() = runBlocking {
        val page1 = List(500) { remote(key(it), Reaction.LIKED, rev = (it + 1).toLong()) }
        val page2 = listOf(remote(key(500), Reaction.LIKED, rev = 501))
        val api = FakePagedRemote(listOf(page1, page2))

        val result = pull(api)

        assertEquals(2, (result as PullResult.Completed).pages)
        assertEquals(501, result.fetched)
        assertEquals(
            "the cursor is the last revision seen, never an offset",
            listOf(0L, 500L),
            api.requests.map { it.afterRev },
        )
        assertTrue(api.requests.all { it.limit == 500 })
    }

    /** **V.** Every run starts at revision zero. No durable cursor exists. */
    @Test
    fun v_every_run_starts_from_zero() = runBlocking {
        val api = FakeRemote(remote(trackA, Reaction.LIKED, rev = 10))

        pull(api)
        pull(api)
        pull(api)

        assertEquals(
            "a persisted cursor would turn a sequence-visibility race into data loss",
            listOf(0L, 0L, 0L),
            api.requests.map { it.afterRev },
        )
    }

    /** **W and AH.** A failure mid-scan keeps earlier pages and records no success. */
    @Test
    fun w_a_failure_after_page_one_keeps_page_one_and_records_no_success() = runBlocking {
        val page1 = List(500) { remote(key(it), Reaction.LIKED, rev = (it + 1).toLong()) }
        val api = FakePagedRemote(listOf(page1), failAfter = 1)

        val result = pull(api)

        assertTrue("$result", result is PullResult.Transient)
        assertEquals("page one is applied and valid", 500, reactions.allReactions().size)

        // The retry starts at zero and is a no-op on everything it already has.
        val retry = pull(FakePagedRemote(listOf(page1)))
        assertTrue("$retry", retry is PullResult.Completed)
        assertEquals(0, (retry as PullResult.Completed).applied)
        assertEquals(500, retry.skippedStale)
    }

    // ==================== the gate ====================

    /**
     * **X and Z.** A tap during a fetch is not blocked, and it is the newer state.
     *
     * The gate is taken per page around local work only. If it were held across the
     * fetch, a listener tapping Like while a scan was in flight would wait on a
     * network round trip - which is the one thing the whole reaction path is built
     * never to do.
     */
    @Test
    fun x_and_z_a_tap_during_a_fetch_is_not_blocked_and_wins() = runBlocking {
        val inFlight = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val api = FakeRemote(remote(trackA, Reaction.NEUTRAL, rev = 5)).also {
            it.beforeAnswer = { inFlight.complete(Unit); release.await() }
        }

        val pulling = async(Dispatchers.IO) { pull(api) }
        withTimeout(10_000) { inFlight.await() }

        // Must not deadlock, and must complete while the fetch is outstanding.
        withTimeout(10_000) {
            withContext(Dispatchers.IO) { reactions.like(trackA, "A", "T", "myata", likedAt = 900L) }
        }
        release.complete(Unit)
        pulling.await()

        assertEquals(
            "the tap is the newer act, and the page that predates it must not win",
            Reaction.LIKED,
            reactions.find(trackA)!!.reaction,
        )
        assertEquals("and it is still owed", 1, outbox.countForTrack(trackA))
    }

    /** **Y.** A tap committed before the page is applied leaves a pending row that holds. */
    @Test
    fun y_a_tap_before_application_holds_the_track() = runBlocking {
        val inFlight = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val api = FakeRemote(remote(trackA, Reaction.NEUTRAL, rev = 5)).also {
            it.beforeAnswer = { inFlight.complete(Unit); release.await() }
        }

        val pulling = async(Dispatchers.IO) { pull(api) }
        withTimeout(10_000) { inFlight.await() }
        withContext(Dispatchers.IO) { reactions.like(trackA, "A", "T", "myata", likedAt = 900L) }
        release.complete(Unit)

        val result = pulling.await()

        assertEquals(Reaction.LIKED, reactions.find(trackA)!!.reaction)
        assertEquals(1, (result as PullResult.Completed).skippedPending)
        assertNull(reactions.find(trackA)!!.remoteRev)
    }

    // ==================== no push side effects ====================

    /** **AA and AB.** Applying a page enqueues nothing and invents no history. */
    @Test
    fun aa_and_ab_application_never_enqueues_anything() = runBlocking {
        val api = FakeRemote(
            remote(trackA, Reaction.LIKED, rev = 1),
            remote(trackB, Reaction.DISLIKED, rev = 2),
            remote(trackC, Reaction.NEUTRAL, rev = 3),
        )

        pull(api)

        assertEquals("adopting what the server holds is not an act", 0, outbox.count())
        assertEquals(0, api.batches)
        assertEquals(0, api.eventsDelivered)
        assertEquals(0, api.reconciles)
        assertEquals(0, api.retirements)
    }

    // ==================== exclusion ====================

    /** **AC and AD.** A pull cannot run while the lease is held by anything else. */
    @Test
    fun ac_and_ad_a_pull_never_overlaps_a_drain_or_a_handoff() = runBlocking {
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = async(Dispatchers.IO) {
            SyncLease.withExclusive { held.complete(Unit); release.await() }
        }
        withTimeout(10_000) { held.await() }

        val api = FakeRemote(remote(trackA, Reaction.LIKED, rev = 10))
        val result = pull(api)

        assertEquals(PullResult.Busy, result)
        assertEquals("nothing may be read while a drain or handoff owns the lease", 0, api.requests.size)
        assertNull(reactions.find(trackA))

        release.complete(Unit)
        holder.await()

        // And it works normally once the lease is free.
        assertTrue(pull(api) is PullResult.Completed)
    }

    // ==================== stream compatibility ====================

    /**
     * The absent-stream contract, in full.
     *
     * `reactions.stream` is nullable; `track_reaction.stream` is not, so a remote row
     * with no stream has to become something locally:
     *
     * ```
     * remote stream present     ->  use it
     * absent, local row exists  ->  keep the local stream
     * absent, no local row      ->  Streams.DEFAULT
     * ```
     *
     * The last is a legacy-compatibility normalisation rather than a faithful
     * representation of the NULL, and it follows the convention this project already
     * set: `ReactionMigration` maps an absent legacy `favorites.stream` the same way.
     * The empty string is deliberately not used - nothing reads it as "unknown", and
     * it would travel back out through the outbox on the listener's next tap.
     */

    /** **A.** Fresh local, absent remote stream: normalised, and the rest is intact. */
    @Test
    fun stream_a_a_fresh_restore_normalises_an_absent_stream() = runBlocking {
        pull(
            FakeRemote(
                RemoteReaction(trackA, Reaction.LIKED, likedAt = 1_700_000L, "Artist", "Title",
                    stream = null, updatedAt = 9_900_000L, rev = 42),
            )
        )

        val row = reactions.find(trackA)!!
        assertEquals(Streams.DEFAULT, row.stream)
        assertNotEquals("the empty string means nothing here", "", row.stream)
        assertEquals(Reaction.LIKED, row.reaction)
        assertEquals(1_700_000L, row.likedAt)
        assertEquals(42L, row.remoteRev)
    }

    /** **B.** An absent remote stream never erases one this device already knows. */
    @Test
    fun stream_b_an_absent_remote_stream_preserves_a_known_local_one() = runBlocking {
        reactions.like(trackA, "A", "T", "gold", likedAt = 500L)
        settle()

        pull(FakeRemote(remote(trackA, Reaction.LIKED, rev = 10, stream = null)))

        assertEquals(
            "a stream the device already knew is better evidence than an absent one",
            "gold",
            reactions.find(trackA)!!.stream,
        )
    }

    /**
     * **C and D.** The normalised value survives a later tap and both serializations.
     *
     * This is the case that made the empty string unacceptable: `unlike` and
     * `undislike` copy `existing.stream` into the outbox event, and both push paths
     * send it verbatim. A meaningless value restored here would leave the device
     * permanently and turn the server's NULL into something no other client writes.
     */
    @Test
    fun stream_c_and_d_the_normalised_value_survives_a_later_tap_and_both_pushes() = runBlocking {
        pull(FakeRemote(remote(trackA, Reaction.LIKED, rev = 42, stream = null)))

        // The listener taps. The event is built from the restored row.
        reactions.unlike(trackA)

        val event = outbox.pendingForTrack(trackA).single()
        assertEquals(Streams.DEFAULT, event.stream)
        assertNotEquals("", event.stream)

        // ATOMIC_RPC serialization: the batch carries the event and the current row.
        assertEquals(SyncProtocol.ATOMIC_RPC, event.syncProtocol)
        val push = CapturingPushApi()
        ReactionSyncEngine(
            reactions = reactions,
            outbox = outbox,
            api = push,
            identity = { com.example.musicplayerapp.data.supabase.ListenerIdentity.Available(listener) },
        ).drain()

        assertEquals(Streams.DEFAULT, push.currentStreams.single())
        assertEquals(listOf(Streams.DEFAULT), push.eventStreams)
        assertTrue(
            "nothing the restore produced may reach the wire as an empty string",
            push.currentStreams.none { it.isEmpty() } && push.eventStreams.none { it.isEmpty() },
        )

        // LEGACY serialization reads the same two sources - the outbox row's own
        // stream and the current TrackReaction - so both are asserted directly.
        assertEquals(Streams.DEFAULT, reactions.find(trackA)!!.stream)
    }

    /** **E.** A present remote stream still replaces the local value. */
    @Test
    fun stream_e_a_present_remote_stream_still_replaces_the_local_one() = runBlocking {
        reactions.like(trackA, "A", "T", "gold", likedAt = 500L)
        settle()

        pull(FakeRemote(remote(trackA, Reaction.LIKED, rev = 10, stream = "myata_hits")))

        assertEquals("myata_hits", reactions.find(trackA)!!.stream)
    }

    // ==================== success signal ====================

    /** **AG.** A completed scan reports itself exactly once, with usable counts. */
    @Test
    fun ag_a_completed_scan_reports_itself_once() = runBlocking {
        reactions.like(trackB, "B", "U", "myata", likedAt = 500L)   // pending, will hold

        val result = pull(
            FakeRemote(
                remote(trackA, Reaction.LIKED, rev = 1),
                remote(trackB, Reaction.NEUTRAL, rev = 2),
            )
        )

        val completed = result as PullResult.Completed
        assertEquals(listener, completed.uid)
        assertEquals(1, completed.pages)
        assertEquals(2, completed.fetched)
        assertEquals(1, completed.applied)
        assertEquals(1, completed.skippedPending)
        assertEquals(0, completed.skippedStale)
    }

    /**
     * **AG.** Upload and restore are two facts, kept apart.
     *
     * One install can have restored an account without ever pushing a reaction, and
     * another can have pushed without ever restoring. A single timestamp would make
     * the first indistinguishable from an install that has done neither - which is
     * exactly the false "never synchronised" the authenticated profile must stop
     * showing.
     */
    @Test
    fun ag_upload_and_pull_timestamps_are_never_collapsed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        LastSyncStore.clearForTest(context)
        try {
            assertNull(LastSyncStore.lastSuccessAt(context))
            assertNull(LastSyncStore.lastPullAt(context))

            LastSyncStore.recordPullSuccess(context, at = 5_000L)
            assertEquals(5_000L, LastSyncStore.lastPullAt(context))
            assertNull("a restore is not an upload", LastSyncStore.lastSuccessAt(context))

            LastSyncStore.recordSuccess(context, at = 9_000L)
            assertEquals(9_000L, LastSyncStore.lastSuccessAt(context))
            assertEquals("and an upload does not move the restore", 5_000L, LastSyncStore.lastPullAt(context))
        } finally {
            LastSyncStore.clearForTest(context)
        }
    }

    // ==================== helpers ====================

    private suspend fun pull(
        api: ReactionSyncApi,
        identity: PullIdentity = PullIdentity.Eligible(listener),
    ): PullResult = ReactionPullEngine(
        reactions = reactions,
        outbox = outbox,
        api = api,
        eligibility = { identity },
        transaction = { block -> db.withTransaction(block) },
    ).pull()

    private fun key(index: Int) = "%064x".format(index + 1)

    private fun remote(
        track: String,
        reaction: Reaction,
        rev: Long,
        stream: String? = "myata",
    ) = RemoteReaction(
        trackKey = track,
        reaction = reaction,
        likedAt = if (reaction == Reaction.LIKED) 1_000L else null,
        artist = "Artist",
        title = "Title",
        stream = stream,
        updatedAt = 2_000L,
        rev = rev,
    )

    private fun remoteLiked(track: String, rev: Long) = remote(track, Reaction.LIKED, rev)

    /** Empties the outbox the way a successful push would, without pushing. */
    private suspend fun settle(track: String? = null) {
        val rows = if (track == null) outbox.pending() else outbox.pendingForTrack(track)
        outbox.deleteAll(rows.map { it.eventId })
    }

    /** Rewrites pending rows as pre-cutover ones. See `SyncProtocolCutoverTest`. */
    private fun makeLegacy() {
        db.openHelper.writableDatabase.execSQL(
            "UPDATE reaction_outbox SET sync_protocol = 'LEGACY'"
        )
    }

    private suspend fun collection(): List<String> =
        reactions.allReactions().filter { it.reaction == Reaction.LIKED }.map { it.trackKey }
}

// ==================== fakes ====================

private data class PageRequest(val listenerId: String, val afterRev: Long, val limit: Int)

/** One page of rows, and a count of every push call that must never happen. */
private open class FakeRemote(private vararg val rows: RemoteReaction) : ReactionSyncApi {

    val requests = mutableListOf<PageRequest>()
    var beforeAnswer: suspend () -> Unit = {}

    var batches = 0
    var eventsDelivered = 0
    var reconciles = 0
    var retirements = 0

    override suspend fun fetchReactionsPage(
        listenerId: String,
        afterRev: Long,
        limit: Int,
    ): PullPage {
        requests += PageRequest(listenerId, afterRev, limit)
        beforeAnswer()
        return PullPage.Rows(rows.filter { it.rev > afterRev })
    }

    override suspend fun applyBatch(
        trackKey: String,
        events: List<ReactionOutboxEntry>,
        current: TrackReaction,
        listenerId: String,
    ): BatchOutcome {
        batches++
        return BatchOutcome.Failed(SyncOutcome.Permanent(500, "a pull must never push"))
    }

    override suspend fun deliverEvent(entry: ReactionOutboxEntry, listenerId: String): SyncOutcome {
        eventsDelivered++
        return SyncOutcome.Success
    }

    override suspend fun reconcileCurrentState(
        trackKey: String,
        current: TrackReaction?,
        listenerId: String,
    ): SyncOutcome {
        reconciles++
        return SyncOutcome.Success
    }

    override suspend fun retireAllCurrentState(listenerId: String): SyncOutcome {
        retirements++
        return SyncOutcome.Success
    }
}

/**
 * Hands back prepared pages in order, so pagination can be asserted exactly.
 *
 * [failAfter] makes the scan fail on the request after that many pages, which is the
 * mid-scan failure a real network produces.
 */
private class FakePagedRemote(
    private val pages: List<List<RemoteReaction>>,
    private val failAfter: Int = Int.MAX_VALUE,
) : FakeRemote() {

    private var served = 0

    override suspend fun fetchReactionsPage(
        listenerId: String,
        afterRev: Long,
        limit: Int,
    ): PullPage {
        requests += PageRequest(listenerId, afterRev, limit)
        if (served >= failAfter) return PullPage.Failed(SyncOutcome.Transient("network"))
        val page = pages.getOrNull(served) ?: emptyList()
        served++
        return PullPage.Rows(page)
    }
}

/** Records exactly what an atomic push would put on the wire. */
private class CapturingPushApi : ReactionSyncApi {

    val currentStreams = mutableListOf<String>()
    val eventStreams = mutableListOf<String>()

    override suspend fun applyBatch(
        trackKey: String,
        events: List<ReactionOutboxEntry>,
        current: TrackReaction,
        listenerId: String,
    ): BatchOutcome {
        currentStreams += current.stream
        eventStreams += events.map { it.stream }
        return BatchOutcome.Applied(
            RemoteReaction(
                trackKey = current.trackKey,
                reaction = current.reaction,
                likedAt = if (current.reaction == Reaction.LIKED) current.likedAt else null,
                artist = current.artist,
                title = current.title,
                stream = current.stream,
                updatedAt = current.updatedAt,
                rev = 999L,
            )
        )
    }

    override suspend fun deliverEvent(entry: ReactionOutboxEntry, listenerId: String): SyncOutcome {
        eventStreams += entry.stream
        return SyncOutcome.Success
    }

    override suspend fun reconcileCurrentState(
        trackKey: String,
        current: TrackReaction?,
        listenerId: String,
    ): SyncOutcome {
        current?.let { currentStreams += it.stream }
        return SyncOutcome.Success
    }

    override suspend fun retireAllCurrentState(listenerId: String) = SyncOutcome.Success

    override suspend fun fetchReactionsPage(listenerId: String, afterRev: Long, limit: Int): PullPage =
        throw AssertionError("the push must not pull")
}
