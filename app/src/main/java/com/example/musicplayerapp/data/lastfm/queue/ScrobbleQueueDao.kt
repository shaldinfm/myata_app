package com.example.musicplayerapp.data.lastfm.queue

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * The scrobble queue: what P5 writes, and what the P6 sender reads, deletes and
 * reschedules - always by the `(stream_id, started_at)` key, never by artist or title.
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

    // ---- the sender (G6b P6a) ---------------------------------------------------

    /**
     * The head of [username]'s **active** queue, oldest airing first: every row but
     * the quarantined ones (`next_attempt_at == `[quarantined]), whether due yet or
     * not. The sender sends the due prefix of this and nothing past a row that is
     * not due - so a row in backoff holds back everything newer, and cached
     * scrobbles stay in order. A quarantined row is out of the active queue and
     * holds back nothing.
     */
    @Query(
        "SELECT * FROM scrobble_queue WHERE lastfm_username = :username " +
            "AND next_attempt_at != :quarantined " +
            "ORDER BY started_at ASC, stream_id ASC LIMIT :limit"
    )
    suspend fun activeHead(username: String, quarantined: Long, limit: Int): List<ScrobbleQueueEntry>

    /** The one occurrence, by its key. A row already purged is a harmless no-op. */
    @Query("DELETE FROM scrobble_queue WHERE stream_id = :streamId AND started_at = :startedAt")
    suspend fun deleteOccurrence(streamId: String, startedAt: Long): Int

    /**
     * One more attempt for the occurrence, not to be tried again before
     * [nextAttemptAt]. An UPDATE: it can never recreate a row that a disconnect has
     * purged while a request was in flight.
     */
    @Query(
        "UPDATE scrobble_queue SET attempts = attempts + 1, next_attempt_at = :nextAttemptAt " +
            "WHERE stream_id = :streamId AND started_at = :startedAt"
    )
    suspend fun recordAttempt(streamId: String, startedAt: Long, nextAttemptAt: Long): Int
}
