package com.example.musicplayerapp

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayerapp.data.AppDatabase
import com.example.musicplayerapp.data.Reaction
import com.example.musicplayerapp.data.ReactionDao
import com.example.musicplayerapp.data.ReactionEvent
import com.example.musicplayerapp.data.ReactionOutboxDao
import com.example.musicplayerapp.data.ReactionOutboxEntry
import com.example.musicplayerapp.data.SyncProtocol
import com.example.musicplayerapp.data.TrackReaction
import com.example.musicplayerapp.data.supabase.BatchOutcome
import com.example.musicplayerapp.data.supabase.ListenerIdentity
import com.example.musicplayerapp.data.supabase.ReactionSyncApi
import com.example.musicplayerapp.data.supabase.ReactionSyncEngine
import com.example.musicplayerapp.data.supabase.RemoteReaction
import com.example.musicplayerapp.data.supabase.SyncOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The G-A7 protocol cutover, on a real database.
 *
 * Two properties are being defended here and they are easy to confuse.
 *
 * **The epoch.** Rows written before the cutover were delivered by a protocol with no
 * application log, and the server refuses an event it has seen but never marked. So
 * those rows finish the way they started, and - because the legacy path publishes the
 * *current* row rather than the event's own state - every reaction made while such a
 * row is still owed has to be legacy too. Otherwise an atomic event's effect could
 * reach the cloud through a legacy write that marks nothing, and the server would
 * later see a genuinely unapplied event whose effect had already been published.
 *
 * **The batch.** After the cutover the unit of delivery is a track, not an event: the
 * current state carries every pending event's effect, so they must all be marked by
 * the transaction that publishes it.
 */
@RunWith(AndroidJUnit4::class)
class SyncProtocolCutoverTest {

    private lateinit var db: AppDatabase
    private lateinit var reactions: ReactionDao
    private lateinit var outbox: ReactionOutboxDao

    private val listener = "11111111-1111-4111-8111-111111111111"
    private val trackA = "a".repeat(64)
    private val trackB = "b".repeat(64)

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

    // ==================== the epoch ====================

    /** **B.** Nothing pending: a new reaction is on the new protocol. */
    @Test
    fun a_fresh_track_mints_atomic_rows() = runBlocking {
        like(trackA)
        assertEquals(listOf(SyncProtocol.ATOMIC_RPC), protocols(trackA))
    }

    /** **C.** A pending legacy row makes the next reaction on that track legacy too. */
    @Test
    fun a_pending_legacy_row_makes_the_next_reaction_legacy() = runBlocking {
        seedLegacy(trackA, ReactionEvent.LIKE)

        dislike(trackA)

        assertEquals(
            "the epoch has to extend, or an atomic event's effect could be " +
                "published by the legacy write that marks nothing",
            listOf(SyncProtocol.LEGACY, SyncProtocol.LEGACY),
            protocols(trackA),
        )
    }

    /** **D.** The epoch is per track. A different track is unaffected. */
    @Test
    fun the_epoch_is_per_track() = runBlocking {
        seedLegacy(trackA, ReactionEvent.LIKE)

        dislike(trackA)
        like(trackB)

        assertEquals(listOf(SyncProtocol.LEGACY, SyncProtocol.LEGACY), protocols(trackA))
        assertEquals(listOf(SyncProtocol.ATOMIC_RPC), protocols(trackB))
    }

    /** **E.** No track can ever hold both protocols at once. */
    @Test
    fun a_track_never_holds_mixed_protocols() = runBlocking {
        seedLegacy(trackA, ReactionEvent.LIKE)

        // Every route in and out of every state, on both tracks, interleaved.
        dislike(trackA); undislike(trackA); like(trackA); unlike(trackA)
        like(trackB); unlike(trackB); dislike(trackB)

        for (track in listOf(trackA, trackB)) {
            assertEquals(
                "mixed protocols on $track",
                1,
                protocols(track).toSet().size,
            )
        }
    }

