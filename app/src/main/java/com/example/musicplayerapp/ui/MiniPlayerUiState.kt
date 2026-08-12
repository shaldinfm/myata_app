package com.example.musicplayerapp.ui

import com.example.musicplayerapp.data.PlayerState
import com.example.musicplayerapp.data.Streams

/**
 * What the Mini Player shows, derived from the playback state that already
 * exists.
 *
 * This is a projection, not a state machine. Every input is read from
 * `StreamsViewModel`, which owns the single MediaController and is the only thing
 * that decides what is playing; nothing here starts, stops or remembers playback,
 * so the pill cannot drift out of step with the player or the notification.
 *
 * It is a plain function of its arguments precisely so the mapping is testable
 * without an Activity - see MiniPlayerUiStateTest.
 */
data class MiniPlayerUiState(
    val title: String,
    val artist: String,
    /** Cover art URL, or null when there is none to load and the logo stands in. */
    val artworkUrl: String?,
    val playing: Boolean,
) {

    companion object {

        /** The repository's marker for "the artwork lookup found nothing". */
        private const val NO_IMAGE = "NO_IMAGE"

        /**
         * Projects the current stream's metadata onto the pill.
         *
         * The title/artist split is the player screen's, not a new one:
         * [PlayerState.song] is the track title and [PlayerState.artist] is the
         * artist, and when the artist is missing both fall back to the brand pair
         * exactly as `MyataStreamFragment.updateUI` does. Keeping the two in step
         * matters because the pill and the player are visible one tap apart.
         *
         * An unrecognised stream key resolves through [Streams.normalise] rather
         * than falling through to nothing, for the same reason every other lookup
         * does (issue #14).
         */
        fun from(
            stream: String?,
            myata: PlayerState?,
            gold: PlayerState?,
            xtra: PlayerState?,
            isPlaying: Boolean,
            fallbackTitle: String,
            fallbackArtist: String,
        ): MiniPlayerUiState {
            val state = when (Streams.normalise(stream) ?: Streams.DEFAULT) {
                Streams.GOLD -> gold
                Streams.XTRA -> xtra
                else -> myata
            }

            val artist = state?.artist?.takeUnless { it.isBlank() }
            val title = state?.song?.takeUnless { it.isBlank() }

            if (artist == null || title == null) {
                return MiniPlayerUiState(fallbackTitle, fallbackArtist, null, isPlaying)
            }

            val artwork = state.img?.takeUnless { it.isBlank() || it == NO_IMAGE }
            return MiniPlayerUiState(title, artist, artwork, isPlaying)
        }
    }
}
