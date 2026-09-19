package com.example.musicplayerapp.scrobble

/**
 * Decides, inside the real playback lifecycle, when the track on air has been
 * *heard* long enough to become a Last.fm scrobble candidate (G6b P4).
 *
 * Pure: no Media3, no Android, no clock of its own. The service reports three
 * things - the player started or stopped actually playing, a poll answered, a
 * scheduled check came due - each with a monotonic elapsed time in milliseconds,
 * and every call returns how long until the next check is worth making, or null.
 * The service owns the timer; this class owns the rules. Main thread only.
 *
 * ## Listened time
 *
 * Only actual playing time counts: the intervals during which `Player.isPlaying`
 * was true. Buffering, pause, stop, reconnect and focus suppression are all
 * "not playing" there, so none of them accrue. Wall-clock `now - started_at` is
 * never listened time - the listener may have joined halfway through.
 *
 * Each valid, fresh observation maps the occurrence's authoritative air time onto
 * the elapsed clock: `windowStart = now + (started_at - server_time) s`, and the
 * window is exactly `duration` long. Listened time is the overlap of the playing
 * intervals with that window. So:
 *
 *  - playing time before the first poll still counts, but only inside the window
 *    (joining midway credits nothing before Play, because nothing was playing);
 *  - credit can never run past `ends_at` - a frozen feed, a failed poll, or a
 *    check that fires long after the last successful poll all see the same fixed
 *    window, and once it is exhausted nothing more accrues;
 *  - credit can never exceed `duration`.
 *
 * No lag correction (owner decision D4): what the listener hears can trail the
 * planned timeline by the stream and player buffers. That error is systematic
 * rather than symmetric, and is accepted for v1 rather than corrected by an
 * unmeasured constant. `SCROBBLE_OCCURRENCE` logs how far into the track each
 * occurrence was first seen, so a live pass can measure it.
 *
 * ## Occurrences
 *
 * An occurrence is [OccurrenceId] = stream + `started_at`. The same `started_at`
 * with a different TrackKey is a metadata correction of the same airing: it
 * updates the candidate if nothing has been emitted yet, and is only logged if
 * something has. A new `started_at` is a new airing, even of the same track. An
 * older `started_at` than the one being tracked is out of order and ignored.
 *
 * ## At most one candidate per occurrence, for the lifetime of the app process
 *
 * Whether an occurrence has been emitted is not this tracker's to keep: it lives
 * in [emissions], which the service passes as the process-wide
 * [ScrobbleEmissions.process], so it survives the service - and this tracker -
 * being destroyed and recreated. A candidate is emitted only for an occurrence
 * [ScrobbleEmissions.hasEmitted] does not know, however late or out of order old
 * metadata reappears. The record also survives station switches and unlinking.
 *
 * Lifetimes: emitted dedupe - the process; partial listen - this tracker (one
 * service instance); dedupe across process death - P5's persistent queue. Nothing
 * in P4 persists.
 *
 * ## Gating
 *
 * [isEnabled] is read at every event and once more immediately before emitting.
 * While it is false nothing accumulates, nothing is emitted, nothing is logged,
 * and any partial listen is discarded. When it turns true, listening starts from
 * that event - never retroactively from before the link was observed.
 *
 * ## Station switch
 *
 * Switching station discards the partial listen (owner decision D1). Coming back
 * to the same airing starts a new partial listen from zero; the emitted mark still
 * forbids a second candidate if the first was already emitted.
 *
 * ## An occurrence opening (G6b P6b)
 *
 * [onOccurrenceOpened] is told when tracking starts following a new airing - the
 * moment Now Playing is for. Only from inside the existing open: so only for a
 * valid, fresh observation with a real TrackKey, only while tracking is active,
 * and additionally only while the player is really playing and the airing's
 * authoritative window has not already ended. Repeated polls, pause and resume,
 * buffering and reconnects never reopen an airing, so never call it again. A
 * station switch back to the same airing does reopen it - suppressing that repeat
 * is the Now Playing sender's job, which dedupes by account and occurrence. The
 * callback observes; nothing it does feeds back into listening or eligibility.
 */
