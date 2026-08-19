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

        val database = AppDatabase.getDatabase(applicationContext)

        val engine = ReactionSyncEngine(
            reactions = database.reactionDao(),
            outbox = database.reactionOutboxDao(),
            api = SupabaseReactionSyncApi(applicationContext),
            // The identity boundary, reached only once the engine has established
            // that there is something to own.
            identity = { AnonymousSession.ensureAuthenticatedListener(applicationContext) },
        )

        return when (val result = runCatching { engine.drain() }.getOrElse { failed ->
            // An unexpected throw is a bug, not a verdict. Retry rather than
            // succeed: succeeding would silently drop the whole run.
            Log.e(TAG, "drain threw", failed)
            return Result.retry()
        }) {
            is DrainResult.Idle -> Result.success()

            is DrainResult.Drained -> {
                Log.d(TAG, "delivered ${result.delivered} reaction(s)")
                Result.success()
            }

            is DrainResult.MoreWorkDue -> {
                Log.d(TAG, "batch full, ${result.remaining} still due")
                ReactionSyncScheduler.onBatchFull(applicationContext)
                Result.success()
            }

            is DrainResult.RetryLater -> {
                Log.d(TAG, "drain deferred: ${result.reason}")
                Result.retry()
            }
        }
    }

    private companion object {
        const val TAG = "ReactionSync"
    }
}
