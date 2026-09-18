package com.example.musicplayerapp.ui.lastfm

import com.example.musicplayerapp.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The state-to-content table.
 *
 * What this protects is combinations. Every one of the four cards is the same
 * heading, two body lines and button box, so the way this goes wrong is not a
 * crash - it is the connected heading over the connect button, or the check left
 * showing on a card that is not connected. Those read as working software.
 */
class LastfmCardStateTest {

    @Test
    fun `disconnected is the frozen Not connected card`() {
        val c = LastfmCard.contentFor(LastfmCardState.Disconnected)

        assertEquals(R.string.lastfm_card_disconnected_heading, c.headingRes)
        assertEquals(R.string.lastfm_card_disconnected_body_1, c.bodyFirstRes)
        assertEquals(R.string.lastfm_card_disconnected_body_2, c.bodySecondRes)
        assertEquals(R.string.lastfm_action_connect, c.buttonLabelRes)
        assertFalse(c.logoTintPrimary)
        assertFalse(c.showCheck)
        assertTrue(c.buttonPrimary)
    }

    @Test
    fun `connected is the frozen Connected card`() {
        val c = LastfmCard.contentFor(LastfmCardState.Connected("f0ul482", 22_258))

        assertEquals(R.string.lastfm_card_connected_heading, c.headingRes)
        assertEquals(R.string.lastfm_action_disconnect, c.buttonLabelRes)
        assertTrue("the mark is primary only when linked", c.logoTintPrimary)
        assertTrue(c.showCheck)
        assertFalse("disconnect is the outlined button", c.buttonPrimary)
        // Both lines come from the account, not from resources.
        assertEquals(0, c.bodyFirstRes)
        assertNull(c.bodySecondRes)
    }

    @Test
    fun `authorization pending says what is unfinished and reopens Last fm`() {
        val c = LastfmCard.contentFor(LastfmCardState.AuthorizationPending)

        assertEquals(R.string.lastfm_card_pending_heading, c.headingRes)
        assertEquals(R.string.lastfm_card_pending_body, c.bodyFirstRes)
        assertNull("one line is the whole message", c.bodySecondRes)
        assertEquals(R.string.lastfm_action_open_lastfm, c.buttonLabelRes)
        assertFalse("nothing is linked yet", c.logoTintPrimary)
        assertFalse(c.showCheck)
        assertTrue(c.buttonPrimary)
    }

    @Test
    fun `re-auth states that the session ended, and never why`() {
        val c = LastfmCard.contentFor(LastfmCardState.ReauthRequired("f0ul482"))

        assertEquals(R.string.lastfm_card_reauth_heading, c.headingRes)
        assertEquals(R.string.lastfm_card_reauth_body_1, c.bodyFirstRes)
        assertEquals(R.string.lastfm_card_reauth_body_2, c.bodySecondRes)
        assertEquals(R.string.lastfm_action_reconnect, c.buttonLabelRes)
        assertFalse("the session is gone, so the mark is not primary", c.logoTintPrimary)
        assertFalse("and the check is not shown", c.showCheck)
        assertTrue(c.buttonPrimary)
    }

    @Test
    fun `only the connected card is marked as connected`() {
        // The tint and the check are the two things that say "your scrobbles are
        // reaching Last.fm". Exactly one state may claim that.
        val states = listOf(
            LastfmCardState.Disconnected,
            LastfmCardState.AuthorizationPending,
            LastfmCardState.ReauthRequired("f0ul482"),
            LastfmCardState.Connected("f0ul482", 1),
        )
        assertEquals(1, states.count { LastfmCard.contentFor(it).logoTintPrimary })
        assertEquals(1, states.count { LastfmCard.contentFor(it).showCheck })
        assertTrue(LastfmCard.contentFor(states.last()).logoTintPrimary)
    }

    @Test
    fun `every state has its own heading and its own button`() {
        // A state that silently reused another's wording would look like the wrong
        // state rather than like a bug.
        val states = listOf(
            LastfmCardState.Disconnected,
            LastfmCardState.AuthorizationPending,
            LastfmCardState.ReauthRequired("x"),
            LastfmCardState.Connected("x", null),
        )
        val headings = states.map { LastfmCard.contentFor(it).headingRes }
        val buttons = states.map { LastfmCard.contentFor(it).buttonLabelRes }
        assertEquals("headings must be distinct", headings.size, headings.toSet().size)
        assertEquals("buttons must be distinct", buttons.size, buttons.toSet().size)
    }

    @Test
    fun `a connected account with no playcount is still connected`() {
        // The count is an enrichment. A failed user_getInfo must not downgrade the
        // card to a state that says the listener is not linked.
        val c = LastfmCard.contentFor(LastfmCardState.Connected("f0ul482", null))

        assertTrue(c.logoTintPrimary)
        assertTrue(c.showCheck)
        assertEquals(R.string.lastfm_action_disconnect, c.buttonLabelRes)
    }

    @Test
    fun `connected carries the username it was given`() {
        val state = LastfmCardState.Connected("f0ul482", 22_258)
        assertEquals("f0ul482", state.username)
        assertEquals(22_258L, state.playcount)
        assertNotEquals(state, LastfmCardState.Connected("someone-else", 22_258))
    }
}
