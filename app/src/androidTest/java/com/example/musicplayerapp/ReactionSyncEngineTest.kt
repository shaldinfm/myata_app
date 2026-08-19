package com.example.musicplayerapp

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayerapp.data.AppDatabase
import com.example.musicplayerapp.data.Reaction
import com.example.musicplayerapp.data.ReactionDao
import com.example.musicplayerapp.data.ReactionEvent
import com.example.musicplayerapp.data.ReactionOutboxDao
import com.example.musicplayerapp.data.ReactionOutboxEntry
import com.example.musicplayerapp.data.TrackKey
import com.example.musicplayerapp.data.TrackReaction
import com.example.musicplayerapp.data.supabase.DrainResult
import com.example.musicplayerapp.data.supabase.ReactionSyncApi
import com.example.musicplayerapp.data.supabase.ReactionSyncEngine
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
 * A fake backend that behaves the way the real one was observed to behave.
 *
 * Not a stub that always says yes: it enforces the two properties the drain leans
 * on, so a change that violates either fails here rather than in production.
 *
 *  - `reaction_events` is **idempotent on event_id and append-only**. Redelivering
 *    an event is success and adds nothing, exactly as `ignore-duplicates` does.
 *  - `reactions` is **current state**, where NEUTRAL is an absent row.
 */
private class FakeBackend : ReactionSyncApi {

    /** Every event the drain handed over, including redeliveries. */
    val delivered = mutableListOf<ReactionOutboxEntry>()

    /** History as it would actually be stored: one row per event_id. */
    val history = LinkedHashMap<String, ReactionOutboxEntry>()

    /** Current remote state: trackKey -> "LIKED"/"DISLIKED". Absence is NEUTRAL. */
    val state = LinkedHashMap<String, String>()

    /** Every reconciliation asked for, as (trackKey, remote value or null for delete). */
    val reconciliations = mutableListOf<Pair<String, String?>>()

    var listenerSeen: String? = null

    var onEvent: (ReactionOutboxEntry) -> SyncOutcome = { SyncOutcome.Success }
    var onState: (String) -> SyncOutcome = { SyncOutcome.Success }

    override suspend fun deliverEvent(entry: ReactionOutboxEntry, listenerId: String): SyncOutcome {
        listenerSeen = listenerId
        val outcome = onEvent(entry)
        if (outcome is SyncOutcome.Success) {
            delivered += entry
            history.putIfAbsent(entry.eventId, entry) // ON CONFLICT DO NOTHING
        }
        return outcome
    }

    override suspend fun reconcileCurrentState(
        trackKey: String,
        current: TrackReaction?,
        listenerId: String,
    ): SyncOutcome {
        listenerSeen = listenerId
        val outcome = onState(trackKey)
        if (outcome is SyncOutcome.Success) {
            val remote = when (current?.reaction) {
                Reaction.LIKED -> "LIKED"
                Reaction.DISLIKED -> "DISLIKED"
                else -> null
            }
            reconciliations += trackKey to remote
            if (remote == null) state.remove(trackKey) else state[trackKey] = remote
        }
        return outcome
    }
}

/**
 * The drain, against a real Room database and a fake backend.
 *
 * Real Room because the ordering guarantee being relied on is SQLite's `rowid`, and
 * a hand-rolled fake queue would prove nothing about it. Fake backend because every
 * question worth asking here is about what the algorithm does when the network
 * misbehaves, and a network cannot be asked to misbehave on cue.
 */
