package com.example.musicplayerapp.data.supabase

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.musicplayerapp.data.AppDatabase
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * When the outbox gets drained, and the proof that a row cannot be stranded.
 *
 * There are exactly two ways a row could be written and never sent, and a design
 * that closes only one of them is the easy mistake here.
 *
 * ## Race A - the row commits, then the process dies before anything is scheduled
 *
 * `ReactionDao` commits the reaction and its outbox row in one transaction, and the
 * enqueue happens *after* that transaction returns. The window between the two is
 * small but real, and nothing inside a Room transaction can close it: WorkManager
 * keeps its own database, so an enqueue cannot join the commit that would make it
 * atomic. Enqueueing *before* the write is worse - it schedules work for a reaction
 * that may never exist.
 *
 * So the window is accepted and covered from the other side: **[onAppStart]**. Every
 * cold start asks the outbox whether anything is pending and schedules a drain if it
 * is. A row that lost its wake-up to a kill is picked up the next time the app is
 * opened, which for a radio app is the next time it is used at all.
 *
 * ## Race B - a reaction commits while the worker is already RUNNING
 *
 * This is the one that a plain `enqueueUniqueWork(..., KEEP, ...)` gets wrong, and
 * quietly. KEEP drops the new request whenever an unfinished one exists, so a Like
 * tapped while the worker is mid-batch enqueues nothing, and the running worker has
 * already read its batch and will never see the new row. The reaction then waits for
 * the next app start - a lost wake-up that looks exactly like everything working.
 *
 * Nor does having the worker re-check the queue before returning close it. The check
 * and the return are not atomic with respect to KEEP: a row committed after the last
 * check but before the worker is finished is still dropped, because the worker is
 * still RUNNING at the instant KEEP is evaluated. The window shrinks; it does not go.
 *
 * The policies, and what each does with a request that arrives mid-run:
 *
 * | policy | mid-run request | verdict |
 * |---|---|---|
 * | `KEEP` | dropped | loses race B |
 * | `REPLACE` | cancels the running worker, starts again | a burst of taps can cancel every run before it finishes - starvation |
 * | `APPEND` | queued behind the current run | closes B, but a failed or cancelled run blocks the chain **forever** |
 * | `APPEND_OR_REPLACE` | queued behind the current run; replaces the chain if it is cancelled or failed | closes B with no permanent block |
 *
 * **[ExistingWorkPolicy.APPEND_OR_REPLACE] is the choice.** It is `APPEND` with the
 * one property that makes it safe to rely on: a chain that ends in failure or
 * cancellation is replaced rather than blocking everything behind it.
 *
 * Two things make the append cheap rather than a pile-up. A run with an empty outbox
 * costs one `COUNT(*)` and no network and no identity, so a redundant appended run
 * is close to free; and the worker never returns `failure()`, so the chain has
 * nothing to poison it - a row the server refuses is parked in the database, not
 * turned into a failed work request.
 */
object ReactionSyncScheduler {

    private const val TAG = "ReactionSync"

    /**
     * One name for all of it. Every enqueue in this file targets this chain, which
     * is what makes "one drain at a time, in order" true.
     */
    const val UNIQUE_WORK = "reaction-outbox-sync"

    /**
     * The policy that closes race B, named rather than inlined so a test can assert
     * on the decision itself. Changing this line is the way this design gets broken,
     * and it would break it silently.
     */
    val POLICY: ExistingWorkPolicy = ExistingWorkPolicy.APPEND_OR_REPLACE

    /**
     * The timer chain, kept deliberately separate from [UNIQUE_WORK].
     *
     * A parked row's wake-up is a *delayed* request. Appending a delayed request to
     * the main chain would put every reaction tapped afterwards behind it - a fresh
     * Like could wait the full backoff, up to a day, which is precisely the
     * behaviour the main chain exists to avoid. So the timer gets its own name and
     * the main chain stays immediate.
     */
    const val RETRY_WORK = "reaction-outbox-retry"

