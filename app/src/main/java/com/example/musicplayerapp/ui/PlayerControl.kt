package com.example.musicplayerapp.ui

import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import com.example.musicplayerapp.R

/**
 * Paints [PlayerControlState] onto the PLAYER's central control.
 *
 * The frozen design has one control - `Controls > play/pause`, 80x80 r20 filled
 * with `primary` - and three faces for it. The filled surface is the button's own
 * background, so the surface is on screen for all three: only what sits in the
 * middle of it changes, and nothing in here touches size, position or visibility
 * of the button itself.
 *
 * That is the fix. Before this, connecting hid the whole button
 * (`View.INVISIBLE`) and left a bare spinner where the control had been: no fill
 * behind it, and the glyph colour is near-white against a #F8F9FA background in
 * Light and #0F253E against a #0F253E background in Dark, so the spinner was
 * invisible in both themes and the control appeared to vanish mid-connect.
 *
 * The button stops taking taps while connecting, which is what hiding it used to
 * do - an INVISIBLE view gets no touches - so a tap during connect still does
 * nothing and no second start command can be issued. It is only the appearance
 * that changes.
 */
class PlayerControl(
    private val button: ImageView,
    private val spinner: ProgressBar,
) {

    fun render(state: PlayerControlState) {
        // The 80x80 primary surface is this view's background and stays drawn in
        // every state. Only its content changes.
        button.visibility = View.VISIBLE
        button.isClickable = state != PlayerControlState.CONNECTING

        when (state) {
            PlayerControlState.CONNECTING -> {
                button.setImageDrawable(null)
                spinner.visibility = View.VISIBLE
            }
            PlayerControlState.PLAY -> {
                spinner.visibility = View.GONE
                button.setImageResource(R.drawable.ic_player_play)
            }
            PlayerControlState.PAUSE -> {
                spinner.visibility = View.GONE
                button.setImageResource(R.drawable.ic_player_pause)
            }
        }

        button.contentDescription = button.context.getString(
            when (state) {
                PlayerControlState.CONNECTING -> R.string.player_connecting
                PlayerControlState.PLAY -> R.string.player_play
                PlayerControlState.PAUSE -> R.string.player_pause
            }
        )
    }
}
