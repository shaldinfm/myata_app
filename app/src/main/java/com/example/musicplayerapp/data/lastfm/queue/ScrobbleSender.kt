package com.example.musicplayerapp.data.lastfm.queue

import android.content.Context
import com.example.musicplayerapp.data.lastfm.IgnoredScrobble
import com.example.musicplayerapp.data.lastfm.LastfmApi
import com.example.musicplayerapp.data.lastfm.LastfmAuth
import com.example.musicplayerapp.data.lastfm.LastfmBackend
import com.example.musicplayerapp.data.lastfm.LastfmRequestFactory
import com.example.musicplayerapp.data.lastfm.LastfmResponses
import com.example.musicplayerapp.data.lastfm.LastfmResult
import com.example.musicplayerapp.data.lastfm.LastfmScrobble
import com.example.musicplayerapp.data.lastfm.LastfmScrobbleOutcome
import com.example.musicplayerapp.data.lastfm.LastfmStoredSession
import com.example.musicplayerapp.data.lastfm.LastfmTransportResult
import com.example.musicplayerapp.data.lastfm.PrefsLastfmSessionStore
import com.example.musicplayerapp.scrobble.ScrobbleLog
import com.example.musicplayerapp.service.PlaybackLog
import kotlin.random.Random
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Sends queued scrobbles to Last.fm (G6b P6a). The one path that ever does.
 *
 * P6a ships this **inert**: nothing in production calls [drain] or schedules the
 * worker that does. P6b wires the triggers, after a separate go-ahead for live writes.
 *
 * ## One drain at a time, in order
 *
 * [drain] runs under one process-wide [Mutex] - every trigger ends up here, so no
 * row is ever in two requests at once. It takes the linked account's **active**
 * queue oldest first and sends only its due prefix: if the oldest active row is
 * backing off, nothing newer goes either, and the drain reports when to wake. A
 * quarantined row is outside the active queue and holds back nothing.
 *
 * ## Only the linked account, and never after a disconnect starts
 *
 * Only rows of the account linked right now are read, and the session is read again
 * immediately before each request. A disconnect never waits for this lock (it takes
 * the queue's own, not this one): a request already in flight may finish after it,
 * but no new one starts, and nothing done with its answer can bring a row back - it
 * only ever deletes or updates rows by key, which are no-ops on purged rows.
 *
 * ## Answers
 *
 * See [ScrobblePolicy]. In short: 11/16/no answer/unmappable answer back the batch
 * off together (so order holds); 9 invalidates exactly the session that failed and
 * stops, keeping every row; any other error stops the sender until the process
 * restarts, keeping every row. A batch's answer is used per scrobble only when its
 * shape matches the request position by position, timestamps included - never by
 * artist or title, which Last.fm may have corrected.
 *
 * ## Logging
 *
 * `SCROBBLE_SEND`, `_SEND_RESULT`, `_SEND_ERROR`, `_SEND_UNREACHABLE`,
 * `_SEND_UNMAPPED`, `_SEND_BLOCKED`: counts, codes, stream ids, unix seconds and
 * exception class names. Never the session key, signature, request body, artist,
 * title or username.
 */
class ScrobbleSender(
    private val dao: () -> ScrobbleQueueDao,
    private val session: () -> LastfmStoredSession,
    private val requests: () -> LastfmRequestFactory?,
    private val api: () -> LastfmApi,
    private val invalidateSession: suspend (username: String, sessionKeyUsed: String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val jitter: () -> Double = { Random.nextDouble(-1.0, 1.0) },
    private val log: ScrobbleLog = ScrobbleLog.NONE,
) {

    sealed class Result {
        /** Nothing active to send. */
        data object Idle : Result()

        /** No account linked with a session key. Nothing read, nothing sent. */
        data object NotLinked : Result()

        /** This build has no Last.fm credentials. */
        data object NotConfigured : Result()

        /** The oldest active row is not due before [atMs]. */
        data class WaitUntil(val atMs: Long) : Result()

        /** Error 9: the session was invalidated; rows kept for re-authentication. */
        data object Reauth : Result()

        /** A non-retryable error earlier in this process; nothing is sent until restart. */
        data object Blocked : Result()
    }

    private val lock = Mutex()

    /**
     * Set by a non-retryable global error. In memory only: a fresh process tries
     * again, once, and a new app version may well have fixed the cause.
     */
    @Volatile
    var blockedUntilProcessRestart = false
        private set

    /** Sends every due scrobble of the linked account, batch by batch, in order. */
    suspend fun drain(): Result = lock.withLock {
        // Each request either deletes its rows or moves them into the future, so the
        // loop always makes progress; the bound is a guard against a bug, not the logic.
        repeat(MAX_BATCHES_PER_DRAIN) { step()?.let { return@withLock it } }
        Result.Idle
    }

    /** One request. Null means "sent a batch, look again"; anything else ends the drain. */
    private suspend fun step(): Result? {
        if (blockedUntilProcessRestart) return Result.Blocked

        val linked = linkedNow() ?: return Result.NotLinked
        val factory = requests() ?: return Result.NotConfigured

        val head = dao().activeHead(linked.username, ScrobblePolicy.QUARANTINE, ScrobblePolicy.MAX_BATCH)
        if (head.isEmpty()) return Result.Idle
        val now = clock()
        val batch = ScrobblePolicy.duePrefix(head, now)
        if (batch.isEmpty()) return Result.WaitUntil(head.first().nextAttemptAt)

        val request = factory.scrobble(
            batch.map { LastfmScrobble(it.artist, it.title, it.startedAt, durationSec = it.durationSec.toInt()) },
            linked.sessionKey,
        ) ?: return Result.NotConfigured

        // The last moment to notice a disconnect or a different account: no new
        // request starts for an account that is no longer the linked one.
        if (linkedNow() != linked) return Result.NotLinked

        log.event("SCROBBLE_SEND", "batch" to batch.size, "oldestStartedAt" to batch.first().startedAt)
        return when (val sent = api().send(request)) {
            is LastfmTransportResult.Unreachable -> {
                log.event("SCROBBLE_SEND_UNREACHABLE", "kind" to sent.kind)
                backOff(batch, now)
                null
            }
            is LastfmTransportResult.Body -> when (val parsed = LastfmResponses.scrobbles(sent.text)) {
                is LastfmResult.Ok -> {
                    if (mapsSafely(batch, parsed.value)) {
                        applyVerdicts(batch, parsed.value, now)
                    } else {
                        log.event("SCROBBLE_SEND_UNMAPPED", "batch" to batch.size, "verdicts" to parsed.value.verdicts.size)
                        backOff(batch, now)
                    }
                    null
                }
                is LastfmResult.Malformed -> {
                    log.event("SCROBBLE_SEND_UNMAPPED", "batch" to batch.size, "verdicts" to null)
                    backOff(batch, now)
                    null
                }
                is LastfmResult.Failure -> onGlobalError(parsed.code, batch, now, linked)
            }
        }
    }

    private suspend fun onGlobalError(code: Int, batch: List<ScrobbleQueueEntry>, now: Long, linked: Linked): Result? {
        log.event("SCROBBLE_SEND_ERROR", "code" to code, "batch" to batch.size)
        return when (ScrobblePolicy.forGlobalError(code)) {
            ScrobblePolicy.Global.Retry -> {
                backOff(batch, now)
                null
            }
            ScrobblePolicy.Global.Reauthenticate -> {
                invalidateSession(linked.username, linked.sessionKey)
                Result.Reauth
            }
            ScrobblePolicy.Global.StopUntilRestart -> {
                blockedUntilProcessRestart = true
                log.event("SCROBBLE_SEND_BLOCKED", "code" to code)
                Result.Blocked
            }
        }
    }

    /**
     * Whether [outcome] can be mapped onto [batch] one to one: the same number of
     * verdicts, each at the same position carrying the timestamp that was sent there
     * (Last.fm does not correct timestamps), and Last.fm's own totals - when given -
     * consistent with the verdicts. Otherwise nothing in the batch is touched.
     */
    private fun mapsSafely(batch: List<ScrobbleQueueEntry>, outcome: LastfmScrobbleOutcome): Boolean {
        val verdicts = outcome.verdicts
        if (verdicts.size != batch.size) return false
        if (verdicts.indices.any { verdicts[it].timestamp != batch[it].startedAt }) return false
        val accepted = verdicts.count { it.ignored == IgnoredScrobble.ACCEPTED }
        val missing = verdicts.count { it.ignored == IgnoredScrobble.MISSING }
        if (outcome.accepted + outcome.ignored != batch.size) return false
        if (outcome.accepted < accepted || outcome.accepted > accepted + missing) return false
        return true
    }

    private suspend fun applyVerdicts(batch: List<ScrobbleQueueEntry>, outcome: LastfmScrobbleOutcome, now: Long) {
        val dao = dao()
        val codes = HashMap<Int, Int>()
        batch.forEachIndexed { i, row ->
            val verdict = outcome.verdicts[i].ignored
            codes.merge(verdict.code, 1, Int::plus)
            when (val action = ScrobblePolicy.forItem(verdict, row.attempts, now)) {
                ScrobblePolicy.Item.Delete -> dao.deleteOccurrence(row.streamId, row.startedAt)
                is ScrobblePolicy.Item.Defer -> dao.recordAttempt(row.streamId, row.startedAt, action.untilMs)
                ScrobblePolicy.Item.Quarantine ->
                    dao.recordAttempt(row.streamId, row.startedAt, ScrobblePolicy.QUARANTINE)
                ScrobblePolicy.Item.Backoff -> dao.recordAttempt(
                    row.streamId, row.startedAt,
                    now + ScrobblePolicy.backoffMs(row.attempts + 1, jitter()),
                )
            }
        }
        log.event("SCROBBLE_SEND_RESULT", "batch" to batch.size, "codes" to codes.toSortedMap().toString())
    }

    /** A request-level failure: the whole batch waits together, so order holds. */
    private suspend fun backOff(batch: List<ScrobbleQueueEntry>, now: Long) {
        val until = now + ScrobblePolicy.backoffMs(batch.first().attempts + 1, jitter())
        val dao = dao()
        for (row in batch) dao.recordAttempt(row.streamId, row.startedAt, until)
    }

    private data class Linked(val username: String, val sessionKey: String)

    private fun linkedNow(): Linked? {
        val s = session()
        val key = s.sessionKey
        val name = s.username
        return if (key.isNullOrEmpty() || name.isNullOrEmpty()) null else Linked(name, key)
    }

    companion object {

        /** Far more batches than one drain should ever need; a guard against a bug looping. */
        const val MAX_BATCHES_PER_DRAIN = 100

        @Volatile
        private var process: ScrobbleSender? = null

        /**
         * The process's one sender. Reads the queue database, the stored session,
         * and the transport and request factory through [LastfmBackend] - so a test
         * runner's offline transport applies, and nothing sends until
         * `MyataApplication` has armed the real one.
         */
        fun forContext(context: Context): ScrobbleSender = process ?: synchronized(this) {
            process ?: run {
                val app = context.applicationContext
                val sessions = PrefsLastfmSessionStore(app)
                ScrobbleSender(
                    dao = { LastfmQueueDatabase.getDatabase(app).scrobbleQueueDao() },
                    session = { sessions.read() },
                    requests = { LastfmBackend.requests() },
                    api = { LastfmBackend.api(app) },
                    invalidateSession = { username, key -> LastfmAuth.forContext(app).invalidateSession(username, key) },
                    log = { name, fields -> PlaybackLog.event(name, *fields) },
                ).also { process = it }
            }
        }
    }
}
