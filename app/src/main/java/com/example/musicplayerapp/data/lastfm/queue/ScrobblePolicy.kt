package com.example.musicplayerapp.data.lastfm.queue

import com.example.musicplayerapp.data.lastfm.IgnoredScrobble
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * What the scrobble sender does with every answer Last.fm can give (G6b P6a).
 * Pure: no clock, no randomness, no I/O - everything varying is passed in, so every
 * row of both tables is a plain JVM test.
 *
 * Grounded in Last.fm's Scrobbling 2.0 guide: only errors 11 and 16 are to be
 * retried; 9 means re-authenticate; "all other error codes indicate the scrobble
 * request was incorrectly formed in some way and should not be retried". Cached
 * scrobbles are sent in order, in batches of at most 50.
 */
object ScrobblePolicy {

    /** Last.fm's documented batch ceiling. */
    const val MAX_BATCH = 50

    /** `next_attempt_at` of a quarantined row: kept, never due, out of the active queue. */
    const val QUARANTINE = Long.MAX_VALUE

    const val BACKOFF_BASE_MS = 30_000L
    const val BACKOFF_CAP_MS = 6 * 60 * 60 * 1000L
    const val JITTER_FRACTION = 0.2

    /** Ignored code 4: try again this much later... */
    const val TOO_NEW_DEFER_MS = 10 * 60 * 1000L

    /** ...at most this many attempts in all, then quarantine. */
    const val TOO_NEW_MAX_ATTEMPTS = 3

    /** Ignored code 5: the daily limit. The held row - and so the queue - waits a day. */
    const val DAILY_LIMIT_DEFER_MS = 24 * 60 * 60 * 1000L

    /**
     * Exponential backoff for the [attempt]th attempt (1-based): 30 s, 60 s, 120 s...
     * capped at 6 h, then scaled by `1 + 0.2 * jitter` for a [jitter] in [-1, 1].
     */
    fun backoffMs(attempt: Int, jitter: Double): Long {
        val exponent = (attempt - 1).coerceIn(0, 30)
        val raw = min(BACKOFF_BASE_MS shl exponent, BACKOFF_CAP_MS)
        val scale = 1.0 + JITTER_FRACTION * jitter.coerceIn(-1.0, 1.0)
        return (raw * scale).roundToLong().coerceAtLeast(1L)
    }

    /**
     * The rows to send now: the oldest active rows, but only the consecutive due
     * prefix. If the oldest is not due, nothing is sent - a newer due row never
     * overtakes an older one in backoff.
     *
     * [head] must already be ordered oldest first and exclude quarantined rows.
     */
    fun duePrefix(head: List<ScrobbleQueueEntry>, nowMs: Long): List<ScrobbleQueueEntry> =
        head.take(MAX_BATCH).takeWhile { it.nextAttemptAt <= nowMs }

    // ---- request-level ----------------------------------------------------------

    sealed class Global {
        /** 11, 16, no answer, or an answer whose outcome cannot be mapped: back off. */
        data object Retry : Global()

        /** 9: the session is gone. Stop, keep every row, wait for re-authentication. */
        data object Reauthenticate : Global()

        /**
         * Everything else. Not retried - Last.fm says it would get the same answer -
         * and not deleted either: a configuration or signing fault must not cost the
         * listener their history. The sender stops until the process restarts.
         */
        data object StopUntilRestart : Global()
    }

    fun forGlobalError(code: Int): Global = when (code) {
        11, 16 -> Global.Retry
        9 -> Global.Reauthenticate
        else -> Global.StopUntilRestart
    }

    // ---- per scrobble -----------------------------------------------------------

    sealed class Item {
        /** Last.fm has recorded it, or decided finally against it. */
        data object Delete : Item()

        /** Keep it; not before [untilMs]. */
        data class Defer(val untilMs: Long) : Item()

        /** Keep it, never send it again. */
        data object Quarantine : Item()

        /** Its disposition is unknown: back off like a failed request, for this row only. */
        data object Backoff : Item()
    }

    /**
     * The verdict for one scrobble of a batch whose mapping was sound.
     *
     * @param attemptsBefore the row's `attempts` before this request.
     */
    fun forItem(verdict: IgnoredScrobble, attemptsBefore: Int, nowMs: Long): Item = when (verdict) {
        IgnoredScrobble.ACCEPTED,
        IgnoredScrobble.ARTIST_IGNORED,
        IgnoredScrobble.TRACK_IGNORED,
        IgnoredScrobble.TIMESTAMP_TOO_OLD -> Item.Delete

        IgnoredScrobble.TIMESTAMP_TOO_NEW ->
            if (attemptsBefore + 1 >= TOO_NEW_MAX_ATTEMPTS) Item.Quarantine
            else Item.Defer(nowMs + TOO_NEW_DEFER_MS)

        IgnoredScrobble.DAILY_LIMIT_EXCEEDED -> Item.Defer(nowMs + DAILY_LIMIT_DEFER_MS)

        IgnoredScrobble.UNKNOWN -> Item.Quarantine

        IgnoredScrobble.MISSING -> Item.Backoff
    }
}
