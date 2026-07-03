package com.example.musicplayerapp.data

import android.net.Uri
import android.util.Log
import com.example.musicplayerapp.UnsafeNetModule
import com.example.musicplayerapp.utils.ServiceUtils
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class MetadataRepository(private val client: OkHttpClient) {

    /**
     * Polls metadata for all streams every 15 seconds.
     * Returns a Flow of Map<StreamKey, PlayerState>.
     */
    fun pollMetadata(): Flow<Map<String, PlayerState>> = flow {
        while (true) {
            val result = fetchAllStreamsMetadata()
            if (result != null) {
                emit(result)
            }
            delay(15000)
        }
    }

    private suspend fun fetchAllStreamsMetadata(): Map<String, PlayerState>? = withContext(Dispatchers.IO) {
        try {
            val url = "https://radiomyata.ru/api_all_tracks.php?v=${System.currentTimeMillis()}"
            val request = Request.Builder().url(url).build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null

                val jsonBody = response.body?.string() ?: return@use null
                val apiResponse = Gson().fromJson(jsonBody, Map::class.java)
                val data = apiResponse["data"] as? Map<String, Any> ?: return@use null

                val results = mutableMapOf<String, PlayerState>()
                
                // Process each stream
                listOf("myata", "gold", "myata_hits").forEach { streamKey ->
                    val streamData = data[streamKey] as? Map<String, Any>
                    if (streamData != null) {
                        val artist = streamData["artist"] as? String ?: ""
                        val song = streamData["track"] as? String ?: ""
                        
                        // We NO LONGER fetch artwork here to keep polling fast.
                        // StreamsViewModel will handle async artwork fetching.
                        val placeholderIndex = (Math.abs((artist + song).hashCode()) % 4) + 1
                        
                        results[streamKey] = PlayerState(
                            artist = artist,
                            song = song,
                            img = null,
                            backgroundImg = null,
                            placeholderIndex = placeholderIndex
                        )
                    }
                }
                results
            }
        } catch (e: Exception) {
            Log.e("MetadataRepo", "Error fetching metadata", e)
            null
        }
    }

    /**
     * Fetches playlists from the server.
     */
    suspend fun fetchPlaylists(): List<MyataPlaylist> = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://radiomyata.ru/covers/playlists.txt")
            val connection: HttpURLConnection = url.openConnection() as HttpsURLConnection
            val lastModified = connection.lastModified
            
            val br = BufferedReader(InputStreamReader(connection.getInputStream()))
            val wholeText = br.readText().split(Regex("\\n\\s*\\n"))
            br.close()

            val playlists = mutableListOf<MyataPlaylist>()
            for (entry in wholeText) {
                if (entry.isNotBlank()) {
                    val parts = entry.split(Regex("\\s+[—–-]\\s+"))
                    if (parts.size >= 2) {
                        val imgUrl = parts[0].trim(' ', '\ufeff', '\n', '\r')
                        val name = parts[1].trim(' ', '\n', '\r')

                        val version = if (lastModified > 0) lastModified else System.currentTimeMillis()
                        val urlWithVersion = "$imgUrl?v=$version"
                        playlists.add(MyataPlaylist(name, Uri.parse(urlWithVersion)))
                    }
                }
            }
            playlists
        } catch (e: Exception) {
            Log.e("MetadataRepo", "Error fetching playlists", e)
            emptyList()
        }
    }
}