    /**
     * The timer chain's policy. There is at most one meaningful "next wake-up", so a
     * newly computed one always supersedes whatever was pending: an earlier one
     * because it is sooner, a later one because the row that wanted the earlier one
     * has since gone. Appending would build a queue of stale timers instead.
     */
    val RETRY_POLICY: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE

    /**
     * A reaction has just been committed locally. Schedule a drain.
     *
     * Safe to call on any thread and safe to call far more often than necessary,
     * which matters because it is called from the tap path: it does no I/O of its
     * own beyond handing WorkManager a request.
     */
    fun onReactionCommitted(context: Context) {
        if (!SupabaseConfig.isConfigured) return
        enqueue(context, "reaction")
    }

    /**
     * Startup recovery, and the other half of race A.
     *
     * Asks the outbox whether anything survived a previous run before scheduling
     * anything at all. That question is one indexed `COUNT(*)` on a table that is
     * almost always empty, off the main thread, and it is the reason a listener who
     * has never reacted never causes a work request - let alone an anonymous
     * identity, which is two more gates further in.
     */
    fun onAppStart(context: Context) {
        if (!SupabaseConfig.isConfigured) return

        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val pending = AppDatabase.getDatabase(app).reactionOutboxDao().count()
                if (pending > 0) {
                    Log.d(TAG, "$pending reaction(s) pending from a previous run")
                    enqueue(app, "startup")
                }
            }.onFailure { Log.w(TAG, "could not check the outbox at startup: ${it.message}") }
        }
    }

    /**
     * The worker asking for another run because its batch filled up.
     *
     * Appending to the chain from inside the chain is exactly what
     * [ExistingWorkPolicy.APPEND_OR_REPLACE] is for: the follow-up is queued behind
     * the run that requested it.
     */
    internal fun onBatchFull(context: Context) = enqueue(context, "batch full")

    /**
     * Schedules the run that a parked row is waiting for.
     *
     * This is the mechanism that turns `next_attempt_at` into guaranteed execution.
     * `APPEND_OR_REPLACE` closes the two commit races but it is **not a timer**: once
     * a chain has finished, nothing schedules anything, and a row parked by a 4xx for
     * an hour - or a day - would otherwise sit there until the listener happened to
     * react again or restart the app. WorkManager persists a delayed request in its
     * own database and reschedules it across process death and reboot, so the timer
     * survives everything except the app being uninstalled.
     *
     * Overlap with the main chain is harmless and worth stating: both remote writes
     * are idempotent and the outbox row is deleted only after both succeed, so the
     * worst case of two runs meeting is a duplicate round trip that changes nothing.
     *
     * @param at wall-clock millis, from [com.example.musicplayerapp.data.ReactionOutboxDao.earliestAttemptAt].
     *   Already in the past is fine and means "as soon as the network allows".
     */
    internal fun scheduleWakeUp(context: Context, at: Long) {
        if (!SupabaseConfig.isConfigured) return

        val delay = (at - System.currentTimeMillis()).coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<ReactionSyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(RETRY_WORK)
            .build()

        runCatching {
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(RETRY_WORK, RETRY_POLICY, request)
            Log.d(TAG, "retry wake-up scheduled in ${delay / 1000}s")
        }.onFailure {
            Log.w(TAG, "could not schedule the retry wake-up: ${it.message}")
        }
    }

    private fun enqueue(context: Context, why: String) {
        val request = OneTimeWorkRequestBuilder<ReactionSyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    // The only constraint. Not "unmetered" and not "charging":
                    // a reaction is a few hundred bytes and a listener who liked
                    // something an hour ago should not have to find wifi for it.
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(UNIQUE_WORK)
            .build()

        runCatching {
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(UNIQUE_WORK, POLICY, request)
            Log.d(TAG, "drain scheduled ($why)")
        }.onFailure {
            // WorkManager not initialised - a unit-test process, or an app being
            // torn down. Losing the schedule is survivable: onAppStart will find the
            // rows next launch. Crashing the tap path would not be.
            Log.w(TAG, "could not schedule a drain ($why): ${it.message}")
        }
    }
}
