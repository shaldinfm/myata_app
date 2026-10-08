package com.example.musicplayerapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The PLAYER's reaction controls show the reaction of the track that is playing - never
 * the previous track's, and never reset by a metadata poll repeating the same track.
 */
class CurrentTrackReactionTest {

    private val first = "v1|artist a|song a"
    private val second = "v1|artist b|song b"

    @Test
    fun `the first track is a change, the same track again is not`() {
        val current = CurrentTrackReaction()

        assertTrue(current.onTrack(first))
        // A poll republishing the same track: nothing to restart, nothing to clear.
        assertFalse(current.onTrack(first))
        assertFalse(current.onTrack(first))
        assertEquals(first, current.trackKey)
    }

    @Test
    fun `the stored reaction of the current track is shown, and survives repeats`() {
        val current = CurrentTrackReaction()
        current.onTrack(first)

        assertEquals(Reaction.LIKED, current.shown(first, Reaction.LIKED, visible = true))
        current.onTrack(first)
        assertEquals(Reaction.LIKED, current.shown(first, Reaction.LIKED, visible = true))
        assertEquals(Reaction.DISLIKED, current.shown(first, Reaction.DISLIKED, visible = true))
    }

    @Test
    fun `a late answer about the previous track is dropped after a track change`() {
        val current = CurrentTrackReaction()
        current.onTrack(first)
        assertTrue(current.onTrack(second))

        // The previous track's Like, arriving after the switch, must not be painted
        // onto the new track.
        assertNull(current.shown(first, Reaction.LIKED, visible = true))
        assertEquals(Reaction.NEUTRAL, current.shown(second, null, visible = true))
        assertEquals(Reaction.DISLIKED, current.shown(second, Reaction.DISLIKED, visible = true))
    }

    @Test
    fun `no row is neutral, and rows that are not this install's to show read neutral`() {
        val current = CurrentTrackReaction()
        current.onTrack(first)

        assertEquals(Reaction.NEUTRAL, current.shown(first, null, visible = true))
        assertEquals(Reaction.NEUTRAL, current.shown(first, Reaction.LIKED, visible = false))
    }

    @Test
    fun `between tracks nothing is current, and coming back to a track is a change`() {
        val current = CurrentTrackReaction()
        current.onTrack(first)

        assertTrue(current.onTrack(null))
        assertNull(current.shown(first, Reaction.LIKED, visible = true))
        assertTrue(current.onTrack(first))
        assertEquals(Reaction.LIKED, current.shown(first, Reaction.LIKED, visible = true))
    }
}
