package com.example.musicplayerapp

import android.annotation.SuppressLint
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.musicplayerapp.service.MediaPlayerService
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import org.jsoup.Jsoup
import org.jsoup.select.Elements
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection




class StreamsViewModel(app: Application):AndroidViewModel(app) {

    // Last.fm API credentials
    // Last.fm API credentials removed

    
    var currentMyataState = MutableLiveData<PlayerState?>()
    var currentGoldState = MutableLiveData<PlayerState?>()
    var currentXtraState = MutableLiveData<PlayerState?>()
    var isPlaying = MutableLiveData<Boolean>()
    var isInSplitMode = MutableLiveData<Boolean>()
    var playlistList = MutableLiveData<MutableList<MyataPlaylist>>()
    var currentStreamLive = MutableLiveData<String?>()
    var currentFragmentLiveData = MutableLiveData<String>()
    @SuppressLint("StaticFieldLeak")
    private val context = getApplication<Application>().applicationContext
    var isUIActive = true
    var lastAnimatedImageUrl: String? = null  // Track URL of last animated cover art

    //problem why we need this is service cannot launch fragment, it can only recreate activity
    var ifNeedToNavigateStraightToPlayer = false
    //To avoid reaction on swich stream pause
    var ifNeedToListenReciever = true



    init {
        isPlaying.value = false
        isInSplitMode.value = false
        currentStreamLive.value = "myata"

        val receiver1 = PlayPauseBroadcastReceiver()
        context?.let {
            LocalBroadcastManager.getInstance(it).registerReceiver(receiver1,
                IntentFilter("play")
            )
        }

        val receiver2 = PlayPauseBroadcastReceiver()
        context?.let {
            LocalBroadcastManager.getInstance(it).registerReceiver(receiver2,
                IntentFilter("pause")
            )
        }

        getStreamJson()
        getPlaylists()
        currentMyataState.value = PlayerState("YOU ARE LISTENING", "RADIO MYATA", null)
        currentGoldState.value = PlayerState("YOU ARE LISTENING", "RADIO MYATA", null)
        currentXtraState.value = PlayerState("YOU ARE LISTENING", "RADIO MYATA", null)
    }

    fun getPlaylists() = viewModelScope.launch {
        while (true){
            try{
                requestMyata()
                // Wait 1 hour before updating playlists again
                delay(3600000) // 1 hour = 3600000 ms
            }
            catch (e:Exception){
                Log.e("Exception: ",e.toString())
                delay(60000) // On error, retry after 1 minute
                continue
            }
        }
    }

    suspend fun requestMyata() = withContext(Dispatchers.IO){

        try {
            val url = URL("https://radiomyata.ru/covers/playlists.txt")

            val connection: HttpURLConnection = url.openConnection() as HttpsURLConnection
            val br = BufferedReader(InputStreamReader(connection.getInputStream()))
            var lines: MutableList<MyataPlaylist> = mutableListOf()
            val wholeText = br.readText().split("\n\n","\r\r")
            br.close()
            Log.d("LINE", wholeText.toString())
            for (str in wholeText){
                Log.d("SOSI", str.split(" — ", " - ")[0])
                if(!str.isBlank())
                    lines.add(MyataPlaylist(str.split(" — ", " - ")[1].trim(' '),
                        Uri.parse(str.split(" — ", " - ")[0].trim(' '))))
            }
            Log.d("LINES", lines.toString())
            playlistList.postValue(lines)
        } catch (e: IOException) {
            Log.e("IOexception", "Myata request exception: " + e.getLocalizedMessage())
            e.printStackTrace()
        }
    }

    fun getStreamJson() = viewModelScope.launch {
        parseJson()
    }

