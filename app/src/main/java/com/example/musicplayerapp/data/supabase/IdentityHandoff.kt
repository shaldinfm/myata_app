package com.example.musicplayerapp.data.supabase

import android.content.Context
import android.util.Log
import com.example.musicplayerapp.data.ReactionDao
import com.example.musicplayerapp.data.ReactionOutboxDao
import com.example.musicplayerapp.data.ReactionWriteGate

/**
 * Moving a device from one listener identity to another without losing, duplicating
 * or inventing anything.
 *
 * The case it exists for: an install has been reacting as anonymous **X**, and the
 * listener now registers or signs in as **Y**. Supabase will not turn an anonymous
 * user into a password account in place, so X does not become Y - X is retired and Y
 * adopts what the device currently thinks.
 *
 * ## The rules it is built to keep
 *
 * 1. X's outbox is drained before anything switches - those rows are X's history;
 * 2. if it cannot drain, the switch is abandoned and **nothing is written**;
 * 3. X's current remote state is retired while X's session is still live, because
 *    RLS makes it unreachable afterwards;
 * 4. X's `reaction_events` are never touched - history stays with whoever made it;
 * 5. the local Room Collection is never read destructively and never cleared;
 * 6. adoption writes **current state only**. No synthetic events, ever;
 * 7. X and Y are never both counted as current state - X is retired before Y exists.
 *
 * ## Ownership boundary
 *
 * Reactions committed **before** the boundary are X's and must drain as X. Reactions
 * committed **after** it belong to whichever identity this ends as - Y on success, X
 * on rollback. They are real transitions with real `occurred_at` values, so
 * attributing them to the terminating identity records what happened rather than
 * inventing it.
 *
 * The boundary is a fact rather than a moment in time because [ReactionWriteGate]
 * holds the final emptiness check and the `PREPARED` commit in one critical section.
 * A tap lands strictly on one side.
 *
 * ## Two locks, two different jobs
 *
 * [SyncLease] excludes drains for the whole section, including one that was already
 * running when this began - a durable flag cannot do that, because such a drain has
 * already passed every flag check. The persisted [HandoffStage] survives process
 * death - a mutex cannot do that. Neither alone is sufficient and they are not
 * alternatives.
 *
 * [ReactionWriteGate] is held across two local operations and no network call, so a
 * tap never waits on the network. Everything slow happens under [SyncLease], which
 * taps never contend for.
 */
object IdentityHandoff {

    private const val TAG = "SupabaseAuth"

    /** How many times the cutover will re-drain before giving up on a busy outbox. */
    const val MAX_ATTEMPTS = 3

    /**
     * Authenticates or creates the destination identity.
     *
     * Injected rather than called directly because G-A4b1 ships the handoff without
     * any auth: the tests drive it with fakes, and G-A4b2 supplies the real
     * email/password call. It is invoked **inside** the exclusive section, after X
     * has been retired.
     */
    fun interface DestinationIdentity {
        /** @return the destination uid, or null if it could not be established. */
        suspend fun authenticate(): String?
    }

    sealed interface Result {
        /** The device is now [uid] and the local state has been adopted into it. */
        data class Switched(val uid: String) : Result

        /**
         * Nothing was written and nothing remote changed. The install is exactly as
         * it was, still owned by the source identity.
         */
        data class Aborted(val why: String) : Result

        /**
         * The switch failed after X had been retired, so the local state was adopted
         * back into X. Recoverable and self-consistent: the listener is still X and
         * X's remote state has been rebuilt from Room.
         */
        data class RolledBack(val uid: String, val why: String) : Result
    }

    /**
     * Runs the whole handoff from [from] to whatever [destination] establishes.
     *
     * Ordering is the design; see the KDoc above and the stage-by-stage comments.
     */
    suspend fun run(
        context: Context,
        from: String,
        reactions: ReactionDao,
        outbox: ReactionOutboxDao,
        api: ReactionSyncApi,
        drain: suspend () -> Boolean,
        destination: DestinationIdentity,
    ): Result {
        repeat(MAX_ATTEMPTS) { attempt ->
            // 1. Drain by the ordinary path, holding nothing. The engine takes the
            //    lease itself, so this cannot be called from inside the exclusive
            //    section below - a non-reentrant mutex would deadlock against itself.
            if (!drain()) return Result.Aborted("outbox could not be drained")

            val outcome = SyncLease.withExclusive {
                // 2. The lease is ours. Any drain that was in flight has finished
                //    and released; no new one can start until this block returns.

                // 3-6. The ownership cutover. Two local operations, no network.
                val prepared = ReactionWriteGate.withOwnershipCutover {
                    if (outbox.count() != 0) {
                        false
                    } else {
                        IdentityStore.markHandoffPrepared(context, from)
                        true
                    }
                }
                // 7. The gate is released here, before anything slow.

                if (!prepared) null else finish(context, from, reactions, api, destination)
            }

            if (outcome != null) return outcome
            Log.d(TAG, "outbox refilled during the cutover; draining again (attempt ${attempt + 1})")
        }
        return Result.Aborted("the outbox kept refilling")
    }

