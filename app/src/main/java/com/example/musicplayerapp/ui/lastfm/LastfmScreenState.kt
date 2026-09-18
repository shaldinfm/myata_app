package com.example.musicplayerapp.ui.lastfm

import com.example.musicplayerapp.R
import com.example.musicplayerapp.data.lastfm.LastfmLink

/**
 * The stored link, as the Last.fm card and the Settings row show it.
 *
 * Pure functions of a [LastfmLink], so the mapping from what is on disk to what is
 * on screen is a JVM test - and so the card and the row can never disagree about
 * which state the listener is in.
 */
object LastfmScreenState {

    /**
     * The card for [link].
     *
     * @param playcount the lifetime count read for the linked username, or null
     *   while it is being read or if it could not be. Only ever shown for the
     *   account it was read for - the caller guarantees that.
     */
    fun cardFor(link: LastfmLink, playcount: Long?): LastfmCardState = when (link) {
        LastfmLink.NotLinked -> LastfmCardState.Disconnected
        is LastfmLink.Pending -> LastfmCardState.AuthorizationPending
        is LastfmLink.Linked -> LastfmCardState.Connected(link.username, playcount)
        is LastfmLink.ReauthRequired -> LastfmCardState.ReauthRequired(link.username)
    }

    /** What the `Интеграции > Last.fm` row says on the right. */
    sealed class RowValue {
        data class Res(val id: Int) : RowValue()
        data class Text(val text: String) : RowValue()
    }

    /**
     * Owner decision, G6b P3b. Only `Не подключён` is drawn in the frozen file; the
     * other three say the state in the fewest words - and the linked one names the
     * account, the way `Профиль` names the signed-in user.
     */
    fun rowValueFor(link: LastfmLink): RowValue = when (link) {
        LastfmLink.NotLinked -> RowValue.Res(R.string.settings_lastfm_not_connected)
        is LastfmLink.Pending -> RowValue.Res(R.string.settings_lastfm_pending)
        is LastfmLink.Linked -> RowValue.Text(link.username)
        is LastfmLink.ReauthRequired -> RowValue.Res(R.string.settings_lastfm_reauth)
    }
}