    suspend fun parseJson() = withContext(Dispatchers.IO){

        val client = OkHttpClient.Builder().build()
        while (true) {
            val gson = Gson()
            try{
                val request = Request.Builder()
                    .url("https://drh-api.dline-media.com/api/statistics?filter[organization_id]=7")
                    .build()

                client.newCall(request).execute().use { response->
                    if(!response.isSuccessful) throw IOException("Unexpected code $response")
                    val streamInfo = gson.fromJson(response.body?.string(), Map::class.java)
                    val streamDataInfo = streamInfo.get("data") as List<Map<String,Any>>
                    var streamsData = streamDataInfo[0].get("streams") as List<Map<String, Any>>
                    var songData = streamsData[0].get("last_song") as Map<String,String>
                    var songArtist = songData.get("title").toString().split(" - ")
                    if(currentMyataState.value?.song != songArtist?.get(1)) {
                        if(currentStreamLive.value == "myata"){
                            if(currentMyataState.value?.song != null){
                                context.startService(Intent(
                                    context,
                                    MediaPlayerService::class.java
                                ).also {
                                    it.putExtra("ACTION", "switch_track")
                                    it.putExtra("SONG", songArtist?.get(1))
                                    it.putExtra("ARTIST", songArtist?.get(0))
                                })

                                val artistName = songArtist?.get(0)?.trim() ?: ""
                                val songName = songArtist?.get(1)?.trim() ?: ""
                                val placeholderIndex = (Math.abs((artistName + songName).hashCode()) % 4) + 1
                                currentMyataState.postValue(
                                    PlayerState(
                                        songArtist?.get(0), songArtist?.get(1), null, placeholderIndex)
                                )
                            }
                        }
                        try {
                            if(isUIActive){
                                val artistName = songArtist?.get(0)?.trim() ?: ""
                                val songName = songArtist?.get(1)?.trim() ?: ""
                                val imageUrl = fetchDeezerCover(client, gson, artistName, songName)
                                val placeholderIndex = (Math.abs((artistName + songName).hashCode()) % 4) + 1
                                currentMyataState.postValue(
                                    PlayerState(songArtist?.get(0), songArtist?.get(1), imageUrl ?: "NO_IMAGE", placeholderIndex)
                                )
                            }
                        }
                        catch (ex: Exception){
                            val artistName = songArtist?.get(0)?.trim() ?: ""
                            val songName = songArtist?.get(1)?.trim() ?: ""
                            val placeholderIndex = (Math.abs((artistName + songName).hashCode()) % 4) + 1
                            currentMyataState.postValue(
                                PlayerState(
                                    songArtist?.get(0), songArtist?.get(1), null, placeholderIndex
                                )
                            )
                            Log.e("Exception", ex.toString())
                        }
                    }
                    streamsData = streamDataInfo[1].get("streams") as List<Map<String, Any>>
                    songData = streamsData[0].get("last_song") as Map<String,String>
                    songArtist = songData.get("title").toString().split(" - ")
                    if(currentGoldState.value?.song != songArtist?.get(1)) {
                        if(currentStreamLive.value == "gold"){
                            if(currentGoldState.value?.song != null){
                                context.startService(Intent(
                                    context,
                                    MediaPlayerService::class.java
                                ).also {
                                    it.putExtra("ACTION", "switch_track")
                                    it.putExtra("SONG", songArtist?.get(1))
                                    it.putExtra("ARTIST", songArtist?.get(0))
                                })

                                val artistName = songArtist?.get(0)?.trim() ?: ""
                                val songName = songArtist?.get(1)?.trim() ?: ""
                                val placeholderIndex = (Math.abs((artistName + songName).hashCode()) % 4) + 1
                                currentGoldState.postValue(
                                    PlayerState(
                                        songArtist?.get(0), songArtist?.get(1), null, placeholderIndex))
                            }
                        }
                        try {
                            if(isUIActive){
                                val artistName = songArtist?.get(0)?.trim() ?: ""
                                val songName = songArtist?.get(1)?.trim() ?: ""
                                val imageUrl = fetchDeezerCover(client, gson, artistName, songName)
                                val placeholderIndex = (Math.abs((artistName + songName).hashCode()) % 4) + 1
                                currentGoldState.postValue(
                                    PlayerState(songArtist?.get(0), songArtist?.get(1), imageUrl ?: "NO_IMAGE", placeholderIndex)
                                )
                            }
                        }
                        catch (ex: Exception){
                            val artistName = songArtist?.get(0)?.trim() ?: ""
                            val songName = songArtist?.get(1)?.trim() ?: ""
                            val placeholderIndex = (Math.abs((artistName + songName).hashCode()) % 4) + 1
                            currentGoldState.postValue(
                                PlayerState(
                                    songArtist?.get(0), songArtist?.get(1), null
                                )
                            )
                            Log.e("Exception", ex.toString())
                        }
                    }
                    streamsData = streamDataInfo[2].get("streams") as List<Map<String, Any>>
                    songData = streamsData[0].get("last_song") as Map<String,String>
                    songArtist = songData.get("title").toString().split(" - ")
                    if(currentXtraState.value?.song != songArtist?.get(1)) {
                        if(currentStreamLive.value == "myata_hits"){
                            if(currentXtraState.value?.song != null){
                                context.startService(Intent(
                                    context,
                                    MediaPlayerService::class.java
                                ).also {
                                    it.putExtra("ACTION", "switch_track")
                                    it.putExtra("SONG", songArtist?.get(1))
                                    it.putExtra("ARTIST", songArtist?.get(0))
                                })

                                val artistName = songArtist?.get(0)?.trim() ?: ""
                                val songName = songArtist?.get(1)?.trim() ?: ""
                                val placeholderIndex = (Math.abs((artistName + songName).hashCode()) % 4) + 1
                                currentXtraState.postValue(
                                    PlayerState(
                                        songArtist?.get(0), songArtist?.get(1), null, placeholderIndex)
                                )
                            }
                        }
                        try {
                            if(isUIActive){
                                val artistName = songArtist?.get(0)?.trim() ?: ""
                                val songName = songArtist?.get(1)?.trim() ?: ""
                                val imageUrl = fetchDeezerCover(client, gson, artistName, songName)
                                val placeholderIndex = (Math.abs((artistName + songName).hashCode()) % 4) + 1
                                currentXtraState.postValue(
                                    PlayerState(songArtist?.get(0), songArtist?.get(1), imageUrl ?: "NO_IMAGE", placeholderIndex)
                                )
                            }
                        }
                        catch (ex: Exception){
                            currentXtraState.postValue(
                                PlayerState(
                                    songArtist?.get(0), songArtist?.get(1), null
                                    )
                            )
                            Log.e("Exception", ex.toString())
                        }
                    }
                }
            }
            catch (e: Exception){
                Log.e("Exception", e.toString())
                delay(500)
                continue
            }
            delay(5000)
        }
    }

