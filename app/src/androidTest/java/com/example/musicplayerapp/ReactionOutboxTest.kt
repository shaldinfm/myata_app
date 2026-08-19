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
import com.example.musicplayerapp.data.TrackKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The outbox as the sync worker will find it.
 *
 * Every test here is a sentence about one listener acting once: what they did, what
 * the app now thinks, and what the backend is owed. The two have to agree, and the
 * only way they can disagree is if the pair of writes is not really one write - so
 * that is what most of this file is about.
 *
 * The transition table being pinned:
 *
 * ```
 *   NEUTRAL  -> LIKED     LIKE          LIKED    -> NEUTRAL   UNLIKE
 *   NEUTRAL  -> DISLIKED  DISLIKE       DISLIKED -> NEUTRAL   UNDISLIKE
 *   LIKED    -> DISLIKED  DISLIKE       DISLIKED -> LIKED     LIKE
 * ```
 *
 * The bottom row is the one worth staring at. Changing your mind is **one** act and
 * therefore one event: a LIKED -> DISLIKED must never appear as an UNLIKE followed
 * by a DISLIKE, because the listener never withdrew anything, and a backend told
 * otherwise would count an act nobody performed.
 */
@RunWith(AndroidJUnit4::class)
class ReactionOutboxTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: ReactionDao
    private lateinit var outbox: ReactionOutboxDao

    private val depecheKey = TrackKey.of("Depeche Mode", "Enjoy the Silence")!!
    private val caveKey = TrackKey.of("Nick Cave", "Red Right Hand")!!

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
        ).build()
        dao = db.reactionDao()
        outbox = db.reactionOutboxDao()
    }

    @After
    fun close() {
        db.close()
    }

    private suspend fun likeDepeche(now: Long = 1_000L, eventId: String? = null) =
        if (eventId == null) {
            dao.like(depecheKey, "Depeche Mode", "Enjoy the Silence", "myata", now, now)
        } else {
            dao.like(depecheKey, "Depeche Mode", "Enjoy the Silence", "myata", now, now, eventId)
        }

    private suspend fun dislikeDepeche(now: Long = 1_000L) =
        dao.dislike(depecheKey, "Depeche Mode", "Enjoy the Silence", "myata", now)

    private suspend fun events() = outbox.pending()

    private suspend fun singleEvent(): ReactionOutboxEntry {
        val all = events()
        assertEquals("expected exactly one queued event, got $all", 1, all.size)
        return all.single()
    }

    // ==================== every real transition is one event ====================

    @Test
    fun neutral_to_liked_queues_one_like() = runBlocking {
        assertTrue(likeDepeche())

        assertEquals(ReactionEvent.LIKE, singleEvent().eventType)
        assertEquals(Reaction.LIKED, dao.find(depecheKey)!!.reaction)
    }

    @Test
    fun liked_to_neutral_queues_one_unlike() = runBlocking {
        likeDepeche()
        assertTrue(dao.unlike(depecheKey, 2_000L))

        assertEquals(
            listOf(ReactionEvent.LIKE, ReactionEvent.UNLIKE),
            events().map { it.eventType },
        )
        assertEquals(Reaction.NEUTRAL, dao.find(depecheKey)!!.reaction)
    }

    @Test
    fun neutral_to_disliked_queues_one_dislike() = runBlocking {
        assertTrue(dislikeDepeche())

        assertEquals(ReactionEvent.DISLIKE, singleEvent().eventType)
        assertEquals(Reaction.DISLIKED, dao.find(depecheKey)!!.reaction)
    }

    @Test
    fun disliked_to_neutral_queues_one_undislike() = runBlocking {
        dislikeDepeche()
        assertTrue(dao.undislike(depecheKey, 2_000L))

        assertEquals(
            listOf(ReactionEvent.DISLIKE, ReactionEvent.UNDISLIKE),
            events().map { it.eventType },
        )
        assertEquals(Reaction.NEUTRAL, dao.find(depecheKey)!!.reaction)
    }

    // ==================== changing your mind is one act ====================

    @Test
    fun liked_to_disliked_queues_one_dislike_and_no_unlike() = runBlocking {
        likeDepeche(now = 1_000L)
        assertTrue(dislikeDepeche(now = 2_000L))

        // Two acts happened, so there are two events - and the second is the
        // DISLIKE alone. The intermediate NEUTRAL was never passed through, so no
        // UNLIKE may appear for it.
        assertEquals(
            listOf(ReactionEvent.LIKE, ReactionEvent.DISLIKE),
            events().map { it.eventType },
        )
        assertFalse(events().any { it.eventType == ReactionEvent.UNLIKE })
        assertEquals(Reaction.DISLIKED, dao.find(depecheKey)!!.reaction)
    }

    @Test
    fun disliked_to_liked_queues_one_like_and_no_undislike() = runBlocking {
        dislikeDepeche(now = 1_000L)
        assertTrue(likeDepeche(now = 2_000L))

        assertEquals(
            listOf(ReactionEvent.DISLIKE, ReactionEvent.LIKE),
            events().map { it.eventType },
        )
        assertFalse(events().any { it.eventType == ReactionEvent.UNDISLIKE })
        assertEquals(Reaction.LIKED, dao.find(depecheKey)!!.reaction)
    }

    // ==================== a no-op is not an act ====================

    @Test
    fun liking_an_already_liked_track_queues_nothing() = runBlocking {
        likeDepeche(now = 1_000L)
        assertFalse(likeDepeche(now = 2_000L))
        assertFalse(likeDepeche(now = 3_000L))

        // Four taps on Like, one opinion, one event.
        assertEquals(ReactionEvent.LIKE, singleEvent().eventType)
    }

    @Test
    fun disliking_an_already_disliked_track_queues_nothing() = runBlocking {
        dislikeDepeche(now = 1_000L)
        assertFalse(dislikeDepeche(now = 2_000L))

        assertEquals(ReactionEvent.DISLIKE, singleEvent().eventType)
    }

    @Test
    fun withdrawing_an_opinion_that_is_not_there_queues_nothing() = runBlocking {
        // No row at all.
        assertFalse(dao.unlike(depecheKey, 1_000L))
        assertFalse(dao.undislike(depecheKey, 1_000L))
        assertEquals(emptyList<ReactionOutboxEntry>(), events())

        // A row in the wrong state: un-liking a disliked track withdraws nothing.
        dislikeDepeche(now = 2_000L)
        assertFalse(dao.unlike(depecheKey, 3_000L))
        assertEquals(ReactionEvent.DISLIKE, singleEvent().eventType)

        // And the mirror: un-disliking a liked one.
        dao.undislike(depecheKey, 4_000L)
        likeDepeche(now = 5_000L)
        assertFalse(dao.undislike(depecheKey, 6_000L))
        assertEquals(
            listOf(ReactionEvent.DISLIKE, ReactionEvent.UNDISLIKE, ReactionEvent.LIKE),
            events().map { it.eventType },
        )
    }

    @Test
    fun a_neutral_row_that_is_neutralised_again_queues_nothing() = runBlocking {
        likeDepeche(now = 1_000L)
        assertTrue(dao.unlike(depecheKey, 2_000L))
        assertFalse(dao.unlike(depecheKey, 3_000L))

        assertEquals(2, events().size)
    }

    // ==================== what an event carries ====================

    @Test
    fun the_event_carries_the_key_and_the_words() = runBlocking {
        dao.like(caveKey, "Nick Cave", "Red Right Hand", "gold", 7_000L, 7_500L)

        val event = singleEvent()
        assertEquals(caveKey, event.trackKey)
        assertEquals("Nick Cave", event.artist)
        assertEquals("Red Right Hand", event.title)
        assertEquals("gold", event.stream)
        assertEquals(ReactionEvent.LIKE, event.eventType)
        // occurredAt is when the listener acted, not liked_at, which is a position
        // in the Collection and can be a value from long before.
        assertEquals(7_500L, event.occurredAt)
        assertEquals(0, event.attempts)
        assertEquals(0L, event.nextAttemptAt)
    }

    @Test
    fun a_withdrawal_carries_the_words_from_the_row_it_withdrew() = runBlocking {
        // unlike() is given only a key. The artist, title and stream in its event
        // can only have come from the stored row, which is the point: the key is a
        // hash and cannot be turned back into words.
        dao.like(caveKey, "Nick Cave", "Red Right Hand", "gold", 1_000L, 1_000L)
        dao.unlike(caveKey, 2_000L)

        val event = events().last()
        assertEquals(ReactionEvent.UNLIKE, event.eventType)
        assertEquals(caveKey, event.trackKey)
        assertEquals("Nick Cave", event.artist)
        assertEquals("Red Right Hand", event.title)
        assertEquals("gold", event.stream)
        assertEquals(2_000L, event.occurredAt)
    }

    // ==================== identity ====================

    @Test
    fun every_event_gets_its_own_id() = runBlocking {
        likeDepeche(now = 1_000L)
        dao.unlike(depecheKey, 2_000L)
        likeDepeche(now = 3_000L)
        dao.dislike(caveKey, "Nick Cave", "Red Right Hand", "gold", 4_000L)

        val ids = events().map { it.eventId }
        assertEquals(4, ids.size)
        assertEquals(4, ids.toSet().size)
        assertTrue(ids.none { it.isBlank() })
    }

    @Test
    fun two_ids_minted_in_a_row_are_different() {
        val ids = List(500) { ReactionOutboxEntry.newEventId() }
        assertEquals(500, ids.toSet().size)
    }

    @Test
    fun two_tracks_queue_independent_events() = runBlocking {
        likeDepeche(now = 1_000L)
        dao.dislike(caveKey, "Nick Cave", "Red Right Hand", "gold", 2_000L)
        dao.unlike(depecheKey, 3_000L)

        val all = events()
        assertEquals(3, all.size)
        assertEquals(
            listOf(ReactionEvent.LIKE, ReactionEvent.UNLIKE),
            all.filter { it.trackKey == depecheKey }.map { it.eventType },
        )
        assertEquals(
            listOf(ReactionEvent.DISLIKE),
            all.filter { it.trackKey == caveKey }.map { it.eventType },
        )
    }

    @Test
    fun the_queue_is_ordered_by_when_it_was_written_not_by_the_clock() = runBlocking {
        // A clock that went backwards between the two acts: an NTP correction, a
        // timezone change, a reboot before time sync. occurred_at now disagrees with
        // what actually happened first.
        dao.dislike(caveKey, "Nick Cave", "Red Right Hand", "gold", 9_000L)
        likeDepeche(now = 1_000L)

        // The queue is in the order the rows were written, not the order the clock
        // claims. Local insertion order is causal and cannot go backwards; a device
        // wall clock can, and handing the backend a listener's history inside out is
        // the failure this ordering exists to prevent. See ReactionOutboxDao.
        assertEquals(listOf(9_000L, 1_000L), events().map { it.occurredAt })
        assertEquals(listOf(caveKey, depecheKey), events().map { it.trackKey })
    }

    // ==================== the pair is one write ====================

    @Test
    fun the_state_and_its_event_are_both_there_afterwards() = runBlocking {
        assertTrue(likeDepeche(now = 1_000L))

        assertEquals(Reaction.LIKED, dao.find(depecheKey)!!.reaction)
        assertEquals(1, dao.likedTracks().first().size)
        assertEquals(1, outbox.count())
        assertEquals(depecheKey, singleEvent().trackKey)
    }

    @Test
    fun a_transition_whose_event_cannot_be_written_leaves_no_state_change() = runBlocking {
        // Mint one event under a known id, so the next one collides with it. This is
        // the only way to make the second half of the pair fail on demand, and what
        // it stands in for is any failure at all between the two writes.
        likeDepeche(now = 1_000L, eventId = "the-same-id")
        assertEquals(1, outbox.count())

        var threw = false
        try {
            dao.like(caveKey, "Nick Cave", "Red Right Hand", "gold", 2_000L, 2_000L, "the-same-id")
            fail("a duplicate event id must not be accepted")
        } catch (expected: Exception) {
            threw = true
        }
        assertTrue(threw)

        // Neither half survived: no reaction for the second track...
        assertNull(dao.find(caveKey))
        assertEquals(listOf(depecheKey), dao.likedTracks().first().map { it.trackKey })
        // ...and no second event.
        assertEquals(1, outbox.count())
        assertEquals(depecheKey, singleEvent().trackKey)
    }

    @Test
    fun a_rolled_back_withdrawal_leaves_the_like_in_place() = runBlocking {
        // The same failure on the other kind of transition: unlike() writes an
        // UPDATE before it enqueues, so a rollback has something real to undo.
        likeDepeche(now = 1_000L, eventId = "the-same-id")

        try {
            dao.unlike(depecheKey, 2_000L, "the-same-id")
            fail("a duplicate event id must not be accepted")
        } catch (expected: Exception) {
            // expected
        }

        // Still liked, still in the Collection, still one event.
        assertEquals(Reaction.LIKED, dao.find(depecheKey)!!.reaction)
        assertEquals(1, dao.likedTracks().first().size)
        assertEquals(1, outbox.count())
        assertEquals(ReactionEvent.LIKE, singleEvent().eventType)
    }

    // ==================== the queue as a sender sees it ====================

    @Test
    fun a_delivered_event_is_the_only_thing_that_leaves_the_queue() = runBlocking {
        likeDepeche(now = 1_000L)
        val event = singleEvent()

        assertNotNull(outbox.find(event.eventId))
        assertEquals(1, outbox.delete(event.eventId))
        assertEquals(0, outbox.count())

        // Deleting the event does not touch what the listener thinks.
        assertEquals(Reaction.LIKED, dao.find(depecheKey)!!.reaction)
        assertEquals(1, dao.likedTracks().first().size)
    }

    @Test
    fun a_backed_off_row_is_not_due_until_its_time() = runBlocking {
        likeDepeche(now = 1_000L)
        val event = singleEvent()

        assertEquals(1, outbox.due(now = 1_000L, limit = 10).size)

        assertEquals(1, outbox.recordFailedAttempt(event.eventId, nextAttemptAt = 60_000L))
        assertEquals(emptyList<ReactionOutboxEntry>(), outbox.due(now = 30_000L, limit = 10))
        assertEquals(1, outbox.due(now = 60_000L, limit = 10).size)
        assertEquals(1, outbox.find(event.eventId)!!.attempts)
        // The act itself is never revised by a failure to deliver it.
        assertEquals(1_000L, outbox.find(event.eventId)!!.occurredAt)
        assertEquals(ReactionEvent.LIKE, outbox.find(event.eventId)!!.eventType)
    }
}
