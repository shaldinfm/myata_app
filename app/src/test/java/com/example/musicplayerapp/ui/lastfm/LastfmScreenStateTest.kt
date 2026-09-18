package com.example.musicplayerapp.ui.lastfm

import com.example.musicplayerapp.R
import com.example.musicplayerapp.data.lastfm.LastfmLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What is on disk, as the card and the Settings row show it - one mapping, so the
 * two can never disagree.
 */
class LastfmScreenStateTest {

    private val linked = LastfmLink.Linked("sk-fake", "f0ul482")
    private val pending = LastfmLink.Pending("tok-fake", 1L)
    private val reauth = LastfmLink.ReauthRequired("f0ul482")

    @Test
    fun `each link maps to its card`() {
        assertEquals(LastfmCardState.Disconnected, LastfmScreenState.cardFor(LastfmLink.NotLinked, null))
        assertEquals(LastfmCardState.AuthorizationPending, LastfmScreenState.cardFor(pending, null))
        assertEquals(LastfmCardState.Connected("f0ul482", 1_248), LastfmScreenState.cardFor(linked, 1_248))
        assertEquals(LastfmCardState.ReauthRequired("f0ul482"), LastfmScreenState.cardFor(reauth, null))
    }

    @Test
    fun `a linked account with no count yet is still connected`() {
        assertEquals(LastfmCardState.Connected("f0ul482", null), LastfmScreenState.cardFor(linked, null))
    }

    @Test
    fun `the count is only ever attached to a linked account`() {
        // A stale count must not survive into another state.
        assertEquals(LastfmCardState.Disconnected, LastfmScreenState.cardFor(LastfmLink.NotLinked, 1_248))
        assertEquals(LastfmCardState.ReauthRequired("f0ul482"), LastfmScreenState.cardFor(reauth, 1_248))
    }

    @Test
    fun `the Settings row says what the owner decided for each state`() {
        assertEquals(
            LastfmScreenState.RowValue.Res(R.string.settings_lastfm_not_connected),
            LastfmScreenState.rowValueFor(LastfmLink.NotLinked),
        )
        assertEquals(
            LastfmScreenState.RowValue.Res(R.string.settings_lastfm_pending),
            LastfmScreenState.rowValueFor(pending),
        )
        assertEquals(
            "the linked row names the account",
            LastfmScreenState.RowValue.Text("f0ul482"),
            LastfmScreenState.rowValueFor(linked),
        )
        assertEquals(
            LastfmScreenState.RowValue.Res(R.string.settings_lastfm_reauth),
            LastfmScreenState.rowValueFor(reauth),
        )
    }

    @Test
    fun `only pending and re-auth carry a second action`() {
        assertEquals(
            R.string.lastfm_action_cancel,
            LastfmCard.contentFor(LastfmCardState.AuthorizationPending).secondaryLabelRes,
        )
        assertEquals(
            R.string.lastfm_action_disconnect,
            LastfmCard.contentFor(LastfmCardState.ReauthRequired("f0ul482")).secondaryLabelRes,
        )
        assertNull(LastfmCard.contentFor(LastfmCardState.Disconnected).secondaryLabelRes)
        assertNull(LastfmCard.contentFor(LastfmCardState.Connected("f0ul482", 1)).secondaryLabelRes)
    }
}
