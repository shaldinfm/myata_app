package com.example.musicplayerapp.ui

/**
 * Which face the PLAYER's one central control is showing.
 *
 * This is a projection, not a state machine: it reads the two LiveDatas
 * `StreamsViewModel` already publishes from the MediaController - `isPlaying`
 * from `Player.Listener.onIsPlayingChanged`, `isBuffering` from
 * `onPlaybackStateChanged(STATE_BUFFERING)` - and answers which glyph belongs on
 * the control. It holds nothing, decides nothing about playback, and cannot
 * disagree with the service: if both inputs went away, so would this.
 *
 * Buffering wins over playing because it is the transient one. Media3 reports
 * `isPlaying == false` while a stream connects, so on the way in the two are not
 * in conflict; on a stream switch, though, the player can still be playing the
 * old stream while the new one buffers, and the control has to say "connecting"
 * rather than "playing" for the swap to read.
 *
 * Kept free of Android types so the whole projection is a unit test.
 */
enum class PlayerControlState {
    /** Nothing is loaded, or a loaded stream is paused: the Play glyph. */
    PLAY,

    /** Connecting or re-buffering: the progress indicator, inside the control. */
    CONNECTING,

    /** A stream is playing: the Pause glyph. */
    PAUSE,

    ;

    companion object {
        fun of(isPlaying: Boolean, isBuffering: Boolean): PlayerControlState = when {
            isBuffering -> CONNECTING
            isPlaying -> PAUSE
            else -> PLAY
        }
    }
}
