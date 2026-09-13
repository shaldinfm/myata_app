package com.example.musicplayerapp.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The behaviour G5c added around the lookup: one call for many askers, a cache
 * that knows the difference between "nothing" and "could not ask", lanes that
 * keep the current track clear of a list, and a provider that is left alone once
 * it has stopped answering.
 *
 * No network and no Android. The provider is a fake that answers on command, so
 * every ordering below is forced rather than waited for - the only `delay` calls
 * are inside the fake, where the test is deliberately holding a lookup open.
 */
class ArtworkResolverTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var clock = 1_000_000L

    @After
    fun tearDown() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    /** A provider that counts, can be held open, and can be told what to answer. */
    private class FakeProvider(
        private val answer: (String, String) -> ArtworkRepository.ArtworkResult,
    ) : ArtworkProvider {
        val calls = AtomicInteger(0)
        val warmed = mutableListOf<String>()
        val inFlight = AtomicInteger(0)
        val peakInFlight = AtomicInteger(0)

        /** Held requests wait on this until the test opens it. */
        var gate: CountDownLatch? = null

        /** Raised once a call has actually started, so a test never sleeps to find out. */
        var started: CountDownLatch? = null

        override suspend fun fetchArtwork(artist: String, track: String): ArtworkRepository.ArtworkResult {
            calls.incrementAndGet()
            val now = inFlight.incrementAndGet()
            peakInFlight.updateAndGet { peak -> maxOf(peak, now) }
            started?.countDown()
            try {
                gate?.let { latch ->
                    while (!latch.await(5, TimeUnit.MILLISECONDS)) delay(5)
                }
                return answer(artist, track)
            } finally {
                inFlight.decrementAndGet()
            }
        }

        override suspend fun warmImage(url: String) {
            synchronized(warmed) { warmed += url }
        }
    }

    /**
     * Waits for a latch *without blocking the test's thread*.
     *
     * `runBlocking` runs every `async` child on its one thread, so a blocking
     * `CountDownLatch.await` here stops the very coroutines it is waiting for from
     * starting - they only ran once the wait timed out, which let an early version
     * of these tests pass without ever holding the lookups open at all.
     */
    private suspend fun CountDownLatch.awaitWhileSuspended() {
        withTimeout(5_000) {
            while (count > 0) delay(2)
        }
    }

    private fun resolved(url: String) = ArtworkRepository.ArtworkResult(
        coverUrl = url,
        confidence = ArtworkConfidence.HIGH,
        source = ArtworkSource.RELEASE,
        outcome = ArtworkOutcome.RESOLVED,
    )

    private val noMatch = ArtworkRepository.ArtworkResult(coverUrl = null, outcome = ArtworkOutcome.NO_MATCH)
    private val failed = ArtworkRepository.ArtworkResult(coverUrl = null, outcome = ArtworkOutcome.FAILED)

    private fun resolver(
        provider: ArtworkProvider,
        noMatchTtlMs: Long = 60_000,
        failureCooldownMs: Long = 10_000,
        failuresBeforeCircuitOpens: Int = 3,
        bulkPermits: Int = 3,
        currentTrackPermits: Int = 2,
    ) = ArtworkResolver(
        repository = provider,
        scope = scope,
        now = { clock },
        noMatchTtlMs = noMatchTtlMs,
        failureCooldownMs = failureCooldownMs,
        failuresBeforeCircuitOpens = failuresBeforeCircuitOpens,
        bulkPermits = bulkPermits,
        currentTrackPermits = currentTrackPermits,
    )

    // ============== de-duplication ==============

    /** Ten consumers, one track, one provider call - and everybody gets the answer. */
    @Test
    fun tenConsumersOfOneTrackProduceOneLookup() = runBlocking {
        val provider = FakeProvider { _, _ -> resolved("https://example.test/a.jpg") }
        provider.gate = CountDownLatch(1)
        provider.started = CountDownLatch(1)
        val resolver = resolver(provider)

        val waiters = (1..10).map { async { resolver.resolve("A", "a") } }
        // Hold everyone at the provider, then let it answer.
        provider.started!!.awaitWhileSuspended()
        provider.gate!!.countDown()

        val results = withTimeout(5_000) { waiters.awaitAll() }

        assertEquals("one provider call for ten consumers", 1, provider.calls.get())
        assertTrue("everyone got the same answer", results.all { it.coverUrl == "https://example.test/a.jpg" })
    }

    /** A consumer that gives up must not take the lookup away from the others. */
    @Test
    fun oneConsumerCancellingDoesNotCancelTheSharedLookup() = runBlocking {
        val provider = FakeProvider { _, _ -> resolved("https://example.test/a.jpg") }
        provider.gate = CountDownLatch(1)
        provider.started = CountDownLatch(1)
        val resolver = resolver(provider)

        val quitter = async { resolver.resolve("A", "a") }
        provider.started!!.awaitWhileSuspended()
        val stayer = async { resolver.resolve("A", "a") }

        quitter.cancel()
        provider.gate!!.countDown()

        val result = withTimeout(5_000) { stayer.await() }

        assertEquals("https://example.test/a.jpg", result.coverUrl)
        assertEquals(1, provider.calls.get())
    }

    @Test
    fun aSuccessfulResultIsReusedByLaterConsumers() = runBlocking {
        val provider = FakeProvider { _, _ -> resolved("https://example.test/a.jpg") }
        val resolver = resolver(provider)

        val first = resolver.resolve("A", "a")
        val second = resolver.resolve("A", "a")
        val third = resolver.resolve("a", " A ")

        assertEquals(1, provider.calls.get())
        assertEquals(first.coverUrl, second.coverUrl)
        assertEquals("case and spacing are the same track", first.coverUrl, third.coverUrl)
    }

    @Test
    fun unrelatedTracksDoNotCollide() = runBlocking {
        // The answer names both halves, so a collision would show up as an equal
        // URL rather than only as a missing call.
        val provider = FakeProvider { artist, track -> resolved("https://example.test/$artist-$track.jpg") }
        val resolver = resolver(provider)

        val a = resolver.resolve("A", "one")
        val b = resolver.resolve("A", "two")
        val c = resolver.resolve("B", "one")

        assertEquals(3, provider.calls.get())
        assertNotEquals(a.coverUrl, b.coverUrl)
        assertNotEquals(a.coverUrl, c.coverUrl)
    }

    // ============== what a null answer means ==============

    /** The G5c rule: a network failure is not the track's fault, so it is retried. */
    @Test
    fun aTransientFailureIsRetriedAndTheRetryIsBelieved() = runBlocking {
        var answer = failed
        val provider = FakeProvider { _, _ -> answer }
        val resolver = resolver(provider, failureCooldownMs = 10_000)

        assertNull(resolver.resolve("A", "a").coverUrl)
        assertEquals(1, provider.calls.get())

        // Inside the cooldown nothing is asked again.
        assertNull(resolver.resolve("A", "a").coverUrl)
        assertEquals("a failure must not become a per-row retry storm", 1, provider.calls.get())

        // After it, the provider is asked again - and the answer now sticks.
        clock += 10_001
        answer = resolved("https://example.test/late.jpg")
        assertEquals("https://example.test/late.jpg", resolver.resolve("A", "a").coverUrl)
        assertEquals(2, provider.calls.get())
    }

    /** A confirmed no-match is believed, but only for its TTL. */
    @Test
    fun aConfirmedNoMatchIsKeptForItsTtlAndThenReasked() = runBlocking {
        var answer = noMatch
        val provider = FakeProvider { _, _ -> answer }
        val resolver = resolver(provider, noMatchTtlMs = 60_000)

        assertNull(resolver.resolve("A", "a").coverUrl)
        clock += 59_000
        assertNull(resolver.resolve("A", "a").coverUrl)
        assertEquals("still inside the TTL", 1, provider.calls.get())

        clock += 2_000
        answer = resolved("https://example.test/new.jpg")
        assertEquals("https://example.test/new.jpg", resolver.resolve("A", "a").coverUrl)
        assertEquals(2, provider.calls.get())
    }

    /**
     * An answer reached without the release search is shown straight away, but it
     * is not the track's real cover - so it expires after its own TTL and the
     * search gets another chance, instead of the artist photo staying for the
     * whole session.
     */
    @Test
    fun aPartialAnswerIsShownNowAndReplacedAfterItsTtl() = runBlocking {
        val partial = ArtworkRepository.ArtworkResult(
            coverUrl = "https://example.test/artist.jpg",
            confidence = ArtworkConfidence.LOW,
            source = ArtworkSource.ARTIST_IMAGE,
            outcome = ArtworkOutcome.PARTIAL,
        )
        var answer = partial
        val provider = FakeProvider { _, _ -> answer }
        val resolver = ArtworkResolver(
            repository = provider, scope = scope, now = { clock },
            partialTtlMs = 600_000,
        )

        assertEquals("https://example.test/artist.jpg", resolver.resolve("A", "a").coverUrl)
        clock += 599_000
        assertEquals("still shown inside its TTL", "https://example.test/artist.jpg", resolver.resolve("A", "a").coverUrl)
        assertEquals(1, provider.calls.get())

        clock += 2_000
        answer = resolved("https://example.test/real-cover.jpg")
        assertEquals("the real cover replaced it", "https://example.test/real-cover.jpg", resolver.resolve("A", "a").coverUrl)
        assertEquals(2, provider.calls.get())
    }

    /** A no-match is remembered far longer than a failure - they are different facts. */
    @Test
    fun aFailureExpiresLongBeforeANoMatchDoes() = runBlocking {
        val provider = FakeProvider { _, track -> if (track == "gone") failed else noMatch }
        val resolver = resolver(provider, noMatchTtlMs = 60_000, failureCooldownMs = 10_000)

        resolver.resolve("A", "gone")
        resolver.resolve("A", "absent")
        clock += 11_000

        resolver.resolve("A", "gone")
        resolver.resolve("A", "absent")

        assertEquals("the failure was retried, the no-match was not", 3, provider.calls.get())
    }

    // ============== the provider that stopped answering ==============

    @Test
    fun aDeadProviderIsLeftAloneAfterThreeFailures() = runBlocking {
        val provider = FakeProvider { _, _ -> failed }
        val resolver = resolver(provider, failuresBeforeCircuitOpens = 3, failureCooldownMs = 10_000)

        repeat(12) { index -> resolver.resolve("A", "track$index") }

        assertEquals("only the first three tracks reached the provider", 3, provider.calls.get())
        assertTrue("the rest were answered from memory", resolver.stats.shortCircuited.get() >= 9)
    }

    /**
     * The History screen behind a blocked provider: thirty rows are already queued
     * for three permits when the first failures come back. Once the breaker opens,
     * the queued rows must not each go on to pay their own timeout.
     */
    @Test
    fun rowsAlreadyQueuedBehindADeadProviderDoNotEachWaitForItsTimeout() = runBlocking {
        val provider = FakeProvider { _, _ ->
            Thread.sleep(20) // a slow failure, so the queue is genuinely full
            failed
        }
        val resolver = resolver(provider, failuresBeforeCircuitOpens = 3, bulkPermits = 3)

        val rows = (1..30).map { index ->
            async { resolver.resolve("A", "row$index", ArtworkPriority.BULK) }
        }
        withTimeout(10_000) { rows.awaitAll() }

        assertTrue(
            "a dead provider was asked ${provider.calls.get()} times for 30 queued rows",
            provider.calls.get() <= 6,
        )
    }

    /**
     * The bug the G5c measurements caught. Lookups already in flight when the
     * breaker opened report their failures late; counted, they re-tripped the
     * breaker the moment its cooldown ended, and the app never asked again even
     * though the network had come back.
     */
    @Test
    fun lateFailuresFromTheSameOutageDoNotKeepTheBreakerOpen() = runBlocking {
        var answer = failed
        val provider = FakeProvider { _, _ -> answer }
        provider.gate = CountDownLatch(1)
        provider.started = CountDownLatch(6)
        val resolver = resolver(
            provider, failuresBeforeCircuitOpens = 3, failureCooldownMs = 10_000, bulkPermits = 6,
        )

        // Six lookups in flight together, all about to fail.
        val inFlight = (1..6).map { index -> async { resolver.resolve("A", "track$index") } }
        provider.started!!.awaitWhileSuspended()
        provider.gate!!.countDown()
        withTimeout(5_000) { inFlight.awaitAll() }

        // The breaker is open now, and a new track is answered from memory.
        resolver.resolve("A", "while open")
        assertEquals(6, provider.calls.get())

        // The network comes back and the cooldown passes.
        clock += 10_001
        answer = resolved("https://example.test/back.jpg")
        provider.gate = null

        assertEquals(
            "the late failures re-tripped the breaker",
            "https://example.test/back.jpg",
            resolver.resolve("A", "after").coverUrl,
        )
    }

    @Test
    fun theProviderIsTriedAgainOnceTheCooldownPasses() = runBlocking {
        var answer = failed
        val provider = FakeProvider { _, _ -> answer }
        val resolver = resolver(provider, failuresBeforeCircuitOpens = 3, failureCooldownMs = 10_000)

        repeat(6) { index -> resolver.resolve("A", "track$index") }
        assertEquals(3, provider.calls.get())

        clock += 10_001
        answer = resolved("https://example.test/back.jpg")

        assertEquals("https://example.test/back.jpg", resolver.resolve("A", "later").coverUrl)
    }

    // ============== lanes ==============

    @Test
    fun bulkLookupsAreBoundedAndDoNotBlockTheCurrentTrack() = runBlocking {
        val provider = FakeProvider { _, track -> resolved("https://example.test/$track.jpg") }
        provider.gate = CountDownLatch(1)
        provider.started = CountDownLatch(3)
        val resolver = resolver(provider, bulkPermits = 3, currentTrackPermits = 2)

        // A History screen's worth of rows, all at once.
        val rows = (1..30).map { index ->
            async { resolver.resolve("A", "row$index", ArtworkPriority.BULK) }
        }
        provider.started!!.awaitWhileSuspended()

        // The track on screen arrives while all three bulk permits are held.
        val current = async { resolver.resolve("B", "now playing", ArtworkPriority.CURRENT_TRACK) }
        provider.gate!!.countDown()

        val nowPlaying = withTimeout(10_000) { current.await() }
        withTimeout(10_000) { rows.awaitAll() }

        assertEquals("https://example.test/now playing.jpg", nowPlaying.coverUrl)
        assertTrue(
            "thirty rows must not become thirty concurrent lookups: ${provider.peakInFlight.get()}",
            provider.peakInFlight.get() <= 3 + 2,
        )
        assertEquals("one lookup per row, none repeated", 31, provider.calls.get())
    }

    // ============== what the cache must not change ==============

    /** G5b's answer has to survive the round trip through the cache untouched. */
    @Test
    fun theMatchersResultSurvivesTheCacheUnchanged() = runBlocking {
        val original = ArtworkRepository.ArtworkResult(
            coverUrl = "https://example.test/cover.jpg",
            confidence = ArtworkConfidence.MEDIUM,
            source = ArtworkSource.ARTIST_IMAGE,
            outcome = ArtworkOutcome.RESOLVED,
        )
        val provider = FakeProvider { _, _ -> original }
        val resolver = resolver(provider)

        val fresh = resolver.resolve("A", "a")
        val cachedResult = resolver.resolve("A", "a")

        assertEquals(original, fresh)
        assertEquals("the cached copy is the same answer", original, cachedResult)
        assertSame("and it is literally the same object, not a rebuilt one", fresh, cachedResult)
        assertEquals(ArtworkConfidence.MEDIUM, cachedResult.confidence)
        assertEquals(ArtworkSource.ARTIST_IMAGE, cachedResult.source)
    }

    // ============== prefetch ==============

    @Test
    fun theCurrentTracksImageIsWarmedAndNothingElseIs() = runBlocking {
        val provider = FakeProvider { _, _ -> resolved("https://example.test/a.jpg") }
        val resolver = resolver(provider)

        resolver.prefetchImage("https://example.test/a.jpg")
        resolver.prefetchImage(null)
        resolver.prefetchImage("NO_IMAGE")

        // The warm-up runs in the resolver's own scope; wait for it to land.
        withTimeout(5_000) {
            while (synchronized(provider.warmed) { provider.warmed.isEmpty() }) delay(5)
        }

        synchronized(provider.warmed) {
            assertEquals(listOf("https://example.test/a.jpg"), provider.warmed.toList())
        }
    }

    @Test
    fun clearingForgetsEverything() = runBlocking {
        val provider = FakeProvider { _, _ -> resolved("https://example.test/a.jpg") }
        val resolver = resolver(provider)

        resolver.resolve("A", "a")
        resolver.clear()
        resolver.resolve("A", "a")

        assertEquals(2, provider.calls.get())
    }

    /** A blank pair is still a key, and still only asked about once. */
    @Test
    fun anEmptyPairIsHandledLikeAnyOtherKey() = runBlocking {
        val provider = FakeProvider { _, _ -> noMatch }
        val resolver = resolver(provider)

        assertNull(resolver.resolve(null, null).coverUrl)
        assertNull(resolver.resolve("", "").coverUrl)

        assertEquals(1, provider.calls.get())
    }
}
