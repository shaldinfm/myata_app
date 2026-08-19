package com.example.musicplayerapp.data

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Reading and draining the outbox.
 *
 * Everything here is about **delivery**, which is why nothing here writes an
 * event. Rows are created by [ReactionDao], inside the transaction that changes
 * the reaction, and there is no other way to make one - an outbox row that does
 * not correspond to a state change would be a reaction nobody expressed.
 *
 * Nothing calls the draining methods yet. They are the shape the sender will use,
 * landing with the table so that the table is complete rather than half a design,
 * and so the tests can assert on the queue the way a sender would see it.
 */
@Dao
interface ReactionOutboxDao {

    /**
     * Everything still waiting, oldest act first.
     *
     * Order matters: LIKE then UNLIKE on one track is a different story from
     * UNLIKE then LIKE, and the backend has to be told them in the order they
     * happened. [ReactionOutboxEntry.occurredAt] is that order; `event_id` breaks
     * ties, because two taps inside one millisecond are possible and an unstable
     * order would make retries non-deterministic.
     */
    @Query("SELECT * FROM reaction_outbox ORDER BY occurred_at ASC, event_id ASC")
    suspend fun pending(): List<ReactionOutboxEntry>

    /** The pending queue as it changes. For diagnostics and tests. */
    @Query("SELECT * FROM reaction_outbox ORDER BY occurred_at ASC, event_id ASC")
    fun observePending(): Flow<List<ReactionOutboxEntry>>

    /**
     * The rows a sender may attempt now, oldest act first.
     *
     * A row whose [ReactionOutboxEntry.nextAttemptAt] is in the future is serving
     * a backoff and is skipped rather than retried into the same failure.
     */
    @Query(
        """
        SELECT * FROM reaction_outbox
        WHERE next_attempt_at <= :now
        ORDER BY occurred_at ASC, event_id ASC
        LIMIT :limit
        """
    )
    suspend fun due(now: Long, limit: Int): List<ReactionOutboxEntry>

    /** How many acts are still undelivered. */
    @Query("SELECT COUNT(*) FROM reaction_outbox")
    suspend fun count(): Int

    @Query("SELECT * FROM reaction_outbox WHERE event_id = :eventId")
    suspend fun find(eventId: String): ReactionOutboxEntry?

    /** Delivered. The only reason a row ever leaves this table. */
    @Query("DELETE FROM reaction_outbox WHERE event_id = :eventId")
    suspend fun delete(eventId: String): Int

    /**
     * Records a failed attempt and when the next one may happen.
     *
     * Separate from delivery so that a failure costs one small write and never
     * touches the event itself: what the listener did is not up for revision.
     */
    @Query(
        """
        UPDATE reaction_outbox
        SET attempts = attempts + 1, next_attempt_at = :nextAttemptAt
        WHERE event_id = :eventId
        """
    )
    suspend fun recordFailedAttempt(eventId: String, nextAttemptAt: Long): Int
}
