package com.example.musicplayerapp.data

import android.util.Log
import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/**
 * Repository for fetching album/artist artwork from various sources.
 * This is the SINGLE source of truth for artwork in the app.
 * 
 * Sources (in priority order):
 * 1. iTunes (album art)
 * 2. Deezer (artist image)
 * 3. Last.fm (scraping + API)
 */
class ArtworkRepository(private val httpClient: OkHttpClient) {
    
    companion object {
        private const val LAST_FM_API_KEY = "4361b8f101111d4e0220aa025a7cc3e1"
    }

    // In-memory cache: key = "artist|track"
    private val cache = ConcurrentHashMap<String, ArtworkResult>()
    
    data class ArtworkResult(
        val coverUrl: String?,
        val backgroundUrl: String? = null
    )

    /**
     * Main entry point: fetches artwork for a given artist and track.
     * Uses cache if available. Returns null URLs if nothing found.
     */
    suspend fun fetchArtwork(artist: String, track: String): ArtworkResult {
        val cleanArtist = getCleanArtistName(artist)
        val cleanTrack = track
            .replace(Regex("\\(.*?\\)|\\[.*?\\]"), "")
            .replace(Regex("(?i)\\b(RMX|REMIX)\\b"), "")
            .trim()

        val cacheKey = "$cleanArtist|$cleanTrack"
        
        // Return cached result if available
        cache[cacheKey]?.let { return it }

        var resultUrl: String? = null

        try {
            // Stage 1: iTunes search with clean artist + track
            val queryArtist = cleanArtist.replace("&", " ")
            val query1 = "$queryArtist $cleanTrack"
            Log.d("ArtworkRepo", "Stage 1: $query1")
            resultUrl = executeItunesSearch(query1, cleanArtist, cleanTrack)

            // Stage 2: Full artist + clean track
            if (resultUrl == null && artist != cleanArtist) {
                val query2 = "$artist $cleanTrack"
                Log.d("ArtworkRepo", "Stage 2: $query2")
                resultUrl = executeItunesSearch(query2, artist, cleanTrack)
            }

            // Stage 3: Track only (fallback for compilations)
            if (resultUrl == null && cleanTrack.length >= 4) {
                Log.d("ArtworkRepo", "Stage 3 (Track only): $cleanTrack")
                resultUrl = executeItunesSearch(cleanTrack, artist, cleanTrack)
            }

        } catch (e: Exception) {
            Log.e("ArtworkRepo", "iTunes search error", e)
        }

        // Deezer fallback (artist image)
        if (resultUrl == null) {
            Log.d("ArtworkRepo", "Trying Deezer for artist image...")
            resultUrl = fetchArtistImageFromDeezer(cleanArtist)
        }

        // Last.fm fallback (scrape)
        if (resultUrl == null) {
            Log.d("ArtworkRepo", "Trying Last.fm scrape...")
            resultUrl = fetchArtistImageFromLastFm(cleanArtist, cleanTrack)
        }

        val result = ArtworkResult(coverUrl = resultUrl)
        cache[cacheKey] = result
        return result
    }

    // ==================== iTunes ====================