    /**
     * **F and G.** The two orders around the final legacy delete, and only those two.
     *
     * The delete and the protocol choice both take [com.example.musicplayerapp.data.ReactionWriteGate],
     * so a tap either sees the row and inherits LEGACY, or the delete has already
     * committed - and with it the remote write that preceded it - and the tap is
     * atomic. There is no third order.
     */
    @Test
    fun f_tap_before_the_final_legacy_delete_is_legacy() = runBlocking {
        val row = seedLegacy(trackA, ReactionEvent.LIKE)

        // Tap first, delete after.
        dislike(trackA)
        outbox.delete(row.eventId)

        assertEquals(listOf(SyncProtocol.LEGACY), protocols(trackA))
    }

    @Test
    fun g_tap_after_the_final_legacy_delete_is_atomic() = runBlocking {
        val row = seedLegacy(trackA, ReactionEvent.LIKE)

        // Delete first, tap after.
        outbox.delete(row.eventId)
        dislike(trackA)

        assertEquals(listOf(SyncProtocol.ATOMIC_RPC), protocols(trackA))
    }

    /**
     * **H.** A death after the remote write but before the local delete leaves the
     * row pending - so the epoch survives, which is the safe answer.
     */
    @Test
    fun h_a_legacy_row_that_was_delivered_but_not_deleted_keeps_the_epoch() = runBlocking {
        seedLegacy(trackA, ReactionEvent.LIKE)
        // The remote half happened; the process died before the delete. Nothing local
        // records that, and nothing should: the row is still owed as far as this
        // device can tell.
        dislike(trackA)

        assertEquals(listOf(SyncProtocol.LEGACY, SyncProtocol.LEGACY), protocols(trackA))
    }

    /** **I.** A parked legacy row holds its own track and no other. */
    @Test
    fun i_a_parked_legacy_row_holds_only_its_own_track() = runBlocking {
        val row = seedLegacy(trackA, ReactionEvent.LIKE)
        outbox.recordFailedAttempt(row.eventId, Long.MAX_VALUE)

        dislike(trackA)
        like(trackB)

        assertEquals(listOf(SyncProtocol.LEGACY, SyncProtocol.LEGACY), protocols(trackA))
        assertEquals(listOf(SyncProtocol.ATOMIC_RPC), protocols(trackB))
    }

    // ==================== the batch ====================

    /** **J and K.** The batch is the whole pending set, backoff included. */
    @Test
    fun j_and_k_the_batch_carries_every_pending_event_for_the_track() = runBlocking {
        like(trackA)
        unlike(trackA)
        dislike(trackA)

        // One sibling is parked far in the future. It must still travel.
        val parked = outbox.pendingForTrack(trackA).first()
        outbox.recordFailedAttempt(parked.eventId, Long.MAX_VALUE)

        val backend = FakeAtomicBackend()
        drain(backend)

        assertEquals(1, backend.batches.size)
        val (track, ids) = backend.batches.single()
        assertEquals(trackA, track)
        assertEquals(
            "a sibling left behind would later look genuinely unapplied",
            3,
            ids.size,
        )
        assertTrue(ids.contains(parked.eventId))
        assertEquals("the batch settles as a unit", 0, outbox.count())
    }

    /**
     * **L and M.** The gate is released before the network call, so a tap during the
     * round trip lands - and lands on the far side of the snapshot.
     */
    @Test
    fun l_and_m_a_tap_during_the_rpc_is_a_later_pending_mutation() = runBlocking {
        like(trackA)

        val inFlight = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val backend = FakeAtomicBackend().also {
            it.beforeAnswer = { inFlight.complete(Unit); release.await() }
        }

        val drain = async(Dispatchers.IO) { drain(backend) }
        withTimeout(10_000) { inFlight.await() }

        // The gate is not held: this must not deadlock, and it must complete.
        withTimeout(10_000) { withContext(Dispatchers.IO) { dislike(trackA) } }
        release.complete(Unit)
        drain.await()

        val left = outbox.pendingForTrack(trackA)
        assertEquals("the tap survives the settlement", 1, left.size)
        assertEquals(ReactionEvent.DISLIKE, left.single().eventType)
        assertEquals(Reaction.DISLIKED, reactions.find(trackA)!!.reaction)
    }

