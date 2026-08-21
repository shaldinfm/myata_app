package com.example.musicplayerapp.ui

import android.view.View
import android.widget.TextView
import com.example.musicplayerapp.R

/**
 * Turns a [HomePlaylistsState] into the five views HOME's playlist section owns.
 *
 * Separate from the fragment for the same reason [HomePlaylistsState] is separate
 * from the ViewModel: the interesting part is a mapping, and a mapping that lives
 * inside `onCreateView` can only be tested by getting a whole screen into each
 * state. Here every state can be applied to real views directly, which is what
 * makes the error and offline branches testable at all - they are unreachable
 * from a device test that has no way to make the network fail on cue.
 *
 * The section occupies one band whichever state it is in: the row and the status
 * container are siblings of identical height, and exactly one of them is ever
 * visible, so nothing below the section moves when the load lands.
 */
object HomePlaylistSection {

    /**
     * @param heading the "Мятные плейлисты" caption.
     * @param row the card RecyclerView.
     * @param status the container holding [loading] and [error].
     * @param errorText the line the reader is given; set only when it is shown, so
     *   a state that hides it never has to choose a string.
     */
    fun apply(
        state: HomePlaylistsState,
        heading: View,
        row: View,
        status: View,
        loading: View,
        error: View,
        errorText: TextView,
    ) {
        // The heading captions the row, so it goes only when the section has
        // nothing it will ever have to say - not while it is still trying.
        heading.visibility = if (state == HomePlaylistsState.EMPTY) View.GONE else View.VISIBLE
        row.visibility = if (state == HomePlaylistsState.POPULATED) View.VISIBLE else View.GONE
        status.visibility = if (state.isStatus) View.VISIBLE else View.GONE
        loading.visibility = if (state == HomePlaylistsState.LOADING) View.VISIBLE else View.GONE
        error.visibility = if (state.isRetryable) View.VISIBLE else View.GONE

        if (state.isRetryable) {
            // The splash's own copy rather than a second wording for the same two
            // situations. These strings outlive the screen they were named after.
            errorText.setText(
                if (state == HomePlaylistsState.ERROR_OFFLINE) R.string.splash_offline_title
                else R.string.splash_error_title
            )
        }
    }

    /** Everything hidden - split view has no room for the section at all. */
    fun hide(heading: View, row: View, status: View) {
        heading.visibility = View.GONE
        row.visibility = View.GONE
        status.visibility = View.GONE
    }
}
