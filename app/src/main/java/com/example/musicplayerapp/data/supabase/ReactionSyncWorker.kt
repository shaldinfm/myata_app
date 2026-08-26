package com.example.musicplayerapp.data.supabase

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.musicplayerapp.data.AppDatabase

/**
 * The scheduled half of the drain: assemble the pieces, run one batch, translate the
 * outcome into a WorkManager verdict.
 *
 * Everything interesting is in [ReactionSyncEngine]; this exists so that what runs
 * under WorkManager is small enough to read in one go.
 *
 * ## It never returns failure
 *
 * Only `success()` and `retry()`. That is a requirement of the scheduling design,
 * not a style choice: the chain is built with
 * [androidx.work.ExistingWorkPolicy.APPEND_OR_REPLACE], and dependents of a **failed**
 * work request are cancelled. A worker that failed on a row the server will never
 * accept would take every later reaction down with it.
 *
 * So a row that cannot be delivered is parked in the database - attempts counted,
 * `next_attempt_at` pushed out - and the run still succeeds. Failure lives in the
 * outbox, where it can be inspected and where it does not propagate, rather than in
 * WorkManager's graph, where it would.
 */
class ReactionSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // A build with no project configured never drains, and that is correct: the
        // outbox simply accumulates, exactly as it does offline. Returning success
        // rather than retry keeps such a build from holding a permanently retrying
        // work request for a backend it does not have.
        if (!SupabaseConfig.isConfigured) return Result.success()

        // The durable half of the handoff exclusion, checked before a single row is
        // read. A worker that was already enqueued when the handoff began - or that
        // WorkManager restarts after a process death mid-handoff - would otherwise
        // sail past the scheduler's gate, which only stops *new* enqueues. The
        // in-memory lease cannot help here: it died with the process.
        if (IdentityStore.handoffInProgress(applicationContext)) {
            Log.d(TAG, "identity handoff in progress; not draining")
            return Result.success()
        }

        val database = AppDatabase.getDatabase(applicationContext)

        val engine = ReactionSyncEngine(
            reactions = database.reactionDao(),
            outbox = database.reactionOutboxDao(),
            // Both boundaries come from ReactionSyncBackend rather than being
            // constructed here. In a shipped build that is the same two objects this
            // line used to name; under instrumentation it is what keeps the app's own
            // startup drain off the live project. See ReactionSyncBackend.
            api = ReactionSyncBackend.api(applicationContext),
            // The identity boundary, reached only once the engine has established
            // that there is something to own.
            identity = { ReactionSyncBackend.identity(applicationContext) },
        )

        return when (val result = runCatching { engine.drain() }.getOrElse { failed ->
            // An unexpected throw is a bug, not a verdict. Retry rather than
            // succeed: succeeding would silently drop the whole run.
            Log.e(TAG, "drain threw", failed)
            return Result.retry()
        }) {
            is DrainResult.Idle -> Result.success()

            is DrainResult.Waiting -> {
                // Nothing to send yet. Leave a timer behind so the parked rows are
                // not waiting on a reaction that may never come.
                ReactionSyncScheduler.scheduleWakeUp(applicationContext, result.until)
                Result.success()
            }

            is DrainResult.Drained -> {
                Log.d(TAG, "delivered ${result.delivered} reaction(s)")
                // The one place a successful sync is recorded, and only when rows
                // actually went out. See LastSyncStore for why Idle is not a sync.
                if (result.delivered > 0) LastSyncStore.recordSuccess(applicationContext)
                result.nextAttemptAt?.let {
                    ReactionSyncScheduler.scheduleWakeUp(applicationContext, it)
                }
                Result.success()
            }

            is DrainResult.MoreWorkDue -> {
                Log.d(TAG, "batch full, ${result.remaining} still due")
                // A full batch only happens after a full batch was delivered.
                LastSyncStore.recordSuccess(applicationContext)
                ReactionSyncScheduler.onBatchFull(applicationContext)
                Result.success()
            }

            is DrainResult.RetryLater -> {
                Log.d(TAG, "drain deferred: ${result.reason}")
                Result.retry()
            }

            is DrainResult.HandoffInProgress -> {
                // A handoff took the lease between the check above and here. Success
                // with no reschedule: the handoff owns the follow-up.
                Log.d(TAG, "identity handoff holds the sync lease; not draining")
                Result.success()
            }

            is DrainResult.Paused -> {
                // Success, and deliberately no reschedule. Retrying a signed-out
                // install is a wake-up that can never accomplish anything, on a
                // backoff schedule, until the listener signs in - and the sign-in is
                // what will schedule the drain. The rows are untouched.
                Log.d(TAG, "cloud sync paused: signed out")
                Result.success()
            }
        }
    }

    private companion object {
        const val TAG = "ReactionSync"
    }
}
