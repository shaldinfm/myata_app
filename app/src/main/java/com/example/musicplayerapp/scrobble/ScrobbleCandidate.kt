package com.example.musicplayerapp.scrobble

/**
 * One physical airing of one track on one station.
 *
 * `started_at` is server-authoritative and strictly increases on a stream, so it is
 * what distinguishes two airings - not the track. Artist and title can be corrected
 * during a single airing; a corrected [com.example.musicplayerapp.data.TrackKey]
 * with the same stream and `started_at` is the **same** occurrence, and must never
 * become a second scrobble.
 */
data class OccurrenceId(val streamId: String, val startedAt: Long)

/**
 * A track occurrence the listener has heard long enough for Last.fm to count it:
 * more than 30 seconds long, and played for half its length or four minutes,
 * whichever comes first.
 *
 * P4 only decides this. It never queues, stores or sends anything; P5 consumes the
 * candidate. `chosenByUser = 0` is a property of every radio scrobble and belongs
 * to P5's request, not here.
 *
 * @property artist display string as the station sent it (after any correction
 *   made before eligibility), not the normalised form.
 * @property startedAt unix seconds, server-authoritative - the future scrobble
 *   timestamp.
 */
data class ScrobbleCandidate(
    val occurrenceId: OccurrenceId,
    val trackKey: String,
    val artist: String,
    val title: String,
    val startedAt: Long,
    val durationSec: Long,
) {
    val streamId: String get() = occurrenceId.streamId

    /** Artist and title are the listener's music; no log should carry them. */
    override fun toString(): String =
        "ScrobbleCandidate(occurrenceId=$occurrenceId, trackKey=${trackKey.take(8)}, durationSec=$durationSec)"
}

/** Where eligible candidates go. P4's is log-only; P5 replaces it. */
fun interface ScrobbleCandidateSink {
    fun onEligible(candidate: ScrobbleCandidate)
}

/**
 * Development diagnostics, written only while tracking is active. Fields are
 * stream ids, unix seconds, durations and an 8-character TrackKey prefix - never
 * artist, title, URLs or anything from the Last.fm session.
 */
fun interface ScrobbleLog {
    fun event(name: String, vararg fields: Pair<String, Any?>)

    companion object {
        val NONE = ScrobbleLog { _, _ -> }
    }
}
