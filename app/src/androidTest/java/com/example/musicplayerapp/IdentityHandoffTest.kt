package com.example.musicplayerapp

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayerapp.data.AppDatabase
import com.example.musicplayerapp.data.Reaction
import com.example.musicplayerapp.data.ReactionDao
import com.example.musicplayerapp.data.ReactionOutboxDao
import com.example.musicplayerapp.data.ReactionOutboxEntry
import com.example.musicplayerapp.data.ReactionWriteGate
import com.example.musicplayerapp.data.TrackKey
import com.example.musicplayerapp.data.TrackReaction
import com.example.musicplayerapp.data.supabase.BatchOutcome
import com.example.musicplayerapp.data.supabase.HandoffStage
import com.example.musicplayerapp.data.supabase.IdentityHandoff
import com.example.musicplayerapp.data.supabase.IdentityState
import com.example.musicplayerapp.data.supabase.IdentityStore
import com.example.musicplayerapp.data.supabase.PullPage
import com.example.musicplayerapp.data.supabase.ReactionSyncApi
import com.example.musicplayerapp.data.supabase.SyncLease
import com.example.musicplayerapp.data.supabase.SyncOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
 * The identity handoff, and the races it exists to lose safely.
 *
 * Real Room, fake backend. The backend is fake because every question here is about
 * what the algorithm does when something fails or arrives at the wrong moment, and a
 * network cannot be asked to do either on cue. Room is real because the ownership
 * boundary is a claim about when a transaction commits, and a hand-rolled queue
 * would prove nothing about that.
 *
 * Process death is simulated the only way an in-process test can: by driving the
 * stages to a chosen point, discarding the in-memory world, and running recovery
 * against what is left on disk - which is exactly the information a cold start has.
 */
