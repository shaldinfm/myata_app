package com.example.musicplayerapp.data

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/**
 * Repository for fetching album artwork from a search provider.
 * This is the SINGLE source of truth for artwork in the app.
 *
 * ## Source (G5b)
 *
 * iTunes, and only iTunes. Two fallbacks were removed rather than repaired:
 *
 *  - **Deezer** was asked for the *artist's photograph* and its answer was used as
 *    though it were the release's cover. A picture of the band is not the artwork
 *    of the record, and it was reaching the player as a confident result for 1 in
 *    18 tracks in the G5 recon sample.
 *  - **Last.fm** was asked over an API key that the service has been rejecting
 *    outright (HTTP 403, "Invalid API key"), so the call could only ever cost a
 *    round trip; the scrape beside it read whatever `og:image` a page happened to
 *    carry, which for a track page can also be an artist shot.
 *
 * Neither is replaced here - that would be a new provider, which G5b does not add.
 * A track iTunes cannot place now resolves to *no cover*, which the player already
 * draws as its own branded plate. A correct plate beats a confident wrong cover.
 *
 * Which of the candidates iTunes returns is the right one is [ArtworkMatcher]'s
 * decision, not this class's: everything here is the request, the parse and the
 * cache.
 */
class ArtworkRepository(private val httpClient: OkHttpClient) {

    /**
     * In-memory cache, keyed on the *whole* pair.
     *
     * The key used to be the artist cut at its first separator and the title with
     * every bracket and the word "remix" stripped out, so `Song`, `Song (Live)`
     * and `Song (X Remix)` were one key and the first answer served all three.
     * Now that a version marker decides which release is correct, it has to be
     * part of the identity of the question as well (G5 recon, cache correctness).
     */
    private val cache = ConcurrentHashMap<String, ArtworkResult>()

    /**
     * @property confidence how sure the matcher was, for logs and tests. Nothing
     *   in the UI reads it and nothing persists it.
     */
    data class ArtworkResult(
        val coverUrl: String?,
        val backgroundUrl: String? = null,
        val confidence: ArtworkConfidence? = null,
    )

    /**
     * Main entry point: fetches artwork for a given artist and track.
     * Uses cache if available. Returns null URLs if nothing found.
     *
     * Every stage below blocks on OkHttp, so the dispatcher switch lives here
     * rather than at each call site: callers pass whatever context they are on,
     * and on Dispatchers.Main every stage threw NetworkOnMainThreadException
     * into its own catch, so the result was an empty one that then got cached.
     */
    suspend fun fetchArtwork(artist: String, track: String): ArtworkResult {
        val cacheKey = "${TrackKey.normalize(artist)}\u001F${TrackKey.normalize(track)}"

        // Return cached result if available. Deliberately ahead of the switch, so
        // a hit costs the caller no dispatch at all.
        cache[cacheKey]?.let { return it }

        return withContext(Dispatchers.IO) {
            val queryArtist = searchableArtist(artist)
            val queryTitle = searchableTitle(track)

            var choice: ArtworkChoice? = null
            try {
                // Stage 1: the artist as the provider is most likely to index it,
                // with the title's own name and no version brackets.
                val query1 = "$queryArtist $queryTitle".trim()
                Log.d("ArtworkRepo", "Stage 1: $query1")
                choice = executeItunesSearch(query1, artist, track)

                // Stage 2: the artist exactly as the station wrote it, credits and
                // all - some collaborations are indexed under the full string.
                if (choice == null && queryArtist != artist.trim()) {
                    val query2 = "${artist.trim()} $queryTitle".trim()
                    Log.d("ArtworkRepo", "Stage 2: $query2")
                    choice = executeItunesSearch(query2, artist, track)
                }

                // Stage 3: the title alone. Safe to try now that the artist has to
                // match by identity: before G5b this stage was where an unrelated
                // act with a similar name could win.
                if (choice == null && queryTitle.length >= 4) {
                    Log.d("ArtworkRepo", "Stage 3 (Track only): $queryTitle")
                    choice = executeItunesSearch(queryTitle, artist, track)
                }
            } catch (e: Exception) {
                Log.e("ArtworkRepo", "iTunes search error", e)
            }

            if (choice == null) {
                Log.d("ArtworkRepo", "No canonical cover for $artist - $track")
            } else {
                Log.d(
                    "ArtworkRepo",
                    "Cover for $artist - $track: ${choice.candidate.collectionName} " +
                        "[${choice.confidence}] ${choice.reason}"
                )
            }

            val result = ArtworkResult(
                coverUrl = choice?.candidate?.artworkUrl,
                confidence = choice?.confidence,
            )
            cache[cacheKey] = result
            result
        }
    }

