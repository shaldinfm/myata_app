package com.example.musicplayerapp.data.lastfm.queue

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * The scrobble queue - only what P5 writes and P6 will read. Delete-after-send and
 * attempt bookkeeping arrive with P6, when their exact shape is known; the columns
 * they need are already in the schema.
 */
@Dao
interface ScrobbleQueueDao {

    /**
     * Queues [entry] unless its occurrence is already queued.
     *
     * `INSERT OR IGNORE` against the `(stream_id, started_at)` primary key: the
     * database decides, atomically, so two racing inserts still leave one row and no
     * check-then-insert window exists. Returns the new rowid, or -1 for a duplicate.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: ScrobbleQueueEntry): Long

    @Query("SELECT * FROM scrobble_queue WHERE stream_id = :streamId AND started_at = :startedAt")
    suspend fun find(streamId: String, startedAt: Long): ScrobbleQueueEntry?

    @Query("SELECT COUNT(*) FROM scrobble_queue")
    suspend fun count(): Int

    /**
     * [username]'s rows, oldest airing first - Last.fm wants cached scrobbles sent in
     * order. `(started_at, stream_id)` is the primary key, so the order is total and
     * deterministic. Never returns another account's rows.
     */
    @Query(
        "SELECT * FROM scrobble_queue WHERE lastfm_username = :username " +
            "ORDER BY started_at ASC, stream_id ASC LIMIT :limit"
    )
    suspend fun pendingFor(username: String, limit: Int): List<ScrobbleQueueEntry>

    /** Explicit `Отключить`: [username]'s rows go, nobody else's. Returns how many. */
    @Query("DELETE FROM scrobble_queue WHERE lastfm_username = :username")
    suspend fun deleteForUsername(username: String): Int
}