    /** **N.** APPLIED settles the represented rows and records the revision. */
    @Test
    fun n_applied_settles_and_records_the_revision() = runBlocking {
        like(trackA)
        val backend = FakeAtomicBackend()

        drain(backend)

        assertEquals(0, outbox.count())
        assertEquals(101L, reactions.find(trackA)!!.remoteRev)
        assertEquals(1, backend.applications.size)
    }

    /**
     * **O and R.** The replay case: the batch landed, the device died before deleting
     * its rows, and it retries. The server settles it without a second application.
     */
    @Test
    fun o_and_r_a_retry_of_a_committed_batch_settles_without_reapplying() = runBlocking {
        like(trackA)
        val backend = FakeAtomicBackend()
        val ids = outbox.pendingForTrack(trackA).map { it.eventId }

        // First delivery lands on the server; the local delete never happens.
        backend.suppressLocalSettlement = true
        drain(backend)
        assertEquals("the server committed it", 1, backend.rows.size)
        assertEquals("the rows survived the death", 1, outbox.count())
        val revAfterFirst = backend.rows.getValue(trackA).rev

        // The retry, once the backoff the lost response earned has expired.
        backend.suppressLocalSettlement = false
        wakeAll()
        drain(backend)

        assertEquals("the rows settle", 0, outbox.count())
        assertEquals(
            "an already-applied batch must never produce a new revision",
            revAfterFirst,
            backend.rows.getValue(trackA).rev,
        )
        assertEquals(2, backend.batches.size)
        assertEquals(
            "the second call carried the same events",
            ids.toSet(),
            backend.batches.last().second.toSet(),
        )
    }

    /**
     * **Q.** ALREADY_APPLIED carrying a newer remote row is adopted - but only
     * because nothing else is pending for the track.
     */
    @Test
    fun q_a_newer_remote_row_is_adopted_when_nothing_local_is_pending() = runBlocking {
        like(trackA)
        val backend = FakeAtomicBackend()

        backend.suppressLocalSettlement = true
        drain(backend)

        // Another device moved the track while this one was dead.
        backend.rows[trackA] = RemoteReaction(
            trackKey = trackA,
            reaction = Reaction.DISLIKED,
            likedAt = null,
            artist = "A",
            title = "T",
            stream = "myata",
            updatedAt = 9_000_000L,
            rev = 555L,
        )

        backend.suppressLocalSettlement = false
        wakeAll()
        drain(backend)

        val local = reactions.find(trackA)!!
        assertEquals(Reaction.DISLIKED, local.reaction)
        assertEquals(555L, local.remoteRev)
        assertEquals(9_000_000L, local.updatedAt)
        assertNull("a non-LIKED row carries no liked_at", local.likedAt)
    }

    /**
     * **P.** A tap that lands *during* the call is not in the batch, so the answer
     * predates it - and settlement must leave it alone.
     *
     * The tap has to happen inside the round trip. A tap before the drain would
     * simply be carried by the batch, which is correct and proves nothing; the
     * dangerous window is the one between the snapshot and the settlement, which is
     * exactly where the gate is released.
     */
    @Test
    fun p_a_remote_row_never_overwrites_a_newer_local_pending_mutation() = runBlocking {
        like(trackA)

        val backend = FakeAtomicBackend()
        backend.beforeAnswer = {
            // Inside the call, after the snapshot, with the gate free.
            withContext(Dispatchers.IO) { dislike(trackA) }
        }

        drain(backend)

        val local = reactions.find(trackA)!!
        assertEquals("the pending act must not be overwritten", Reaction.DISLIKED, local.reaction)
        assertNull("nor may a revision be claimed while an act is owed", local.remoteRev)
        assertEquals("and the act is still owed", 1, outbox.countForTrack(trackA))
    }

