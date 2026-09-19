package com.example.musicplayerapp.data.lastfm.nowplaying

import com.example.musicplayerapp.scrobble.OccurrenceId

/**
 * Which airings Now Playing has already been attempted for, per Last.fm account
 * (G6b P6b). Process memory only.
 *
 * Keyed by `(lastfmUsername, OccurrenceId)` in meaning, held as one monotonic mark
 * per `(username, stream)`: the highest `started_at` attempted. `started_at` only
 * grows on a stream, so an airing at or below the mark has been attempted - which
 * suppresses repeats, a station switch back to the same airing, a recreated service,
 * and a failed attempt alike - while a newer airing, or another account, is free.
 * Bounded by accounts times streams, however long the process lives. A fresh
 * process starts empty and may announce the current airing once more.
 */
class NowPlayingAttempts {

    private val marks = HashMap<Pair<String, String>, Long>()

    /**
     * Marks [occurrence] attempted for [username] and returns true, unless it (or a
     * later airing on its stream) already was - then false, and nothing changes.
     * Called **before** the request is sent, so a failure is never retried here.
     */
    @Synchronized
    fun tryMark(username: String, occurrence: OccurrenceId): Boolean {
        val key = username to occurrence.streamId
        val mark = marks[key]
        if (mark != null && occurrence.startedAt <= mark) return false
        marks[key] = occurrence.startedAt
        return true
    }

    companion object {
        /** The process's one record, shared by every service instance. */
        val process = NowPlayingAttempts()
    }
}
