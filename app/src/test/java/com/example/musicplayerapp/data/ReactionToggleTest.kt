package com.example.musicplayerapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Every cell of the reaction table, including the ones no screen can reach today.
 *
 * The six transitions and their events are the product decision this whole feature
 * is; asserting them here rather than through the UI is what makes "removing a Like
 * never reports a dislike" a fact about the model instead of a fact about one
 * fragment.
 */
class ReactionToggleTest {

    // ==================== Where a tap leads ====================

    @Test
    fun `tapping like`() {
        assertEquals(Reaction.LIKED, ReactionToggle.likeTap(Reaction.NEUTRAL))
        assertEquals(Reaction.NEUTRAL, ReactionToggle.likeTap(Reaction.LIKED))
        assertEquals(Reaction.LIKED, ReactionToggle.likeTap(Reaction.DISLIKED))
    }

    @Test
    fun `tapping dislike`() {
        assertEquals(Reaction.DISLIKED, ReactionToggle.dislikeTap(Reaction.NEUTRAL))
        assertEquals(Reaction.DISLIKED, ReactionToggle.dislikeTap(Reaction.LIKED))
        assertEquals(Reaction.NEUTRAL, ReactionToggle.dislikeTap(Reaction.DISLIKED))
    }

    @Test
    fun `a control always undoes its own opinion`() {
        // Tapping a control twice from neutral, or from the state that control owns,
        // returns to where it started: an opinion is never one-way, and never needs
        // the other control to clear it.
        assertEquals(Reaction.NEUTRAL, ReactionToggle.likeTap(ReactionToggle.likeTap(Reaction.NEUTRAL)))
        assertEquals(Reaction.LIKED, ReactionToggle.likeTap(ReactionToggle.likeTap(Reaction.LIKED)))
        assertEquals(Reaction.NEUTRAL, ReactionToggle.dislikeTap(ReactionToggle.dislikeTap(Reaction.NEUTRAL)))
        assertEquals(Reaction.DISLIKED, ReactionToggle.dislikeTap(ReactionToggle.dislikeTap(Reaction.DISLIKED)))
    }

    @Test
    fun `two taps that cross over end neutral, not back at the old opinion`() {
        // Like, twice, starting disliked: the first changes the listener's mind, the
        // second withdraws that. Landing back on DISLIKED would restore an opinion
        // they had already replaced.
        assertEquals(Reaction.NEUTRAL, ReactionToggle.likeTap(ReactionToggle.likeTap(Reaction.DISLIKED)))
        assertEquals(Reaction.NEUTRAL, ReactionToggle.dislikeTap(ReactionToggle.dislikeTap(Reaction.LIKED)))
    }

    // ==================== The six transitions ====================

    @Test
    fun `neutral to liked is LIKE`() {
        assertEquals(ReactionEvent.LIKE, ReactionToggle.eventFor(Reaction.NEUTRAL, Reaction.LIKED))
    }

    @Test
    fun `liked to neutral is UNLIKE, never DISLIKE`() {
        val event = ReactionToggle.eventFor(Reaction.LIKED, Reaction.NEUTRAL)
        assertEquals(ReactionEvent.UNLIKE, event)
    }

    @Test
    fun `neutral to disliked is DISLIKE`() {
        assertEquals(ReactionEvent.DISLIKE, ReactionToggle.eventFor(Reaction.NEUTRAL, Reaction.DISLIKED))
    }

    @Test
    fun `disliked to neutral is UNDISLIKE`() {
        assertEquals(ReactionEvent.UNDISLIKE, ReactionToggle.eventFor(Reaction.DISLIKED, Reaction.NEUTRAL))
    }

    @Test
    fun `liked to disliked is one DISLIKE, with no invented UNLIKE`() {
        assertEquals(ReactionEvent.DISLIKE, ReactionToggle.eventFor(Reaction.LIKED, Reaction.DISLIKED))
    }

    @Test
    fun `disliked to liked is one LIKE, with no invented UNDISLIKE`() {
        assertEquals(ReactionEvent.LIKE, ReactionToggle.eventFor(Reaction.DISLIKED, Reaction.LIKED))
    }

    // ==================== No-ops ====================

    @Test
    fun `staying in the same state reports nothing`() {
        for (state in Reaction.entries) {
            assertNull("$state -> $state", ReactionToggle.eventFor(state, state))
        }
    }

    @Test
    fun `a repeated tap on an active control is a no-op transition`() {
        // The second tap on Dislike while disliked goes to NEUTRAL, which is a real
        // transition. What must not report is a tap that lands where it already is,
        // which is what the DAO reports when another screen got there first.
        assertNull(ReactionToggle.eventFor(Reaction.LIKED, Reaction.LIKED))
        assertNull(ReactionToggle.eventFor(Reaction.DISLIKED, Reaction.DISLIKED))
    }

    // ==================== The table, whole ====================

    @Test
    fun `every transition in the table`() {
        val table = mapOf(
            (Reaction.NEUTRAL to Reaction.LIKED) to ReactionEvent.LIKE,
            (Reaction.LIKED to Reaction.NEUTRAL) to ReactionEvent.UNLIKE,
            (Reaction.NEUTRAL to Reaction.DISLIKED) to ReactionEvent.DISLIKE,
            (Reaction.DISLIKED to Reaction.NEUTRAL) to ReactionEvent.UNDISLIKE,
            (Reaction.LIKED to Reaction.DISLIKED) to ReactionEvent.DISLIKE,
            (Reaction.DISLIKED to Reaction.LIKED) to ReactionEvent.LIKE,
            (Reaction.NEUTRAL to Reaction.NEUTRAL) to null,
            (Reaction.LIKED to Reaction.LIKED) to null,
            (Reaction.DISLIKED to Reaction.DISLIKED) to null,
        )

        // Every ordered pair of states is covered, so a new state cannot be added
        // without this test asking what it means.
        assertEquals(Reaction.entries.size * Reaction.entries.size, table.size)

        for ((transition, expected) in table) {
            val (from, to) = transition
            assertEquals("$from -> $to", expected, ReactionToggle.eventFor(from, to))
        }
    }
}
