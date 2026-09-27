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
 * 6. adoption writes **current state only**, and only where the destination holds
 *    nothing for the track - an existing account reaction wins. No synthetic events;
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
        // Before the first drain and before anything is written. A handoff moves a
        // device from one identity to another and rebuilds remote state from Room; an
        // install whose account is being deleted must do neither. Running one here
        // would retire rows the deletion is about to remove anyway, and - on the
        // rollback path - write them straight back into an account that may already be
        // gone. Aborted, not deferred: nothing schedules a retry, because the deletion
        // is what decides what happens next.
        if (IdentityStore.deletionInFlight(context)) {
            return Result.Aborted("an account deletion is unresolved")
        }

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

        // The device Collection becomes Y's - the genuine anonymous -> account
        // transition. Were the tables ever another account's, that account's rows
        // would be parked for it instead, and nothing of them adopted.
        CollectionScope.enterAccountHoldingLease(context, to, adoptDevice = true)
        adopt(context, to, reactions, api, intoDestination = true)
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
        adopt(context, from, reactions, api, intoDestination = false)
        IdentityStore.clearHandoff(context)
        return Result.RolledBack(from, why)
    }

    /**
     * Writes the device's current reactions into [uid] as **current state only**,
     * and only for tracks [uid] holds nothing for.
     *
     * Insert-if-absent ([LocalOnlyUpload.publish]), never an overwrite. The device's
     * rows are pre-sign-in guest state, and an established account's existing reaction
     * wins over it; a track the account has never reacted to takes the device's
     * opinion. Idempotent: a crash part-way through is repaired by running the whole
     * thing again, which finds what it already inserted and skips it.
     *
     * All three states are offered, NEUTRAL included, so an account that has never
     * seen a track learns the device's withdrawal too.
     *
     * **No event is written.** `reaction_events` is history, and none of this is
     * something the listener did just now.
     *
     * ## Revisions
     *
     * Into the destination, `CollectionScope.enterAccountHoldingLease` has already
     * given the adopted device rows `CollectionScope.ADOPTED_BASELINE` ("the account
     * had no row") and left the account's own unparked rows their revisions; the next
     * pull records the real ones. On a rollback the rows stay the device's and the
     * source's revisions are void - its rows were deleted before the switch - so they
     * are cleared, as before.
     *
     * Nothing else about the local state is touched. The Collection survives a
     * handoff intact, which is the property this whole file exists to protect.
     */
    private suspend fun adopt(
        context: Context,
        uid: String,
        reactions: ReactionDao,
        api: ReactionSyncApi,
        intoDestination: Boolean,
    ) {
        // Into the destination, the scope transition has already given the adopted
        // device rows CollectionScope.ADOPTED_BASELINE and left the account's own
        // unparked rows their revisions; clearing them here would turn guest state into
        // "no baseline yet", which the initial restore rebases over the account's own
        // reactions. On a rollback the rows stay the device's and X's revisions are
        // void - X's rows were just deleted - so they are forgotten as before.
        if (!intoDestination) reactions.clearRemoteRevs()

        // Insert-if-absent, never an overwrite: an established account's existing
        // reaction wins over the device's pre-sign-in state. Retiring the source first
        // means a rollback into X finds nothing and rebuilds it whole.
        val rows = reactions.allReactions()
        val result = LocalOnlyUpload(reactions, api).publish(rows, uid)
        Log.d(TAG, "adopted ${result.inserted}/${rows.size} reaction(s); ${result.alreadyPresent} already held by the account")
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
                    CollectionScope.enterAccountHoldingLease(context, record.to ?: sessionUid, adoptDevice = true)
                    adopt(context, record.to ?: sessionUid, reactions, api, intoDestination = true)
                    IdentityStore.clearHandoff(context)
                    Result.Switched(record.to ?: sessionUid)
                }

                sessionUid != null && sessionUid != record.from -> {
                    // PREPARED or SWITCH_PENDING, but the session is already someone
                    // else - the switch succeeded and the process died before the
                    // durable commit. Promote, then adopt.
                    IdentityStore.markHandoffSwitched(context, record.from, sessionUid)
                    CollectionScope.enterAccountHoldingLease(context, sessionUid, adoptDevice = true)
                    adopt(context, sessionUid, reactions, api, intoDestination = true)
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