@RunWith(AndroidJUnit4::class)
class IdentityHandoffTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var db: AppDatabase
    private lateinit var dao: ReactionDao
    private lateinit var outbox: ReactionOutboxDao
    private lateinit var api: HandoffBackend

    private val x = "11111111-1111-4111-8111-111111111111"
    private val y = "22222222-2222-4222-8222-222222222222"

    private val depeche = TrackKey.of("Depeche Mode", "Enjoy the Silence")!!
    private val cave = TrackKey.of("Nick Cave", "Red Right Hand")!!

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = db.reactionDao()
        outbox = db.reactionOutboxDao()
        api = HandoffBackend()
        IdentityStore.clearForTest(context)
        IdentityStore.adoptAnonymous(context, x)
    }

    @After
    fun close() {
        db.close()
        IdentityStore.clearForTest(context)
    }

    // ==================== helpers ====================

    /** Drains by emptying the outbox, the way a successful real drain would. */
    private val cleanDrain: suspend () -> Boolean = {
        outbox.pending().forEach { outbox.delete(it.eventId) }
        true
    }

    private suspend fun like(key: String = depeche, at: Long = 1_000L) =
        dao.like(key, "Artist", "Title", "myata", at, at)

    /**
     * **W.** Every recorded server revision is forgotten at the identity boundary.
     *
     * A rev identifies one row belonging to one `auth.users` id, so it is a fact
     * about a listener rather than about a track. Both outcomes of a handoff void
     * every value at once: the source's remote rows are deleted before the switch,
     * and whatever is written afterwards - into the destination on success, back into
     * the source on rollback - gets fresh revisions this device is never told.
     * Carrying the old numbers across would leave the install asserting a match with
     * rows that no longer exist, and G-A7c's pull would then skip a page it needed.
     *
     * The Collection itself is untouched, which the rest of this suite already
     * asserts row by row and this re-checks for the row it moved.
     */
    @Test
    fun remote_revisions_are_cleared_when_the_identity_changes() = runBlocking {
        like()
        dao.recordRemoteRev(depeche, 4_242L)
        assertEquals(4_242L, dao.find(depeche)!!.remoteRev)

        val result = handoff(destination = { y })

        assertTrue("$result", result is IdentityHandoff.Result.Switched)
        val row = dao.find(depeche)!!
        assertNull(
            "a revision belonging to the retired identity is not a fact about the new one",
            row.remoteRev,
        )
        assertEquals("and the Collection is not what a handoff moves", Reaction.LIKED, row.reaction)
        assertEquals(1_000L, row.likedAt)
    }

    /** The same clearing happens when the switch fails and the state goes back to X. */
    @Test
    fun remote_revisions_are_cleared_on_a_rollback_too() = runBlocking {
        like()
        dao.recordRemoteRev(depeche, 4_242L)

        val result = handoff(destination = { null })

        assertTrue("$result", result is IdentityHandoff.Result.RolledBack)
        assertNull(
            "X's rows were deleted and rebuilt, so the old revisions describe nothing",
            dao.find(depeche)!!.remoteRev,
        )
        assertEquals(Reaction.LIKED, dao.find(depeche)!!.reaction)
    }

    private suspend fun handoff(
        drain: suspend () -> Boolean = cleanDrain,
        destination: IdentityHandoff.DestinationIdentity = IdentityHandoff.DestinationIdentity { y },
    ) = IdentityHandoff.run(context, x, dao, outbox, api, drain, destination)

    // ==================== the ordinary path ====================

    @Test
    fun a_clean_handoff_retires_the_source_and_adopts_into_the_destination() = runBlocking {
        like(depeche)
        like(cave)

        val result = handoff()

        assertEquals(IdentityHandoff.Result.Switched(y), result)
        // Retired first, adopted second, and never the other way round - that
        // ordering is what stops both identities holding current state at once.
        assertEquals(listOf(x), api.retirements)
        assertEquals(setOf(y), api.adoptedBy.keys)
        assertEquals(setOf(depeche, cave), api.adoptedBy.getValue(y).keys)
        // No event was written by the adoption. History is not manufactured.
        assertTrue("adoption must write no events", api.events.isEmpty())
        assertEquals(IdentityState.Registered(y), IdentityStore.state(context))
        assertNull("the record must be cleared", IdentityStore.handoff(context))
        // Local Room is untouched throughout.
        assertEquals(2, dao.allReactions().size)
    }

    @Test
    fun adoption_is_idempotent() = runBlocking {
        like(depeche)
        handoff()
        val afterFirst = api.adoptedBy.getValue(y).toMap()

        // Re-running the whole adoption is how every crash in this file is repaired,
        // so it has to be free of consequence when nothing was lost.
        IdentityStore.markHandoffSwitched(context, x, y)
        IdentityHandoff.recover(context, y, dao, api)

        assertEquals(afterFirst, api.adoptedBy.getValue(y))
        assertTrue(api.events.isEmpty())
    }

    // ==================== the drain gate ====================

    @Test
    fun a_source_outbox_that_cannot_drain_aborts_and_writes_nothing() = runBlocking {
        like(depeche)

        val result = handoff(drain = { false })

        assertTrue(result is IdentityHandoff.Result.Aborted)
        // Nothing remote, nothing durable, nothing local.
        assertTrue(api.retirements.isEmpty())
        assertTrue(api.adoptedBy.isEmpty())
        assertNull(IdentityStore.handoff(context))
        assertEquals(IdentityState.Anonymous(x), IdentityStore.state(context))
        assertEquals(1, outbox.count())
    }

    @Test
    fun a_mutation_between_the_drain_and_the_lease_is_caught_by_the_final_count() = runBlocking {
        // The drain reports empty, then a reaction lands before the cutover can take
        // the lease. The final count must see it and the attempt must start over.
        var attempts = 0
        val drainThenTap: suspend () -> Boolean = {
            attempts++
            outbox.pending().forEach { outbox.delete(it.eventId) }
            if (attempts == 1) like(cave) // slips in after this drain reports empty
            true
        }

        val result = handoff(drain = drainThenTap)

        assertEquals(IdentityHandoff.Result.Switched(y), result)
        assertEquals("the first attempt must be abandoned and re-drained", 2, attempts)
        // And exactly one retirement: the abandoned attempt must not have retired X.
        assertEquals(listOf(x), api.retirements)
    }

    @Test
    fun an_outbox_that_keeps_refilling_gives_up_rather_than_spinning() = runBlocking {
        val alwaysRefills: suspend () -> Boolean = {
            outbox.pending().forEach { outbox.delete(it.eventId) }
            like(TrackKey.of("A", System.nanoTime().toString())!!)
            true
        }

        val result = handoff(drain = alwaysRefills)

        assertTrue(result is IdentityHandoff.Result.Aborted)
        assertTrue("nothing may be retired on a give-up", api.retirements.isEmpty())
        assertNull(IdentityStore.handoff(context))
    }

    // ==================== the sync lease ====================

    @Test
    fun a_handoff_waits_for_a_drain_that_is_already_in_flight() = runBlocking {
        val drainStarted = CompletableDeferred<Unit>()
        val letDrainFinish = CompletableDeferred<Unit>()
        val retireSeenWhileDraining = CompletableDeferred<Boolean>()

        // A drain holding the lease, parked mid-run.
        val inFlight = async(Dispatchers.IO) {
            SyncLease.withExclusive {
                drainStarted.complete(Unit)
                letDrainFinish.await()
                // Still inside the lease: the handoff must not have retired yet.
                retireSeenWhileDraining.complete(api.retirements.isNotEmpty())
            }
        }
        drainStarted.await()

        val handoffRun = async(Dispatchers.IO) { handoff() }

        // Give the handoff every chance to overtake, then prove it did not.
        repeat(20) { kotlinx.coroutines.yield() }
        assertTrue("retire must not begin while a drain holds the lease", api.retirements.isEmpty())

        letDrainFinish.complete(Unit)
        inFlight.await()
        val result = withTimeout(10_000) { handoffRun.await() }

        assertFalse(
            "the old drain must have finished before retire began",
            retireSeenWhileDraining.await(),
        )
        assertEquals(IdentityHandoff.Result.Switched(y), result)
        assertEquals(listOf(x), api.retirements)
    }

    @Test
    fun no_drain_can_start_while_the_handoff_holds_the_lease() = runBlocking {
        val insideHandoff = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        api.onRetire = {
            insideHandoff.complete(Unit)
            runBlocking { release.await() }
            SyncOutcome.Success
        }

        val handoffRun = async(Dispatchers.IO) { handoff() }
        insideHandoff.await()

        // tryAcquire is what every drain uses. It must fail outright, not queue.
        val acquired = SyncLease.tryAcquire { "got it" }
        assertNull("a drain must not be able to take the lease mid-handoff", acquired)

        release.complete(Unit)
        assertEquals(IdentityHandoff.Result.Switched(y), withTimeout(10_000) { handoffRun.await() })
    }

    // ==================== the ownership boundary ====================

    @Test
    fun a_mutation_racing_the_cutover_lands_strictly_on_one_side() = runBlocking {
        like(depeche)

        // Hold the write gate as if a tap were mid-commit, start the handoff, and
        // prove it cannot slip PREPARED in between. The tap either completed before
        // the cutover took the gate, or begins after PREPARED is on disk.
        val tapHolding = CompletableDeferred<Unit>()
        val releaseTap = CompletableDeferred<Unit>()
        val tap = async(Dispatchers.IO) {
            ReactionWriteGate.withReactionWrite {
                tapHolding.complete(Unit)
                releaseTap.await()
            }
        }
        tapHolding.await()

        val handoffRun = async(Dispatchers.IO) { handoff() }
        repeat(20) { kotlinx.coroutines.yield() }
        assertNull(
            "PREPARED must not be committed while a reaction write holds the gate",
            IdentityStore.handoff(context),
        )

        releaseTap.complete(Unit)
        tap.await()
        assertEquals(IdentityHandoff.Result.Switched(y), withTimeout(10_000) { handoffRun.await() })
    }

    @Test
    fun a_reaction_after_prepared_stays_local_and_belongs_to_the_destination() = runBlocking {
        like(depeche)

        var tappedDuring = false
        api.onRetire = {
            if (!tappedDuring) {
                tappedDuring = true
                // A tap during the exclusive section: it must commit locally and go
                // nowhere, because nothing may drain until the handoff ends.
                runBlocking { like(cave, at = 9_000L) }
            }
            SyncOutcome.Success
        }

        val result = handoff()

        assertEquals(IdentityHandoff.Result.Switched(y), result)
        assertEquals("the post-boundary row must still be queued", 1, outbox.count())
        // It is Y's to deliver: the section terminated as Y.
        assertEquals(IdentityState.Registered(y), IdentityStore.state(context))
        assertEquals(2, dao.allReactions().size)
    }

    @Test
    fun a_reaction_after_prepared_belongs_to_the_source_when_the_handoff_rolls_back() = runBlocking {
        like(depeche)
        api.onRetire = { SyncOutcome.Success }

        val result = handoff(destination = IdentityHandoff.DestinationIdentity { null })

        assertTrue(result is IdentityHandoff.Result.RolledBack)
        // Back to the source, and the source's remote state rebuilt from Room.
        assertEquals(IdentityState.Anonymous(x), IdentityStore.state(context))
        assertEquals(setOf(x), api.adoptedBy.keys)
        assertNull(IdentityStore.handoff(context))
    }

    // ==================== process death ====================

    @Test
    fun death_before_retire_rolls_back_to_the_source() = runBlocking {
        like(depeche)
        // The disk as it would be if the process died between PREPARED and the delete.
        IdentityStore.markHandoffPrepared(context, x)

        val result = IdentityHandoff.recover(context, sessionUid = x, reactions = dao, api = api)

        assertTrue(result is IdentityHandoff.Result.RolledBack)
        assertEquals(setOf(x), api.adoptedBy.keys)
        assertEquals(IdentityState.Anonymous(x), IdentityStore.state(context))
        assertNull(IdentityStore.handoff(context))
    }

    @Test
    fun death_during_or_after_retire_recovers_the_same_way() = runBlocking {
        like(depeche)
        like(cave)
        IdentityStore.markHandoffPrepared(context, x)
        // Whether the delete half-ran or fully ran, the disk says the same thing -
        // which is the point of one PREPARED stage rather than three.
        api.retireAllCurrentState(x)

        val result = IdentityHandoff.recover(context, sessionUid = x, reactions = dao, api = api)

        assertTrue(result is IdentityHandoff.Result.RolledBack)
        assertEquals(setOf(depeche, cave), api.adoptedBy.getValue(x).keys)
        assertNull(IdentityStore.handoff(context))
    }

    @Test
    fun death_before_the_switch_rolls_back() = runBlocking {
        like(depeche)
        IdentityStore.markHandoffSwitchPending(context, x)

        val result = IdentityHandoff.recover(context, sessionUid = x, reactions = dao, api = api)

        assertTrue(result is IdentityHandoff.Result.RolledBack)
        assertEquals(IdentityState.Anonymous(x), IdentityStore.state(context))
    }

    @Test
    fun death_after_the_remote_switch_but_before_the_durable_commit_completes_forward() = runBlocking {
        like(depeche)
        // SWITCH_PENDING on disk, but the restored session is already the destination:
        // the switch took and the process died before the commit.
        IdentityStore.markHandoffSwitchPending(context, x)

        val result = IdentityHandoff.recover(context, sessionUid = y, reactions = dao, api = api)

        assertEquals(IdentityHandoff.Result.Switched(y), result)
        assertEquals(IdentityState.Registered(y), IdentityStore.state(context))
        assertEquals(setOf(y), api.adoptedBy.keys)
        assertNull(IdentityStore.handoff(context))
    }

    /**
     * A death **after** SWITCHED(Y) must not leave X-scoped revisions on Y's rows.
     *
     * This is the crash boundary the forward path's clearing does not by itself
     * cover: the switch is already durable, so recovery - not `run` - is what
     * finishes the job. Recovery reaches the same `adopt`, which clears the
     * revisions before it writes anything, so the handoff cannot be considered
     * complete with X's numbers still attached.
     *
     * Why the numbers are void rather than merely stale: X's remote rows were deleted
     * before the switch, and the rows adoption writes into Y are given fresh
     * revisions this device is never told. A rev left behind would have this install
     * asserting a match with a row that does not exist, which is exactly the claim
     * G-A7c's pull will use to decide it may skip a page.
     */
    @Test
    fun recovery_after_a_durable_switch_clears_x_scoped_revisions() = runBlocking {
        like(depeche)
        like(cave)
        dao.recordRemoteRev(depeche, 4_242L)
        dao.recordRemoteRev(cave, 4_243L)

        // Durably switched, then the process died before adoption finished.
        IdentityStore.markHandoffSwitched(context, x, y)
        assertEquals(4_242L, dao.find(depeche)!!.remoteRev)

        val result = IdentityHandoff.recover(context, sessionUid = y, reactions = dao, api = api)

        assertEquals(IdentityHandoff.Result.Switched(y), result)
        assertNull(
            "a revision belonging to the retired identity is not a fact about the new one",
            dao.find(depeche)!!.remoteRev,
        )
        assertNull(dao.find(cave)!!.remoteRev)
        assertNull("and only then is the handoff complete", IdentityStore.handoff(context))

        // The Collection is not what a handoff moves, and recovery is not an exception.
        assertEquals(setOf(depeche, cave), api.adoptedBy.getValue(y).keys)
        assertEquals(Reaction.LIKED, dao.find(depeche)!!.reaction)
        assertEquals(Reaction.LIKED, dao.find(cave)!!.reaction)
        assertTrue("and it invents no history", api.events.isEmpty())
    }

    @Test
    fun death_during_partial_adoption_finishes_it() = runBlocking {
        like(depeche)
        like(cave)
        IdentityStore.markHandoffSwitched(context, x, y)
        // One row made it before the death.
        api.reconcileCurrentState(depeche, dao.find(depeche), y)

        val result = IdentityHandoff.recover(context, sessionUid = y, reactions = dao, api = api)

        assertEquals(IdentityHandoff.Result.Switched(y), result)
        assertEquals(setOf(depeche, cave), api.adoptedBy.getValue(y).keys)
        assertTrue(api.events.isEmpty())
        assertNull(IdentityStore.handoff(context))
    }

    @Test
    fun a_handoff_with_no_session_defers_rather_than_guessing() = runBlocking {
        like(depeche)
        IdentityStore.markHandoffSwitchPending(context, x)

        val result = IdentityHandoff.recover(context, sessionUid = null, reactions = dao, api = api)

        assertNull("with no session the outcome is unknowable; do not guess", result)
        // The record survives so the next restore can decide, and nothing was touched.
        assertEquals(HandoffStage.SWITCH_PENDING, IdentityStore.handoff(context)?.stage)
        assertTrue(api.adoptedBy.isEmpty())
        assertTrue(api.retirements.isEmpty())
    }

    @Test
    fun recovery_holds_the_lease_while_it_works() = runBlocking {
        like(depeche)
        IdentityStore.markHandoffSwitched(context, x, y)

        val inside = CompletableDeferred<Boolean>()
        api.onReconcile = {
            // tryAcquire never waits, so calling it from this non-suspend hook is
            // safe: it either takes the free lease or reports it is held.
            if (!inside.isCompleted) {
                inside.complete(runBlocking { SyncLease.tryAcquire { true } } == null)
            }
            SyncOutcome.Success
        }

        IdentityHandoff.recover(context, sessionUid = y, reactions = dao, api = api)

        assertTrue("recovery must exclude drains the same way the forward path does", inside.await())
    }

    // ==================== the gate seen by the rest of the app ====================

    @Test
    fun an_in_flight_handoff_is_visible_to_every_sync_entry_point() = runBlocking {
        assertFalse(IdentityStore.handoffInProgress(context))

        IdentityStore.markHandoffPrepared(context, x)
        assertTrue(
            "the scheduler and worker both gate on this, and it must survive a restart",
            IdentityStore.handoffInProgress(context),
        )

        IdentityStore.clearHandoff(context)
        assertFalse(IdentityStore.handoffInProgress(context))
    }
}

