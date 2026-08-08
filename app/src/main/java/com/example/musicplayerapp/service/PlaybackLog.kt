package com.example.musicplayerapp.service

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player

/**
 * One tag for everything that decides whether audio is coming out of the speaker.
 *
 * Playback bugs in this app are intermittent and arrive as user reports, so the
 * log has to be readable after the fact by someone who was not there:
 *
 *     adb logcat | grep MyataPlayback
 *
 * Every line carries the time since process start, the event, and why it
 * happened. Events are discrete — nothing here fires on a timer — so a long
 * listening session stays quiet unless something actually changes.
 *
 * Never pass user data, credentials or full URLs through this. Stream identity
 * is logged as the stream key ("myata") and the transport scheme, which is all
 * the diagnosis needs.
 */
object PlaybackLog {

    const val TAG = "MyataPlayback"

    private val processStartMs = SystemClock.elapsedRealtime()

    private fun stamp(): String {
        val elapsed = SystemClock.elapsedRealtime() - processStartMs
        return String.format("%+8.3fs", elapsed / 1000.0)
    }

    private fun format(event: String, fields: Array<out Pair<String, Any?>>): String {
        val body = fields
            .filter { it.second != null }
            .joinToString(" ") { "${it.first}=${it.second}" }
        return if (body.isEmpty()) "${stamp()} $event" else "${stamp()} $event $body"
    }

    /** A normal, expected event. */
    fun event(event: String, vararg fields: Pair<String, Any?>) {
        Log.i(TAG, format(event, fields))
    }

    /** Something went wrong, or a request was dropped on the floor. */
    fun problem(event: String, vararg fields: Pair<String, Any?>) {
        Log.w(TAG, format(event, fields))
    }

    fun stateName(playbackState: Int): String = when (playbackState) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> "UNKNOWN($playbackState)"
    }

    /**
     * The single most useful signal for "why did the radio stop?": it separates a
     * user pause from audio-focus loss from the system pulling the headphones out.
     */
    fun playWhenReadyReason(reason: Int): String = when (reason) {
        Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> "USER_REQUEST"
        Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> "AUDIO_FOCUS_LOSS"
        Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> "AUDIO_BECOMING_NOISY"
        Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE -> "REMOTE"
        Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM -> "END_OF_MEDIA_ITEM"
        Player.PLAY_WHEN_READY_CHANGE_REASON_SUPPRESSED_TOO_LONG -> "SUPPRESSED_TOO_LONG"
        else -> "UNKNOWN($reason)"
    }

    fun suppressionReason(reason: Int): String = when (reason) {
        Player.PLAYBACK_SUPPRESSION_REASON_NONE -> "NONE"
        Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS -> "TRANSIENT_AUDIO_FOCUS_LOSS"
        else -> "UNKNOWN($reason)"
    }

    /** Error identity without dragging the whole stack trace into every line. */
    fun describe(error: PlaybackException): Array<Pair<String, Any?>> = arrayOf(
        "errorCode" to error.errorCode,
        "errorCodeName" to error.errorCodeName,
        "cause" to (error.cause?.javaClass?.simpleName ?: "none")
    )
}
