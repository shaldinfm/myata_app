package com.example.musicplayerapp.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * What [ArtworkResolver] asks. [ArtworkRepository] is the one implementation;
 * the interface exists so the resolver's own behaviour - de-duplication, the
 * caches, the lanes, the circuit breaker - can be tested against a provider that
 * answers on command rather than over the network.
 */
interface ArtworkProvider {

    /** One lookup, no caching. */
    suspend fun fetchArtwork(artist: String, track: String): ArtworkRepository.ArtworkResult

    /** Pulls an image into the HTTP cache; failures are not the caller's problem. */
    suspend fun warmImage(url: String)
}

/** Which lane a lookup runs in. The track on screen does not queue behind a list. */
enum class ArtworkPriority {
    /** What the listener is hearing now: the PLAYER, the pill, the session. */
    CURRENT_TRACK,

    /** A row in История эфира or the Collection - many at once, none urgent. */
    BULK,
}

/**
 * The one place the app resolves artwork, for every surface (G5c).
 *
 * Before this there were three [ArtworkRepository] instances - one per ViewModel
 * and one in the playback service - each with a private map. The same track was
 * therefore looked up once per surface, the three pager pages asked for every
 * History row three times over, and nothing any of them learned was shared. This
 * owns that work instead: one instance, one cache, one lookup per track.
 *
 * Four things it does that a plain repository call could not:
 *
 *  - **De-duplicates in flight.** Ten consumers asking for one track while the
 *    provider is still answering produce one provider call and ten identical
 *    answers.
 *  - **Tells failure from absence.** A provider that could not be reached is a
 *    transient failure and is retried later; a provider that answered and had
 *    nothing is a confirmed no-match and is remembered for [noMatchTtlMs]. Before
 *    G5c both were cached as "no artwork" for the life of the process, so one bad
 *    minute at launch meant a session of plates.
 *  - **Bounds concurrency.** History can bind thirty rows at once; without a limit
 *    that was thirty sockets and thirty blocked `Dispatchers.IO` threads.
 *  - **Stops asking a dead provider.** After [failuresBeforeCircuitOpens]
 *    consecutive failures it answers from memory for [failureCooldownMs] instead
 *    of making every remaining row wait for its own timeout - which is the
 *    difference between one slow screen and a minute of them on a network where
 *    the provider is blocked.
 *
 * Nothing here decides *which* cover is right: that is [ArtworkMatcher]'s, and
 * G5b's result, source and confidence pass through untouched.
 */
