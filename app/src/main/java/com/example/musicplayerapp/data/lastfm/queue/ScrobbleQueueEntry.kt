package com.example.musicplayerapp.data.lastfm.queue

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * One eligible airing waiting to become a Last.fm scrobble (G6b P5).
 *
 * The primary key is the occurrence itself - `(stream_id, started_at)`, P4's
 * `OccurrenceId` - so the database, not a check before the insert, is what makes a
 * second row for the same airing impossible. That holds across process death, which
 * P4's in-memory `ScrobbleEmissions` deliberately does not.
 *
 * Every row belongs to the Last.fm account that was linked when it became eligible
 * ([lastfmUsername]). P6 sends a row only while that same account is linked, so a
 * listen queued as one account can never be scrobbled as another. The session key
 * is never stored here - nor anything else secret or derived from a secret.
 *
 * [attempts] and [nextAttemptAt] are P6's retry scheduling, present from version 1
 * so the sender needs no schema change. P5 never sends and never writes them.
 *
 * @property startedAt unix seconds, server-authoritative: the scrobble timestamp.
 * @property queuedAt device wall-clock milliseconds when the row was written.
 */
@Entity(
    tableName = "scrobble_queue",
    primaryKeys = ["stream_id", "started_at"],
    indices = [Index(value = ["next_attempt_at"])],
)
data class ScrobbleQueueEntry(
    @ColumnInfo(name = "stream_id")
    val streamId: String,

    @ColumnInfo(name = "started_at")
    val startedAt: Long,

    @ColumnInfo(name = "track_key")
    val trackKey: String,

    @ColumnInfo(name = "artist")
    val artist: String,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "duration_sec")
    val durationSec: Long,

    @ColumnInfo(name = "lastfm_username")
    val lastfmUsername: String,

    @ColumnInfo(name = "queued_at")
    val queuedAt: Long,

    @ColumnInfo(name = "attempts", defaultValue = "0")
    val attempts: Int = 0,

    @ColumnInfo(name = "next_attempt_at", defaultValue = "0")
    val nextAttemptAt: Long = 0L,
) {
    /** Artist and title are the listener's music; no log should carry them. */
    override fun toString(): String =
        "ScrobbleQueueEntry(streamId=$streamId, startedAt=$startedAt, trackKey=${trackKey.take(8)}, " +
            "durationSec=$durationSec, queuedAt=$queuedAt, attempts=$attempts, nextAttemptAt=$nextAttemptAt)"
}
