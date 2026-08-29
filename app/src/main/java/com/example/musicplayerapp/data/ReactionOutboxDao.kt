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

    /**
     * The soonest moment any pending row may next be attempted, or null when there
     * are none at all.
     *
     * This is the timer the drain runs on. A row parked by a failure is invisible to
     * [due] until its moment arrives, and nothing else in the system is watching a
     * clock - so without this the row would simply wait for the next reaction or the
     * next app start. See [com.example.musicplayerapp.data.supabase.ReactionSyncScheduler.scheduleWakeUp].
     *
     * MIN over a column that is indexed, on a table whose whole purpose is to
     * usually be empty.
     */
    @Query("SELECT MIN(next_attempt_at) FROM reaction_outbox")
    suspend fun earliestAttemptAt(): Long?

    @Query("SELECT * FROM reaction_outbox WHERE event_id = :eventId")
    suspend fun find(eventId: String): ReactionOutboxEntry?

    /**
     * The tracks a sender may work on now, in the order their oldest due row was
     * written.
     *
     * The atomic protocol's unit of delivery is a track, not an event, so the drain
     * picks tracks rather than rows. `MIN(rowid)` preserves the FIFO property the
     * per-row drain had: the track whose oldest owed act came first is served first.
     *
     * [limit] bounds tracks per run rather than rows, so a listener who reacted to
     * one track forty times still costs one round trip.
     */
    @Query(
        """
        SELECT track_key FROM reaction_outbox
        WHERE next_attempt_at <= :now
        GROUP BY track_key
        ORDER BY MIN(rowid) ASC
        LIMIT :limit
        """
    )
    suspend fun dueTrackKeys(now: Long, limit: Int): List<String>

    /**
     * Every pending row for one track, oldest act first, whatever protocol owns it.
     *
     * Deliberately ignores `next_attempt_at`. A sibling parked by a backoff has to
     * travel in the same batch as the rows around it: the state application the batch
     * publishes already carries that sibling's effect, so leaving it behind would let
     * it surface later as a genuinely unapplied event whose effect had already been
     * delivered - and re-apply a state the listener has since moved on from.
     */
    @Query("SELECT * FROM reaction_outbox WHERE track_key = :trackKey ORDER BY rowid ASC")
    suspend fun pendingForTrack(trackKey: String): List<ReactionOutboxEntry>

    /** Whether anything at all is still owed for [trackKey]. */
    @Query("SELECT COUNT(*) FROM reaction_outbox WHERE track_key = :trackKey")
    suspend fun countForTrack(trackKey: String): Int

    /**
     * Delivered, as a set.
     *
     * The atomic protocol settles a whole batch at once, inside one Room transaction
     * with the state adoption that follows - so a death cannot leave some rows of a
     * settled batch behind while others are gone.
     */
    @Query("DELETE FROM reaction_outbox WHERE event_id IN (:eventIds)")
    suspend fun deleteAll(eventIds: List<String>): Int

    /**
     * Discards every pending row. **Account deletion only.**
     *
     * Every other delete in this class removes rows that have been *delivered*. This
     * one throws away work that never will be, and it is correct for exactly one
     * reason: the identity those events belong to is gone, so there is nobody left for
     * them to be delivered as.
     *
     * Leaving them would be worse than losing them. The rows carry no `listener_id` -
     * ownership is attached at send time - so a later anonymous identity would happily
     * adopt them and upload the deleted account's reactions under a new uid. The gates
     * stop that while the deletion marker exists; this is what stops it afterwards.
     *
     * Deleting from an empty table is zero rows and no error, so the cleanup can be
     * re-run after a process death.
     *
     * @return how many rows went, for the log.
     */
    @Query("DELETE FROM reaction_outbox")
    suspend fun clearAll(): Int

    /** [recordFailedAttempt] for a whole batch: the batch failed as a unit. */
    @Query(
        """
        UPDATE reaction_outbox
        SET attempts = attempts + 1, next_attempt_at = :nextAttemptAt
        WHERE event_id IN (:eventIds)
        """
    )
    suspend fun recordFailedAttempts(eventIds: List<String>, nextAttemptAt: Long): Int

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
