package com.example.musicplayerapp

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.musicplayerapp.data.ArtworkRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * `ArtworkRepository.fetchArtwork` is `suspend` but blocks on OkHttp, and for the
 * whole of its life it did so on whatever thread the caller was on. From
 * `StreamsViewModel` that is `Dispatchers.Main`, where every stage threw
 * `NetworkOnMainThreadException` into its own `catch (Exception)` and the function
 * quietly returned an empty result - so the ViewModel's artwork lookup never
 * returned a cover at all.
 *
 * What is asserted here is the thread the request actually executes on, because
 * that is the property that was wrong. The exception was only its symptom, and a
 * test that reached for one would need a real socket to provoke StrictMode.
 *
 * No socket is opened: an application interceptor answers every request from
 * memory. For a synchronous `execute()` the whole chain runs on the thread that
 * called it, so the thread the interceptor sees is the thread the repository body
 * is running on.
 */
@RunWith(AndroidJUnit4::class)
class ArtworkRepositoryDispatchTest {

    private companion object {
        const val ITUNES_HIT = """
            {"resultCount":1,"results":[{
              "trackName":"Smalltown Boy",
              "artistName":"Bronski Beat",
              "collectionName":"The Age of Consent",
              "artworkUrl100":"https://example.invalid/cover/100x100bb.jpg"
            }]}
        """

        const val ITUNES_EMPTY = """{"resultCount":0,"results":[]}"""

        const val DEEZER_HIT = """
            {"data":[{"name":"Bronski Beat","picture_xl":"https://example.invalid/artist-xl.jpg"}]}
        """
    }

    /** Answers from memory, and records who asked and for what. */
    private class CannedResponses : Interceptor {
        val threads = CopyOnWriteArrayList<String>()
        val hosts = CopyOnWriteArrayList<String>()
        var itunes: String = ITUNES_HIT
        var deezer: String = DEEZER_HIT

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            threads.add(Thread.currentThread().name)
            hosts.add(request.url.host)

            val body = when {
                request.url.host.contains("itunes") -> itunes
                request.url.host.contains("deezer") -> deezer
                else -> ""
            }

            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(if (body.isEmpty()) 404 else 200)
                .message("canned")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    private fun repositoryAnswering(canned: CannedResponses) =
        ArtworkRepository(OkHttpClient.Builder().addInterceptor(canned).build())

    /**
     * The regression. Before the fix this failed on the first assertion: the
     * request ran on the caller's own main thread.
     */
    @Test
    fun aMainThreadCallerGetsItsNetworkOffTheMainThread() {
        val canned = CannedResponses()
        val repository = repositoryAnswering(canned)

        val result = runBlocking {
            withContext(Dispatchers.Main) {
                assertEquals(
                    "precondition: this caller is on the main thread, as StreamsViewModel is",
                    Looper.getMainLooper().thread,
                    Thread.currentThread()
                )
                repository.fetchArtwork("Bronski Beat", "Smalltown Boy")
            }
        }

        assertEquals("the iTunes stage should have been asked exactly once", 1, canned.threads.size)
        assertNotEquals(
            "the request must not run on the caller's main thread",
            Looper.getMainLooper().thread.name,
            canned.threads[0]
        )

        // And because it no longer fails on thread context, the stage produces a
        // cover - at the 600px size the repository rewrites the URL to.
        assertEquals("https://example.invalid/cover/600x600bb.jpg", result.coverUrl)
    }

    /**
     * The cache read sits ahead of the dispatcher switch on purpose. A repeat
     * track - which 15-second metadata polling produces constantly - must cost
     * neither a request nor a thread hop.
     */
    @Test
    fun aRepeatLookupIsAnsweredFromCacheWithoutAskingAgain() {
        val canned = CannedResponses()
        val repository = repositoryAnswering(canned)

        val first = runBlocking { repository.fetchArtwork("Bronski Beat", "Smalltown Boy") }
        val requestsAfterFirst = canned.threads.size

        val second = runBlocking {
            withContext(Dispatchers.Main) {
                val onMain = repository.fetchArtwork("Bronski Beat", "Smalltown Boy")
                // A hit returns on the caller's own thread: it never left main.
                assertEquals(Looper.getMainLooper().thread, Thread.currentThread())
                onMain
            }
        }

        assertEquals(first.coverUrl, second.coverUrl)
        assertEquals(
            "a cache hit must not reach the network",
            requestsAfterFirst,
            canned.threads.size
        )
    }

    /**
     * Source priority, and the shape of a cold miss: iTunes is asked three times
     * before Deezer is asked at all. Every one of those is a serial round trip.
     */
    @Test
    fun theFallbacksKeepTheirOrderWhenItunesFindsNothing() {
        val canned = CannedResponses().apply { itunes = ITUNES_EMPTY }
        val repository = repositoryAnswering(canned)

        val result = runBlocking { repository.fetchArtwork("Bronski Beat", "Smalltown Boy") }

        assertEquals("https://example.invalid/artist-xl.jpg", result.coverUrl)
        assertTrue(
            "iTunes is exhausted before Deezer is tried, one request at a time: ${canned.hosts}",
            canned.hosts.takeWhile { it.contains("itunes") }.isNotEmpty() &&
                canned.hosts.first { !it.contains("itunes") }.contains("deezer")
        )
    }

    /**
     * Nothing found anywhere is an empty result, not an exception: the callers
     * treat it as "no cover" and neither metadata nor playback is disturbed.
     */
    @Test
    fun aTotalFailureIsAnEmptyResultRatherThanAThrow() {
        val canned = CannedResponses().apply {
            itunes = ITUNES_EMPTY
            deezer = """{"data":[]}"""
        }
        val repository = repositoryAnswering(canned)

        val result = runBlocking { repository.fetchArtwork("No Such Artist", "No Such Track") }

        assertEquals(null, result.coverUrl)
        assertEquals(null, result.backgroundUrl)
    }
}