    /**
     * **S.** A batch whose rows were only half deleted still settles: the remainder is
     * a subset of an already-marked set, which is ALREADY_APPLIED.
     */
    @Test
    fun s_a_partially_deleted_batch_settles_safely() = runBlocking {
        like(trackA)
        unlike(trackA)
        val backend = FakeAtomicBackend()

        backend.suppressLocalSettlement = true
        drain(backend)
        val rev = backend.rows.getValue(trackA).rev

        // Half the settlement happened before the process died.
        outbox.delete(outbox.pendingForTrack(trackA).first().eventId)
        assertEquals(1, outbox.count())

        backend.suppressLocalSettlement = false
        wakeAll()
        drain(backend)

        assertEquals(0, outbox.count())
        assertEquals("no second application", rev, backend.rows.getValue(trackA).rev)
    }

    /** **T.** Over the server's limit: parked whole, nothing sent, nothing deleted. */
    @Test
    fun t_an_oversized_batch_fails_safely_with_no_partial_delivery() = runBlocking {
        // 257 alternating transitions on one track.
        repeat(129) { like(trackA); unlike(trackA) }
        val pending = outbox.countForTrack(trackA)
        assertTrue("need more than the server's limit, had $pending", pending > 256)

        val backend = FakeAtomicBackend()
        drain(backend)

        assertEquals("nothing may be sent", 0, backend.batches.size)
        assertEquals("nothing may be deleted", pending, outbox.countForTrack(trackA))
        assertTrue(
            "the whole batch is parked together",
            outbox.pendingForTrack(trackA).all { it.attempts == 1 },
        )
    }

    /** **U.** A lost session parks nothing and counts nothing. */
    @Test
    fun u_an_unavailable_session_never_parks_the_batch() = runBlocking {
        like(trackA)
        val backend = FakeAtomicBackend().also {
            it.onBatch = { SyncOutcome.AuthUnavailable("401") }
        }

        drain(backend)

        val row = outbox.pendingForTrack(trackA).single()
        assertEquals("no attempt may be counted against a blameless row", 0, row.attempts)
        assertEquals("and no backoff set", 0L, row.nextAttemptAt)
    }

    /** **V.** A permanent failure on one track does not hold up another. */
    @Test
    fun v_a_permanent_failure_on_one_track_does_not_block_another() = runBlocking {
        like(trackA)
        like(trackB)
        val backend = FakeAtomicBackend().also {
            it.onBatch = { track ->
                if (track == trackA) SyncOutcome.Permanent(400, "poison") else null
            }
        }

        drain(backend)

        assertEquals("the poisoned track is parked", 1, outbox.countForTrack(trackA))
        assertEquals("the innocent track went through", 0, outbox.countForTrack(trackB))
    }

    /** **X.** Settlement and adoption create no events of their own. */
    @Test
    fun x_settlement_creates_no_synthetic_events() = runBlocking {
        like(trackA)
        val backend = FakeAtomicBackend()
        val minted = outbox.pendingForTrack(trackA).map { it.eventId }.toSet()

        backend.suppressLocalSettlement = true
        drain(backend)
        backend.rows[trackA] = RemoteReaction(
            trackKey = trackA, reaction = Reaction.NEUTRAL, likedAt = null,
            artist = "A", title = "T", stream = "myata", updatedAt = 9_000_000L, rev = 555L,
        )
        backend.suppressLocalSettlement = false
        wakeAll()
        drain(backend)

        assertEquals(
            "adoption is not something the listener did",
            minted,
            backend.history.keys,
        )
        assertEquals("and it enqueues nothing locally", 0, outbox.count())
    }

    // ==================== helpers ====================

    private suspend fun drain(backend: FakeAtomicBackend) {
        ReactionSyncEngine(
            reactions = reactions,
            outbox = outbox,
            api = backend,
            identity = { ListenerIdentity.Available(listener) },
        ).drain()
    }

    /**
     * Makes every pending row due again.
     *
     * A lost response is parked with a backoff, exactly as a transient failure should
     * be, so a retry in the same millisecond would find nothing due. Real life waits;
     * a test says so and moves on.
     */
    private suspend fun wakeAll() {
        outbox.recordFailedAttempts(outbox.pending().map { it.eventId }, 0L)
    }

    private suspend fun protocols(track: String): List<SyncProtocol> =
        outbox.pendingForTrack(track).map { it.syncProtocol }

    private suspend fun like(track: String) =
        reactions.like(track, "A", "T", "myata", likedAt = System.currentTimeMillis())