@RunWith(AndroidJUnit4::class)
class ReactionSyncEngineTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var db: AppDatabase
    private lateinit var dao: ReactionDao
    private lateinit var outbox: ReactionOutboxDao
    private lateinit var backend: FakeBackend

    private val listener = "11111111-2222-3333-4444-555555555555"

    private val depeche = TrackKey.of("Depeche Mode", "Enjoy the Silence")!!
    private val cave = TrackKey.of("Nick Cave", "Red Right Hand")!!

    private var identityCalls = 0
    private var identity: String? = listener
    private var clock = 1_000_000L

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = db.reactionDao()
        outbox = db.reactionOutboxDao()
        backend = FakeBackend()
        identityCalls = 0
        identity = listener
        clock = 1_000_000L
    }

    @After
    fun close() = db.close()

    private fun engine(batchSize: Int = ReactionSyncEngine.BATCH_SIZE) = ReactionSyncEngine(
        reactions = dao,
        outbox = outbox,
        api = backend,
        identity = { identityCalls++; identity },
        now = { clock },
        batchSize = batchSize,
    )

    private suspend fun like(key: String = depeche, artist: String = "Depeche Mode", title: String = "Enjoy the Silence") =
        dao.like(key, artist, title, "myata", clock, clock)

    private suspend fun dislike(key: String = depeche, artist: String = "Depeche Mode", title: String = "Enjoy the Silence") =
        dao.dislike(key, artist, title, "myata", clock)

    // ==================== the identity gate ====================

    @Test
    fun an_empty_outbox_never_asks_for_an_identity() = runBlocking {
        assertEquals(DrainResult.Idle, engine().drain())

        // The whole point of the lazy identity: a listener who has never reacted
        // must not become a row in auth.users because a worker happened to run.
        assertEquals(0, identityCalls)
        assertNull(backend.listenerSeen)
        assertTrue(backend.history.isEmpty())
    }

    @Test
    fun work_present_asks_for_an_identity_exactly_once() = runBlocking {
        like()
        like(cave, "Nick Cave", "Red Right Hand")

        engine().drain()

        // Once per run, not once per row.
        assertEquals(1, identityCalls)
        assertEquals(listener, backend.listenerSeen)
    }

    @Test
    fun no_identity_sends_nothing_and_keeps_everything() = runBlocking {
        like()
        identity = null

        val result = engine().drain()

        assertTrue(result is DrainResult.RetryLater)
        assertTrue(backend.history.isEmpty())
        // The row is untouched: not delivered, not penalised, not backed off. It did
        // nothing wrong, and a missing session is not its fault.
        val row = outbox.pending().single()
        assertEquals(0, row.attempts)
        assertEquals(0L, row.nextAttemptAt)
    }

    // ==================== the six transitions ====================

    @Test
    fun neutral_to_liked_writes_history_and_liked_state() = runBlocking {
        like()
        engine().drain()

        assertEquals(listOf(ReactionEvent.LIKE), backend.history.values.map { it.eventType })
        assertEquals("LIKED", backend.state[depeche])
        assertEquals(0, outbox.count())
    }

    @Test
    fun liked_to_neutral_writes_history_and_removes_state() = runBlocking {
        like()
        engine().drain()
        assertEquals("LIKED", backend.state[depeche])

        clock += 1_000
        dao.unlike(depeche, clock)
        engine().drain()

        assertEquals(
            listOf(ReactionEvent.LIKE, ReactionEvent.UNLIKE),
            backend.history.values.map { it.eventType },
        )
        // NEUTRAL is absence, not a third value.
        assertFalse(backend.state.containsKey(depeche))
        assertEquals(0, outbox.count())
    }

    @Test
    fun neutral_to_disliked_writes_disliked_state() = runBlocking {
        dislike()
        engine().drain()

        assertEquals(listOf(ReactionEvent.DISLIKE), backend.history.values.map { it.eventType })
        assertEquals("DISLIKED", backend.state[depeche])
    }

    @Test
    fun disliked_to_neutral_removes_state() = runBlocking {
        dislike()
        engine().drain()
        clock += 1_000
        dao.undislike(depeche, clock)
        engine().drain()

        assertEquals(
            listOf(ReactionEvent.DISLIKE, ReactionEvent.UNDISLIKE),
            backend.history.values.map { it.eventType },
        )
        assertFalse(backend.state.containsKey(depeche))
    }

    @Test
    fun liked_to_disliked_is_one_event_and_ends_disliked() = runBlocking {
        like()
        engine().drain()
        clock += 1_000
        dislike()
        engine().drain()

        assertEquals(
            listOf(ReactionEvent.LIKE, ReactionEvent.DISLIKE),
            backend.history.values.map { it.eventType },
        )
        // No invented UNLIKE on the way through.
        assertFalse(backend.history.values.any { it.eventType == ReactionEvent.UNLIKE })
        assertEquals("DISLIKED", backend.state[depeche])
    }

    @Test
    fun disliked_to_liked_is_one_event_and_ends_liked() = runBlocking {
        dislike()
        engine().drain()
        clock += 1_000
        like()
        engine().drain()

        assertEquals(
            listOf(ReactionEvent.DISLIKE, ReactionEvent.LIKE),
            backend.history.values.map { it.eventType },
        )
        assertFalse(backend.history.values.any { it.eventType == ReactionEvent.UNDISLIKE })
        assertEquals("LIKED", backend.state[depeche])
    }

    @Test
    fun all_six_transitions_in_one_offline_burst_end_in_the_right_state() = runBlocking {
        // Nothing drains between them: this is a phone in a lift.
        like(); clock += 10
        dao.unlike(depeche, clock); clock += 10
        dislike(); clock += 10
        dao.undislike(depeche, clock); clock += 10
        like(); clock += 10
        dislike(); clock += 10
        like()

        engine().drain()

        assertEquals(
            listOf(
                ReactionEvent.LIKE, ReactionEvent.UNLIKE, ReactionEvent.DISLIKE,
                ReactionEvent.UNDISLIKE, ReactionEvent.LIKE, ReactionEvent.DISLIKE,
                ReactionEvent.LIKE,
            ),
            backend.history.values.map { it.eventType },
        )
        assertEquals("LIKED", backend.state[depeche])
        assertEquals(0, outbox.count())
    }

    // ==================== state comes from Room, not the event ====================

    @Test
    fun current_state_is_read_from_room_not_folded_from_the_event() = runBlocking {
        // One pending LIKE event, and a local state that has since become NEUTRAL.
        like()
        clock += 1_000
        dao.unlike(depeche, clock)
        // Drop the UNLIKE row so only the stale LIKE event is left to deliver.
        val unlikeRow = outbox.pending().last { it.eventType == ReactionEvent.UNLIKE }
        outbox.delete(unlikeRow.eventId)

        engine().drain()

        // The event is delivered as it happened - history is history.
        assertEquals(listOf(ReactionEvent.LIKE), backend.history.values.map { it.eventType })
        // But the state written is what the listener thinks NOW, which is nothing.
        assertEquals(listOf(depeche to null), backend.reconciliations)
        assertFalse(backend.state.containsKey(depeche))
    }

    @Test
    fun a_week_old_event_cannot_restore_a_state_the_listener_has_changed() = runBlocking {
        like()
        val stale = outbox.pending().single()

        // A week passes; the listener now dislikes it. Only the old LIKE is pending.
        clock += 7 * 24 * 60 * 60 * 1000L
        dislike()
        outbox.pending().filter { it.eventId != stale.eventId }
            .forEach { outbox.delete(it.eventId) }

        engine().drain()

        assertEquals(listOf(ReactionEvent.LIKE), backend.history.values.map { it.eventType })
        // Not LIKED. The stale event delivered its history and reconciled to now.
        assertEquals("DISLIKED", backend.state[depeche])
    }

    @Test
    fun the_event_carries_its_original_identity_words_and_time() = runBlocking {
        val at = clock
        dao.like(cave, "Nick Cave", "Red Right Hand", "gold", at, at)
        val queued = outbox.pending().single()

        // Rename the track locally afterwards, so a lazy implementation that read
        // the current row for the event payload would be caught.
        clock += 5_000
        dao.dislike(cave, "Nick Cave RENAMED", "Red Right Hand RENAMED", "myata", clock)
        outbox.pending().filter { it.eventId != queued.eventId }.forEach { outbox.delete(it.eventId) }

        engine().drain()

        val sent = backend.history.values.single()
        assertEquals(queued.eventId, sent.eventId)
        assertEquals(at, sent.occurredAt)
        assertEquals("Nick Cave", sent.artist)
        assertEquals("Red Right Hand", sent.title)
        assertEquals("gold", sent.stream)
        assertEquals(ReactionEvent.LIKE, sent.eventType)
    }

    // ==================== both writes, or the row stays ====================

    @Test
    fun the_row_survives_when_only_the_event_landed() = runBlocking {
        like()
        backend.onState = { SyncOutcome.Transient("no network") }

        val result = engine().drain()

        assertTrue(result is DrainResult.RetryLater)
        assertEquals(1, backend.history.size)
        // The state write never happened, so the row is still owed. Deleting it here
        // would lose the reconciliation for good.
        assertEquals(1, outbox.count())
    }

    @Test
    fun a_retried_row_does_not_duplicate_history() = runBlocking {
        like()
        backend.onState = { SyncOutcome.Transient("no network") }
        engine().drain()

        // The network comes back; the same row is delivered again.
        backend.onState = { SyncOutcome.Success }
        clock += 60_000
        engine().drain()

        // Handed over twice, stored once. This is the whole job of event_id.
        assertEquals(2, backend.delivered.size)
        assertEquals(1, backend.history.size)
        assertEquals("LIKED", backend.state[depeche])
        assertEquals(0, outbox.count())
    }

    @Test
    fun a_retry_reuses_the_original_event_id() = runBlocking {
        like()
        val original = outbox.pending().single().eventId

        backend.onState = { SyncOutcome.Transient("down") }
        engine().drain()
        clock += 60_000
        backend.onState = { SyncOutcome.Success }
        engine().drain()

        assertEquals(listOf(original, original), backend.delivered.map { it.eventId })
    }

    // ==================== failure handling ====================

    @Test
    fun a_transient_failure_backs_the_row_off_and_keeps_it() = runBlocking {
        like()
        backend.onEvent = { SyncOutcome.Transient("timeout") }

        val result = engine().drain()

        assertTrue(result is DrainResult.RetryLater)
        val row = outbox.pending().single()
        assertEquals(1, row.attempts)
        assertEquals(clock + 30_000L, row.nextAttemptAt)
        // And it is genuinely not due yet, so the next run will not hot-loop it.
        assertEquals(0, outbox.dueCount(clock))
        assertEquals(1, outbox.dueCount(clock + 30_000L))
    }

    @Test
    fun a_backed_off_row_is_skipped_until_its_time() = runBlocking {
        like()
        backend.onEvent = { SyncOutcome.Transient("timeout") }
        engine().drain()

        backend.onEvent = { SyncOutcome.Success }
        // Too early: nothing due, so the run is idle rather than a retry storm.
        assertEquals(DrainResult.Idle, engine().drain())
        assertTrue(backend.history.isEmpty())

        clock += 30_000
        engine().drain()
        assertEquals(1, backend.history.size)
    }

    @Test
    fun auth_failure_midway_does_not_penalise_the_row() = runBlocking {
        like()
        backend.onEvent = { SyncOutcome.AuthUnavailable("401") }

        val result = engine().drain()

        assertTrue(result is DrainResult.RetryLater)
        val row = outbox.pending().single()
        assertEquals(0, row.attempts)
        assertEquals(0L, row.nextAttemptAt)
    }

    @Test
    fun a_poison_row_does_not_block_the_rows_behind_it() = runBlocking {
        like()
        like(cave, "Nick Cave", "Red Right Hand")
        val poison = outbox.pending().first()

        backend.onEvent = { entry ->
            if (entry.eventId == poison.eventId) SyncOutcome.Permanent(400, "check constraint")
            else SyncOutcome.Success
        }

        val result = engine().drain()

        // The good row went. The poison one stayed, counted and parked for an hour.
        assertEquals(listOf(cave), backend.history.values.map { it.trackKey })
        assertEquals("LIKED", backend.state[cave])
        val remaining = outbox.pending().single()
        assertEquals(poison.eventId, remaining.eventId)
        assertEquals(1, remaining.attempts)
        assertEquals(clock + 3_600_000L, remaining.nextAttemptAt)
        // The run itself is not a failure: a row the server refuses must not turn
        // into a failed work request that cancels everything chained behind it.
        assertTrue(result is DrainResult.Drained)
    }

    @Test
    fun a_poison_row_never_blocks_the_current_state_of_its_own_track() = runBlocking {
        // The case the drain contract calls out: an older event on a track is stuck,
        // a newer one on the SAME track must still fix the remote state.
        like()
        val poison = outbox.pending().single()
        clock += 1_000
        dislike()

        backend.onEvent = { entry ->
            if (entry.eventId == poison.eventId) SyncOutcome.Permanent(400, "bad row")
            else SyncOutcome.Success
        }

        engine().drain()

        // History is missing the stuck one, which is honest and recoverable.
        assertEquals(listOf(ReactionEvent.DISLIKE), backend.history.values.map { it.eventType })
        // The current state is right regardless, because it came from Room.
        assertEquals("DISLIKED", backend.state[depeche])
    }

    @Test
    fun a_permanent_failure_is_never_discarded() = runBlocking {
        like()
        backend.onEvent = { SyncOutcome.Permanent(403, "rls") }

        repeat(5) {
            engine().drain()
            clock += 25 * 60 * 60 * 1000L
        }

        // Still there after five days of trying. A row that cannot sync is the only
        // evidence that something is wrong.
        val row = outbox.pending().single()
        assertEquals(5, row.attempts)
    }

    // ==================== order, batching, restart ====================

    @Test
    fun delivery_order_is_local_insertion_order_not_the_clock() = runBlocking {
        // A clock that jumps backwards between two reactions - an NTP correction, a
        // timezone change. occurred_at is now out of order; rowid is not.
        dao.like(depeche, "Depeche Mode", "Enjoy the Silence", "myata", 9_000L, 9_000L)
        dao.like(cave, "Nick Cave", "Red Right Hand", "gold", 1_000L, 1_000L)

        engine().drain()

        assertEquals(listOf(9_000L, 1_000L), backend.history.values.map { it.occurredAt })
        assertEquals(listOf(depeche, cave), backend.history.values.map { it.trackKey })
    }

    @Test
    fun a_full_batch_reports_that_more_is_due() = runBlocking {
        repeat(5) { i ->
            val key = TrackKey.of("Artist $i", "Title $i")!!
            dao.like(key, "Artist $i", "Title $i", "myata", clock + i, clock + i)
        }

        val result = engine(batchSize = 2).drain()

        assertTrue(result is DrainResult.MoreWorkDue)
        assertEquals(3, (result as DrainResult.MoreWorkDue).remaining)
        assertEquals(2, backend.history.size)
        assertEquals(3, outbox.count())
    }

    @Test
    fun repeated_bounded_runs_drain_everything_in_order() = runBlocking {
        val keys = (0 until 5).map { TrackKey.of("Artist $it", "Title $it")!! }
        keys.forEachIndexed { i, key ->
            dao.like(key, "Artist $i", "Title $i", "myata", clock + i, clock + i)
        }

        val small = engine(batchSize = 2)
        repeat(3) { small.drain() }

        assertEquals(0, outbox.count())
        assertEquals(keys, backend.history.values.map { it.trackKey })
    }

    @Test
    fun pending_work_survives_a_process_restart() = runBlocking {
        val name = "sync_restart_test.db"
        context.deleteDatabase(name)

        fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name).build()

        // First run: two reactions, and a backend that is entirely offline.
        val first = open()
        try {
            first.reactionDao().like(depeche, "Depeche Mode", "Enjoy the Silence", "myata", 1_000L, 1_000L)
            first.reactionDao().dislike(cave, "Nick Cave", "Red Right Hand", "gold", 2_000L)

            val offline = FakeBackend().apply { onEvent = { SyncOutcome.Transient("offline") } }
            val result = ReactionSyncEngine(
                first.reactionDao(), first.reactionOutboxDao(), offline, { listener }, { 5_000L },
            ).drain()

            assertTrue(result is DrainResult.RetryLater)
            assertEquals(2, first.reactionOutboxDao().count())
        } finally {
            first.close()
        }

        // Second run, as after process death. The work is still owed, and the
        // backoff written before the kill is still being served.
        val second = open()
        try {
            assertEquals(2, second.reactionOutboxDao().count())
            val online = FakeBackend()
            val result = ReactionSyncEngine(
                second.reactionDao(), second.reactionOutboxDao(), online, { listener },
                // Past the 30s backoff the first run recorded.
                { 5_000L + 60_000L },
            ).drain()

            assertTrue(result is DrainResult.Drained)
            assertEquals(
                listOf(ReactionEvent.LIKE, ReactionEvent.DISLIKE),
                online.history.values.map { it.eventType },
            )
            assertEquals("LIKED", online.state[depeche])
            assertEquals("DISLIKED", online.state[cave])
            assertEquals(0, second.reactionOutboxDao().count())
        } finally {
            second.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun two_tracks_reconcile_independently() = runBlocking {
        like()
        like(cave, "Nick Cave", "Red Right Hand")
        clock += 1_000
        dao.unlike(depeche, clock)

        engine().drain()

        assertEquals(3, backend.history.size)
        assertFalse(backend.state.containsKey(depeche))
        assertEquals("LIKED", backend.state[cave])
        assertEquals(0, outbox.count())
    }
}
