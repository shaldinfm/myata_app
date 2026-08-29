package com.example.musicplayerapp.data.supabase

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.example.musicplayerapp.data.AppDatabase
import com.example.musicplayerapp.data.ReactionWriteGate

/**
 * What a device does to itself once the server has confirmed the account is gone.
 *
 * ## It may only run at `CONFIRMED`
 *
 * Every step here is destructive and none of it is reversible, so it is gated on the
 * one stage that means *the server said so*. A caller must have committed
 * [DeletionStage.CONFIRMED] before calling; the two callers - [AccountDeletion] and
 * [IdentityReconciler] - both do, and neither reaches this from `REQUESTED`.
 *
 * ## The order is the design
 *
 * ```
 * 1  signOutLocal()                     <- NETWORK. Outside the gate. Stops here if it fails.
 * 2  ReactionWriteGate.withDeliveryStep {
 * 2a     one Room transaction: clear reaction_outbox, then track_reaction
 * 2b     LastSyncStore.forget(uid)
 * 2c     IdentityStore.forgetDeletedAccount()   <- LAST
 *    }
 * ```
 *
 * **The sign-out is a network call, which is why it goes first and outside the gate.**
 * `signOut(SignOutScope.LOCAL)` is local in *scope* - it invalidates this device's
 * session and nobody else's - but not in the network sense: supabase-kt issues an HTTP
 * `POST /logout?scope=local` whenever a session exists, and skips it only when there is
 * none. Read off the resolved 3.2.6 artifact, where the single branch in
 * `AuthImpl.signOut` is on session presence and the scope is merely a request
 * parameter. Holding the gate across that would make a listener tapping Like wait on a
 * round trip, up to the client's read timeout - the one thing the gate exists to
 * prevent.
 *
 * **Everything after it is ONE gate section, and that section is the cutover.** An
 * earlier version took the gate for the purge alone and released it before the identity
 * was cleared, so a tap landing in between committed a `track_reaction` and an outbox
 * row *after* the purge - and those rows survived the deletion, belonging to an account
 * that no longer existed. One section from the purge through
 * [IdentityStore.forgetDeletedAccount] closes it: a tap either lands before the gate is
 * taken, and the purge removes it, or it waits and lands after this install is already
 * a guest, where it is an ordinary new guest-side action rather than a survivor of a
 * deleted account. There is no third possibility, because the mutex admits no
 * interleaving.
 *
 * **Room before identity, inside that section.** 2c writes [IdentityState.None], and
 * `None` is the one state [ListenerSession.identity] may mint an anonymous uid from.
 * Reversing 2a and 2c would let a drain find leftover outbox rows, create a brand-new
 * listener, and upload the deleted account's reactions into it. `SyncLease` - held by
 * both callers across this whole routine - closes that window against sync; the gate
 * closes it against taps, which `SyncLease` does not serialise at all.
 *
 * **No network call happens inside the gate.** 2a, 2b and 2c are Room and
 * `SharedPreferences` only.
 *
 * ## Why a failed sign-out stops everything
 *
 * If [EmailAuthApi.signOutLocal] cannot clear the session, the device is still holding
 * credentials for an account that no longer exists. Carrying on would write `None`
 * over an install that can still present a token, and the marker that says cleanup is
 * owed would be gone with it - so nothing would ever come back to finish the job.
 *
 * Stopping now happens **before anything local is touched**: no purge, no forgotten
 * timestamps, no identity change. That is a strictly better place to stop than the
 * previous order allowed, where the Collection had already been erased by the time the
 * sign-out was even attempted.
 *
 * ## Idempotence, step by step
 *
 * A process death anywhere is repaired by running the whole thing again: signing out
 * with no session succeeds without a request, deleting from an empty table is zero
 * rows, removing absent preference keys is a no-op, and 2c rewrites the same values.
 * There is no progress marker inside this routine, deliberately - one more durable
 * thing to get wrong, for a sequence that is cheap to repeat.
 */
internal object AccountDeletionCleanup {

    private const val TAG = "SupabaseAuth"

    /** How far the cleanup got. Nothing else in the app distinguishes these. */
    sealed interface Outcome {

        /** Everything is done. The install is a guest and owes nothing. */
        data object Completed : Outcome

        /**
         * The server deletion stands, and the device has not finished acting on it.
         *
         * `CONFIRMED` is still on disk and the install is still sync-dead. The next
         * reconciliation retries; no caller may report the deletion as complete.
         */
        data class Deferred(val why: String) : Outcome
    }

    /**
     * Runs the whole sequence for [uid].
     *
     * Must be called with [SyncLease] already held - both callers take it, and this
     * does not, because the mutex is not reentrant.
     */
    suspend fun run(context: Context, uid: String): Outcome {
        val app = context.applicationContext
        val database = AppDatabase.getDatabase(app)

        // 1. The session, first and OUTSIDE the gate, because this is a network call.
        //    Nothing local has been touched yet, so a failure here costs nothing: the
        //    rows, the timestamps and the CONFIRMED marker all stand, and a later start
        //    runs the whole routine again.
        if (!EmailAuthBackend.api(app).signOutLocal()) {
            Log.w(TAG, "account deletion: the session did not clear; cleanup stays owed")
            return Outcome.Deferred("the session did not clear")
        }

        // 2. The cutover. One gate section, no network inside it, and nothing may
        //    interleave: a tap is either purged by 2a or lands after 2c, by which time
        //    this install is already a guest.
        ReactionWriteGate.withDeliveryStep {
            // 2a. One transaction, so a death cannot empty one table and leave the other.
            database.withTransaction {
                val events = database.reactionOutboxDao().clearAll()
                val reactions = database.reactionDao().clearAll()
                Log.d(TAG, "account deletion: cleared $events pending event(s), $reactions reaction(s)")
            }

            // Test seam. Nothing in `src/main` sets it; a suite uses it to observe the
            // inside of the cutover, which is the only place the exclusion property is
            // visible - from outside, a tap that was correctly excluded and one that
            // interleaved leave identical final state.
            insideCutover?.invoke()

            // 2b. What this account had synchronised. Named keys, so another account on
            //     the same install keeps its own history.
            LastSyncStore.forget(app, uid)

            // 2c. Last, and the only place the marker may be cleared. One commit:
            //     identity, legacy marker and deletion record cannot disagree after it.
            IdentityStore.forgetDeletedAccount(app)
        }

        Log.d(TAG, "account deletion: local cleanup complete")
        return Outcome.Completed
    }

    /**
     * Invoked inside the cutover, after the purge and before the identity is cleared.
     *
     * The same kind of seam as `ReactionPullTrigger.clock` and `ReactionSyncEngine.now`,
     * and it exists because this property cannot be observed any other way: a tap that
     * was correctly excluded from the cutover and one that interleaved with it leave
     * **identical** final state, so only an observation taken from inside tells them
     * apart.
     *
     * Null in every shipped build; nothing in `src/main` assigns it.
     */
    @Volatile
    internal var insideCutover: (suspend () -> Unit)? = null
}
