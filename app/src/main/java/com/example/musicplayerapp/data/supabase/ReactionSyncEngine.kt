package com.example.musicplayerapp.data.supabase

import android.util.Log
import com.example.musicplayerapp.data.ReactionDao
import com.example.musicplayerapp.data.ReactionOutboxDao
import com.example.musicplayerapp.data.ReactionOutboxEntry

/**
 * Draining the outbox to Supabase. The whole algorithm, and no Android in it
 * beyond a log tag.
 *
 * Separated from [ReactionSyncWorker] because the worker is a scheduling detail and
 * this is the part with the interesting failure modes. Given a fake
 * [ReactionSyncApi], every transition, every class of failure and every restart can
 * be driven from a test against a real Room database in a few milliseconds.
 *
 * ## The order of operations, and why it is that order
 *
 * For each pending row, oldest local write first:
 *
 * 1. **deliver the event** to `reaction_events`, with its original `event_id`,
 *    `occurred_at` and words. Idempotent, so a retry is free;
 * 2. **reconcile the current state** from the *current* `track_reaction` row, read
 *    now, not from the event;
 * 3. **only then delete the outbox row.**
 *
 * Step 3 last is the crash contract. A process death anywhere before it leaves the
 * row pending, and the next run repeats both remote writes - which is safe because
 * both are idempotent. The alternative, deleting first, would lose a listener's
 * reaction to a badly timed kill.
 *
 * ## Why a stale event cannot corrupt the current state
 *
 * Step 2 reads Room, not the event. A row that has waited a week in someone's
 * pocket still delivers its week-old history entry - that is what history is - but
 * the state it then writes is whatever the listener thinks now. There is no
 * ordering requirement for correctness of the current state, which is what makes
 * the per-row backoff in this file safe: a parked row cannot hold the remote state
 * wrong, because the next row through will set it right.
 *
 * ## Identity
 *
 * [identity] is called **once, and only after** [ReactionOutboxDao.count] has
 * proved there is work. A listener who never reacts never reaches it, so no
 * anonymous user is ever created for them. When it returns null the drain stops
 * with [DrainResult.RetryLater] and touches nothing: no session is not a reason to
 * penalise a row, and it is never a reason to mint a second identity - see
 * [AnonymousSession].
 */