class ArtworkResolver internal constructor(
    private val repository: ArtworkProvider,
    /**
     * The scope a shared lookup runs in.
     *
     * Deliberately not the caller's. A History row that scrolls away cancels its
     * own `await`, and the lookup it started carries on for whoever else is
     * waiting - and for the cache. A lookup owned by the first consumer would be
     * cancelled out from under the other nine.
     */
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val noMatchTtlMs: Long = NO_MATCH_TTL_MS,
    private val failureCooldownMs: Long = FAILURE_COOLDOWN_MS,
    private val partialTtlMs: Long = PARTIAL_TTL_MS,
    private val failuresBeforeCircuitOpens: Int = FAILURES_BEFORE_CIRCUIT_OPENS,
    bulkPermits: Int = BULK_PERMITS,
    currentTrackPermits: Int = CURRENT_TRACK_PERMITS,
) {

    private val bulkLane = Semaphore(bulkPermits)
    private val currentTrackLane = Semaphore(currentTrackPermits)

    private val cached = ConcurrentHashMap<String, Entry>()
    private val inFlight = ConcurrentHashMap<String, Deferred<ArtworkRepository.ArtworkResult>>()

    /** Consecutive failures across all tracks; a single success clears it. */
    private val consecutiveFailures = AtomicInteger(0)

    @Volatile
    private var circuitOpenUntil = 0L

    private class Entry(val result: ArtworkRepository.ArtworkResult, val at: Long)

    /** What the app has learned, for the measurements and the tests. */
    class Stats {
        val providerCalls = AtomicInteger(0)
        val cacheHits = AtomicInteger(0)
        val joinedInFlight = AtomicInteger(0)
        val shortCircuited = AtomicInteger(0)
        val concurrentNow = AtomicInteger(0)
        val concurrentPeak = AtomicInteger(0)

        internal fun enter() {
            val now = concurrentNow.incrementAndGet()
            concurrentPeak.updateAndGet { peak -> maxOf(peak, now) }
        }

        internal fun leave() = concurrentNow.decrementAndGet()

        fun snapshot(): String =
            "providerCalls=${providerCalls.get()} cacheHits=${cacheHits.get()} " +
                "joined=${joinedInFlight.get()} shortCircuited=${shortCircuited.get()} " +
                "peakConcurrent=${concurrentPeak.get()}"
    }

    val stats = Stats()

    /**
     * The artwork for one track, from cache when it is known and from the
     * provider when it is not.
     *
     * Never throws for a provider problem - a failure is an empty result the
     * caller draws its plate for, and one that will be tried again later.
     */
    suspend fun resolve(
        artist: String?,
        title: String?,
        priority: ArtworkPriority = ArtworkPriority.BULK,
    ): ArtworkRepository.ArtworkResult {
        val safeArtist = artist.orEmpty()
        val safeTitle = title.orEmpty()

        // The identity the rest of the app already agrees on (G5a): TrackKey v1
        // where the pair is a track, a normalised pair where it is not. Two
        // surfaces asking for the same track therefore ask with the same key.
        val key = NowPlayingArtwork.identityOf(safeArtist, safeTitle)

        valid(key)?.let {
            stats.cacheHits.incrementAndGet()
            return it
        }

        if (circuitIsOpen()) {
            stats.shortCircuited.incrementAndGet()
            return FAILED
        }

        var started = false
        val shared = inFlight.computeIfAbsent(key) {
            started = true
            scope.async {
                try {
                    lookup(key, safeArtist, safeTitle, priority)
                } finally {
                    inFlight.remove(key)
                }
            }
        }
        if (!started) stats.joinedInFlight.incrementAndGet()

        // Awaiting is per-consumer: a caller that goes away cancels only its own
        // wait, never the lookup the others are still waiting for.
        return try {
            shared.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FAILED
        }
    }

    /**
     * Warms the image bytes for a cover that is about to be shown.
     *
     * One URL, fire and forget, in the resolver's own scope. It exists because
     * the decode is not the slow part - the download is - and the current track's
     * cover is the one image the listener is guaranteed to look at.
     */
    fun prefetchImage(url: String?) {
        val target = url?.takeIf { it.startsWith("http") } ?: return
        scope.launch {
            runCatching { currentTrackLane.withPermit { repository.warmImage(target) } }
        }
    }

    private suspend fun lookup(
        key: String,
        artist: String,
        title: String,
        priority: ArtworkPriority,
    ): ArtworkRepository.ArtworkResult {
        val lane = if (priority == ArtworkPriority.CURRENT_TRACK) currentTrackLane else bulkLane

        return lane.withPermit {
            // Another consumer may have finished this exact track while this one
            // waited for a permit.
            valid(key)?.let {
                stats.cacheHits.incrementAndGet()
                return@withPermit it
            }

            // And the provider may have been declared dead while it waited. The
            // check at the door is not enough on its own: thirty rows queued behind
            // three permits all passed it before the first failure came back, and
            // without this they would each still pay their own timeout.
            if (circuitIsOpen()) {
                stats.shortCircuited.incrementAndGet()
                return@withPermit FAILED
            }

            stats.providerCalls.incrementAndGet()
            stats.enter()
            val result = try {
                repository.fetchArtwork(artist, title)
            } finally {
                stats.leave()
            }

            when (result.outcome) {
                ArtworkOutcome.FAILED -> {
                    // Not a fact about the track, a fact about the network. Held
                    // only long enough to stop a burst of rows re-asking at once.
                    // Counted only while the breaker is closed: a failure landing
                    // after it opened is the outage it already knows about.
                    if (circuitOpenUntil == 0L) consecutiveFailures.incrementAndGet()
                    cached[key] = Entry(result, now())
                }
                else -> {
                    consecutiveFailures.set(0)
                    circuitOpenUntil = 0L
                    cached[key] = Entry(result, now())
                }
            }
            result
        }
    }

    /** The cached answer for [key] if it is still one, or null to ask again. */
    private fun valid(key: String): ArtworkRepository.ArtworkResult? {
        val entry = cached[key] ?: return null
        val age = now() - entry.at
        val stillValid = when (entry.result.outcome) {
            ArtworkOutcome.RESOLVED -> true
            ArtworkOutcome.PARTIAL -> age < partialTtlMs
            ArtworkOutcome.NO_MATCH -> age < noMatchTtlMs
            ArtworkOutcome.FAILED -> age < failureCooldownMs
        }
        if (!stillValid) {
            cached.remove(key, entry)
            return null
        }
        return entry.result
    }

    private fun circuitIsOpen(): Boolean {
        val openUntil = circuitOpenUntil
        if (openUntil != 0L) {
            if (now() < openUntil) return true
            // The cooldown is over: close, and forget whatever was counted while it
            // was open. Those were lookups already in flight when it opened - the
            // same outage reporting in late, not new evidence - and counting them
            // re-tripped the breaker the moment it closed, so an app whose network
            // came back never asked again.
            circuitOpenUntil = 0L
            consecutiveFailures.set(0)
            return false
        }
        if (consecutiveFailures.get() >= failuresBeforeCircuitOpens) {
            circuitOpenUntil = now() + failureCooldownMs
            consecutiveFailures.set(0)
            return true
        }
        return false
    }

    /** Drops everything learned. For tests and for a low-memory signal. */
    fun clear() {
        cached.clear()
        consecutiveFailures.set(0)
        circuitOpenUntil = 0L
    }

    companion object {
        private val FAILED = ArtworkRepository.ArtworkResult(
            coverUrl = null,
            outcome = ArtworkOutcome.FAILED,
        )

        /**
         * How long "the provider answered and had nothing" is believed.
         *
         * A track the catalogue does not carry will not start being carried in the
         * next hour, so this is long enough to stop every re-bind re-asking and
         * short enough that a track added to the store shows up the same day. The
         * cache is in memory only, so a process restart re-asks regardless.
         */
        const val NO_MATCH_TTL_MS = 6 * 60 * 60 * 1000L

        /**
         * How long a *failure* is remembered - a cooldown, not an answer. Long
         * enough that thirty rows do not each pay their own timeout, short enough
         * that a network coming back is noticed while the listener is still there.
         */
        const val FAILURE_COOLDOWN_MS = 60 * 1000L

        /**
         * How long an answer reached *without* the release search is kept.
         *
         * It is shown meanwhile - an artist photograph is better than the plate -
         * but it is not the track's real cover, and on a network where the search
         * comes back it should be replaced within minutes rather than stay for the
         * whole session.
         */
        const val PARTIAL_TTL_MS = 10 * 60 * 1000L

        /** Consecutive failures after which the provider is left alone entirely. */
        const val FAILURES_BEFORE_CIRCUIT_OPENS = 3

        /**
         * History and Collection rows: enough to fill a screen, not a socket each.
         *
         * Measured against thirty rows bound at once on a healthy network (G5c):
         * unbounded, the last cover arrived after ~0.7 s but only by opening 64
         * provider calls from one phone - the kind of burst that invites the
         * provider's rate limiting. Three permits held that to 4 but pushed the
         * last cover to ~6.3 s; six keeps it near ~3 s with at most 8 in flight
         * counting the current-track lane. Neither the current track nor the first
         * cover is affected by this number, and on a real screen far fewer than
         * thirty rows bind at once.
         */
        const val BULK_PERMITS = 6

        /** Kept clear of the bulk lane so the track on screen never queues behind it. */
        const val CURRENT_TRACK_PERMITS = 2
    }
}