    private suspend fun unlike(track: String) = reactions.unlike(track)
    private suspend fun dislike(track: String) = reactions.dislike(track, "A", "T", "myata")
    private suspend fun undislike(track: String) = reactions.undislike(track)

    /**
     * A row exactly as migration 3->4 leaves one: written by the old build, tagged
     * LEGACY by the SQL default.
     */
    private suspend fun seedLegacy(track: String, event: ReactionEvent): ReactionOutboxEntry {
        // The state has to exist too, or the legacy path has nothing to reconcile.
        if (reactions.find(track) == null) {
            reactions.like(track, "A", "T", "myata", likedAt = 1L)
            outbox.deleteAll(outbox.pendingForTrack(track).map { it.eventId })
        }
        val row = ReactionOutboxEntry(
            eventId = ReactionOutboxEntry.newEventId(),
            trackKey = track,
            artist = "A",
            title = "T",
            stream = "myata",
            eventType = event,
            occurredAt = 1L,
            syncProtocol = SyncProtocol.LEGACY,
        )
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO reaction_outbox (event_id, track_key, artist, title, stream, " +
                "event_type, occurred_at, attempts, next_attempt_at, sync_protocol) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, 'LEGACY')",
            arrayOf(row.eventId, track, "A", "T", "myata", event.wire, 1L),
        )
        return row
    }
}

/**
 * `apply_reaction_event_batch` as a fake, including the one behaviour the cutover
 * depends on: a batch whose every event is already applied writes nothing and
 * produces no new revision.
 */
private class FakeAtomicBackend : ReactionSyncApi {

    val batches = mutableListOf<Pair<String, List<String>>>()
    val applications = LinkedHashMap<String, Long>()
    val history = LinkedHashMap<String, ReactionOutboxEntry>()
    val rows = LinkedHashMap<String, RemoteReaction>()

    var onBatch: (String) -> SyncOutcome? = { null }
    var beforeAnswer: suspend () -> Unit = {}

    /**
     * Makes the server commit while the client learns nothing - the process death
     * this protocol exists to survive.
     */
    var suppressLocalSettlement = false

    private var nextRev = 100L

    override suspend fun applyBatch(
        trackKey: String,
        events: List<ReactionOutboxEntry>,
        current: TrackReaction,
        listenerId: String,
    ): BatchOutcome {
        batches += trackKey to events.map { it.eventId }
        beforeAnswer()
        onBatch(trackKey)?.let { return BatchOutcome.Failed(it) }
        if (events.isEmpty() || events.size > 256) {
            return BatchOutcome.Failed(SyncOutcome.Permanent(400, "event count out of range"))
        }

        for (event in events) history.putIfAbsent(event.eventId, event)

        val alreadyApplied = events.all { applications.containsKey(it.eventId) }
        if (!alreadyApplied) {
            val rev = ++nextRev
            rows[trackKey] = RemoteReaction(
                trackKey = trackKey,
                reaction = current.reaction,
                likedAt = if (current.reaction == Reaction.LIKED) {
                    current.likedAt ?: current.updatedAt
                } else null,
                artist = current.artist,
                title = current.title,
                stream = current.stream,
                updatedAt = current.updatedAt,
                rev = rev,
            )
            for (event in events) applications.putIfAbsent(event.eventId, rev)
        }

        if (suppressLocalSettlement) {
            // Committed remotely, and the answer never reaches the client.
            return BatchOutcome.Failed(SyncOutcome.Transient("response lost"))
        }
        return if (alreadyApplied) {
            BatchOutcome.AlreadyApplied(rows[trackKey])
        } else {
            BatchOutcome.Applied(rows.getValue(trackKey))
        }
    }

    override suspend fun deliverEvent(entry: ReactionOutboxEntry, listenerId: String) =
        SyncOutcome.Success

    override suspend fun reconcileCurrentState(
        trackKey: String,
        current: TrackReaction?,
        listenerId: String,
    ) = SyncOutcome.Success

    override suspend fun retireAllCurrentState(listenerId: String) = SyncOutcome.Success
}