    // ==================== iTunes ====================

    private fun executeItunesSearch(term: String, expectedArtist: String, expectedTrack: String): ArtworkChoice? {
        val encodedTerm = java.net.URLEncoder.encode(term, "UTF-8")
        val url = "https://itunes.apple.com/search?term=$encodedTerm&media=music&entity=song&limit=20"

        val request = Request.Builder().url(url).build()

        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null

            val bodyContent = response.body?.string() ?: return@use null
            val candidates = parseCandidates(bodyContent)
            if (candidates.isEmpty()) return@use null

            ArtworkMatcher.choose(expectedArtist, expectedTrack, candidates)
        }
    }

    /**
     * The provider's answer as [ArtworkCandidate]s. Anything without the three
     * fields a decision needs - who, what, and a picture - is not a candidate.
     */
    @VisibleForTesting
    internal fun parseCandidates(body: String): List<ArtworkCandidate> {
        val json = Gson().fromJson(body, Map::class.java) ?: return emptyList()

        @Suppress("UNCHECKED_CAST")
        val results = json["results"] as? List<Map<String, Any>> ?: return emptyList()

        return results.mapNotNull { item ->
            val trackName = item["trackName"] as? String ?: return@mapNotNull null
            val artistName = item["artistName"] as? String ?: return@mapNotNull null
            val artworkUrl = item["artworkUrl100"] as? String ?: return@mapNotNull null

            ArtworkCandidate(
                trackName = trackName,
                artistName = artistName,
                collectionName = item["collectionName"] as? String ?: "",
                collectionArtistName = item["collectionArtistName"] as? String,
                releaseDate = item["releaseDate"] as? String,
                trackCount = (item["trackCount"] as? Number)?.toInt(),
                artworkUrl = artworkUrl.replace("100x100bb", "600x600bb"),
            )
        }
    }

    // ==================== query text ====================

    /**
     * The artist as a search term: the credited act, without the guests.
     *
     * It removes credits (`feat.`, `ft.`, `with`, `pres.`) and bracketed asides,
     * and nothing else. In particular it does **not** cut the name at a comma or a
     * slash the way `getCleanArtistName` did, which searched iTunes for `AC` when
     * the station was playing `AC/DC`, and for `Earth` when it was playing
     * `Earth, Wind & Fire`.
     */
    private fun searchableArtist(artist: String): String {
        val withoutBrackets = artist.replace(Regex("\\(.*?\\)|\\[.*?\\]|\\{.*?\\}"), " ")
        val credit = Regex("(?i)\\b(feat\\.?|ft\\.?|featuring|with|pres\\.?|presents)\\b")
        val head = credit.split(withoutBrackets).firstOrNull().orEmpty()
        return head.replace(Regex("\\s+"), " ").trim().ifEmpty { artist.trim() }
    }

    /**
     * The title as a search term: its own name, without the version brackets.
     *
     * The marker is dropped from the *query* only, so the search reaches the
     * release, and it is fully honoured when the answers are judged - a station
     * asking for a live take will not be given the studio cover, because
     * [ArtworkMatcher] still requires the versions to agree.
     */
    private fun searchableTitle(track: String): String {
        val withoutBrackets = track.replace(Regex("\\(.*?\\)|\\[.*?\\]"), " ")
        val dash = withoutBrackets.indexOf(" - ")
        val head = if (dash > 0) withoutBrackets.substring(0, dash) else withoutBrackets
        return head.replace(Regex("\\s+"), " ").trim().ifEmpty { track.trim() }
    }

    /**
     * Clears the in-memory cache. Useful when memory is low.
     */
    fun clearCache() {
        cache.clear()
    }
}
