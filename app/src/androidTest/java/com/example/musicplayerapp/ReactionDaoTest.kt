package com.example.musicplayerapp

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayerapp.data.AppDatabase
import com.example.musicplayerapp.data.Reaction
import com.example.musicplayerapp.data.ReactionDao
import com.example.musicplayerapp.data.TrackKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The reaction model as the screens use it.
 *
 * Every test here is a sentence about the Collection: what is in it, in what order,
 * and what a tap does to it. The three states are the point - LIKED is the
 * Collection, NEUTRAL and DISLIKED are both "not in the Collection", and the
 * difference between them is an opinion the listener expressed, not a membership.
 */
@RunWith(AndroidJUnit4::class)
class ReactionDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: ReactionDao

    private val depecheKey = TrackKey.of("Depeche Mode", "Enjoy the Silence")!!
    private val caveKey = TrackKey.of("Nick Cave", "Red Right Hand")!!

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
        ).build()
        dao = db.reactionDao()
    }

    @After
    fun close() {
        db.close()
    }

    private suspend fun likeDepeche(likedAt: Long = 1_000L) = dao.like(
        trackKey = depecheKey,
        artist = "Depeche Mode",
        title = "Enjoy the Silence",
        stream = "myata",
        likedAt = likedAt,
    )

    private suspend fun likeCave(likedAt: Long = 2_000L) = dao.like(
        trackKey = caveKey,
        artist = "Nick Cave",
        title = "Red Right Hand",
        stream = "gold",
        likedAt = likedAt,
    )

    // ==================== LIKED is the Collection ====================

    @Test
    fun a_liked_track_is_in_the_collection() = runBlocking {
        assertTrue(likeDepeche())

        val collection = dao.likedTracks().first()
        assertEquals(1, collection.size)
        assertEquals("Depeche Mode", collection[0].artist)
        assertEquals("Enjoy the Silence", collection[0].track)
        assertEquals("myata", collection[0].stream)
        assertEquals(1_000L, collection[0].addedAt)
        assertEquals(depecheKey, collection[0].trackKey)
        assertTrue(dao.isLiked(depecheKey).first())
    }

    @Test
    fun liking_the_same_track_twice_changes_nothing() = runBlocking {
        assertTrue(likeDepeche(likedAt = 1_000L))
        assertFalse(likeDepeche(likedAt = 9_999L))

        // Still one row, and still in its original position: a repeated tap is not a
        // second opinion and must not move the row to the top of the list either.
        val collection = dao.likedTracks().first()
        assertEquals(1, collection.size)
        assertEquals(1_000L, collection[0].addedAt)
    }

    // ==================== NEUTRAL is not the Collection ====================

    @Test
    fun unliking_removes_it_from_the_collection_but_keeps_the_row() = runBlocking {
        likeDepeche()
        assertTrue(dao.unlike(depecheKey))

        assertEquals(emptyList<Any>(), dao.likedTracks().first())
        assertFalse(dao.isLiked(depecheKey).first())

        // The row survives as NEUTRAL - not DISLIKED, which is the whole point.
        val row = dao.find(depecheKey)
        assertNotNull(row)
        assertEquals(Reaction.NEUTRAL, row!!.reaction)
    }

    @Test
    fun unliking_something_that_is_not_liked_changes_nothing() = runBlocking {
        assertFalse(dao.unlike(depecheKey))

        likeDepeche()
        assertTrue(dao.unlike(depecheKey))
        assertFalse(dao.unlike(depecheKey))
    }

    // ==================== DISLIKED is not the Collection either ====================

    @Test
    fun a_disliked_track_is_not_in_the_collection() = runBlocking {
        assertTrue(
            dao.dislike(
                trackKey = caveKey,
                artist = "Nick Cave",
                title = "Red Right Hand",
                stream = "gold",
            )
        )

        assertEquals(emptyList<Any>(), dao.likedTracks().first())
        assertFalse(dao.isLiked(caveKey).first())
        assertEquals(Reaction.DISLIKED, dao.find(caveKey)!!.reaction)
    }

    @Test
    fun a_track_can_move_straight_between_liked_and_disliked() = runBlocking {
        likeCave()
        assertTrue(
            dao.dislike(caveKey, "Nick Cave", "Red Right Hand", "gold")
        )
        assertEquals(emptyList<Any>(), dao.likedTracks().first())

        assertTrue(likeCave(likedAt = 3_000L))
        assertEquals(1, dao.likedTracks().first().size)
        assertEquals(Reaction.LIKED, dao.find(caveKey)!!.reaction)
    }

    @Test
    fun undisliking_returns_to_neutral_and_not_to_the_collection() = runBlocking {
        dao.dislike(caveKey, "Nick Cave", "Red Right Hand", "gold")
        assertTrue(dao.undislike(caveKey))

        assertEquals(Reaction.NEUTRAL, dao.find(caveKey)!!.reaction)
        assertEquals(emptyList<Any>(), dao.likedTracks().first())
        assertFalse(dao.isLiked(caveKey).first())
    }

    @Test
    fun each_withdrawal_only_touches_its_own_opinion() = runBlocking {
        // undislike must not clear a Like, and unlike must not clear a Dislike:
        // which opinion is being withdrawn is the whole content of the act.
        likeDepeche()
        assertFalse(dao.undislike(depecheKey))
        assertEquals(Reaction.LIKED, dao.find(depecheKey)!!.reaction)

        dao.dislike(caveKey, "Nick Cave", "Red Right Hand", "gold")
        assertFalse(dao.unlike(caveKey))
        assertEquals(Reaction.DISLIKED, dao.find(caveKey)!!.reaction)
    }

    @Test
    fun the_observed_reaction_is_what_the_player_draws() = runBlocking {
        // Null while nobody has reacted; the caller reads that as NEUTRAL.
        assertEquals(null, dao.observeReaction(depecheKey).first())

        likeDepeche()
        assertEquals(Reaction.LIKED, dao.observeReaction(depecheKey).first())

        dao.dislike(depecheKey, "Depeche Mode", "Enjoy the Silence", "myata")
        assertEquals(Reaction.DISLIKED, dao.observeReaction(depecheKey).first())

        dao.undislike(depecheKey)
        assertEquals(Reaction.NEUTRAL, dao.observeReaction(depecheKey).first())
    }

    @Test
    fun a_second_track_keeps_its_own_reaction() = runBlocking {
        // Switching tracks shows that track's stored reaction, not the last one's.
        likeDepeche()
        dao.dislike(caveKey, "Nick Cave", "Red Right Hand", "gold")

        assertEquals(Reaction.LIKED, dao.observeReaction(depecheKey).first())
        assertEquals(Reaction.DISLIKED, dao.observeReaction(caveKey).first())
        assertEquals(listOf("Depeche Mode"), dao.likedTracks().first().map { it.artist })
    }

    // ==================== Ordering and Undo ====================

    @Test
    fun the_collection_is_newest_liked_first() = runBlocking {
        likeDepeche(likedAt = 1_000L)
        likeCave(likedAt = 2_000L)

        assertEquals(
            listOf("Nick Cave", "Depeche Mode"),
            dao.likedTracks().first().map { it.artist },
        )
    }

    @Test
    fun undo_puts_a_row_back_where_it_was() = runBlocking {
        likeDepeche(likedAt = 1_000L)
        likeCave(likedAt = 2_000L)

        // Remove the older row, then undo it exactly as FavoritesViewModel does: like
        // it again with the addedAt the removed row carried.
        val removed = dao.likedTracks().first().single { it.trackKey == depecheKey }
        assertTrue(dao.unlike(removed.trackKey))
        assertEquals(listOf("Nick Cave"), dao.likedTracks().first().map { it.artist })

        assertTrue(
            dao.like(
                trackKey = removed.trackKey,
                artist = removed.artist,
                title = removed.track,
                stream = removed.stream,
                likedAt = removed.addedAt,
            )
        )

        // Back in second place, not at the top.
        assertEquals(
            listOf("Nick Cave", "Depeche Mode"),
            dao.likedTracks().first().map { it.artist },
        )
        assertEquals(1_000L, dao.likedTracks().first().last().addedAt)
    }

    @Test
    fun a_track_the_key_refuses_can_hold_no_reaction() = runBlocking {
        // Not a DAO rule but the contract the callers rely on: these never reach the
        // database, because there is no key to file them under.
        assertEquals(null, TrackKey.of("", "Enjoy the Silence"))
        assertEquals(null, TrackKey.of("YOUR MUSIC! YOUR STATION!", "Jingle"))
        assertEquals(emptyList<Any>(), dao.likedTracks().first())
    }
}