class ScrobbleTracker(
    private val isEnabled: () -> Boolean,
    private val sink: ScrobbleCandidateSink,
    private val emissions: ScrobbleEmissions,
    private val log: ScrobbleLog = ScrobbleLog.NONE,
    private val onOccurrenceOpened: (OpenedOccurrence) -> Unit = {},
) {

    private class Occurrence(
        val id: OccurrenceId,
        var trackKey: String,
        var artist: String,
        var title: String,
        var durationSec: Long,
        val windowStartMs: Long,
        var emitted: Boolean,
    ) {
        val windowEndMs: Long get() = windowStartMs + durationSec * 1000L
    }

    // What the player is doing. Recorded even while inactive - it is not listening
    // data, it is what lets activation start an interval at the right moment.
    private var playerPlaying = false
    private var playerStream: String? = null

    // Listening state. Cleared whenever tracking is inactive.
    private var active = false
    private var stream: String? = null
    private val intervals = ArrayList<LongArray>()
    private var playingSinceMs: Long? = null
    private var occurrence: Occurrence? = null
    private val feedState = HashMap<String, String>()

    /** `Player.isPlaying` changed, while [streamId] was the selected station. */
    fun onPlaying(isPlaying: Boolean, streamId: String, nowMs: Long): Long? {
        playerPlaying = isPlaying
        playerStream = streamId
        if (!sync(nowMs)) return null
        if (streamId != stream) switchTo(streamId, nowMs)
        if (isPlaying) {
            if (playingSinceMs == null) playingSinceMs = nowMs
        } else {
            closePlaying(nowMs)
        }
        return evaluate(nowMs)
    }

    /** The listener chose [streamId]. A different station discards the partial listen. */
    fun onStreamSelected(streamId: String, nowMs: Long): Long? {
        playerStream = streamId
        if (!sync(nowMs)) return null
        if (streamId != stream) switchTo(streamId, nowMs)
        return evaluate(nowMs)
    }

    /** A poll answered for [FeedObservation.streamId]. */
    fun onObservation(observation: FeedObservation, nowMs: Long): Long? {
        if (!sync(nowMs)) return null
        // An answer parsed before a station switch describes the old station.
        if (observation.streamId != stream) return evaluate(nowMs)

        if (!observation.isValid) {
            noteFeed(observation, "invalid")
            return evaluate(nowMs)
        }
        if (!observation.isFresh) {
            noteFeed(observation, "stale")
            return evaluate(nowMs)
        }
        noteFeed(observation, "fresh")

        val startedAt = observation.startedAt!!
        val current = occurrence
        when {
            current == null || startedAt > current.id.startedAt -> open(observation, nowMs)
            startedAt == current.id.startedAt -> correct(current, observation)
            else -> log.event(
                "SCROBBLE_OUT_OF_ORDER",
                "stream" to observation.streamId,
                "startedAt" to startedAt,
                "trackingStartedAt" to current.id.startedAt,
            )
        }
        return evaluate(nowMs)
    }

    /** A check scheduled from an earlier return value came due. */
    fun onTick(nowMs: Long): Long? {
        if (!sync(nowMs)) return null
        return evaluate(nowMs)
    }

    /** The service is going away. The emitted marks go with it; P4 persists nothing. */
    fun release() {
        clearListening()
        active = false
    }

    // ---------------------------------------------------------------- internals

    /** Reads the gate. Returns whether tracking is active after this event. */
    private fun sync(nowMs: Long): Boolean {
        if (!isEnabled()) {
            if (active) {
                clearListening()
                active = false
            }
            return false
        }
        if (!active) {
            active = true
            stream = playerStream
            // From this event on, never before it.
            playingSinceMs = if (playerPlaying) nowMs else null
        }
        return true
    }

    private fun clearListening() {
        intervals.clear()
        playingSinceMs = null
        occurrence = null
        stream = null
        feedState.clear()
    }

    private fun switchTo(streamId: String, nowMs: Long) {
        occurrence?.let { logClosed(it, nowMs, "stream_switch") }
        intervals.clear()
        occurrence = null
        stream = streamId
        playingSinceMs = if (playerPlaying) nowMs else null
    }

    private fun closePlaying(nowMs: Long) {
        val since = playingSinceMs ?: return
        if (nowMs > since) {
            intervals += longArrayOf(since, nowMs)
            if (intervals.size > MAX_INTERVALS) intervals.removeAt(0)
        }
        playingSinceMs = null
    }

    private fun open(observation: FeedObservation, nowMs: Long) {
        // Anything the current occurrence earned up to now is settled first.
        occurrence?.let {
            checkEmit(it, nowMs)
            if (!active) return
            logClosed(it, nowMs, "next")
        }

        val startedAt = observation.startedAt!!
        val serverTime = observation.serverTime!!
        val streamId = observation.streamId
        val windowStart = nowMs + (startedAt - serverTime) * 1000L
        intervals.removeAll { it[1] <= windowStart }

        val next = Occurrence(
            id = OccurrenceId(streamId, startedAt),
            trackKey = observation.trackKey!!,
            artist = observation.artist!!,
            title = observation.title!!,
            durationSec = observation.durationSec!!,
            windowStartMs = windowStart,
            emitted = emissions.hasEmitted(OccurrenceId(streamId, startedAt)),
        )
        occurrence = next
        log.event(
            "SCROBBLE_OCCURRENCE",
            "stream" to streamId,
            "startedAt" to startedAt,
            "durationSec" to next.durationSec,
            "key" to next.trackKey.take(8),
            "firstSeenAfterSec" to (serverTime - startedAt),
            "alreadyEmitted" to next.emitted,
        )

        // Now Playing: really playing, and the airing not already over.
        if (playerPlaying && nowMs < next.windowEndMs) {
            val opened = OpenedOccurrence(next.id, next.trackKey, next.artist, next.title, next.durationSec)
            try {
                onOccurrenceOpened(opened)
            } catch (e: Exception) {
                // Whatever the listener does, the tracker's own state is already
                // settled and must stay so.
                log.event("SCROBBLE_OPEN_HOOK_FAILED", "error" to e.javaClass.simpleName)
            }
        }
    }

    private fun correct(current: Occurrence, observation: FeedObservation) {
        val trackKey = observation.trackKey!!
        val duration = observation.durationSec!!
        val changed = trackKey != current.trackKey ||
            observation.artist != current.artist ||
            observation.title != current.title ||
            duration != current.durationSec
        if (!changed) return

        if (current.emitted) {
            // One candidate per airing: the one already out stands, unretracted.
            log.event(
                "SCROBBLE_CORRECTION_AFTER_EMIT",
                "stream" to current.id.streamId,
                "startedAt" to current.id.startedAt,
                "key" to trackKey.take(8),
            )
            return
        }
        current.trackKey = trackKey
        current.artist = observation.artist!!
        current.title = observation.title!!
        current.durationSec = duration
        log.event(
            "SCROBBLE_CORRECTION",
            "stream" to current.id.streamId,
            "startedAt" to current.id.startedAt,
            "key" to trackKey.take(8),
            "durationSec" to duration,
        )
    }

    /** Emits if due; returns how long until the next check is worth making. */
    private fun evaluate(nowMs: Long): Long? {
        val current = occurrence ?: return null
        checkEmit(current, nowMs)
        if (!active) return null
        return nextCheckDelay(current, nowMs)
    }

    private fun checkEmit(current: Occurrence, nowMs: Long) {
        if (current.emitted) return
        val threshold = ScrobbleEligibility.thresholdMs(current.durationSec) ?: return
        val listened = listenedMs(current, nowMs)
        if (listened < threshold) return
        // The account may have been unlinked since this event began.
        if (!isEnabled()) {
            clearListening()
            active = false
            return
        }
        val streamId = current.id.streamId
        if (emissions.hasEmitted(current.id)) {
            current.emitted = true
            return
        }
        current.emitted = true
        emissions.markEmitted(current.id)
        log.event(
            "SCROBBLE_ELIGIBLE",
            "stream" to streamId,
            "startedAt" to current.id.startedAt,
            "durationSec" to current.durationSec,
            "key" to current.trackKey.take(8),
            "listenedMs" to listened,
            "thresholdMs" to threshold,
        )
        sink.onEligible(
            ScrobbleCandidate(
                occurrenceId = current.id,
                trackKey = current.trackKey,
                artist = current.artist,
                title = current.title,
                startedAt = current.id.startedAt,
                durationSec = current.durationSec,
            )
        )
    }

    private fun listenedMs(current: Occurrence, nowMs: Long): Long {
        var total = 0L
        for (interval in intervals) total += overlap(interval[0], interval[1], current)
        playingSinceMs?.let { total += overlap(it, nowMs, current) }
        return total
    }

    private fun overlap(fromMs: Long, toMs: Long, current: Occurrence): Long =
        (minOf(toMs, current.windowEndMs) - maxOf(fromMs, current.windowStartMs)).coerceAtLeast(0L)

    private fun nextCheckDelay(current: Occurrence, nowMs: Long): Long? {
        if (current.emitted || playingSinceMs == null) return null
        val threshold = ScrobbleEligibility.thresholdMs(current.durationSec) ?: return null
        val remaining = threshold - listenedMs(current, nowMs)
        // Credit accrues one-for-one from the later of now and the window's start,
        // and stops at its end: if that is not enough, no check can help.
        val dueAt = maxOf(nowMs, current.windowStartMs) + remaining
        if (dueAt > current.windowEndMs) return null
        return (dueAt - nowMs).coerceAtLeast(0L)
    }

    private fun logClosed(current: Occurrence, nowMs: Long, reason: String) {
        log.event(
            "SCROBBLE_CLOSED",
            "stream" to current.id.streamId,
            "startedAt" to current.id.startedAt,
            "listenedMs" to listenedMs(current, nowMs),
            "emitted" to current.emitted,
            "reason" to reason,
        )
    }

    private fun noteFeed(observation: FeedObservation, state: String) {
        if (feedState.put(observation.streamId, state) == state) return
        log.event(
            "SCROBBLE_FEED",
            "stream" to observation.streamId,
            "state" to state,
            "overrunSec" to observation.serverTime?.let { now -> observation.endsAt?.let { now - it } },
        )
    }

    private companion object {
        /** Far more pause/resume cycles than one track can hold. */
        const val MAX_INTERVALS = 256
    }
}
