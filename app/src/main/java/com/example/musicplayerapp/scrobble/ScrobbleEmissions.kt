package com.example.musicplayerapp.scrobble

/**
 * Which occurrences have already produced a [ScrobbleCandidate] - the one piece of
 * scrobble state that must outlive a [ScrobbleTracker].
 *
 * A tracker belongs to one `MediaPlayerService` instance, and the service can be
 * destroyed and recreated while the app process lives on (`stopSelf()` on the
 * `stop` action, or Media3 releasing an idle session). Kept in the tracker, this
 * record would die with it, and a long airing heard again after the recreation
 * could emit a second candidate for the same [OccurrenceId]. Kept here, in one
 * [process]-wide instance every tracker shares, it cannot.
 *
 * Lifetimes, precisely:
 *  - **emitted dedupe** (this class): the app process;
 *  - **partial listen** (in [ScrobbleTracker]): one service/tracker instance;
 *  - **persistent dedupe** across process death: not here - P5's queue.
 *
 * In memory only. Nothing is written to disk.
 *
 * ## Monotonic per-stream mark
 *
 * `started_at` strictly increases on a stream, so one number per stream is
 * enough: the highest `started_at` emitted. [hasEmitted] is true for that
 * occurrence **and every older one** on the same stream. That is what makes the
 * guarantee hold however late or out of order old metadata reappears, with no
 * eviction window to forget an occurrence. The price is conservative: an older
 * occurrence that was never emitted cannot emit once a newer one has.
 */
class ScrobbleEmissions {

    private val emittedThrough = HashMap<String, Long>()

    /** True if [id], or a later occurrence on the same stream, has been emitted. */
    @Synchronized
    fun hasEmitted(id: OccurrenceId): Boolean =
        id.startedAt <= (emittedThrough[id.streamId] ?: Long.MIN_VALUE)

    /** Records that [id] has been emitted. Never lowers a stream's mark. */
    @Synchronized
    fun markEmitted(id: OccurrenceId) {
        val current = emittedThrough[id.streamId]
        if (current == null || id.startedAt > current) emittedThrough[id.streamId] = id.startedAt
    }

    companion object {
        /**
         * The app process's one store, shared by every tracker the service creates.
         * Tests construct their own instead, so no state leaks between them.
         */
        val process = ScrobbleEmissions()
    }
}
