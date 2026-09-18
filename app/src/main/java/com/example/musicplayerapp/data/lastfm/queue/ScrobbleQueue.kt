package com.example.musicplayerapp.data.lastfm.queue

import android.content.Context
import com.example.musicplayerapp.data.lastfm.LastfmLink
import com.example.musicplayerapp.data.lastfm.PrefsLastfmSessionStore
import com.example.musicplayerapp.scrobble.ScrobbleCandidate
import com.example.musicplayerapp.scrobble.ScrobbleCandidateSink
import com.example.musicplayerapp.scrobble.ScrobbleLog
import com.example.musicplayerapp.service.PlaybackLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Removes an account's queued scrobbles. What explicit `Отключить` calls. */
fun interface ScrobbleQueuePurger {
    suspend fun purge(username: String)

    companion object {
        val NONE = ScrobbleQueuePurger { }
    }
}

/**
 * Where P4's scrobble candidates become durable rows (G6b P5). Sends nothing.
 *
 * ## Writing
 *
 * [sink] is what `MediaPlayerService` hands the tracker. At emission it reads the
 * linked account - on the main thread, right after the tracker re-checked the gate -
 * captures its username, and [enqueue]s. The insert runs on this queue's own
 * process-lifetime [scope], never the service's: destroying the service does not
 * cancel an insert already launched. Only process death in the milliseconds before
 * the commit can lose one, and that window is accepted.
 *
 * Every row is bound to that username. A duplicate occurrence is rejected by the
 * database's primary key, not by a check here. A database error is logged by
 * exception class and the candidate dropped; there is no local retry.
 *
 * ## Disconnect
 *
 * [purge] removes one account's rows. Writes and purges share one [Mutex], and a
 * write re-reads the linked account inside it: a candidate emitted just before an
 * explicit disconnect is either inserted before the purge runs - and purged by it -
 * or finds the account gone and is skipped. `LastfmAuth.disconnect` purges before
 * and after clearing the session, so nothing queued in between survives either.
 *
 * ## Logging
 *
 * `SCROBBLE_QUEUED`, `_QUEUE_DUPLICATE`, `_QUEUE_SKIPPED`, `_QUEUE_FAILED`,
 * `_QUEUE_PURGED` and `_QUEUE_PURGE_FAILED`: stream ids, unix seconds, an
 * 8-character TrackKey prefix, counts and exception class names only - never
 * artist, title, the username or anything from the session.
 */
class ScrobbleQueue(
    private val dao: () -> ScrobbleQueueDao,
    private val linkedUsername: () -> String?,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: ScrobbleLog = ScrobbleLog.NONE,
) : ScrobbleQueuePurger {

    sealed class Outcome {
        data object Queued : Outcome()
        data object Duplicate : Outcome()
        data class Skipped(val reason: String) : Outcome()
        data class Failed(val kind: String) : Outcome()
    }

    private val lock = Mutex()

    /** The sink the playback service hands P4's tracker. */
    fun sink(): ScrobbleCandidateSink = ScrobbleCandidateSink { candidate ->
        val username = linkedUsername()
        if (username == null) {
            // Unreachable while the tracker re-checks its gate just before emitting,
            // which reads the same session; kept so a gap can only drop, never misfile.
            log.event("SCROBBLE_QUEUE_SKIPPED", *fields(candidate), "reason" to "not_linked")
        } else {
            enqueue(candidate, username)
        }
    }

    /** Queues [candidate] for [username] off the caller's thread; returns at once. */
    fun enqueue(candidate: ScrobbleCandidate, username: String): Job =
        scope.launch { write(candidate, username) }

    /** The insert itself. Exposed for tests; production goes through [enqueue]. */
    suspend fun write(candidate: ScrobbleCandidate, username: String): Outcome = lock.withLock {
        val outcome = try {
            if (linkedUsername() != username) {
                Outcome.Skipped("account_changed")
            } else if (dao().insert(entryFor(candidate, username)) == -1L) {
                Outcome.Duplicate
            } else {
                Outcome.Queued
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Outcome.Failed(e.javaClass.simpleName)
        }
        when (outcome) {
            Outcome.Queued -> log.event("SCROBBLE_QUEUED", *fields(candidate))
            Outcome.Duplicate -> log.event("SCROBBLE_QUEUE_DUPLICATE", *fields(candidate))
            is Outcome.Skipped -> log.event("SCROBBLE_QUEUE_SKIPPED", *fields(candidate), "reason" to outcome.reason)
            is Outcome.Failed -> log.event("SCROBBLE_QUEUE_FAILED", *fields(candidate), "error" to outcome.kind)
        }
        outcome
    }

    /** Explicit disconnect: [username]'s rows, nobody else's. Never throws but for cancellation. */
    override suspend fun purge(username: String) {
        lock.withLock {
            try {
                val removed = dao().deleteForUsername(username)
                log.event("SCROBBLE_QUEUE_PURGED", "rows" to removed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.event("SCROBBLE_QUEUE_PURGE_FAILED", "error" to e.javaClass.simpleName)
            }
        }
    }

    private fun entryFor(candidate: ScrobbleCandidate, username: String) = ScrobbleQueueEntry(
        streamId = candidate.occurrenceId.streamId,
        startedAt = candidate.occurrenceId.startedAt,
        trackKey = candidate.trackKey,
        artist = candidate.artist,
        title = candidate.title,
        durationSec = candidate.durationSec,
        lastfmUsername = username,
        queuedAt = clock(),
    )

    private fun fields(candidate: ScrobbleCandidate): Array<Pair<String, Any?>> = arrayOf(
        "stream" to candidate.occurrenceId.streamId,
        "startedAt" to candidate.occurrenceId.startedAt,
        "key" to candidate.trackKey.take(8),
    )

    companion object {

        @Volatile
        private var process: ScrobbleQueue? = null

        /**
         * The process's one queue: the service's sink and `LastfmAuth`'s disconnect
         * share it, and with it the lock that orders their writes.
         */
        fun forContext(context: Context): ScrobbleQueue = process ?: synchronized(this) {
            process ?: run {
                val app = context.applicationContext
                val sessions = PrefsLastfmSessionStore(app)
                ScrobbleQueue(
                    dao = { LastfmQueueDatabase.getDatabase(app).scrobbleQueueDao() },
                    linkedUsername = {
                        (LastfmLink.of(sessions.read(), System.currentTimeMillis()) as? LastfmLink.Linked)?.username
                    },
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                    log = { name, fields -> PlaybackLog.event(name, *fields) },
                ).also { process = it }
            }
        }
    }
}
