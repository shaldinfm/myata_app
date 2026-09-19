package com.example.musicplayerapp.scrobble

/**
 * An airing the tracker has just started following, while it is really playing and
 * still inside its air window (G6b P6b) - what Now Playing announces.
 *
 * Deliberately not a [ScrobbleCandidate]: a candidate is an airing heard long enough
 * to count; this is one that has only just begun to be heard. It carries only what
 * `track.updateNowPlaying` needs. The stream and `started_at` live in
 * [occurrenceId], for local dedupe and logging - never sent as Last.fm's `streamId`.
 */
data class OpenedOccurrence(
    val occurrenceId: OccurrenceId,
    val trackKey: String,
    val artist: String,
    val title: String,
    val durationSec: Long,
) {
    /** Artist and title are the listener's music; no log should carry them. */
    override fun toString(): String =
        "OpenedOccurrence(occurrenceId=$occurrenceId, trackKey=${trackKey.take(8)}, durationSec=$durationSec)"
}
