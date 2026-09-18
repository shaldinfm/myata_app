package com.example.musicplayerapp.ui.lastfm

import com.example.musicplayerapp.R

/**
 * The one place a [LastfmCardState] becomes something drawable.
 *
 * A pure lookup, so the fragment can only ever draw a combination this table
 * allows: there is no path by which the connected heading reaches the disconnect
 * button, or the pending state renders a check mark. `LastfmCardStateTest` pins
 * the whole table.
 */
object LastfmCard {

    fun contentFor(state: LastfmCardState): LastfmCardContent = when (state) {

        LastfmCardState.Disconnected -> LastfmCardContent(
            headingRes = R.string.lastfm_card_disconnected_heading,
            bodyFirstRes = R.string.lastfm_card_disconnected_body_1,
            bodySecondRes = R.string.lastfm_card_disconnected_body_2,
            buttonLabelRes = R.string.lastfm_action_connect,
            logoTintPrimary = false,
            showCheck = false,
            buttonPrimary = true,
        )

        LastfmCardState.AuthorizationPending -> LastfmCardContent(
            headingRes = R.string.lastfm_card_pending_heading,
            bodyFirstRes = R.string.lastfm_card_pending_body,
            // One line is the whole message. The card hides the second rather than
            // inventing filler to match the frozen card's two.
            bodySecondRes = null,
            buttonLabelRes = R.string.lastfm_action_open_lastfm,
            // Not connected yet, so the mark stays secondary - the tint tracks the
            // session, not the fact that something is in progress.
            logoTintPrimary = false,
            showCheck = false,
            buttonPrimary = true,
            // The way out of a half-finished authorisation: forget the token and
            // go back to where the listener started.
            secondaryLabelRes = R.string.lastfm_action_cancel,
        )

        is LastfmCardState.Connected -> LastfmCardContent(
            headingRes = R.string.lastfm_card_connected_heading,
            // Both body lines are filled in from the account rather than a
            // resource - see SettingsLastfmFragment.
            bodyFirstRes = 0,
            bodySecondRes = null,
            buttonLabelRes = R.string.lastfm_action_disconnect,
            logoTintPrimary = true,
            showCheck = true,
            buttonPrimary = false,
        )

        is LastfmCardState.ReauthRequired -> LastfmCardContent(
            headingRes = R.string.lastfm_card_reauth_heading,
            bodyFirstRes = R.string.lastfm_card_reauth_body_1,
            bodySecondRes = R.string.lastfm_card_reauth_body_2,
            buttonLabelRes = R.string.lastfm_action_reconnect,
            // The session is gone, so the mark goes back to secondary. That is the
            // whole point of tinting it: it says whether scrobbles are reaching
            // Last.fm, and right now they are not.
            logoTintPrimary = false,
            showCheck = false,
            buttonPrimary = true,
            // The way out for a listener who does not want to reconnect: clear
            // everything, the retained username included.
            secondaryLabelRes = R.string.lastfm_action_disconnect,
        )
    }
}
