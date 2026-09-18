package com.example.musicplayerapp.scrobble

/**
 * Last.fm's rule for when a play counts: the track is longer than 30 seconds, and
 * it has been played for at least half its duration or for 4 minutes, whichever
 * comes first.
 */
object ScrobbleEligibility {

    /** A track this long or shorter never counts. */
    const val MIN_DURATION_SEC = 30L

    /** The 4-minute arm. */
    const val MAX_THRESHOLD_MS = 240_000L

    /**
     * How much actual playing time [durationSec] needs, in milliseconds, or null if
     * no amount of listening makes it eligible.
     *
     * Computed in milliseconds so an odd duration keeps its half second: 31 s needs
     * 15 500 ms, not 15 000.
     */
    fun thresholdMs(durationSec: Long): Long? {
        if (durationSec <= MIN_DURATION_SEC) return null
        return minOf(durationSec * 1000L / 2, MAX_THRESHOLD_MS)
    }
}

/**
 * Whether playback tracking may run at all (owner decision D2/D3). All three must
 * hold; when any does not, the tracker accumulates nothing, emits nothing, logs
 * nothing, and forgets any partial listen.
 */
object ScrobbleGate {
    fun isActive(isTv: Boolean, isConfigured: Boolean, isLinked: Boolean): Boolean =
        !isTv && isConfigured && isLinked
}
