package com.example.musicplayerapp.data.lastfm.queue

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.musicplayerapp.service.PlaybackLog
import java.util.concurrent.TimeUnit

/**
 * When queued scrobbles are sent (G6b P6a) - event-driven one-time work, never a
 * periodic poll. Both chains run [LastfmScrobbleWorker], and so the same
 * [ScrobbleSender.drain].
 *
 *  - [DRAIN_WORK]: "there may be something to send". Under [DRAIN_POLICY] each new
 *    request is **appended** to the existing unique-work chain - not collapsed - so
 *    a row queued mid-drain is never lost to a drain that already read its batch.
 *    The redundant runs this can leave are cheap: a worker with nothing due exits
 *    after one query, with no network. And the worker always returns success, so a
 *    finished run never poisons the chain behind it. This is the same intentional
 *    pattern `ReactionSyncScheduler` uses, and its KDoc sets out why the other
 *    policies are worse.
 *  - [RETRY_WORK]: the single timer for the oldest row in backoff, replaced by each
 *    new schedule rather than stacked.
 *
 * Both need a connected network; WorkManager holds them through offline spells,
 * process death and reboots.
 *
 * **P6a: nothing in production calls [requestDrain].** The sender ships inert; P6b
 * wires the triggers - app start, a successful link, a newly queued row - after a
 * separate go-ahead for live writes.
 */
object LastfmScrobbleScheduler {

    const val DRAIN_WORK = "lastfm-scrobble-drain"
    val DRAIN_POLICY: ExistingWorkPolicy = ExistingWorkPolicy.APPEND_OR_REPLACE

    const val RETRY_WORK = "lastfm-scrobble-retry"
    val RETRY_POLICY: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE

    private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Send whatever is due, as soon as there is a network. */
    fun requestDrain(context: Context) {
        val request = OneTimeWorkRequestBuilder<LastfmScrobbleWorker>().setConstraints(connected).build()
        WorkManager.getInstance(context).enqueueUniqueWork(DRAIN_WORK, DRAIN_POLICY, request)
    }

    /** Wake the sender again in [delayMs], for the oldest row that is backing off. */
    fun scheduleRetry(context: Context, delayMs: Long) {
        val request = OneTimeWorkRequestBuilder<LastfmScrobbleWorker>()
            .setConstraints(connected)
            .setInitialDelay(delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(RETRY_WORK, RETRY_POLICY, request)
    }
}

/**
 * Runs one [ScrobbleSender.drain] and, if the oldest row is backing off, sets the
 * timer for it. Always succeeds: retries are the queue's `next_attempt_at`, not
 * WorkManager's backoff, so there is one retry policy and it is [ScrobblePolicy]'s.
 */
class LastfmScrobbleWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val outcome = ScrobbleSender.forContext(applicationContext).drain()
        if (outcome is ScrobbleSender.Result.WaitUntil) {
            LastfmScrobbleScheduler.scheduleRetry(applicationContext, outcome.atMs - System.currentTimeMillis())
        }
        PlaybackLog.event("SCROBBLE_WORK", "outcome" to outcome.javaClass.simpleName)
        return Result.success()
    }
}
