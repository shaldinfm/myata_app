package com.example.musicplayerapp.data

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What the repository reports when a provider is down, and which providers it
 * bothers asking (G5c).
 *
 * The shape that matters most is the one measured from Russia on Rostelecom:
 * `itunes.apple.com` unreachable, Deezer and both image CDNs fine. Before G5c
 * every lookup on that network paid the full iTunes deadline before reaching the
 * Deezer step that was going to answer anyway, and the artist photo it produced
 * was then kept for the whole session as though it were the track's real answer.
 *
 * No network: an interceptor plays each provider, and can refuse to connect the
 * way a blocked host does.
 */
class ArtworkRepositoryResilienceTest {

    private companion object {
        const val ITUNES_HIT = """
            {"resultCount":1,"results":[{
              "trackName":"Smalltown Boy","artistName":"Bronski Beat",
              "collectionName":"The Age of Consent","releaseDate":"1984-10-15T07:00:00Z",
              "artworkUrl100":"https://example.invalid/cover/100x100bb.jpg"
            }]}
        """
        const val ITUNES_EMPTY = """{"resultCount":0,"results":[]}"""
        const val DEEZER_HIT = """{"data":[{"name":"Bronski Beat","picture_xl":"https://example.invalid/artist.jpg"}]}"""
        const val DEEZER_EMPTY = """{"data":[]}"""
    }

    /** Plays both providers; either can be told to behave like a blocked host. */
    private class Network : Interceptor {
        val asked = CopyOnWriteArrayList<String>()
        var itunesBlocked = false
        var deezerBlocked = false
        var itunes = ITUNES_HIT
        var deezer = DEEZER_HIT

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val host = request.url.host
            asked += host
            val blocked = (host.contains("itunes") && itunesBlocked) || (host.contains("deezer") && deezerBlocked)
            if (blocked) throw IOException("connect timed out: $host")

            val body = if (host.contains("itunes")) itunes else deezer
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("canned")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }

