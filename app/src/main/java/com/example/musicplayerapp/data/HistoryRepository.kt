package com.example.musicplayerapp.data

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Repository for fetching track history from the radio API.
 */
class HistoryRepository(private val client: OkHttpClient) {
    
    private val gson = Gson()
    
    companion object {
        private const val TAG = "HistoryRepository"
        private const val BASE_URL = "https://radiomyata.ru/api_track_history.php"
    }
    
    /**
     * Fetches track history for the given stream.
     * @param stream Stream identifier: "myata", "gold", or "myata_hits"
     * @param limit Number of tracks to fetch (default 20)
     * @return List of history tracks, or empty list on error
     */
    suspend fun getHistory(stream: String, limit: Int = 20): List<HistoryTrack> = withContext(Dispatchers.IO) {
        val url = "$BASE_URL?stream=$stream&limit=$limit"
        
        try {
            val request = Request.Builder()
                .url(url)
                .header("Cache-Control", "no-cache")
                .build()
                
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "API request failed: ${response.code}")
                    return@withContext emptyList()
                }
                
                val body = response.body?.string() ?: return@withContext emptyList()
                Log.d(TAG, "History API response: ${body.take(200)}...")
                
                val historyResponse = gson.fromJson(body, HistoryResponse::class.java)
                
                if (historyResponse.success && historyResponse.data != null) {
                    historyResponse.data
                } else {
                    Log.e(TAG, "API returned success=false or null data")
                    emptyList()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching history for $stream", e)
            emptyList()
        }
    }
}
