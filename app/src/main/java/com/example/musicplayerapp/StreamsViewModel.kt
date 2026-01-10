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




import androidx.lifecycle.SavedStateHandle

class StreamsViewModel(app: Application, private val savedStateHandle: SavedStateHandle):AndroidViewModel(app) {

    // Spotify API credentials
    // Spotify API (Removed) -> iTunes API (No auth required)
    // private val spotifyClientId = "..." 
    // private val spotifyClientSecret = "..."

    
    var currentMyataState = MutableLiveData<PlayerState?>()
    var currentGoldState = MutableLiveData<PlayerState?>()
    var currentXtraState = MutableLiveData<PlayerState?>()
    var isPlaying = MutableLiveData<Boolean>()
    var isBuffering = MutableLiveData<Boolean>()
    var isInSplitMode = MutableLiveData<Boolean>()
    var playlistList = MutableLiveData<MutableList<MyataPlaylist>>()
    var lastObservedStream: String? = null
    
    // Use SavedStateHandle for persistence
    var currentStreamLive = savedStateHandle.getLiveData<String>("stream_live", "myata")
    
    var currentFragmentLiveData = MutableLiveData<String>()
    @SuppressLint("StaticFieldLeak")
    private val context = getApplication<Application>().applicationContext
    var isUIActive = true
    var lastAnimatedImageUrl: String? = null  // Track URL of last animated cover art
    var cachedTopInset: Int? = null // Cache for window insets to prevent UI jumping

    private val client = UnsafeNetModule.getUnsafeOkHttpClient()

    //problem why we need this is service cannot launch fragment, it can only recreate activity
    var ifNeedToNavigateStraightToPlayer = false
    //To avoid reaction on swich stream pause
    var ifNeedToListenReciever = true



    init {
        isPlaying.value = false
        isBuffering.value = false
        isInSplitMode.value = false
        currentStreamLive.value = "myata"

        val receiver1 = PlayPauseBroadcastReceiver()
        val receiver2 = MetadataBroadcastReceiver()
        context?.let {
            LocalBroadcastManager.getInstance(it).registerReceiver(receiver1,
                IntentFilter("play")
            )
            LocalBroadcastManager.getInstance(it).registerReceiver(receiver1,
                IntentFilter("pause")
            )
            LocalBroadcastManager.getInstance(it).registerReceiver(receiver1,
                IntentFilter("buffering")
            )
            val filter = IntentFilter("metadata_update")
            LocalBroadcastManager.getInstance(it).registerReceiver(receiver2, filter)
        }

        // Pre-warm not needed for iTunes (public API)
        // val token = ...

        startMetadataUpdates()
        getPlaylists()
        currentMyataState.value = PlayerState("YOU ARE LISTENING", "RADIO MYATA", null)
        currentGoldState.value = PlayerState("YOU ARE LISTENING", "RADIO MYATA", null)
        currentXtraState.value = PlayerState("YOU ARE LISTENING", "RADIO MYATA", null)

        // Sync initial state with service
        context.startService(Intent(context, MediaPlayerService::class.java).apply {
            putExtra("ACTION", "get_status")
        })
    }



