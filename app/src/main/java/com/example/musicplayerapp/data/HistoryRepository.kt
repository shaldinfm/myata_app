package com.example.musicplayerapp.data

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * What one history request came back with.
 *
 * G4b split this out of what used to be a plain list. The repository answered
 * every failure - a non-2xx, a dead network, a page that was not JSON - with an
 * empty list, so "the station has no history" and "the API could not be reached"
 * were the same value and no screen could tell them apart. The full-screen
 * История эфира draws them as two different frozen frames, `history-empty` and
 * `history-error`, so the difference has to survive the trip.
 */
sealed class HistoryResult {
    /** The endpoint answered `success: true`. [tracks] may be empty - that is `history-empty`. */
    data class Loaded(val tracks: List<HistoryTrack>) : HistoryResult()

    /** The request did not produce a history. [reason] is for the log, never for the screen. */
    data class Failed(val reason: String) : HistoryResult()
}

/**
 * Repository for fetching track history from the radio API.
 *
 * Still the only history backend. Nothing about the request changed in G4b - the
 * same URL, the same no-cache header, the same parsing - only what a failure is
 * reported as.
 */
class HistoryRepository(private val client: OkHttpClient) {

    private val gson = Gson()

    companion object {
        private const val TAG = "HistoryRepository"
        private const val BASE_URL = "https://radiomyata.ru/api_track_history.php"

        /**
         * Replaces the network for instrumented tests, the way
         * `ReportConfig.endpointOverrideForTest` replaces the report endpoint.
         *
         * The four History frames are states of the network - loading, an answer,
         * an empty answer, a failure - and a test that waited for the real station
         * to produce each of them would be a test of the station. Null in
         * production, always.
         */
        @VisibleForTesting
        @Volatile
        var sourceOverrideForTest: (suspend (stream: String, limit: Int) -> HistoryResult)? = null
    }

    /**
     * Fetches track history for the given stream.
     *
     * @param stream Stream identifier: "myata", "gold", or "myata_hits"
     * @param limit Number of tracks to ask for
     */
    suspend fun fetchHistory(stream: String, limit: Int): HistoryResult {
        sourceOverrideForTest?.let { return it(stream, limit) }

        return withContext(Dispatchers.IO) {
            val url = "$BASE_URL?stream=$stream&limit=$limit"
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("Cache-Control", "no-cache")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(TAG, "API request failed: ${response.code}")
                        return@withContext HistoryResult.Failed("HTTP ${response.code}")
                    }

                    val body = response.body?.string()
                        ?: return@withContext HistoryResult.Failed("no body")
                    Log.d(TAG, "History API response: ${body.take(200)}...")

                    // Gson answers an empty or whitespace body with null rather than
                    // throwing, so that case is named here instead of surfacing as
                    // an NPE the catch below would have to explain.
                    val parsed = gson.fromJson(body, HistoryResponse::class.java)
                        ?: return@withContext HistoryResult.Failed("empty body")

                    if (!parsed.success) {
                        Log.e(TAG, "API returned success=false")
                        return@withContext HistoryResult.Failed("success=false")
                    }
                    // success with no `data` has always been treated as "nothing
                    // to show", and it stays that: the endpoint said it succeeded.
                    HistoryResult.Loaded(parsed.data.orEmpty())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching history for $stream", e)
                HistoryResult.Failed(e.javaClass.simpleName)
            }
        }
    }
}