    private fun executeItunesSearch(term: String, expectedArtist: String, expectedTrack: String): String? {
        val encodedTerm = java.net.URLEncoder.encode(term, "UTF-8")
        val url = "https://itunes.apple.com/search?term=$encodedTerm&media=music&entity=song&limit=20"

        val request = Request.Builder().url(url).build()

        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            
            val bodyContent = response.body?.string() ?: return@use null
            val json = Gson().fromJson(bodyContent, Map::class.java)
            @Suppress("UNCHECKED_CAST")
            val results = json["results"] as? List<Map<String, Any>> ?: return@use null

            if (results.isEmpty()) return@use null

            val simpleExpectedArtist = simplifyString(expectedArtist)
            val simpleExpectedTrack = simplifyString(expectedTrack)
            val validMatches = mutableListOf<Map<String, Any>>()

            for (item in results) {
                val trackName = item["trackName"] as? String ?: continue
                val artistName = item["artistName"] as? String ?: continue
                val artworkUrl = item["artworkUrl100"] as? String ?: continue

                val simpleArtistName = simplifyString(artistName)
                val simpleTrackName = simplifyString(trackName)

                val matchArtist = isFuzzyMatch(simpleArtistName, simpleExpectedArtist) ||
                                  isWordMatch(simpleArtistName, simpleExpectedArtist) ||
                                  isWordMatch(simpleExpectedArtist, simpleArtistName) ||
                                  isTransliterationMatch(simpleArtistName, simpleExpectedArtist)

                val matchTrack = isWordMatch(simpleTrackName, simpleExpectedTrack) ||
                                 isTransliterationMatch(simpleTrackName, simpleExpectedTrack)

                // Exclude piano covers
                val isPianoCover = (simpleTrackName.contains("piano") || simpleArtistName.contains("piano")) &&
                                   !simpleExpectedTrack.contains("piano") &&
                                   !simpleExpectedArtist.contains("piano")

                if (matchArtist && matchTrack && !isPianoCover) {
                    validMatches.add(item)
                }
            }

            if (validMatches.isEmpty()) return@use null

            val bestMatch = validMatches.maxByOrNull { item ->
                val collectionName = item["collectionName"] as? String ?: ""
                val itemName = item["trackName"] as? String ?: ""
                calculateAlbumPriority(collectionName, itemName, expectedTrack)
            }
            
            bestMatch?.get("artworkUrl100")?.toString()?.replace("100x100bb", "600x600bb")
        }
    }

    private fun calculateAlbumPriority(collectionName: String, trackName: String?, expectedTrack: String?): Int {
        val lowerName = collectionName.lowercase()
        var score = 1

        // Penalize compilations
        if (lowerName.contains("greatest hits") || lowerName.contains("best of") ||
            lowerName.contains("essential") || lowerName.contains("anthology") ||
            lowerName.contains("collection") || lowerName.contains("compilation")) {
            return 0
        }

        // Penalize piano/tribute if not expected
        if (expectedTrack != null) {
            val lowerTrack = trackName?.lowercase() ?: ""
            val lowerExpected = expectedTrack.lowercase()
            if ((lowerTrack.contains("piano") || lowerTrack.contains("tribute") || lowerTrack.contains("cover")) &&
                !lowerExpected.contains("piano") && !lowerExpected.contains("tribute") && !lowerExpected.contains("cover")) {
                return -5
            }
        }

        // Boost singles
        if (lowerName.contains(" - single") || lowerName.contains(" (single)")) {
            score += 2
        }

        // Boost title tracks
        if (trackName != null) {
            val simpleTrack = simplifyString(trackName)
            val simpleColl = simplifyString(collectionName)
            if (simpleColl.contains(simpleTrack)) {
                score += 3
            }
        }

        return score
    }

    // ==================== Deezer ====================

    private fun fetchArtistImageFromDeezer(artist: String): String? {
        try {
            val encodedTerm = java.net.URLEncoder.encode(artist, "UTF-8")
            val url = "https://api.deezer.com/search/artist?q=$encodedTerm"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "MyataRadio/1.0")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null

                val bodyContent = response.body?.string() ?: return null
                val json = Gson().fromJson(bodyContent, Map::class.java)
                @Suppress("UNCHECKED_CAST")
                val data = json["data"] as? List<Map<String, Any>> ?: return null

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
        } catch (e: Exception) {
            Log.e("ArtworkRepo", "Deezer error: ${e.message}")
        }
        return null
    }

    // ==================== Last.fm ====================

    private fun fetchArtistImageFromLastFm(artist: String, track: String?): String? {
        try {
            val finalArtist = java.net.URLEncoder.encode(artist, "UTF-8")

            // Try track page first
            if (track != null) {
                val finalTrack = java.net.URLEncoder.encode(track, "UTF-8")
                val trackUrl = "https://www.last.fm/music/$finalArtist/_/$finalTrack"
                scrapeLastFmPage(trackUrl)?.let { return it }
            }

            // Try artist API
            tryFetchArtistImage(artist)?.let { return it }

            // Try splitting artist names
            val separators = listOf(" & ", " vs. ", " feat. ", " ft. ", " pres. ", " / ", " x ", ", ")
            for (sep in separators) {
                if (artist.contains(sep, ignoreCase = true)) {
                    val primaryArtist = artist.split(sep, ignoreCase = true)[0].trim()
                    if (primaryArtist.isNotEmpty() && primaryArtist != artist) {
                        tryFetchArtistImage(primaryArtist)?.let { return it }
                    }
                }
            }

        } catch (e: Exception) {
            Log.e("ArtworkRepo", "Last.fm error: ${e.message}")
        }
        return null
    }

    private fun scrapeLastFmPage(url: String): String? {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val html = response.body?.string() ?: return null

                val regex = Regex("property=[\"']og:image[\"']\\s+content=[\"']([^\"']+)[\"']|content=[\"']([^\"']+)[\"']\\s+property=[\"']og:image[\"']")
                val match = regex.find(html) ?: return null

                val result = match.groups[1]?.value ?: match.groups[2]?.value ?: return null

                // Filter out placeholder images
                if (result.contains("default_artist") || result.contains("star_") ||
                    result.contains("lastfm_logo") || result.contains("15d8133be114.png") ||
                    result.contains("4128a6eb29f94943c9d206c08e625904") ||
                    result.contains("2a96cbd8b46e442fc41c2b86b821562f") ||
                    result.contains("c6f59c1e5e7240a3a385ca9e9d268632")) {
                    return null
                }

                return result.replace("300x300", "600x600")
            }
        } catch (e: Exception) {
            Log.d("ArtworkRepo", "Scrape failed for $url")
        }
        return null
    }

    private fun tryFetchArtistImage(artistName: String): String? {
        try {
            val artistUrl = "http://ws.audioscrobbler.com/2.0/?method=artist.getInfo&api_key=$LAST_FM_API_KEY&artist=${java.net.URLEncoder.encode(artistName, "UTF-8")}&format=json"
            val request = Request.Builder().url(artistUrl).build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val json = response.body?.string() ?: return null
                val jsonObject = org.json.JSONObject(json)
                val artistObj = jsonObject.optJSONObject("artist") ?: return null
                val images = artistObj.optJSONArray("image") ?: return null

                for (i in images.length() - 1 downTo 0) {
                    val img = images.getJSONObject(i)
                    val imgUrl = img.optString("#text")
                    if (imgUrl.isNotEmpty() &&
                        !imgUrl.contains("2a96cbd8b46e442fc41c2b86b821562f") &&
                        !imgUrl.contains("4128a6eb29f94943c9d206c08e625904") &&
                        !imgUrl.contains("c6f59c1e5e7240a3a385ca9e9d268632")) {
                        return imgUrl
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore errors
        }
        return null
    }

    // ==================== String Helpers ====================

    private fun simplifyString(input: String): String {
        val nfd = java.text.Normalizer.normalize(input, java.text.Normalizer.Form.NFD)
        val pattern = java.util.regex.Pattern.compile("\\p{InCombiningDiacriticalMarks}+")
        var clean = pattern.matcher(nfd).replaceAll("")

        clean = clean.replace("Ø", "O", ignoreCase = true)
                     .replace("ø", "o", ignoreCase = true)
                     .replace("Æ", "AE", ignoreCase = true)
                     .replace("æ", "ae", ignoreCase = true)

        val connectors = Regex("(?i)\\b(feat\\.|ft\\.|vs\\.|feat|ft|vs|and|featuring|presents|pres\\.)\\b|&")
        clean = connectors.replace(clean, " ")

        clean = clean.replace(Regex("[^\\p{L}\\p{Nd}]"), " ").lowercase()
        return clean.replace(Regex("\\s+"), " ").trim()
    }

    private fun getCleanArtistName(artist: String): String {
        val splitRegex = Regex("[,|;*\\\\/]|(?i)\\b(feat\\.?|ft\\.?|vs\\.?|pres\\.?|with|featuring|x)\\b", RegexOption.IGNORE_CASE)
        var cleaned = artist.split(splitRegex)[0]

        cleaned = cleaned.replace(Regex("\\(.*?\\)|\\{.*?\\}|\\[.*?\\]"), "")

        // Normalize diacritics
        cleaned = cleaned.replace("ð", "d", ignoreCase = true)
            .replace("Ð", "D").replace("ø", "o", ignoreCase = true)
            .replace("Ø", "O").replace("æ", "ae", ignoreCase = true)
            .replace("Æ", "AE").replace("þ", "th", ignoreCase = true)
            .replace("Þ", "TH").replace("í", "i", ignoreCase = true)
            .replace("ï", "i", ignoreCase = true).replace("ü", "u", ignoreCase = true)
            .replace("ö", "o", ignoreCase = true).replace("ä", "a", ignoreCase = true)
            .replace("ë", "e", ignoreCase = true).replace("ñ", "n", ignoreCase = true)
            .replace("ß", "ss")

        cleaned = cleaned.replace(" & ", " and ").replace(" + ", " and ")
        cleaned = cleaned.replace(Regex("[^\\p{L}\\p{N}\\s&'\\+\\-,.]"), " ")

        return cleaned.trim()
    }

    private fun isFuzzyMatch(text1: String, text2: String): Boolean {
        val stopWords = setOf("the", "a", "an", "or", "of", "feat", "ft", "vs", "featuring", "presents", "pres", "with", "&")

        fun getTokens(text: String): Set<String> {
            return text.lowercase()
                .split(Regex("[\\s\\p{Punct}]+"))
                .filter { it.length > 1 && !stopWords.contains(it) }
                .toSet()
        }

        val tokens1 = getTokens(text1)
        val tokens2 = getTokens(text2)

        if (tokens1.isEmpty() || tokens2.isEmpty()) return false

        val intersection = tokens1.intersect(tokens2)
        val ratio1 = intersection.size.toDouble() / tokens1.size
        val ratio2 = intersection.size.toDouble() / tokens2.size

        return ratio1 >= 0.66 || ratio2 >= 0.66
    }

    private fun isWordMatch(text: String, word: String): Boolean {
        if (word.isEmpty()) return true
        return try {
            val pattern = "\\b${java.util.regex.Pattern.quote(word)}\\b".toRegex()
            pattern.containsMatchIn(text)
        } catch (e: Exception) {
            text.contains(word)
        }
    }

    private fun transliterate(text: String): String {
        val mapping = mapOf(
            'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e",
            'ж' to "zh", 'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m",
            'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u",
            'ф' to "f", 'х' to "kh", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "shch",
            'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya"
        )
        val sb = StringBuilder()
        for (char in text.lowercase()) {
            sb.append(mapping[char] ?: char)
        }
        return sb.toString()
    }

    private fun isTransliterationMatch(text1: String, text2: String): Boolean {
        val t1 = transliterate(text1)
        val t2 = transliterate(text2)
        return t1.contains(t2, ignoreCase = true) || t2.contains(t1, ignoreCase = true)
    }

    /**
     * Clears the in-memory cache. Useful when memory is low.
     */
    fun clearCache() {
        cache.clear()
    }
}
