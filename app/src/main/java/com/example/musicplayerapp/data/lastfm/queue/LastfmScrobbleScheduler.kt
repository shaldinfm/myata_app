package com.example.musicplayerapp.data.lastfm.queue

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.musicplayerapp.data.lastfm.LastfmConfig
import com.example.musicplayerapp.data.lastfm.LastfmLink
import com.example.musicplayerapp.data.lastfm.PrefsLastfmSessionStore
import com.example.musicplayerapp.service.PlaybackLog
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * When queued scrobbles are sent (G6b P6a, wired in P6b) - event-driven one-time
 * work, never a periodic poll. Both chains run [LastfmScrobbleWorker], and so the
 * same [ScrobbleSender.drain]. Both need a connected network; WorkManager holds them
 * through offline spells, process death and reboots.
 *
 *  - [DRAIN_WORK]: "there may be something to send". Requested after a row is first
 *    queued, after a Last.fm link or re-link, and at app start while linked.
 *  - [RETRY_WORK]: the single timer for the oldest row in backoff, replaced by each
 *    new schedule rather than stacked.
 *
 * ## Coalescing, without losing a wake-up
 *
 * Every trigger fires **after** its row is committed. [requestDrainNow] then looks
 * at the drain chain:
 *
 *  - a run that has **not started** (ENQUEUED, or BLOCKED behind a running one)
 *    exists - skip: that run has not read the queue yet, and when it does it will
 *    see the committed row;
 *  - otherwise - nothing pending, or a run already RUNNING that may have read the
 *    queue before the row landed - enqueue with [DRAIN_POLICY], which appends a
 *    follow-up behind a running run (so a row committed after its last read is
 *    still sent) and starts a fresh chain behind a finished one.
 *
 * The check and the enqueue are one step under [requestLock], and the enqueue is
 * awaited before the lock is released, so concurrent requests see each other's
 * work. Offline for hours, the chain stays one pending run, not one per track. A
 * redundant run is cheap: nothing due is one query and no network. The worker
 * always succeeds, so no finished run poisons the chain.
 */
object LastfmScrobbleScheduler {

    const val DRAIN_WORK = "lastfm-scrobble-drain"
    val DRAIN_POLICY: ExistingWorkPolicy = ExistingWorkPolicy.APPEND_OR_REPLACE

    const val RETRY_WORK = "lastfm-scrobble-retry"
    val RETRY_POLICY: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE

    private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requestLock = Mutex()

    /**
     * The coalescing rule, pure: enqueue unless a drain run exists that has not
     * started yet - such a run will read the queue after the row was committed.
     */
    fun shouldEnqueue(states: Collection<WorkInfo.State>): Boolean =
        states.none { it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.BLOCKED }

    /**
     * Ask for a drain, off the caller's thread; returns at once. Never throws: the row
     * that prompted it is already committed, and the next trigger or app start will
     * ask again.
     */
    fun requestDrain(context: Context) {
        val app = context.applicationContext
        scope.launch {
            try {
                requestDrainNow(app)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PlaybackLog.event("SCROBBLE_DRAIN_REQUEST_FAILED", "error" to e.javaClass.simpleName)
            }
        }
    }

    /** [requestDrain]'s body. Returns whether a run was enqueued. For tests, and for [requestDrain]. */
    suspend fun requestDrainNow(context: Context): Boolean = requestLock.withLock {
        val workManager = WorkManager.getInstance(context)
        val states = workManager.getWorkInfosForUniqueWork(DRAIN_WORK).get().map { it.state }
        if (!shouldEnqueue(states)) return@withLock false
        val request = OneTimeWorkRequestBuilder<LastfmScrobbleWorker>().setConstraints(connected).build()
        workManager.enqueueUniqueWork(DRAIN_WORK, DRAIN_POLICY, request).result.get()
        true
    }

    /**
     * App start: if this build has Last.fm and an account is linked, send whatever the
     * queue holds. Only schedules - the read and the check happen off the main thread.
     */
    fun onAppStart(context: Context) {
        val app = context.applicationContext
        scope.launch {
            try {
                if (!LastfmConfig.isConfigured) return@launch
                val link = LastfmLink.of(PrefsLastfmSessionStore(app).read(), System.currentTimeMillis())
                if (link is LastfmLink.Linked) requestDrainNow(app)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PlaybackLog.event("SCROBBLE_DRAIN_REQUEST_FAILED", "error" to e.javaClass.simpleName)
            }
        }
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
 * A closed write gate returns `WritesDisabled`, which sets no timer.
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
