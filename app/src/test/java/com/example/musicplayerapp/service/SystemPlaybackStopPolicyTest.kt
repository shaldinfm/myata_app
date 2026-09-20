package com.example.musicplayerapp.service

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which `playWhenReady` reasons mean the system took the audio away.
 *
 * This is the half of the headphones contract that *is* testable without hardware.
 * The route loss itself cannot be staged - the system's `AUDIO_BECOMING_NOISY`
 * broadcast is protected, so neither a test nor adb may send it - but the decision
 * the app makes when it arrives is this one line, and getting it wrong costs in both
 * directions:
 *
 *  - too narrow, and an unplugged headset is reconnected to a second later by the
 *    reconnect loop, out of the phone speaker, which is issue #13;
 *  - too wide, and the app's own Play/Pause is read as somebody else stopping the
 *    radio, so a normal pause would clear the durable intent for a reason that is not
 *    true.
 */
class SystemPlaybackStopPolicyTest {

    @Test
    fun `the output going away ends the listener's intent`() {
        assertTrue(
            SystemPlaybackStopPolicy.endsUserIntent(
                Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY
            )
        )
    }

    @Test
    fun `audio focus lost for good ends the listener's intent`() {
        assertTrue(
            SystemPlaybackStopPolicy.endsUserIntent(
                Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS
            )
        )
    }

    /**
     * Every Play and Pause this app performs arrives with `USER_REQUEST` - including
     * `ForwardingPlayer.pause()`, which ends the intent on its own path, with its own
     * reason in the log. Acting on it here as well would make the log lie about who
     * stopped the radio.
     */
    @Test
    fun `the app's own requests are not the system taking the audio away`() {
        assertFalse(
            SystemPlaybackStopPolicy.endsUserIntent(
                Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
            )
        )
    }

    @Test
    fun `a media button and the end of an item are not the output going away`() {
        assertFalse(
            SystemPlaybackStopPolicy.endsUserIntent(
                Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE
            )
        )
        assertFalse(
            SystemPlaybackStopPolicy.endsUserIntent(
                Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM
            )
        )
    }

    /**
     * A transient duck that outlasted its window. The app has never treated it as a
     * stop - the listener asked for audio and has not said otherwise - and this pins
     * that, so a later change to it is deliberate rather than a slip.
     */
    @Test
    fun `a suppression that outlasted its window is not a stop`() {
        assertFalse(
            SystemPlaybackStopPolicy.endsUserIntent(
                Player.PLAY_WHEN_READY_CHANGE_REASON_SUPPRESSED_TOO_LONG
            )
        )
    }

    @Test
    fun `an unknown reason is not acted on`() {
        // A future Media3 reason must not be guessed at: the safe direction is "the
        // listener still wants audio", which is what the absence of a cancellation
        // means.
        assertFalse(SystemPlaybackStopPolicy.endsUserIntent(reason = 12345))
    }
}
