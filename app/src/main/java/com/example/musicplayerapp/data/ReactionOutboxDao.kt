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
     * Everything still waiting, in the order it was written locally.
     *
     * ## Why `rowid` and not `occurred_at`
     *
     * Order matters: LIKE then UNLIKE on one track is a different story from UNLIKE
     * then LIKE. The obvious key for that is [ReactionOutboxEntry.occurredAt] - and
     * it is the wrong one, because it is a device wall clock. An NTP correction or a
     * timezone change can move it backwards between two taps, and then the queue
     * hands the backend a listener's history inside out. `event_id` is worse still:
     * a random UUID, so ordering by it is arbitrary, merely *stable*.
     *
     * SQLite's implicit `rowid` is the real local order. Rows are only ever inserted
     * inside the reaction transaction, one per committed transition, so insertion
     * order is transition order - a causal order rather than a measured one, immune
     * to whatever the clock does.
     *
     * **`rowid` is strictly local and ephemeral.** SQLite reuses the values of
     * deleted rows, so it is not a global sequence: it is never sent to Supabase,
     * never persisted anywhere else, and never compared against a row that has
     * already been delivered. What it does guarantee is the only thing a drain
     * needs - that among rows pending *at the same time*, `rowid` order is insertion
     * order, because a new row is always given one more than the largest currently
     * in the table.
     */
    @Query("SELECT * FROM reaction_outbox ORDER BY rowid ASC")
    suspend fun pending(): List<ReactionOutboxEntry>

    /** The pending queue as it changes, in the same local order. Diagnostics and tests. */
    @Query("SELECT * FROM reaction_outbox ORDER BY rowid ASC")
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
        ORDER BY rowid ASC
        LIMIT :limit
        """
    )
    suspend fun due(now: Long, limit: Int): List<ReactionOutboxEntry>

    /**
     * How many rows a sender could attempt right now.
     *
     * What the drain asks at the end of a batch to decide whether to schedule
     * itself again. Distinct from [count], which includes rows serving a backoff:
     * "there is still work" and "there is still work I may do" are different
     * questions and confusing them is how a hot retry loop starts.
     */
    @Query("SELECT COUNT(*) FROM reaction_outbox WHERE next_attempt_at <= :now")
    suspend fun dueCount(now: Long): Int

    /** How many acts are still undelivered, backoff or not. */
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