    /**
     * Everything after `PREPARED`, still holding the lease.
     *
     * Split out only so [run]'s retry loop reads as the loop it is; this is not a
     * separate phase and must never be called without the lease.
     */
    private suspend fun finish(
        context: Context,
        from: String,
        reactions: ReactionDao,
        api: ReactionSyncApi,
        destination: DestinationIdentity,
    ): Result {
        // Retire X while X's session is still the live one.
        val retired = api.retireAllCurrentState(from)
        if (retired !is SyncOutcome.Success) {
            // Nothing irreversible happened - the delete either failed or partially
            // applied - but PREPARED is on disk, so rebuild X from Room and clear.
            return rollback(context, from, reactions, api, "retire failed: $retired")
        }

        IdentityStore.markHandoffSwitchPending(context, from)

        val to = destination.authenticate()
            ?: return rollback(context, from, reactions, api, "destination not established")

        // The identity and the stage in one commit, so they cannot disagree after a
        // death between two separate writes.
        IdentityStore.markHandoffSwitched(context, from, to)

        adopt(context, to, reactions, api)
        IdentityStore.clearHandoff(context)
        Log.d(TAG, "handoff complete")
        return Result.Switched(to)
    }

    /**
     * Puts the device back where it started: X's remote state rebuilt from Room.
     *
     * The same adoption routine as the success path, pointed at the source instead of
     * the destination - which is why the rollback path is exercised every time the
     * ordinary path is.
     */
    private suspend fun rollback(
        context: Context,
        from: String,
        reactions: ReactionDao,
        api: ReactionSyncApi,
        why: String,
    ): Result {
        Log.w(TAG, "handoff rolling back: $why")
        adopt(context, from, reactions, api)
        IdentityStore.clearHandoff(context)
        return Result.RolledBack(from, why)
    }

    /**
     * Writes the device's current reactions into [uid] as **current state only**.
     *
     * Idempotent, because every row goes through the same `updated_at`-guarded upsert
     * the ordinary drain uses. That is what lets a crash part-way through be repaired
     * by running the whole thing again rather than by recording how far it got.
     *
     * All three states are adopted, NEUTRAL included: since migration 0002 a
     * withdrawal is a row with its own `updated_at`, and dropping those would hand Y
     * a state that cannot lose a last-writer-wins comparison it should lose.
     *
     * **No event is written.** `reaction_events` is history, and none of this is
     * something the listener did just now.
     */
    private suspend fun adopt(
        context: Context,
        uid: String,
        reactions: ReactionDao,
        api: ReactionSyncApi,
    ) {
        val rows = reactions.allReactions()
        var written = 0
        for (row in rows) {
            if (api.reconcileCurrentState(row.trackKey, row, uid) is SyncOutcome.Success) written++
        }
        Log.d(TAG, "adopted $written/${rows.size} reaction(s) into the destination")
    }

    /**
     * Resolves a handoff that a process death interrupted.
     *
     * Called at startup with whatever session actually restored. The stage on disk
     * says what may have happened remotely; the session says which identity this
     * device currently *is*; together they are enough, except in one case that is
     * deliberately left alone rather than guessed at.
     *
     * Runs under [SyncLease] for the same reason the forward path does: it performs
     * remote retirement and adoption, and a drain must not be interleaved with either.
     */
    suspend fun recover(
        context: Context,
        sessionUid: String?,
        reactions: ReactionDao,
        api: ReactionSyncApi,
    ): Result? {
        val record = IdentityStore.handoff(context) ?: return null

        return SyncLease.withExclusive {
            when {
                // The switch took: a session exists and it is not the source. Finish
                // what was interrupted rather than undoing it.
                record.stage == HandoffStage.SWITCHED && sessionUid != null -> {
                    adopt(context, record.to ?: sessionUid, reactions, api)
                    IdentityStore.clearHandoff(context)
                    Result.Switched(record.to ?: sessionUid)
                }

                sessionUid != null && sessionUid != record.from -> {
                    // PREPARED or SWITCH_PENDING, but the session is already someone
                    // else - the switch succeeded and the process died before the
                    // durable commit. Promote, then adopt.
                    IdentityStore.markHandoffSwitched(context, record.from, sessionUid)
                    adopt(context, sessionUid, reactions, api)
                    IdentityStore.clearHandoff(context)
                    Result.Switched(sessionUid)
                }

                sessionUid == record.from -> {
                    // Still the source. Covers every crash point around the delete -
                    // before it, during it, and after it but before any later stage -
                    // because retiring is idempotent and the repair is the same:
                    // rebuild X from Room.
                    rollback(context, record.from, reactions, api, "interrupted at ${record.stage}")
                }

                else -> {
                    // No session at all. Cannot tell whether a destination was
                    // created, and guessing either way is worse than waiting: the
                    // record stays and the next restore decides. Local Room is intact
                    // throughout, so nothing is at risk meanwhile.
                    Log.w(TAG, "handoff at ${record.stage} with no session; deferring recovery")
                    null
                }
            }
        }
    }
}