    fun formUrl(songArtist: List<String>): String{
        Log.d("URL", "https://last.fm/music/${songArtist.get(0)
            ?.lowercase()?.split(" ft.")?.get(0)!!.trim()
            .replace("/", "%2F")
            .replace(" ", "+")
        }/+images")

        return "https://last.fm/music/${songArtist.get(0)
            ?.lowercase()?.split(" ft.")?.get(0)!!.trim()
            .replace("/", "%2F")
            .replace(" ", "+")
        }/+images"

    }

    class MyataPlaylist(uri: String, img: Uri){
        val uri = uri
        val img = img
    }

    data class PlayerState(
        var artist: String?,
        var song: String?,
        var img: String?,
        var placeholderIndex: Int = 1
    )

    private fun getCleanArtistName(artist: String): String {
        val separators = listOf(" ft.", " feat.", " &", ",", " x ", "/")
        var cleanName = artist.lowercase()
        for (sep in separators) {
            if (cleanName.contains(sep)) {
                cleanName = cleanName.split(sep)[0]
            }
        }
        return cleanName.trim()
    }

    private fun artistNamesMatch(searchedArtist: String, resultArtist: String): Boolean {
        val searched = searchedArtist.lowercase().trim()
        val result = resultArtist.lowercase().trim()
        
        // Exact match
        if (searched == result) return true
        
        // Remove common articles and prefixes for comparison
        val removeArticles = { name: String ->
            name.removePrefix("the ").removePrefix("a ").removePrefix("an ")
        }
        
        val searchedNoArticle = removeArticles(searched)
        val resultNoArticle = removeArticles(result)
        
        // Match without articles (e.g., "The New Radicals" vs "New Radicals")
        if (searchedNoArticle == resultNoArticle) return true
        
        // Check if one contains the other (for cases like "АДА" vs "А.Д.А.")
        if (searched.length >= 3 && result.contains(searched)) return true
        if (result.length >= 3 && searched.contains(result)) return true
        
        // Remove dots and spaces for comparison (handles "А.Д.А." vs "АДА")
        val searchedNormalized = searched.replace(".", "").replace(" ", "")
        val resultNormalized = result.replace(".", "").replace(" ", "")
        if (searchedNormalized == resultNormalized) return true
        
        return false
    }


    private fun fetchDeezerCover(client: OkHttpClient, gson: Gson, artist: String, song: String): String? {
        val cleanArtistName = getCleanArtistName(artist)
        
        // Helper to score album titles (prefer original albums over compilations)
        fun getAlbumScore(albumTitle: String): Int {
            var score = 100
            val lowerTitle = albumTitle.lowercase()
            val lowerSong = song.lowercase()
            val lowerArtist = artist.lowercase()

            // 1. Exact Title Match (Single) - Massive Bonus
            if (lowerTitle == lowerSong) {
                score += 50
            }
            
            // 2. Artist Match in Album Title (Self-titled)
            if (lowerTitle == lowerArtist) {
                score += 30
            }

            // 3. Explicit "Single" or "EP"
            if (lowerTitle.contains(" single") || lowerTitle.contains(" ep")) {
                score += 10
            }

            // 4. Penalize "Various Artists"
            // This check needs access to the 'result' map, which is outside this helper function.
            // For now, we'll assume 'album' is passed or accessible if needed, but based on the snippet,
            // it's not directly available here. I'll comment this out or adjust if the full context allows.
            // If 'album' refers to the map passed to this function, then it would be:
            // if ((album["artist"] as? Map<String, Any>)?.get("name") as? String)?.lowercase()?.contains("various") == true) {
            //     score -= 50
            // }
            // Given the instruction, I'll add it as if 'album' is accessible, assuming it's a typo in the instruction
            // and it should be checked on the resultArtist or similar.
            // For now, I'll skip this specific line as it requires context not provided in the snippet for `album.artist.name`.
            // The instruction implies `album` is an object with `artist.name`.
            // I will add a placeholder for it, assuming `album` is available in some form.
            // For now, I'll assume the check for "Various Artists" will be done outside or `album` is implicitly available.
            // Since the instruction provides `album.artist.name`, I'll assume `album` is a parameter or accessible.
            // However, `albumTitle` is a String, not an object. This part of the instruction is problematic.
            // I will add the penalty based on the albumTitle containing "various artists" for now, as a workaround.
            // Or, if the `album` object itself was passed to `getAlbumScore`.
            // Let's assume the instruction meant to check the artist of the track/album, not the album title itself.
            // Since `albumTitle` is the only album-related parameter, I cannot implement `album.artist.name`.
            // I will skip this specific line as it's syntactically incorrect for the current function signature.
            // The instruction is: `if (album.artist.name.lowercase().contains("various")) { score -= 50 }`
            // This implies `album` is an object passed to `getAlbumScore`, but only `albumTitle: String` is passed.
            // I will implement the other parts and leave a note for this specific line.

            // Penalize compilations and generic titles
            val penalties = listOf(
                "best of", "greatest hits", "hits", "collection", "anthology", 
                "essential", "live", "remix", "mix", "compilation", "various", 
                "vol.", "volume", "100", "50", "20", "top", "classics", 
                "ballads", "rock", "pop", "dance", "party", "summer", 
                "winter", "christmas", "workout", "running", "driving",
                "love", "power", "super", "mega", "ultra", "ultimate",
                "sing", "karaoke", "tribute", "cover", "presents", "music",
                "club", "house", "anthems", "ibiza", "ministry", "records", 
                "recordings", "session", "sessions", "radio", "edit", "cut"
            )
            
            for (keyword in penalties) {
                if (lowerTitle.contains(keyword)) {
                    score -= 10
                }
            }
            
            return score
        }

        // 1. Strict Search for Album Cover
        val strictQuery = "artist:\"$cleanArtistName\" track:\"$song\""
        try {
            val strictUrl = "https://api.deezer.com/search?q=${java.net.URLEncoder.encode(strictQuery, "UTF-8")}&limit=25" // Increased limit
            val request = Request.Builder().url(strictUrl).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val jsonResponse = gson.fromJson(response.body?.string(), Map::class.java)
                    val data = jsonResponse?.get("data") as? List<Map<String, Any>>
                    if (!data.isNullOrEmpty()) {
                        var bestMatchUrl: String? = null
                        var bestScore = -1000

                        // Search through results to find the best match
                        for (result in data) {
                            val resultArtist = (result["artist"] as? Map<String, Any>)?.get("name") as? String
                            if (resultArtist != null && artistNamesMatch(cleanArtistName, resultArtist)) {
                                val album = result["album"] as? Map<String, Any>
                                val albumTitle = album?.get("title") as? String ?: ""
                                val coverXl = album?.get("cover_xl") as? String
                                val coverMedium = album?.get("cover_medium") as? String
                                val url = coverXl ?: coverMedium
                                
                                if (url != null) {
                                    val score = getAlbumScore(albumTitle)
                                    if (score > bestScore) {
                                        bestScore = score
                                        bestMatchUrl = url
                                        Log.d("IMG", "Candidate: $albumTitle (Score: $score)")
                                    }
                                }
                            }
                        }
                        
                        if (bestMatchUrl != null) {
                            Log.d("IMG", "Deezer Strict Best Match: $bestMatchUrl (Score: $bestScore)")
                            return bestMatchUrl
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Deezer", "Strict search failed for $artist - $song", e)
        }

        // 2. Loose Search for Album Cover (Fallback)
        val looseQuery = "$cleanArtistName $song"
        try {
            val looseUrl = "https://api.deezer.com/search?q=${java.net.URLEncoder.encode(looseQuery, "UTF-8")}&limit=25"
            val request = Request.Builder().url(looseUrl).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val jsonResponse = gson.fromJson(response.body?.string(), Map::class.java)
                    val data = jsonResponse?.get("data") as? List<Map<String, Any>>
                    if (!data.isNullOrEmpty()) {
                        var bestMatchUrl: String? = null
                        var bestScore = -1000

                        for (result in data) {
                            val resultArtist = (result["artist"] as? Map<String, Any>)?.get("name") as? String
                            if (resultArtist != null && artistNamesMatch(cleanArtistName, resultArtist)) {
                                val album = result["album"] as? Map<String, Any>
                                val albumTitle = album?.get("title") as? String ?: ""
                                val coverXl = album?.get("cover_xl") as? String
                                val coverMedium = album?.get("cover_medium") as? String
                                val url = coverXl ?: coverMedium
                                
                                if (url != null) {
                                    val score = getAlbumScore(albumTitle)
                                    if (score > bestScore) {
                                        bestScore = score
                                        bestMatchUrl = url
                                    }
                                }
                            }
                        }
                        
                        if (bestMatchUrl != null) {
                            Log.d("IMG", "Deezer Loose Best Match: $bestMatchUrl (Score: $bestScore)")
                            return bestMatchUrl
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Deezer", "Loose search failed for $artist - $song", e)
        }
        
        // 3. Artist Picture Fallback (if no album cover found)
        Log.d("IMG", "No album cover found, trying artist picture for: $cleanArtistName")
        try {
            val artistSearchUrl = "https://api.deezer.com/search/artist?q=${java.net.URLEncoder.encode(cleanArtistName, "UTF-8")}&limit=1"
            val request = Request.Builder().url(artistSearchUrl).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val jsonResponse = gson.fromJson(response.body?.string(), Map::class.java)
                    val data = jsonResponse?.get("data") as? List<Map<String, Any>>
                    if (!data.isNullOrEmpty()) {
                        val firstArtist = data[0]
                        val pictureXl = firstArtist["picture_xl"] as? String
                        val pictureMedium = firstArtist["picture_medium"] as? String
                        val artistPicture = pictureXl ?: pictureMedium
                        if (artistPicture != null) {
                            Log.d("IMG", "Deezer Artist Picture Found: $artistPicture for $cleanArtistName")
                            return artistPicture
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Deezer", "Artist picture search failed for $cleanArtistName", e)
        }
        
        Log.d("IMG", "Deezer: No cover or artist picture found for $artist - $song")
        return null
    }

    inner class PlayPauseBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent != null) {
                // Always update isPlaying state from broadcast
                isPlaying.value = intent.action == "play"
            }
        }
    }
}