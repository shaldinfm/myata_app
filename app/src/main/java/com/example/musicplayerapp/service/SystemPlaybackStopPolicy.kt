package com.example.musicplayerapp.service

import androidx.media3.common.Player

/**
 * Which `playWhenReady` reasons mean "the system stopped this, and the listener
 * has to ask again".
 *
 * Two of them, and both are the audio output rather than the listener:
 *
 *  - [Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY] - Bluetooth
 *    dropped or headphones were unplugged. ExoPlayer clears `playWhenReady` itself
 *    when the app asks it to (`setHandleAudioBecomingNoisy(true)`), and the
 *    alternative is the radio carrying on out of the phone speaker - issue #13.
 *  - [Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS] - another app took the
 *    output for good, which is also a "press Play again" event.
 *
 * Getting this wrong costs in both directions, which is why the split is pinned by
 * a JVM test rather than left inside the listener: too narrow and a dropped headset
 * is reconnected to a second later by the reconnect loop, too wide and the app's own
 * Play/Pause or a media button would be read as somebody else stopping the radio.
 *
 * Deliberately *not* on this list:
 *
 *  - `USER_REQUEST` - every Play and Pause the app itself performs arrives with
 *    this reason, including the `ForwardingPlayer.pause()` that already ends the
 *    intent on its own path.
 *  - `REMOTE` and `END_OF_MEDIA_ITEM` - a media button and the end of an item are
 *    not the output going away. A live stream has no end, so this never fires here.
 *  - `SUPPRESSED_TOO_LONG` - a transient duck that outlasted its window. The app has
 *    never treated it as a stop and still does not: the listener asked for audio and
 *    has not said otherwise.
 */
internal object SystemPlaybackStopPolicy {

    fun endsUserIntent(reason: Int): Boolean = when (reason) {
        Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY,
        Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> true
        else -> false
    }
}
