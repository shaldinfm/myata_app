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
 * 1  outbox and Collection, in one Room transaction, under ReactionWriteGate
 * 2  signOutLocal()            <- stops here if it fails
 * 3  LastSyncStore.forget(uid)
 * 4  IdentityStore.forgetDeletedAccount()
 * ```
 *
 * **Room before identity.** Step 4 writes [IdentityState.None], and `None` is the one
 * state [ListenerSession.identity] may mint an anonymous uid from. Reversing 1 and 4
 * would open a window in which a drain finds leftover outbox rows, creates a brand-new
 * listener, and uploads the deleted account's reactions into it. `SyncLease` - held by
 * both callers across this whole routine - closes that window; doing the rows first
 * closes it again, and the second lock is the one that survives a process death.
 *
 * **The gate wraps local work only.** [ReactionWriteGate.withDeliveryStep] is held
 * across the Room transaction and nothing else. No network call happens under it, so a
 * listener tapping Like never waits on one - the rule the gate exists to keep.
 *
 * ## Why a failed sign-out stops everything
 *
 * If [EmailAuthApi.signOutLocal] cannot clear the session, the device is still holding
 * credentials for an account that no longer exists. Carrying on would write `None`
 * over an install that can still present a token, and the marker that says cleanup is
 * owed would be gone with it - so nothing would ever come back to finish the job.
 * Stopping leaves `CONFIRMED` durable and every later start retries from the top,
 * which is safe because steps 1 and 3 are idempotent.
 *
 * ## Idempotence, step by step
 *
 * A process death between any two steps is repaired by running the whole thing again:
 * deleting from an empty table is zero rows, signing out with no session succeeds,
 * removing absent preference keys is a no-op, and step 4 rewrites the same values.
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

        // 1. Local rows, in one transaction so a death cannot empty one table and
        //    leave the other. Under the write gate, which a tap also takes, so this
        //    cannot interleave with a reaction being committed.
        ReactionWriteGate.withDeliveryStep {
            database.withTransaction {
                val events = database.reactionOutboxDao().clearAll()
                val reactions = database.reactionDao().clearAll()
                Log.d(TAG, "account deletion: cleared $events pending event(s), $reactions reaction(s)")
            }
        }

        // 2. The session. LOCAL scope, like every other sign-out in this app.
        if (!EmailAuthBackend.api(app).signOutLocal()) {
            Log.w(TAG, "account deletion: the session did not clear; cleanup stays owed")
            return Outcome.Deferred("the session did not clear")
        }

        // 3. What this account had synchronised. Named keys, so another account on the
        //    same install keeps its own history.
        LastSyncStore.forget(app, uid)

        // 4. Last, and the only place the marker may be cleared. One commit: identity,
        //    legacy marker and deletion record cannot disagree afterwards.
        IdentityStore.forgetDeletedAccount(app)
        Log.d(TAG, "account deletion: local cleanup complete")
        return Outcome.Completed
    }
}