class ReactionSyncEngine(
    private val reactions: ReactionDao,
    private val outbox: ReactionOutboxDao,
    private val api: ReactionSyncApi,
    private val identity: suspend () -> String?,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val batchSize: Int = BATCH_SIZE,
) {

    suspend fun drain(): DrainResult {
        // Cheapest possible question first, and the reason it is first is that the
        // answer for most listeners is zero and the next line would otherwise create
        // them a database identity for nothing.
        if (outbox.count() == 0) return DrainResult.Idle

        val listenerId = identity()
            ?: return DrainResult.RetryLater("no listener identity available")

        val batch = outbox.due(now(), batchSize)
        if (batch.isEmpty()) {
            // Rows exist but every one is serving a backoff. Nothing to do now, and
            // nothing wrong either.
            return DrainResult.Idle
        }

        var delivered = 0

        for (row in batch) {
            when (val event = api.deliverEvent(row, listenerId)) {
                is SyncOutcome.Success -> Unit

                is SyncOutcome.Transient -> {
                    park(row, transient = true, why = event.reason)
                    return DrainResult.RetryLater("transient on event: ${event.reason}")
                }

                is SyncOutcome.AuthUnavailable -> {
                    // The row is blameless: no attempt is counted and no backoff is
                    // set. Only the run is over.
                    return DrainResult.RetryLater("auth: ${event.reason}")
                }

                is SyncOutcome.Permanent -> {
                    park(row, transient = false, why = "${event.status} ${event.reason}")
                    // Deliberately not a return. One row the server will never
                    // accept must not stop the rows behind it, which are almost
                    // certainly fine.
                    continue
                }
            }

            // The current opinion, read now. Null means no row at all, which is the
            // same as NEUTRAL and reconciles to a remote delete.
            val current = reactions.find(row.trackKey)

            when (val state = api.reconcileCurrentState(row.trackKey, current, listenerId)) {
                is SyncOutcome.Success -> {
                    // Both halves are in. Only now does the row stop being owed.
                    outbox.delete(row.eventId)
                    delivered++
                }

                is SyncOutcome.Transient -> {
                    park(row, transient = true, why = state.reason)
                    return DrainResult.RetryLater("transient on state: ${state.reason}")
                }

                is SyncOutcome.AuthUnavailable ->
                    return DrainResult.RetryLater("auth: ${state.reason}")

                is SyncOutcome.Permanent -> {
                    park(row, transient = false, why = "${state.status} ${state.reason}")
                    continue
                }
            }
        }

        val stillDue = outbox.dueCount(now())
        return when {
            delivered > 0 && stillDue > 0 -> DrainResult.MoreWorkDue(stillDue)
            delivered > 0 -> DrainResult.Drained(delivered)
            else -> DrainResult.Idle
        }
    }

    /**
     * Counts an attempt against a row and decides when it may be tried again.
     *
     * Never deletes. A row that cannot sync is the only evidence that something is
     * wrong, and discarding it would turn a visible problem into a listener's
     * reaction quietly never arriving.
     *
     * What is logged is deliberately thin: the first eight characters of the key,
     * which is a hash and identifies a track only to somebody who already has it,
     * the transition, the attempt count and the server's reason. Not the artist, not
     * the title, not the listener id. Enough to find a stuck row in a bug report,
     * not enough to be a record of what somebody listens to.
     */
    private suspend fun park(row: ReactionOutboxEntry, transient: Boolean, why: String) {
        val attempt = row.attempts + 1
        val delay = if (transient) transientBackoff(attempt) else permanentBackoff(attempt)
        outbox.recordFailedAttempt(row.eventId, now() + delay)

        val kind = if (transient) "transient" else "permanent"
        Log.w(
            TAG,
            "$kind failure for ${row.eventType.wire} ${row.trackKey.take(8)} " +
                "(attempt $attempt, retry in ${delay / 1000}s): $why"
        )
    }

    companion object {

        private const val TAG = "ReactionSync"

        /**
         * How many rows one run will try.
         *
         * Bounded so a listener who reacted a hundred times on a plane does not turn
         * their first minute of connectivity into a hundred serial round trips
         * inside one worker. When the batch is full and more is due, the run says so
         * and the worker schedules the next one.
         */
        const val BATCH_SIZE = 50

        private const val SECOND = 1_000L
        private const val MINUTE = 60 * SECOND
        private const val HOUR = 60 * MINUTE

        /** 30s doubling to an hour. The network came back or it did not. */
        internal fun transientBackoff(attempt: Int): Long =
            (30 * SECOND shl (attempt - 1).coerceIn(0, 7)).coerceAtMost(HOUR)

        /**
         * An hour doubling to a day.
         *
         * Long, because a permanent failure will still be permanent in a minute, and
         * bounded, because "never again" is not an option: the row is kept, so it has
         * to be retried on some schedule, and a day is slow enough to cost nothing
         * and fast enough that a server-side fix heals things without an app update.
         */
        internal fun permanentBackoff(attempt: Int): Long =
            (HOUR shl (attempt - 1).coerceIn(0, 5)).coerceAtMost(24 * HOUR)
    }
}

/** What one drain run concluded, and what the worker should do about it. */
sealed interface DrainResult {

    /** Nothing owed, or nothing due yet. The common case, and it costs one COUNT. */
    data object Idle : DrainResult

    /** Everything that was due went out. */
    data class Drained(val delivered: Int) : DrainResult

    /** The batch filled up and more is due now. Schedule another run immediately. */
    data class MoreWorkDue(val remaining: Int) : DrainResult

    /**
     * Could not get on with it - offline, no session, the server is unwell. Nothing
     * was lost; WorkManager should back off and try again.
     */
    data class RetryLater(val reason: String) : DrainResult
}