        fun itunesCalls() = asked.count { it.contains("itunes") }
        fun deezerCalls() = asked.count { it.contains("deezer") }
    }

    private var clock = 1_000_000L

    private fun repository(network: Network) = ArtworkRepository(
        httpClient = OkHttpClient.Builder().addInterceptor(network).build(),
        itunesHealth = ArtworkRepository.ProviderHealth(now = { clock }, failuresToOpen = 3, cooldownMs = 60_000),
        deezerHealth = ArtworkRepository.ProviderHealth(now = { clock }, failuresToOpen = 3, cooldownMs = 60_000),
    )

    private fun lookup(repository: ArtworkRepository, track: String = "Smalltown Boy") =
        runBlocking { repository.fetchArtwork("Bronski Beat", track) }

    // ============== the four outcomes ==============

    @Test
    fun aReleaseFoundByTheSearchIsResolvedAndDeezerIsNeverAsked() {
        val network = Network()
        val result = lookup(repository(network))

        assertEquals(ArtworkOutcome.RESOLVED, result.outcome)
        assertEquals(ArtworkSource.RELEASE, result.source)
        assertEquals(0, network.deezerCalls())
    }

    @Test
    fun anArtistImageAfterAGenuineSearchMissIsResolved() {
        val network = Network().apply { itunes = ITUNES_EMPTY }
        val result = lookup(repository(network))

        assertEquals("the search answered, so the artist image is the real answer", ArtworkOutcome.RESOLVED, result.outcome)
        assertEquals(ArtworkSource.ARTIST_IMAGE, result.source)
    }

    @Test
    fun nothingAnywhereAfterBothAnsweredIsANoMatch() {
        val network = Network().apply {
            itunes = ITUNES_EMPTY
            deezer = DEEZER_EMPTY
        }

        assertEquals(ArtworkOutcome.NO_MATCH, lookup(repository(network)).outcome)
    }

    @Test
    fun nothingWhileEveryProviderWasDownIsAFailureNotANoMatch() {
        val network = Network().apply {
            itunesBlocked = true
            deezerBlocked = true
        }

        assertEquals(ArtworkOutcome.FAILED, lookup(repository(network)).outcome)
    }

    @Test
    fun aSearchThatAnsweredNothingWhileDeezerWasDownIsAFailure() {
        val network = Network().apply {
            itunes = ITUNES_EMPTY
            deezerBlocked = true
        }

        assertEquals(
            "the fallback could not be asked, so the absence is not confirmed",
            ArtworkOutcome.FAILED,
            lookup(repository(network)).outcome,
        )
    }

    // ============== the Rostelecom shape ==============

    /**
     * iTunes blocked, Deezer reachable. The artist image is worth showing, but it
     * was reached without the release search, so it is PARTIAL - kept briefly and
     * replaced when the search comes back - rather than RESOLVED and kept forever.
     */
    @Test
    fun anArtistImageReachedWithoutTheSearchIsPartial() {
        val network = Network().apply { itunesBlocked = true }
        val result = lookup(repository(network))

        assertEquals(ArtworkOutcome.PARTIAL, result.outcome)
        assertEquals("https://example.invalid/artist.jpg", result.coverUrl)
        assertEquals("G5b's labelling is unchanged", ArtworkSource.ARTIST_IMAGE, result.source)
        assertEquals(ArtworkConfidence.LOW, result.confidence)
    }

    /**
     * After three unreachable searches the rest of a History screen goes straight
     * to the fallback instead of each row spending the whole iTunes deadline first.
     */
    @Test
    fun aBlockedSearchIsSkippedAfterThreeFailures() {
        val network = Network().apply { itunesBlocked = true }
        val repository = repository(network)

        repeat(10) { index -> lookup(repository, "Track $index") }

        assertEquals("only the first three lookups tried iTunes", 3, network.itunesCalls())
        assertEquals("every lookup still reached the fallback", 10, network.deezerCalls())
    }

    @Test
    fun theSearchIsTriedAgainOnceItsCooldownPasses() {
        val network = Network().apply { itunesBlocked = true }
        val repository = repository(network)

        repeat(5) { index -> lookup(repository, "Track $index") }
        assertEquals(3, network.itunesCalls())

        clock += 60_001
        network.itunesBlocked = false
        val result = lookup(repository)

        assertEquals(4, network.itunesCalls())
        assertEquals("the real cover is back", ArtworkOutcome.RESOLVED, result.outcome)
        assertEquals(ArtworkSource.RELEASE, result.source)
    }

    /** Stragglers from the outage must neither extend the cooldown nor pre-load the next one. */
    @Test
    fun failuresReportedWhileDownDoNotExtendOrPreloadTheCooldown() {
        val health = ArtworkRepository.ProviderHealth(now = { clock }, failuresToOpen = 3, cooldownMs = 60_000)

        repeat(3) { health.unreachable() }
        assertTrue("three failures open it", !health.available())

        // Late reports from requests that were already in flight.
        clock += 30_000
        repeat(5) { health.unreachable() }

        clock += 30_001
        assertTrue("the cooldown was not pushed out by the late reports", health.available())

        // A single real failure after recovery must not reopen it on old evidence.
        health.unreachable()
        assertTrue("one fresh failure is not three", health.available())
    }

    @Test
    fun oneAnswerClearsTheFailureCount() {
        val network = Network()
        val repository = repository(network)

        network.itunesBlocked = true
        repeat(2) { index -> lookup(repository, "Down $index") }
        network.itunesBlocked = false
        lookup(repository, "Up")
        network.itunesBlocked = true
        repeat(2) { index -> lookup(repository, "Down again $index") }

        assertEquals("never three in a row, so never skipped", 5, network.itunesCalls())
    }

    @Test
    fun aDownFallbackIsSkippedTooWithoutTouchingTheSearch() {
        val network = Network().apply {
            itunes = ITUNES_EMPTY
            deezerBlocked = true
        }
        val repository = repository(network)

        repeat(6) { index -> lookup(repository, "Track $index") }

        assertEquals("the search is healthy and is asked every time", true, network.itunesCalls() >= 6)
        assertEquals("the fallback is left alone after three failures", 3, network.deezerCalls())
        assertTrue(network.asked.isNotEmpty())
    }
}