    inner class PlayPauseBroadcastReceiver : BroadcastReceiver() {
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())
        private var bufferingResetRunnable: Runnable? = null
        
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent != null) {
                when(intent.action) {
                    "play" -> {
                        isPlaying.value = true
                        // Debounce: delay buffering reset to allow stream to stabilize
                        bufferingResetRunnable?.let { handler.removeCallbacks(it) }
                        bufferingResetRunnable = Runnable {
                            isBuffering.value = false
                        }
                        handler.postDelayed(bufferingResetRunnable!!, 500)
                    }
                    "pause" -> {
                        isPlaying.value = false
                        // Cancel any pending buffering reset
                        bufferingResetRunnable?.let { handler.removeCallbacks(it) }
                        isBuffering.value = false
                    }
                    "buffering" -> {
                        // Cancel any pending buffering reset - stream is buffering again
                        bufferingResetRunnable?.let { handler.removeCallbacks(it) }
                        isBuffering.value = true
                    }
                }
            }
        }
    }

    inner class MetadataBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent != null && intent.action == "metadata_update") {
                val artist = intent.getStringExtra("artist")
                val song = intent.getStringExtra("song")
                val stream = intent.getStringExtra("stream")
                val albumArtUrl = intent.getStringExtra("albumArtUrl")
                
                if (stream != null) {
                    when(stream) {
                        "myata" -> {
                            var current = currentMyataState.value
                            if (current == null) current = PlayerState(artist, song, albumArtUrl)
                            else {
                                current.artist = artist
                                current.song = song
                                if (albumArtUrl != null) current.img = albumArtUrl
                            }
                            currentMyataState.postValue(current)
                        }
                        "gold" -> {
                            var current = currentGoldState.value
                            if (current == null) current = PlayerState(artist, song, albumArtUrl)
                            else {
                                current.artist = artist
                                current.song = song
                                if (albumArtUrl != null) current.img = albumArtUrl
                            }
                            currentGoldState.postValue(current)
                        }
                        "myata_hits" -> {
                            var current = currentXtraState.value
                            if (current == null) current = PlayerState(artist, song, albumArtUrl)
                            else {
                                current.artist = artist
                                current.song = song
                                if (albumArtUrl != null) current.img = albumArtUrl
                            }
                            currentXtraState.postValue(current)
                        }
                    }
                    
                    // Trigger a fetch if we don't have art (and it wasn't passed)
                    if (artist != null && song != null && (albumArtUrl == null || albumArtUrl == "NO_IMAGE")) {
                        viewModelScope.launch {
                             try {
                                 // Only fetch if we really need it? 
                                 // Actually fetchArtistImage checks cache so it's cheap to call again
                                 val images = fetchArtistImage(artist, song)
                                 val fetchedCover = images.first
                                 
                                 if (fetchedCover != null) {
                                     val fetchedBackground = images.second
                                     val placeholderIndex = (Math.abs((artist + song).hashCode()) % 4) + 1
                                     
                                     // Create FRESH state to avoid race conditions with LiveData.value
                                     val newState = PlayerState(artist, song, fetchedCover, fetchedBackground, placeholderIndex)

                                     // Update again with image
                                     when(stream) {
                                         "myata" -> currentMyataState.postValue(newState)
                                         "gold" -> currentGoldState.postValue(newState)
                                         "myata_hits" -> currentXtraState.postValue(newState)
                                     }
                                 }
                             } catch (e: Exception) {
                                 Log.e("MetadataReceiver", "Failed to fetch art", e)
                             }
                        }
                    }
                }
            }
        }
    }

    fun getPlaylists() = viewModelScope.launch {
        while (true){
            try{
                if (requestMyata()) {
                    // Wait 1 hour before updating playlists again
                    delay(3600000) // 1 hour = 3600000 ms
                } else {
                    delay(1000) // Retry after 1 second
                }
            }
            catch (e:Exception){
                Log.e("Exception: ",e.toString())
                delay(1000) // On error, retry after 1 second
                continue
            }
        }
    }

    suspend fun requestMyata(): Boolean = withContext(Dispatchers.IO){

        try {
            val url = URL("https://radiomyata.ru/covers/playlists.txt")

            val connection: HttpURLConnection = url.openConnection() as HttpsURLConnection
            val lastModified = connection.lastModified // Get file modification time
            
            val br = BufferedReader(InputStreamReader(connection.getInputStream()))
            var lines: MutableList<MyataPlaylist> = mutableListOf()
            val wholeText = br.readText().split("\n\n","\r\r")
            br.close()
            Log.d("LINE", wholeText.toString())
            for (str in wholeText){
                Log.d("SOSI", str.split(" — ", " - ")[0])
                if(!str.isBlank()) {
                    val parts = str.split(" — ", " - ")
                    if (parts.size >= 2) {
                        val imgUrl = parts[0].trim(' ')
                        val name = parts[1].trim(' ')
                        // Use Last-Modified time as version to cache effectively until file updates
                        val version = if (lastModified > 0) lastModified else System.currentTimeMillis()
                        val urlWithVersion = "$imgUrl?v=$version"
                        lines.add(MyataPlaylist(name, Uri.parse(urlWithVersion)))
                    }
                }
            }
            Log.d("LINES", lines.toString())
            playlistList.postValue(lines)
            return@withContext true
        } catch (e: IOException) {
            Log.e("IOexception", "Myata request exception: " + e.getLocalizedMessage())
            e.printStackTrace()
            return@withContext false
        }
    }

    fun startMetadataUpdates() = viewModelScope.launch {
        while (true) {
            updateAllStreamsMetadata()
            delay(15000) // Update every 15 seconds
        }
    }

    fun triggerMetadataUpdate() {
        viewModelScope.launch {
            updateAllStreamsMetadata()
        }
    }
    
    fun refreshPlayerStatus() {
        context?.let {
            it.startService(Intent(it, MediaPlayerService::class.java).apply {
                putExtra("ACTION", "get_status")
            })
            // Also force metadata update from API just in case
            triggerMetadataUpdate() 
        }
    }

    private suspend fun updateAllStreamsMetadata() = withContext(Dispatchers.IO) {
        try {
            // Use the JSON API
            val url = "https://radiomyata.ru/api_all_tracks.php?v=${System.currentTimeMillis()}"
            val request = Request.Builder().url(url).build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e("Metadata", "API request failed: ${response.code}")
                    return@use
                }

                val jsonBody = response.body?.string() ?: return@use
                val apiResponse = Gson().fromJson(jsonBody, Map::class.java)
                val data = apiResponse["data"] as? Map<String, Any> ?: return@use

                processStreamUpdate("myata", data["myata"] as? Map<String, Any>, currentMyataState)
                processStreamUpdate("gold", data["gold"] as? Map<String, Any>, currentGoldState)
                processStreamUpdate("myata_hits", data["myata_hits"] as? Map<String, Any>, currentXtraState)
            }
        } catch (e: Exception) {
            Log.e("Metadata", "Error updating metadata: ${e.message}")
        }
    }

    private suspend fun processStreamUpdate(
        streamKey: String,
        streamData: Map<String, Any>?,
        liveData: MutableLiveData<PlayerState?>
    ) {
        if (streamData == null) return

        val artist = streamData["artist"] as? String ?: ""
        val song = streamData["track"] as? String ?: ""
        
        // Logic to update state and service
        val currentSong = liveData.value?.song
        val currentImg = liveData.value?.img
        
        // Update if track changed OR if we are active but have no image (retry fetch after background)
        if (currentSong != song || (isUIActive && (currentImg == "NO_IMAGE" || currentImg == null))) {
            
            // 1. Update Service if this stream is currently playing
            // Only update service if track ACTUALLY changed to avoid spamming "switch_track" on simple image retry
            if (currentStreamLive.value == streamKey && isPlaying.value == true && currentSong != song) {
                withContext(Dispatchers.Main) {
                    context.startService(Intent(context, MediaPlayerService::class.java).also {
                        it.putExtra("ACTION", "switch_track")
                        it.putExtra("SONG", song)
                        it.putExtra("ARTIST", artist)
                    })
                }
            }

            // 2. Fetch images
            var cover: String? = null
            var background: String? = null
            
            try {
                if (isUIActive) {
                    // This uses cache internally, so it's safe to call repeatedly
                    val images = fetchArtistImage(artist, song)
                    cover = images.first
                    background = images.second
                }
            } catch (e: Exception) {
                Log.e("Metadata", "Image fetch failed for $streamKey", e)
            }

            // 3. Post value
            val placeholderIndex = (Math.abs((artist + song).hashCode()) % 4) + 1
            liveData.postValue(
                PlayerState(artist, song, cover ?: "NO_IMAGE", background, placeholderIndex)
            )
        }
    }

    private fun parseXmlMetadata(xml: String): Pair<String, String>? {
        try {
            val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            val builder = factory.newDocumentBuilder()
            val inputSource = org.xml.sax.InputSource(java.io.StringReader(xml))
            val doc = builder.parse(inputSource)
            
            val elements = doc.getElementsByTagName("ELEM")
            
            for (i in 0 until elements.length) {
                val element = elements.item(i) as org.w3c.dom.Element
                if (element.getAttribute("STATUS") == "playing") {
                    val artist = element.getElementsByTagName("ARTIST").item(0)?.textContent?.trim() ?: ""
                    val name = element.getElementsByTagName("NAME").item(0)?.textContent?.trim() ?: ""
                    return Pair(artist, name)
                }
            }
        } catch (e: Exception) {
            Log.e("Metadata", "XML Parsing error", e)
        }
        return null
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
        var backgroundImg: String? = null,
        var placeholderIndex: Int = 1
    )

    // Removed getSpotifyAccessToken as iTunes API does not require authentication

    private val artistImageCache = java.util.Collections.synchronizedMap(java.util.HashMap<String, Pair<String?, String?>>())

    private suspend fun fetchArtistImage(artist: String, track: String): Pair<String?, String?> {
        val cleanArtist = getCleanArtistName(artist)
        // Remove (...) and [...] content, and standalone "RMX"/"REMIX"
        val cleanTrack = track
            .replace(Regex("\\(.*?\\)|\\[.*?\\]"), "")
            .replace(Regex("(?i)\\b(RMX|REMIX)\\b"), "")
            .trim()
        
        // Cache key should include both artist and track
        val cacheKey = "$cleanArtist|$cleanTrack"
        // Check cache first
        if (artistImageCache.containsKey(cacheKey)) {
            return artistImageCache[cacheKey]!!
        }

        return withContext(Dispatchers.IO) {
            var albumUrl: String? = null
            var artistUrl: String? = null
            
            try {
                // Search Query: Replace & with space to be more flexible
                val queryArtist = cleanArtist.replace("&", " ")
                val query1 = "$queryArtist $cleanTrack"
                Log.d("iTunes_Search", "Stage 1: $query1")
                
                // Pass original cleanArtist (with &) for strict validation
                albumUrl = executeItunesSearch(query1, cleanArtist, cleanTrack)
            
            if (albumUrl == null) {
                // Stage 2: Search with Full Artist + Clean Track (if different)
                // Useful if "feat." contained important info or cleaning was too aggressive
                if (artist != cleanArtist) {
                    val query2 = "$artist $cleanTrack"
                    Log.d("iTunes_Search", "Stage 2: $query2")
                    // We still pass cleanArtist/cleanTrack for validation, or maybe relax validation?
                    // Let's relax validation slightly for the second attempt if needed, 
                    // but usually matching 'cleanTrack' is a strict requirement.
                    albumUrl = executeItunesSearch(query2, cleanArtist, cleanTrack)
                }
            }
            
            if (albumUrl == null) {
                 Log.d("iTunes_Search", "No album art found after retries.")
            } else {
                 Log.d("iTunes_Image", "Album Art Found: $albumUrl")
            }
            
        } catch (e: Exception) {
            Log.e("iTunes_Search", "Error: ${e.message}")
        }

        if (albumUrl == null) {
              Log.d("iTunes_Search", "No album art found, trying Deezer for Artist Image...")
              artistUrl = fetchArtistImageFromDeezer(cleanArtist)
              if (artistUrl != null) {
                  // If we found an artist image, use it as the "album art" for display purposes if legal/desired?
                  // The user asked "can we pull artist photo?". 
                  // In StreamViewModel, we return Pair(albumUrl, artistUrl).
                  // But usually albumUrl is what is displayed in the square box.
                  // So we should assign artistUrl to albumUrl so it shows up in the player?
                  // Yes, "can we pull artist photo" implies showing it INSTEAD of the missing album art.
                  albumUrl = artistUrl
                  Log.d("Deezer_Search", "Found artist image: $albumUrl")
              } else {
                  // Fallback 2: Last.fm Scrape
                  Log.d("LastFM_Scrape", "Deezer failed/blocked. Trying Last.fm scrape...")
                  artistUrl = fetchArtistImageFromLastFm(cleanArtist)
                  if (artistUrl != null) {
                      albumUrl = artistUrl
                      Log.d("LastFM_Scrape", "Found artist image: $albumUrl")
                  }
              }
        }
        
        // Cache the result
        val result = Pair(albumUrl, artistUrl)
        artistImageCache[cacheKey] = result
        result
    }
    }
    
    private fun fetchArtistImageFromDeezer(artist: String): String? {
        try {
            val encodedTerm = java.net.URLEncoder.encode(artist, "UTF-8")
            val url = "https://api.deezer.com/search/artist?q=$encodedTerm"
            
            val request = Request.Builder().url(url).build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                
                val bodyContent = response.body?.string() ?: return null
                val json = Gson().fromJson(bodyContent, Map::class.java)
                val data = json["data"] as? List<Map<String, Any>>
                
                if (!data.isNullOrEmpty()) {
                    val simpleExpected = simplifyString(artist)
                    
                    for (item in data) {
                        val name = item["name"] as? String ?: continue
                        if (simplifyString(name) == simpleExpected) {
                            val pic = item["picture_xl"] as? String ?: item["picture_big"] as? String
                            if (pic != null && !pic.contains("/artist//")) {
                                return pic
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Deezer_Search", "Error: ${e.message}")
        }
        return null
    }

    private fun fetchArtistImageFromLastFm(artist: String): String? {
        try {
            // "Matt Maeson" -> "Matt+Maeson"
            val finalArtist = java.net.URLEncoder.encode(artist, "UTF-8")
            val url = "https://www.last.fm/music/$finalArtist"
            
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .build()
                
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val html = response.body?.string() ?: return null
                
                // Regex for og:image
                val regex = Regex("property=[\"']og:image[\"']\\s+content=[\"']([^\"']+)[\"']|content=[\"']([^\"']+)[\"']\\s+property=[\"']og:image[\"']")
                val match = regex.find(html)
                
                if (match != null) {
                   val url1 = match.groups[1]?.value
                   val url2 = match.groups[2]?.value
                   val result = url1 ?: url2
                   
                   if (result != null && !result.contains("default_artist") && !result.contains("star_")) {
                       return result
                   }
                }
            }
        } catch (e: Exception) {
            Log.e("LastFM_Scrape", "Error: ${e.message}")
        }
        return null
    }


    private fun executeItunesSearch(term: String, expectedArtist: String, expectedTrack: String): String? {
        try {
            val encodedTerm = java.net.URLEncoder.encode(term, "UTF-8")
            val url = "https://itunes.apple.com/search?term=$encodedTerm&media=music&entity=song&limit=5"
            
            val request = Request.Builder()
                .url(url)
                .build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                
                val bodyContent = response.body?.string() ?: return null
                val json = Gson().fromJson(bodyContent, Map::class.java)
                val results = json["results"] as? List<Map<String, Any>>
                
                if (!results.isNullOrEmpty()) {
                    val simpleExpectedArtist = simplifyString(expectedArtist)
                    val simpleExpectedTrack = simplifyString(expectedTrack)
                    
                    val validMatches = mutableListOf<Map<String, Any>>()
                    
                    for (item in results) {
                        val trackName = item["trackName"] as? String
                        val artistName = item["artistName"] as? String
                        val artworkUrl = item["artworkUrl100"] as? String 
                        
                        if (trackName != null && artistName != null && artworkUrl != null) {
                            val simpleArtistName = simplifyString(artistName)
                            val simpleTrackName = simplifyString(trackName)
                            
                            // Check Artist: match in artist field OR in track field (for features) using WORD BOUNDARIES
                            val matchArtist = isWordMatch(simpleArtistName, simpleExpectedArtist) || 
                                              isWordMatch(simpleTrackName, simpleExpectedArtist)
                                              
                            // Check Track: standard match
                            val matchTrack = isWordMatch(simpleTrackName, simpleExpectedTrack)
                            
                            if (matchArtist && matchTrack) {
                                validMatches.add(item)
                            }
                        }
                    }
                    
                    if (validMatches.isNotEmpty()) {
                        // Sort by Priority (Collection Name) -> Then by original iTunes index (preserved by stable sort)
                        // Higher score = Higher priority.
                        val bestMatch = validMatches.maxByOrNull { item ->
                            val collectionName = item["collectionName"] as? String ?: ""
                            calculateAlbumPriority(collectionName)
                        }
                        
                        return bestMatch?.get("artworkUrl100")?.toString()?.replace("100x100bb", "600x600bb")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("iTunes_Search", "Request Error: ${e.message}")
        }
        return null
    }

    private fun calculateAlbumPriority(collectionName: String): Int {
        val lowerName = collectionName.lowercase()
        // Penalize compilations and "best of"
        if (lowerName.contains("greatest hits") || 
            lowerName.contains("best of") || 
            lowerName.contains("essential") || 
            lowerName.contains("anthology") ||
            lowerName.contains("collection") || 
            lowerName.contains("compilation")) {
            return 0 // Low Priority
        }
        return 1 // High Priority (Album/Single)
    }
    
    // Helper for regex word boundary check
    private fun isWordMatch(text: String, word: String): Boolean {
        if (word.isEmpty()) return true
        return try {
             // \bWORD\b check
             val pattern = "\\b${java.util.regex.Pattern.quote(word)}\\b".toRegex()
             pattern.containsMatchIn(text)
        } catch (e: Exception) {
             text.contains(word) // fallback
        }
    }

    private fun simplifyString(input: String): String {
        // NFD normalization to separate accents
        val nfd = java.text.Normalizer.normalize(input, java.text.Normalizer.Form.NFD)
        // Remove diacritical marks
        val pattern = java.util.regex.Pattern.compile("\\p{InCombiningDiacriticalMarks}+")
        var clean = pattern.matcher(nfd).replaceAll("")
        
        // Manual replacements for common issues
        clean = clean.replace("Ø", "O", ignoreCase = true)
                     .replace("ø", "o", ignoreCase = true)
                     .replace("Æ", "AE", ignoreCase = true)
                     .replace("æ", "ae", ignoreCase = true)
        
        // Remove connectors: feat, ft, vs, and, &
        val connectors = Regex("(?i)\\b(feat\\.|ft\\.|vs\\.|feat|ft|vs|and|featuring|presents|pres\\.)\\b|&")
        clean = connectors.replace(clean, " ")

        // Replace non-alphanumeric with SPACE (preserve word boundaries)
        clean = clean.replace(Regex("[^a-zA-Z0-9]"), " ").lowercase()
        
        // Collapse spaces
        return clean.replace(Regex("\\s+"), " ").trim()
    }

    private suspend fun fetchArtistImageFallback(artist: String, track: String): Pair<String?, String?> {
        val cleanArtist = getCleanArtistName(artist)
        // Fallback logic empty
        return Pair(null, null)
    }

    private fun getCleanArtistName(artist: String): String {
        // 1. Split by common separators BUT keep & as part of band name
        // Added more separators and full caps support (FT., FEAT.)
        var cleaned = artist.split(Regex(" ,| feat| feat\\.| vs| ft| ft\\.| and |/|\\(|\\[| featuring", RegexOption.IGNORE_CASE))[0].trim()
        
        // 2. Remove "Pres." or "Presents" at start
        cleaned = cleaned.replace(Regex("^(pres\\.|presents)\\s+", RegexOption.IGNORE_CASE), "")
        
        // 3. Remove any remaining trailing special chars or whitespace
        return cleaned.trim()
    }
}