/**
 * A backend that records who was retired, who adopted what, and whether anything
 * tried to write history.
 *
 * `events` exists to be asserted **empty**: the frozen contract says adoption creates
 * no synthetic `reaction_events`, and a fake that could not have recorded a violation
 * would not be evidence of its absence.
 */
private class HandoffBackend : ReactionSyncApi {

    override suspend fun fetchReactionsPage(listenerId: String, afterRev: Long, limit: Int) =
        PullPage.Rows(emptyList())

    /** The atomic path, recorded like the legacy one. See [RecordingSyncApi]. */
    override suspend fun applyBatch(
        trackKey: String,
        events: List<ReactionOutboxEntry>,
        current: TrackReaction,
        listenerId: String,
    ): BatchOutcome {
        for (event in events) this.events += event
        val outcome = onReconcile(trackKey)
        if (outcome !is SyncOutcome.Success) return BatchOutcome.Failed(outcome)
        adoptedBy.getOrPut(listenerId) { linkedMapOf() }[trackKey] = current.reaction.name
        return BatchOutcome.Applied(current.asRemote(++rev))
    }

    private var rev = 0L

    val retirements = mutableListOf<String>()
    val adoptedBy = linkedMapOf<String, MutableMap<String, String>>()
    val events = mutableListOf<ReactionOutboxEntry>()

    var onRetire: (String) -> SyncOutcome = { SyncOutcome.Success }
    var onReconcile: (String) -> SyncOutcome = { SyncOutcome.Success }

    override suspend fun deliverEvent(entry: ReactionOutboxEntry, listenerId: String): SyncOutcome {
        events += entry
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
