package com.example.musicplayerapp.data.lastfm.queue

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * The scrobble queue: what P5 writes, and what the P6 sender reads, finalizes and
 * reschedules - always by the `(stream_id, started_at)` key, never by artist or title.
 *
 * An abstract class rather than an interface for the two Room transactions,
 * [enqueue] and [finalizeOccurrence], which together keep a finalized airing from
 * ever being queued again (see [ScrobbleFinalized]).
 */
@Dao
abstract class ScrobbleQueueDao {

    /** What [enqueue] did with a candidate row. */
    enum class Enqueued {
        /** A new row: the airing is pending. */
        QUEUED,

        /** The airing is already pending; the queue's primary key refused a second row. */
        DUPLICATE,

        /** The airing is at or below this account's finalized mark on its stream. Nothing written. */
        ALREADY_FINALIZED,
    }

    /**
     * Queues [entry] unless its occurrence is already queued.
     *
     * `INSERT OR IGNORE` against the `(stream_id, started_at)` primary key: the
     * database decides, atomically, so two racing inserts still leave one row and no
     * check-then-insert window exists. Returns the new rowid, or -1 for a duplicate.
     *
     * Knows nothing of [ScrobbleFinalized]: production queues through [enqueue].
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insert(entry: ScrobbleQueueEntry): Long

    @Query("SELECT * FROM scrobble_queue WHERE stream_id = :streamId AND started_at = :startedAt")
    abstract suspend fun find(streamId: String, startedAt: Long): ScrobbleQueueEntry?

    @Query("SELECT COUNT(*) FROM scrobble_queue")
    abstract suspend fun count(): Int

    /**
     * [username]'s rows, oldest airing first - Last.fm wants cached scrobbles sent in
     * order. `(started_at, stream_id)` is the primary key, so the order is total and
     * deterministic. Never returns another account's rows.
     */
    @Query(
        "SELECT * FROM scrobble_queue WHERE lastfm_username = :username " +
            "ORDER BY started_at ASC, stream_id ASC LIMIT :limit"
    )
    abstract suspend fun pendingFor(username: String, limit: Int): List<ScrobbleQueueEntry>

    /**
     * Explicit `Отключить`: [username]'s pending rows go, nobody else's. Returns how
     * many. [ScrobbleFinalized] is not touched: what was finalized stays finalized.
     */
    @Query("DELETE FROM scrobble_queue WHERE lastfm_username = :username")
    abstract suspend fun deleteForUsername(username: String): Int

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
    abstract suspend fun activeHead(username: String, quarantined: Long, limit: Int): List<ScrobbleQueueEntry>

    /**
     * The one occurrence, by its key. A row already purged is a harmless no-op. Not
     * the sender's terminal path any more - that is [finalizeOccurrence].
     */
    @Query("DELETE FROM scrobble_queue WHERE stream_id = :streamId AND started_at = :startedAt")
    abstract suspend fun deleteOccurrence(streamId: String, startedAt: Long): Int

    /**
     * One more attempt for the occurrence, not to be tried again before
     * [nextAttemptAt]. An UPDATE: it can never recreate a row that a disconnect has
     * purged while a request was in flight.
     */
    @Query(
        "UPDATE scrobble_queue SET attempts = attempts + 1, next_attempt_at = :nextAttemptAt " +
            "WHERE stream_id = :streamId AND started_at = :startedAt"
    )
    abstract suspend fun recordAttempt(streamId: String, startedAt: Long, nextAttemptAt: Long): Int

    // ---- finalization (G6b P6b) ----------------------------------------------------

    /** [username]'s finalized mark on [streamId], or null if nothing there was ever finalized. */
    @Query("SELECT started_at FROM scrobble_finalized WHERE lastfm_username = :username AND stream_id = :streamId")
    abstract suspend fun finalizedThrough(username: String, streamId: String): Long?

    /** [finalizeOccurrence]'s first step: the mark's row, if the account/stream has none yet. */
    @Query(
        "INSERT OR IGNORE INTO scrobble_finalized (lastfm_username, stream_id, started_at) " +
            "VALUES (:username, :streamId, :startedAt)"
    )
    abstract suspend fun insertFinalizedIfAbsent(username: String, streamId: String, startedAt: Long)

    /**
     * [finalizeOccurrence]'s second step: raises the mark to [startedAt], never lowers
     * it. Two statements instead of an upsert because `ON CONFLICT DO UPDATE` needs
     * SQLite 3.24, which API 24 does not ship.
     */
    @Query(
        "UPDATE scrobble_finalized SET started_at = :startedAt " +
            "WHERE lastfm_username = :username AND stream_id = :streamId AND started_at < :startedAt"
    )
    abstract suspend fun raiseFinalized(username: String, streamId: String, startedAt: Long): Int

    /** [finalizeOccurrence]'s last step: the row, only if it is still [username]'s. */
    @Query(
        "DELETE FROM scrobble_queue " +
            "WHERE lastfm_username = :username AND stream_id = :streamId AND started_at = :startedAt"
    )
    abstract suspend fun deleteOccurrenceOf(username: String, streamId: String, startedAt: Long): Int

    /**
     * Queues [entry] - unless its account has already finalized that airing, or a
     * newer one, on its stream. The mark is read and the row inserted in one
     * transaction, so a finalization cannot slip between the check and the insert.
     * Never raises the mark: a queued airing is pending, not finalized.
     */
    @Transaction
    open suspend fun enqueue(entry: ScrobbleQueueEntry): Enqueued {
        val finalized = finalizedThrough(entry.lastfmUsername, entry.streamId)
        if (finalized != null && entry.startedAt <= finalized) return Enqueued.ALREADY_FINALIZED
        return if (insert(entry) == -1L) Enqueued.DUPLICATE else Enqueued.QUEUED
    }

    /**
     * Last.fm has answered this airing terminally for [username]: raise the account's
     * mark on [streamId] to [startedAt] and delete the row, as **one** transaction -
     * no committed state has the row gone and the mark missing. Returns the rows
     * deleted.
     *
     * The mark is raised even when the row is already gone (purged by a disconnect
     * while the request was in flight): Last.fm has it either way. The delete is
     * scoped to [username], so a row a different account has since queued for the
     * same airing is left for that account.
     */
    @Transaction
    open suspend fun finalizeOccurrence(username: String, streamId: String, startedAt: Long): Int {
        insertFinalizedIfAbsent(username, streamId, startedAt)
        raiseFinalized(username, streamId, startedAt)
        return deleteOccurrenceOf(username, streamId, startedAt)
    }
}
