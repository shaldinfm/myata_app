package com.example.musicplayerapp.service

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import javax.net.ssl.SSLException

/**
 * Decides whether a playback failure is worth reconnecting for.
 *
 * Kept out of MediaPlayerService and free of Android dependencies so the rules can
 * be unit tested: HTTP status handling in particular cannot be exercised against
 * the live radio server, and it is the part where a wrong answer is expensive -
 * retrying a 404 forever, or giving up on a 503 that would have cleared.
 */
object StreamErrorPolicy {

    /** Bounded walk of the cause chain; a cyclic chain must not hang the player. */
    private fun Throwable.findCause(predicate: (Throwable) -> Boolean): Throwable? {
        var current: Throwable? = this
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            if (predicate(current)) return current
            current = current.cause
            depth++
        }
        return null
    }

    /**
     * True for transient transport failures only.
     *
     * An HTTP failure is judged from the real response code on the cause rather
     * than from the generic ERROR_CODE_IO_BAD_HTTP_STATUS, because 503 and 404 need
     * opposite answers and the error code alone cannot tell them apart.
     *
     * Everything not listed - 4xx, cleartext-not-permitted, decoder, parsing,
     * permission, file-not-found - fails again identically on retry, so retrying
     * only burns battery and hides the real problem from the user.
     */
    fun isRecoverable(error: PlaybackException): Boolean {
        val badStatus = error.findCause { it is HttpDataSource.InvalidResponseCodeException }
                as? HttpDataSource.InvalidResponseCodeException
        if (badStatus != null) {
            return isRecoverableHttpStatus(badStatus.responseCode)
        }

        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            // The usual shape of a live connection dropped mid-stream.
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
            PlaybackException.ERROR_CODE_TIMEOUT,
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> true
            else -> false
        }
    }

    /** 5xx is the server having a moment; 408 and 429 explicitly mean "try again". */
    fun isRecoverableHttpStatus(responseCode: Int): Boolean =
        responseCode >= 500 || responseCode == 408 || responseCode == 429

    /**
     * Only a genuine TLS failure may justify the legacy cleartext fallback, and
     * only on TV - see issue #16. An ordinary network drop must never qualify.
     */
    fun isTlsFailure(error: PlaybackException): Boolean =
        error.findCause { it is SSLException } != null

    /** 1s, 2s, 4s, 8s, 16s, then capped. Jitter is added by the caller. */
    fun backoffDelayMs(attempt: Int, baseMs: Long, maxMs: Long): Long =
        (baseMs shl attempt.coerceIn(0, 5)).coerceAtMost(maxMs)

    private const val MAX_CAUSE_DEPTH = 8
}
