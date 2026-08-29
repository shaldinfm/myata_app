package com.example.musicplayerapp.data.supabase

import android.util.Log
import com.example.musicplayerapp.data.ReactionDao
import com.example.musicplayerapp.data.ReactionOutboxDao
import com.example.musicplayerapp.data.ReactionOutboxEntry
import com.example.musicplayerapp.data.ReactionWriteGate
import com.example.musicplayerapp.data.SyncProtocol

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
 * [identity] is called **once per run, and only after a batch of rows that may
 * actually be sent has been found** - after [ReactionOutboxDao.count] and after
 * [ReactionOutboxDao.due]. A listener who never reacts never reaches it, and neither
 * does one whose only pending row is parked behind a backoff, so no anonymous user
 * is created for either. When it returns null the drain stops with
 * [DrainResult.RetryLater] and touches nothing: no session is not a reason to
 * penalise a row, and it is never a reason to mint a second identity - see
 * [ListenerSession].
 *
 * ## Waking a parked row
 *
 * A row that failed is given a `next_attempt_at` in the future, which makes it
 * invisible to [ReactionOutboxDao.due] until its moment. Nothing else in the system
 * watches a clock, so every run that leaves such a row behind reports **when** it
 * becomes eligible - [DrainResult.Waiting], or [DrainResult.Drained.nextAttemptAt] -
 * and the worker turns that into a delayed WorkManager request. Without it a row
 * parked by a 4xx for an hour would sit there until the listener happened to react
 * again or restart the app.
 */
