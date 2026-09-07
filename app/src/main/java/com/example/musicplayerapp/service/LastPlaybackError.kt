package com.example.musicplayerapp.service

import android.os.SystemClock
import androidx.media3.common.PlaybackException

/**
 * The most recent playback error this process has seen, so a problem report can
 * say what it was.
 *
 * ## Why this exists at all
 *
 * `PlaybackLog` already computes exactly this at the moment it happens, and
 * writes it to logcat - which is unreachable from a listener's phone. Issue #15
 * (playback stopping by itself) is open precisely because no diagnostic data ever
 * reaches us, and the frozen `Сообщить о проблеме` diagnostics card has a line for
 * it. This is the smallest thing that makes that line true.
 *
 * ## It is passive, and that is a hard constraint rather than a description
 *
 * Playback never reads this. Nothing here can influence reconnect, recovery,
 * error classification or any other decision the service makes - the only writer
 * is a single statement at the existing `onPlayerError` observation point, placed
 * after the recovery decision has already been taken, and the only reader is the
 * report form. Deleting this file would change no playback behaviour whatsoever.
 *
 * ## What it is allowed to hold
 *
 * A safe structured code and a monotonic timestamp. Deliberately **not**:
 *
 *  - a stack trace, or any part of one
 *  - `PlaybackException.message`, or the cause's message - both can carry a URL,
 *    a host, a header or a file path put there by a library we do not control
 *  - a URL, host or stream address in any form
 *  - anything about the listener or the account
 *
 * `errorCodeName` is a Media3 constant name from a closed set
 * (`ERROR_CODE_IO_BAD_HTTP_STATUS`, `ERROR_CODE_TIMEOUT`, …), so it is a code
 * rather than free text. That is the whole payload.
 *
 * ## Memory only
 *
 * Nothing is written to disk, to SharedPreferences, to Room or to logcat from
 * here. The value dies with the process, which is the correct lifetime: an error
 * from a previous launch is not evidence about this one, and a report that
 * attributed it to this one would be worse than a report with no error at all.
 */
object LastPlaybackError {

    /**
     * A captured failure.
     *
     * [elapsedRealtimeMs] is `SystemClock.elapsedRealtime`, not wall clock: the
     * report needs "how long ago", and a wall clock can move under it.
     */
    data class Capture(
        val codeName: String,
        val elapsedRealtimeMs: Long,
    )

    @Volatile
    private var capture: Capture? = null

    /**
     * Records [error]'s identity. Called from the service's existing error
     * observation point and from nowhere else.
     *
     * Only `errorCode` and `errorCodeName` are read off [error]; the exception
     * itself is not retained, so nothing can later reach its message, its cause or
     * its stack from here.
     */
    fun record(error: PlaybackException) {
        capture = Capture(
            codeName = sanitise(error.errorCodeName),
            elapsedRealtimeMs = SystemClock.elapsedRealtime(),
        )
    }

    /** What the report should say, or null when this process has seen no error. */
    fun peek(): Capture? = capture

    /** Test seam. Production code never calls this. */
    fun clearForTest() {
        capture = null
    }

    /**
     * Belt and braces around the one field we take.
     *
     * `errorCodeName` is documented to be a constant name and is one in every
     * Media3 version this app has shipped against, but it arrives from a library,
     * and the cost of being wrong is a message with something unexpected in it
     * being posted to a third party. So the shape is enforced rather than trusted:
     * upper-case letters, digits and underscores, capped, and anything else
     * collapses to a value that says the code was unusable rather than passing it
     * through.
     */
    private fun sanitise(codeName: String?): String {
        val trimmed = codeName?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.length > 64) return UNKNOWN
        return if (trimmed.all { it in 'A'..'Z' || it in '0'..'9' || it == '_' }) trimmed else UNKNOWN
    }

    const val UNKNOWN = "ERROR_CODE_UNKNOWN"
}
