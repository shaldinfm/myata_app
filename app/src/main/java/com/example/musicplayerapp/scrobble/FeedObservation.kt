package com.example.musicplayerapp.scrobble

import com.example.musicplayerapp.data.TrackKey

/**
 * One stream's entry from one successful `api_all_tracks.php` poll, exactly as the
 * station sent it - every field nullable, nothing defaulted.
 *
 * `MediaPlayerService`'s poller substitutes the device clock for a missing
 * `server_time` and `server_time + 15` for a missing `ends_at`. Those are fine for
 * deciding when to poll next and wrong for deciding what the listener heard, so the
 * tracker reads its own copy of the fields and never sees the substitutes.
 *
 * All times are unix **seconds** on the station's clocks: `started_at`/`ends_at`
 * come from the playout system, `server_time` from the web server that served the
 * response.
 *
 * @property streamId the stream this entry was read for - `myata`, `gold`,
 *   `myata_hits` - captured when the response was parsed, so an answer that lands
 *   after a station switch still says which station it describes.
 */
data class FeedObservation(
    val streamId: String,
    val artist: String?,
    val title: String?,
    val startedAt: Long?,
    val durationSec: Long?,
    val endsAt: Long?,
    val serverTime: Long?,
) {

    /** The canonical identity of the track content, or null for blanks and the jingle. */
    val trackKey: String? = TrackKey.of(artist, title)

    /**
     * Everything the tracker needs is present and self-consistent. See [Freshness].
     *
     * Not the same as [isFresh]: a valid observation of a frozen feed is still
     * valid - it is simply old.
     */
    val isValid: Boolean
        get() {
            val started = startedAt ?: return false
            val duration = durationSec ?: return false
            val ends = endsAt ?: return false
            val now = serverTime ?: return false
            return trackKey != null &&
                started > 0 &&
                duration > 0 &&
                kotlin.math.abs((ends - started) - duration) <= Freshness.CONSISTENCY_TOLERANCE_SEC &&
                started <= now + Freshness.FUTURE_SKEW_SEC
        }

    /**
     * The feed has not overrun this track by more than a normal gap between tracks.
     * Only meaningful for a [isValid] observation.
     */
    val isFresh: Boolean
        get() {
            val ends = endsAt ?: return false
            val now = serverTime ?: return false
            return now <= ends + Freshness.STALE_GRACE_SEC
        }

    /** Artist and title are the listener's music; no log should carry them. */
    override fun toString(): String =
        "FeedObservation(streamId=$streamId, trackKey=${trackKey?.take(8)}, startedAt=$startedAt, " +
            "durationSec=$durationSec, endsAt=$endsAt, serverTime=$serverTime)"

    companion object {

        /**
         * Reads one stream's entry from the poll response the way the service's Gson
         * `Map` parse leaves it: numbers arrive as `Double`, strings as `String`, and
         * anything missing or of the wrong type is null - never a default.
         */
        fun from(streamId: String, streamData: Map<*, *>, serverTime: Any?): FeedObservation =
            FeedObservation(
                streamId = streamId,
                artist = streamData["artist"] as? String,
                title = streamData["track"] as? String,
                startedAt = seconds(streamData["started_at"]),
                durationSec = seconds(streamData["duration"]),
                endsAt = seconds(streamData["ends_at"]),
                serverTime = seconds(serverTime),
            )

        private fun seconds(value: Any?): Long? = when (value) {
            is Number -> value.toDouble().takeIf { it.isFinite() }?.toLong()
            else -> null
        }
    }
}

/**
 * The permanent freshness gate (G6b P4, owner decisions D5/D6).
 *
 * The station's feeds have frozen before - Gold and Myata Hits independently - and
 * a frozen feed presents one unchanging track forever. The gate is what stops that
 * track being credited or opened as new. It stays even while every feed is healthy.
 *
 *  - **valid**: a real track (non-null [TrackKey]), `started_at > 0`, `duration > 0`,
 *    `server_time` actually present, `|(ends_at - started_at) - duration| <= 1`, and
 *    `started_at <= server_time + 30`;
 *  - **fresh**: `server_time <= ends_at + 120`.
 *
 * Only a valid, fresh observation can open an occurrence or correct one. An invalid
 * or stale observation changes nothing - it never opens, never credits, and never
 * resets the occurrence already being tracked. Credit is separately clipped to the
 * occurrence's own air window, so a feed that freezes mid-track, or polls that
 * start failing, stop accruing at `ends_at` whatever arrives afterwards.
 */
object Freshness {

    /** How far the playout clock may run ahead of the web server's (D5). */
    const val FUTURE_SKEW_SEC = 30L

    /**
     * How long past `ends_at` the feed may still show the same track: a normal
     * inter-track gap - jingles and promos run up to ~50 s - plus poll lag (D6).
     */
    const val STALE_GRACE_SEC = 120L

    /** `ends_at` and `started_at + duration` are integers from one computation. */
    const val CONSISTENCY_TOLERANCE_SEC = 1L
}