class ReactionSyncEngine(
    private val reactions: ReactionDao,
    private val outbox: ReactionOutboxDao,
    private val api: ReactionSyncApi,
    private val identity: suspend () -> ListenerIdentity,
    /**
     * Whether a permanent account deletion is unresolved on this install.
     *
     * A predicate rather than a `Context`, so the algorithm still needs no Android -
     * the same reason [identity] is injected. Deliberately **not defaulted**: a drain
     * constructed without a deliberate answer would default to "no deletion", which
     * is the unsafe direction and exactly the bug this parameter exists to prevent.
     */
    private val deletionInFlight: suspend () -> Boolean,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val batchSize: Int = BATCH_SIZE,
) {

    /**
     * One drain, under [SyncLease].
     *
     * The lease is taken with `tryAcquire`, never waited on. A drain that cannot
     * have it is one an identity handoff is deliberately excluding, and queueing
     * behind that handoff would park a WorkManager thread for the length of somebody
     * registering - a network round trip - to do work that will be scheduled again
     * the moment the handoff releases.
     *
     * The lease is the *in-process* half of that exclusion. The durable handoff
     * stage is the other half and is checked by [ReactionSyncWorker] before this is
     * ever called, because a mutex cannot survive the process and a flag cannot stop
     * a drain that has already passed it.
     */
    suspend fun drain(): DrainResult =
        SyncLease.tryAcquire { drainHoldingLease() } ?: DrainResult.HandoffInProgress

    private suspend fun drainHoldingLease(): DrainResult {
        // Before the outbox is even counted. An install whose account is being deleted
        // must not deliver anything: the rows belong to an identity that is going away
        // or has already gone, and the server would refuse them or - worse - accept
        // them under a replacement identity. **No row is read.**
        if (deletionInFlight()) return DrainResult.DeletionInProgress

        // Cheapest possible question first, and the reason it is first is that the
        // answer for most listeners is zero and the next line would otherwise create
        // them a database identity for nothing.
        if (outbox.count() == 0) return DrainResult.Idle

        val tracks = outbox.dueTrackKeys(now(), batchSize)
        if (tracks.isEmpty()) {
            // Rows exist but every one is serving a backoff. Nothing to send yet, so
            // no identity is requested for it - asking here would mint an anonymous
            // user for somebody whose only pending row is one the server has already
            // refused. The caller is told when to come back instead.
            return DrainResult.Waiting(outbox.earliestAttemptAt() ?: now())
        }

        val listenerId = when (val who = identity()) {
            is ListenerIdentity.Available -> who.uid

            // Deliberately signed out. Not a failure, and emphatically not something
            // to retry: no row is read, no attempt is counted, nothing is parked, and
            // the caller schedules no follow-up. The rows stay exactly as they are,
            // waiting for an explicit sign-in.
            is ListenerIdentity.Paused -> return DrainResult.Paused

            is ListenerIdentity.Unavailable ->
                return DrainResult.RetryLater("no listener identity: ${who.reason}")
        }

        var delivered = 0

        for (trackKey in tracks) {
            // A track's pending rows are homogeneous by construction - see
            // ReactionDao.protocolFor - so this asks which protocol owns the track,
            // not which owns a row. The legacy branch is checked first anyway: if a
            // bug ever did produce a mixed set, the safe reading is that the track
            // still owes a pre-cutover delivery.
            val pending = outbox.pendingForTrack(trackKey)
            if (pending.isEmpty()) continue

            val outcome = if (pending.any { it.syncProtocol == SyncProtocol.LEGACY }) {
                drainLegacyTrack(pending, listenerId)
            } else {
                drainAtomicTrack(trackKey, listenerId)
            }

            when (outcome) {
                is TrackOutcome.Delivered -> delivered += outcome.rows

                // One track the server will never accept must not stop the tracks
                // behind it, which are almost certainly fine. Parked and skipped.
                is TrackOutcome.Parked -> continue

                // The network or the session, not this track. Nothing else will get
                // through either, so the run ends here rather than failing every
                // remaining track in turn.
                is TrackOutcome.StopRun -> return outcome.result
            }
        }

        // What is owed, and when. Both halves matter: rows that are due now want
        // another run immediately, and rows parked in the future want a timer. A run
        // that reported neither is how a parked row used to be forgotten until the
        // next reaction or the next app start.
        val stillDue = outbox.dueCount(now())
        if (delivered > 0 && stillDue > 0) return DrainResult.MoreWorkDue(stillDue, listenerId)

        val parkedUntil = outbox.earliestAttemptAt()
        return when {
            delivered > 0 -> DrainResult.Drained(delivered, parkedUntil, listenerId)
            parkedUntil != null -> DrainResult.Waiting(parkedUntil)
            else -> DrainResult.Idle
        }
    }

    /** What one track's turn concluded, and whether the run may continue. */
    private sealed interface TrackOutcome {
        data class Delivered(val rows: Int) : TrackOutcome
        data object Parked : TrackOutcome
        data class StopRun(val result: DrainResult) : TrackOutcome
    }

    // ------------------------------------------------------------ legacy --

    /**
     * The pre-cutover path, unchanged: deliver the event, reconcile the current
     * state, delete the row. One row at a time, oldest act first.
     *
     * Deliberately untouched except for where the delete happens. These rows were
     * written by a build that had no application log, and the atomic function refuses
     * an event it has seen but never marked - correctly, because whether that event's
     * state write ever landed is undecidable. So they finish the way they started.
     *
     * The one change: the delete runs under [ReactionWriteGate]. It is the moment a
     * track's legacy epoch can end, and a tap choosing its protocol has to serialise
     * against it. See [ReactionWriteGate.withDeliveryStep].
     */
    private suspend fun drainLegacyTrack(
        pending: List<ReactionOutboxEntry>,
        listenerId: String,
    ): TrackOutcome {
        var delivered = 0
        val moment = now()

        for (row in pending.filter { it.syncProtocol == SyncProtocol.LEGACY }) {
            if (row.nextAttemptAt > moment) continue

            when (val event = api.deliverEvent(row, listenerId)) {
                is SyncOutcome.Success -> Unit

                is SyncOutcome.Transient -> {
                    park(row, transient = true, why = event.reason)
                    return TrackOutcome.StopRun(
                        DrainResult.RetryLater("transient on event: ${event.reason}")
                    )
                }

                is SyncOutcome.AuthUnavailable ->
                    // The row is blameless: no attempt is counted and no backoff is
                    // set. Only the run is over.
                    return TrackOutcome.StopRun(DrainResult.RetryLater("auth: ${event.reason}"))

                is SyncOutcome.Permanent -> {
                    park(row, transient = false, why = "${event.status} ${event.reason}")
                    continue
                }
            }

            // The current opinion, read now - NEUTRAL included, which since migration
            // 0002 upserts a row like any other state rather than deleting one. Null
            // means the local row is gone entirely, which is data removal, not a
            // withdrawal; that is the only case that still reconciles to a delete.
            val current = reactions.find(row.trackKey)

            when (val state = api.reconcileCurrentState(row.trackKey, current, listenerId)) {
                is SyncOutcome.Success -> {
                    // Both halves are in. Only now does the row stop being owed - and
                    // the delete is gated, because it may be the one that ends this
                    // track's legacy epoch.
                    ReactionWriteGate.withDeliveryStep { outbox.delete(row.eventId) }
                    delivered++
                }

                is SyncOutcome.Transient -> {
                    park(row, transient = true, why = state.reason)
                    return TrackOutcome.StopRun(
                        DrainResult.RetryLater("transient on state: ${state.reason}")
                    )
                }

                is SyncOutcome.AuthUnavailable ->
                    return TrackOutcome.StopRun(DrainResult.RetryLater("auth: ${state.reason}"))

                is SyncOutcome.Permanent -> {
                    park(row, transient = false, why = "${state.status} ${state.reason}")
                    continue
                }
            }
        }

        return if (delivered > 0) TrackOutcome.Delivered(delivered) else TrackOutcome.Parked
    }

    // ------------------------------------------------------------ atomic --

    /**
     * One track, one snapshot, one call, one settlement.
     *
     * ```
     * gate:    read the current row + EVERY pending atomic row for this track
     * ------   release
     * network: apply_reaction_event_batch
     * ------
     * gate:    delete the represented rows, then adopt only if nothing else is owed
     * ```
     *
     * The batch is the whole pending set, not the due subset. A sibling parked by a
     * backoff has to travel with its neighbours: the state this call publishes
     * already carries that sibling's effect, so leaving it behind would let it
     * surface later as a genuinely unapplied event whose effect had already been
     * delivered - and re-apply a state the listener has since moved past. That is the
     * defect the whole application log exists to prevent, so the client must not
     * manufacture it locally.
     */
    private suspend fun drainAtomicTrack(trackKey: String, listenerId: String): TrackOutcome {
        val snapshot = ReactionWriteGate.withDeliveryStep {
            val current = reactions.find(trackKey)
            val events = outbox.pendingForTrack(trackKey)
                .filter { it.syncProtocol == SyncProtocol.ATOMIC_RPC }
            if (current == null || events.isEmpty()) null else current to events
        } ?: return TrackOutcome.Parked

        val (current, events) = snapshot

        // The server accepts 1..256 and refuses the whole call above that. Splitting
        // is not an option and never will be: a prefix of the batch would need the
        // state as of that prefix, and only the final state exists. So the batch is
        // parked whole, with every row preserved, and the failure is loud.
        if (events.size > MAX_BATCH_EVENTS) {
            parkBatch(events, transient = false, why = "batch of ${events.size} exceeds $MAX_BATCH_EVENTS")
            return TrackOutcome.Parked
        }

        return when (val answer = api.applyBatch(trackKey, events, current, listenerId)) {
            // Genuinely unapplied, now applied and marked. The row that came back is
            // this device's own write, so adopting its revision is recording what we
            // just did rather than accepting somebody else's state.
            is BatchOutcome.Applied -> {
                settle(trackKey, events, answer.row)
                TrackOutcome.Delivered(events.size)
            }

            // Already delivered, and the server wrote nothing. These rows are settled
            // evidence of a batch that landed before this device died; the answer may
            // carry a newer row, because another device can have moved the track
            // since. No new revision was created and none is asked for.
            is BatchOutcome.AlreadyApplied -> {
                settle(trackKey, events, answer.row)
                TrackOutcome.Delivered(events.size)
            }

            is BatchOutcome.Failed -> when (val outcome = answer.outcome) {
                is SyncOutcome.Success -> TrackOutcome.Delivered(0)

                is SyncOutcome.Transient -> {
                    parkBatch(events, transient = true, why = outcome.reason)
                    TrackOutcome.StopRun(DrainResult.RetryLater("transient: ${outcome.reason}"))
                }

                // No usable session. The batch did nothing wrong, so nothing is
                // counted against it and nothing is parked; only the run ends. This
                // is the branch a null auth.uid() has to reach - see classifyStatus.
                is SyncOutcome.AuthUnavailable ->
                    TrackOutcome.StopRun(DrainResult.RetryLater("auth: ${outcome.reason}"))

                is SyncOutcome.Permanent -> {
                    parkBatch(events, transient = false, why = "${outcome.status} ${outcome.reason}")
                    TrackOutcome.Parked
                }
            }
        }
    }

    /**
     * Settles a delivered batch, and adopts the remote row only if it is still safe
     * to.
     *
     * One Room transaction inside one hold of [ReactionWriteGate], and the ordering
     * is the whole point:
     *
     *  1. delete exactly the rows this batch represented - never "everything pending
     *     for the track", which would discard a tap that landed during the call;
     *  2. ask whether anything is still owed for the track;
     *  3. adopt the returned state **only when nothing is**.
     *
     * Step 3's condition is what stops an answer overwriting an act the listener can
     * see. A row still pending is a local mutation the server has not been told
     * about, and by policy that wins - not because the returned state is provably
     * older, which across devices is not knowable, but because a genuine local act
     * is not something a settlement may quietly undo. Recording the revision alone
     * would be no better: a rev is a claim that local state matches that server row,
     * and while a mutation is outstanding it does not.
     *
     * A null [row] means the track has no remote row at all - data removal, in
     * practice - and there is nothing to adopt. The rows still settle: their events
     * are delivered whatever the current state looks like.
     */
    private suspend fun settle(
        trackKey: String,
        events: List<ReactionOutboxEntry>,
        row: RemoteReaction?,
    ) {
        ReactionWriteGate.withDeliveryStep {
            settleWithinTransaction(trackKey, events.map { it.eventId }, row)
        }
    }

    private suspend fun settleWithinTransaction(
        trackKey: String,
        eventIds: List<String>,
        row: RemoteReaction?,
    ) {
        outbox.deleteAll(eventIds)

        if (row == null) return
        if (outbox.countForTrack(trackKey) > 0) return

        val local = reactions.find(trackKey) ?: return
        if (local.reaction == row.reaction && local.updatedAt == row.updatedAt) {
            // Same state, different bookkeeping: this is the ordinary APPLIED answer
            // to our own write. Record the revision and leave the row alone.
            reactions.recordRemoteRev(trackKey, row.rev)
        } else {
            reactions.adoptRemote(
                trackKey = trackKey,
                reaction = row.reaction,
                artist = row.artist,
                title = row.title,
                // An absent remote stream is the server not having recorded one, which
                // is weaker evidence than what this device already holds. There is
                // always a local row on this path, so nothing has to be invented.
                stream = row.stream ?: local.stream,
                likedAt = row.likedAt,
                updatedAt = row.updatedAt,
                rev = row.rev,
            )
        }
    }

    /** [park], for a batch that failed as a unit. */
    private suspend fun parkBatch(
        events: List<ReactionOutboxEntry>,
        transient: Boolean,
        why: String,
    ) {
        val attempt = (events.maxOfOrNull { it.attempts } ?: 0) + 1
        val delay = if (transient) transientBackoff(attempt) else permanentBackoff(attempt)
        outbox.recordFailedAttempts(events.map { it.eventId }, now() + delay)

        val kind = if (transient) "transient" else "permanent"
        Log.w(
            TAG,
            "$kind failure for a batch of ${events.size} on ${events.first().trackKey.take(8)} " +
                "(attempt $attempt, retry in ${delay / 1000}s): $why"
        )
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

        /**
         * What `apply_reaction_event_batch` accepts, and therefore what the
         * client must not exceed: 1..256 events in one call.
         *
         * Unreachable in practice - repeated identical taps enqueue nothing, so
         * getting here means alternating like/unlike on one track 256 times
         * offline without a single successful drain. The check exists because
         * the failure has to be safe rather than partial if it ever happens.
         */
        const val MAX_BATCH_EVENTS = 256

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

    /** Nothing owed at all. The common case, and it costs one COUNT. */
    data object Idle : DrainResult

    /**
     * Rows are owed but none may be attempted yet: every one is serving a backoff.
     *
     * [until] is the soonest any of them becomes eligible, and the worker turns it
     * into a delayed run. Without that, a parked row has nothing watching a clock for
     * it - `APPEND_OR_REPLACE` closes the commit and mid-run races, but it is not a
     * timer, and a chain that has finished schedules nothing by itself.
     */
    data class Waiting(val until: Long) : DrainResult

    /**
     * Everything that was due went out.
     *
     * @property nextAttemptAt when a row that is still parked becomes eligible, or
     *   null if the outbox is now empty. A run that delivered something can still
     *   leave a poison row behind it, and that row needs its timer just as much.
     */
    data class Drained(
        val delivered: Int,
        val nextAttemptAt: Long?,

        /**
         * The identity that owned this drain, carried out rather than looked up later.
         *
         * `ReactionSyncWorker` files the upload timestamp under this uid. It cannot
         * ask who the listener is *now*: the drain has released [SyncLease] by then,
         * and a sign-out followed by a sign-in as another account can land in the
         * gap - which would file X's delivery under Y. The only identity that can
         * answer "whose rows were these" is the one that sent them.
         */
        val listenerId: String,
    ) : DrainResult

    /** The batch filled up and more is due now. Schedule another run immediately. */
    data class MoreWorkDue(val remaining: Int, val listenerId: String) : DrainResult

    /**
     * Could not get on with it - offline, no session, the server is unwell. Nothing
     * was lost; WorkManager should back off and try again.
     */
    data class RetryLater(val reason: String) : DrainResult

    /**
     * An identity handoff owns the sync path right now.
     *
     * Not a failure and not a pause: it is a short exclusive section - drain, retire,
     * switch, adopt - during which no drain may touch a remote row, because the
     * identity those rows belong to is mid-change. **No outbox row was read.**
     *
     * The handoff schedules the follow-up when it finishes or rolls back, so this
     * needs no retry of its own; retrying would only contend for a lease it cannot
     * have.
     */
    data object HandoffInProgress : DrainResult

    /**
     * Cloud sync is paused by a deliberate sign-out.
     *
     * Distinct from [RetryLater] because the right response is the opposite one. A
     * retry is for something that will fix itself; this will not, until the listener
     * signs in, and a worker that retried it would wake the device on a backoff
     * schedule forever to do nothing. **No outbox row was read or written** - the
     * check happens before the batch is touched.
     */
    data object Paused : DrainResult

    /**
     * A permanent account deletion is unresolved on this install.
     *
     * Closer to [Paused] than to [HandoffInProgress]: there is nothing to wait for and
     * nothing to retry. Either the account is gone and these rows will never be
     * delivered by anybody, or the outcome is not yet known and delivering would be
     * pushing into an account somebody has asked to destroy. A worker that retried
     * this on a backoff would wake the device forever to do nothing.
     *
     * **No outbox row was read or written** - the check happens before the count.
     *
     * It is distinct from [Paused] because the two are not the same situation and a
     * log that conflated them would send the next reader looking for a sign-out that
     * never happened.
     */
    data object DeletionInProgress : DrainResult
}
