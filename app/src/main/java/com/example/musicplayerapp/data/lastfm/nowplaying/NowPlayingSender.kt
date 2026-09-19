package com.example.musicplayerapp.data.lastfm.nowplaying

import android.content.Context
import com.example.musicplayerapp.data.lastfm.LastfmApi
import com.example.musicplayerapp.data.lastfm.LastfmAuth
import com.example.musicplayerapp.data.lastfm.LastfmBackend
import com.example.musicplayerapp.data.lastfm.LastfmRequestFactory
import com.example.musicplayerapp.data.lastfm.LastfmResponses
import com.example.musicplayerapp.data.lastfm.LastfmResult
import com.example.musicplayerapp.data.lastfm.LastfmStoredSession
import com.example.musicplayerapp.data.lastfm.LastfmTransportResult
import com.example.musicplayerapp.data.lastfm.PrefsLastfmSessionStore
import com.example.musicplayerapp.scrobble.OpenedOccurrence
import com.example.musicplayerapp.scrobble.ScrobbleLog
import com.example.musicplayerapp.service.PlaybackLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Tells Last.fm what is playing (G6b P6b): `track.updateNowPlaying`, once per airing
 * per account, fire-and-forget.
 *
 * Last.fm: Now Playing "should be sent as soon as a user starts listening", and
 * "Now Playing requests that fail should not be retried". So: no queue, no
 * WorkManager, no retry. The attempt is recorded in [attempts] **before** the
 * request goes, so neither a failure nor a recreated service can send it twice.
 *
 * Sends artist, track and duration with the auth fields - no `chosenByUser` (the
 * method has none), no album, no Last.fm `streamId`.
 *
 * Nothing is sent unless the write gate is open, a build is configured, and an
 * account is linked with a session key. Error 9 invalidates exactly the session the
 * request used - compare-and-clear, so a late answer cannot clear a newer session -
 * and the scrobble queue is never touched. Everything else is logged and dropped.
 *
 * Logs: `NOW_PLAYING_SEND`, `_RESULT`, `_SKIPPED` - stream, `started_at`, an
 * 8-character TrackKey prefix, outcome, numeric Last.fm code, exception class.
 * Never artist, title, username, key, signature or body.
 */
class NowPlayingSender(
    private val session: () -> LastfmStoredSession,
    private val requests: () -> LastfmRequestFactory?,
    private val api: () -> LastfmApi,
    private val writesEnabled: () -> Boolean,
    private val invalidateSession: suspend (username: String, sessionKeyUsed: String) -> Unit,
    private val attempts: NowPlayingAttempts,
    private val scope: CoroutineScope,
    private val log: ScrobbleLog = ScrobbleLog.NONE,
) {

    /** The tracker's `onOccurrenceOpened`. Returns the request's job, or null if none was started. */
    fun onOccurrenceOpened(opened: OpenedOccurrence): Job? {
        val fields = fields(opened)
        if (!writesEnabled()) return skipped(fields, "writes_disabled")
        val stored = session()
        val username = stored.username
        val key = stored.sessionKey
        if (username.isNullOrEmpty() || key.isNullOrEmpty()) return skipped(fields, "not_linked")
        val factory = requests() ?: return skipped(fields, "not_configured")
        if (!attempts.tryMark(username, opened.occurrenceId)) return skipped(fields, "already_attempted")

        val request = factory.updateNowPlaying(
            artist = opened.artist,
            track = opened.title,
            sessionKey = key,
            durationSec = opened.durationSec.toInt(),
        ) ?: return skipped(fields, "not_configured")

        log.event("NOW_PLAYING_SEND", *fields)
        return scope.launch {
            val outcome = try {
                when (val sent = api().send(request)) {
                    is LastfmTransportResult.Unreachable -> "unreachable:${sent.kind}"
                    is LastfmTransportResult.Body -> when (val parsed = LastfmResponses.nowPlaying(sent.text)) {
                        is LastfmResult.Ok -> "ok"
                        is LastfmResult.Malformed -> "malformed"
                        is LastfmResult.Failure -> {
                            if (parsed.code == 9) invalidateSession(username, key)
                            "error:${parsed.code}"
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "failed:${e.javaClass.simpleName}"
            }
            log.event("NOW_PLAYING_RESULT", *fields, "outcome" to outcome)
        }
    }

    private fun skipped(fields: Array<Pair<String, Any?>>, reason: String): Job? {
        log.event("NOW_PLAYING_SKIPPED", *fields, "reason" to reason)
        return null
    }

    private fun fields(opened: OpenedOccurrence): Array<Pair<String, Any?>> = arrayOf(
        "stream" to opened.occurrenceId.streamId,
        "startedAt" to opened.occurrenceId.startedAt,
        "key" to opened.trackKey.take(8),
    )

    companion object {

        @Volatile
        private var process: NowPlayingSender? = null

        /**
         * The process's one sender, on the transport and factory [LastfmBackend]
         * provides - so a test runner's offline transport applies, and the write
         * gate is read live on every call.
         */
        fun forContext(context: Context): NowPlayingSender = process ?: synchronized(this) {
            process ?: run {
                val app = context.applicationContext
                val sessions = PrefsLastfmSessionStore(app)
                NowPlayingSender(
                    session = { sessions.read() },
                    requests = { LastfmBackend.requests() },
                    api = { LastfmBackend.api(app) },
                    writesEnabled = { LastfmBackend.writesEnabled },
                    invalidateSession = { u, k -> LastfmAuth.forContext(app).invalidateSession(u, k) },
                    attempts = NowPlayingAttempts.process,
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                    log = { name, fields -> PlaybackLog.event(name, *fields) },
                ).also { process = it }
            }
        }
    }
}
