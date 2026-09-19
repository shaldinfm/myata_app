package com.example.musicplayerapp.data.lastfm.queue

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * The newest airing this device has **finalized** for one Last.fm account on one
 * stream (G6b P6b) - one row per `(lastfm_username, stream_id)`, nothing else.
 *
 * Finalized means Last.fm answered the scrobble terminally - accepted, or ignored
 * for good (codes 1, 2, 3) - and the queue row was deleted in the same transaction
 * that raised this mark ([ScrobbleQueueDao.finalizeOccurrence]). Without it nothing
 * durable would remember the airing once its row was gone: P4's emitted mark is
 * process memory, so a process restarted while a long track is still on air could
 * earn it again and queue a second scrobble for the same `started_at`.
 * [ScrobbleQueueDao.enqueue] refuses anything at or below this mark.
 *
 * A *pending* row is never represented here: while it waits, the queue's own
 * primary key is its dedupe, and a disconnect that purges it leaves no mark - a
 * later link may earn that airing again. The mark itself survives a disconnect.
 *
 * Monotonic per stream, like P4's mark: `started_at` only grows on a stream, so an
 * older airing that was never finalized is conservatively refused once a newer one
 * has been. Scoped by the exact username, so one account's history never suppresses
 * another's.
 *
 * @property startedAt unix seconds, server-authoritative.
 */
@Entity(
    tableName = "scrobble_finalized",
    primaryKeys = ["lastfm_username", "stream_id"],
)
data class ScrobbleFinalized(
    @ColumnInfo(name = "lastfm_username")
    val lastfmUsername: String,

    @ColumnInfo(name = "stream_id")
    val streamId: String,

    @ColumnInfo(name = "started_at")
    val startedAt: Long,
)
