package com.example.musicplayerapp.data

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Repository for fetching album artwork from a search provider.
 * This is the SINGLE source of truth for artwork in the app.
 *
 * ## Sources (G5b)
 *
 * The owner's fallback hierarchy, in order, and the plate is the *last* rung
 * rather than the preferred one:
 *
 *  1. iTunes, ranked by [ArtworkMatcher] - a canonical release, else a reissue,
 *     else another version of the track, else a compilation that carries it;
 *  2. Deezer's picture of the artist, when iTunes matched nothing at all. It is
 *     returned as [ArtworkSource.ARTIST_IMAGE] and never pretends to be a
 *     release's cover;
 *  3. nothing, which the player draws as its own branded plate.
 *
 * **Last.fm is not in the list.** It was asked over an API key the service
 * rejects outright - HTTP 403, "Invalid API key", every time we have measured it -
 * so that path could only ever cost a round trip and return nothing. The scrape
 * beside it read whatever `og:image` a page happened to carry. Both are gone; a
 * working key or a replacement provider is a separate, owner-approved change.
 *
 * Which of the iTunes candidates is the right one is [ArtworkMatcher]'s decision,
 * not this class's: everything here is the request, the parse and the cache.
 */
class ArtworkRepository(
    private val httpClient: OkHttpClient,
    /**
     * The client cover images are fetched with - bigger payloads, longer clocks
     * and its own disk cache. Defaults to [httpClient] so a test can pass one.
     */
    private val imageClient: OkHttpClient = httpClient,
    /**
     * Whether the release search is worth asking right now. Measured from Russia,
     * `itunes.apple.com` can be unreachable on one ISP while Deezer and both image
     * CDNs are fine - and in that case every lookup used to wait out the whole
     * iTunes deadline before reaching the Deezer step that was going to answer.
     */
    private val itunesHealth: ProviderHealth = ProviderHealth(),
    /** The same for the artist-image fallback. */
    private val deezerHealth: ProviderHealth = ProviderHealth(),
) : ArtworkProvider {

    /**
     * A provider that has stopped answering, remembered so it is not asked again
     * straight away.
     *
     * After [failuresToOpen] consecutive transport failures the provider is
     * skipped for [cooldownMs], then tried again; one answer - even an empty one -
     * clears it. This is transport health only: which candidate is the right cover
     * is still entirely [ArtworkMatcher]'s decision whenever the provider is asked.
     */
    class ProviderHealth(
        private val now: () -> Long = System::currentTimeMillis,
        private val failuresToOpen: Int = 3,
        private val cooldownMs: Long = 60_000L,
    ) {
        private val failures = java.util.concurrent.atomic.AtomicInteger(0)

        @Volatile
        private var openUntil = 0L

        /** Worth asking now. */
        fun available(): Boolean {
            val until = openUntil
            if (until == 0L) return true
            if (now() < until) return false
            // Cooldown over: try it again from a clean count, so one more failure
            // does not reopen it on the strength of failures from the last outage.
            openUntil = 0L
            failures.set(0)
            return true
        }

        /** It answered - with something or with nothing, but it answered. */
        fun answered() {
            failures.set(0)
            openUntil = 0L
        }

        /** It could not be reached. */
        fun unreachable() {
            // Already known to be down: a request that was in flight when it was
            // declared down is not news, and must not push the cooldown further out.
            if (openUntil != 0L) return
            if (failures.incrementAndGet() >= failuresToOpen) {
                openUntil = now() + cooldownMs
                failures.set(0)
            }
        }
    }

    /**
     * @property confidence how sure the matcher was, for logs and tests. Nothing
     *   in the UI reads it and nothing persists it.
     * @property outcome whether this is an answer or the absence of one, and if
     *   absent, whose fault that was. [ArtworkResolver] caches on it: an answer is
     *   kept, a confirmed no-match expires, a failure is only a cooldown.
     */
    data class ArtworkResult(
        val coverUrl: String?,
        val backgroundUrl: String? = null,
        val confidence: ArtworkConfidence? = null,
        /** Whether [coverUrl] is a release's cover or a stand-in picture of the act. */
        val source: ArtworkSource? = null,
        val outcome: ArtworkOutcome = if (coverUrl != null) ArtworkOutcome.RESOLVED else ArtworkOutcome.NO_MATCH,
    )

    /**
     * Fetches artwork for a given artist and track - one lookup, no caching.
     *
     * Caching moved out to [ArtworkResolver] in G5c, along with the decision of
     * what a null answer means. This asks the providers and reports what came
     * back, including *why* nothing did: a provider that could not be reached is
     * an [ArtworkOutcome.FAILED] to be tried again, while a provider that
     * answered and had nothing is an [ArtworkOutcome.NO_MATCH]. Telling those
     * apart is what stopped one bad minute from meaning a session of plates.
     *
     * Every stage below blocks on OkHttp, so the dispatcher switch lives here
     * rather than at each call site: callers pass whatever context they are on,
     * and on Dispatchers.Main every stage threw NetworkOnMainThreadException
     * into its own catch, so the result was an empty one that then got cached.
     */
    override suspend fun fetchArtwork(artist: String, track: String): ArtworkResult {
        return withContext(Dispatchers.IO) {
            val queryArtist = searchableArtist(artist)
            val queryTitle = searchableTitle(track)

            var choice: ArtworkChoice? = null

            // Not asked at all while it is known to be down: the lookup goes
            // straight to the fallback instead of spending its deadline here.
            val itunesAsked = itunesHealth.available()
            var itunesUnreachable = !itunesAsked
            if (!itunesAsked) {
                Log.d("ArtworkRepo", "iTunes skipped (recently unreachable) for $artist - $track")
            }

            if (itunesAsked) try {
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
                itunesHealth.answered()
            } catch (e: IOException) {
                // Could not be reached, rather than asked and found wanting.
                itunesUnreachable = true
                itunesHealth.unreachable()
                Log.e("ArtworkRepo", "iTunes unreachable: ${e.javaClass.simpleName} ${e.message}")
            } catch (e: Exception) {
                Log.e("ArtworkRepo", "iTunes search error", e)
            }

            val result = if (choice != null) {
                Log.d(
                    "ArtworkRepo",
                    "Cover for $artist - $track: ${choice.candidate.collectionName} " +
                        "[${choice.confidence}] ${choice.reason}"
                )
                ArtworkResult(
                    coverUrl = choice.candidate.artworkUrl,
                    confidence = choice.confidence,
                    source = ArtworkSource.RELEASE,
                    outcome = ArtworkOutcome.RESOLVED,
                )
            } else {
                // Step 5: no release could be matched, so the act's own picture
                // stands in rather than the plate. Marked as what it is.
                var deezerUnreachable = !deezerHealth.available()
                val artistImage = if (deezerUnreachable) {
                    null
                } else {
                    try {
                        fetchArtistImageFromDeezer(queryArtist).also { deezerHealth.answered() }
                    } catch (e: IOException) {
                        deezerUnreachable = true
                        deezerHealth.unreachable()
                        Log.e("ArtworkRepo", "Deezer unreachable: ${e.javaClass.simpleName}")
                        null
                    }
                }

                val outcome = when {
                    // The fallback answered, but the release search was never
                    // really asked - so this is what to show *for now*.
                    artistImage != null && itunesUnreachable -> ArtworkOutcome.PARTIAL
                    artistImage != null -> ArtworkOutcome.RESOLVED
                    // Nothing, and not every provider could be asked: that is not
                    // evidence the track has no artwork.
                    itunesUnreachable || deezerUnreachable -> ArtworkOutcome.FAILED
                    else -> ArtworkOutcome.NO_MATCH
                }

                Log.d(
                    "ArtworkRepo",
                    when (outcome) {
                        ArtworkOutcome.RESOLVED -> "Artist image for $artist - $track (no release matched)"
                        ArtworkOutcome.PARTIAL -> "Artist image for $artist - $track (release search unreachable; will retry)"
                        ArtworkOutcome.NO_MATCH -> "Nothing found at all for $artist - $track"
                        ArtworkOutcome.FAILED -> "Provider unreachable for $artist - $track; will retry"
                    }
                )

                ArtworkResult(
                    coverUrl = artistImage,
                    confidence = artistImage?.let { ArtworkConfidence.LOW },
                    source = artistImage?.let { ArtworkSource.ARTIST_IMAGE },
                    outcome = outcome,
                )
            }

            result
        }
    }

    /**
     * Downloads a cover and throws the bytes away, so the next reader finds it in
     * the HTTP cache instead of on the network.
     *
     * Both artwork CDNs send a `max-age` measured in months, so this is an
     * ordinary cache fill with no expiry policy of our own. Failures are silent:
     * a warm-up that did not happen costs a slower first paint and nothing else.
     */
    override suspend fun warmImage(url: String) {
        withContext(Dispatchers.IO) {
            runCatching {
                imageClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    response.body?.bytes()
                }
            }
        }
    }

    // ==================== iTunes ====================

    private fun executeItunesSearch(term: String, expectedArtist: String, expectedTrack: String): ArtworkChoice? {
        val encodedTerm = java.net.URLEncoder.encode(term, "UTF-8")
        val url = "https://itunes.apple.com/search?term=$encodedTerm&media=music&entity=song&limit=20"

        val request = Request.Builder().url(url).build()

        return httpClient.newCall(request).execute().use { response ->
            // A 403 or a 5xx is the provider refusing to answer, which is not the
            // same as answering that it has nothing - it is thrown so the caller
            // records a failure and tries again later.
            if (!response.isSuccessful) throw IOException("iTunes HTTP ${response.code}")

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

    // ==================== artist image (last resort) ====================

    /**
     * A picture of the act, for when no release could be matched at all.
     *
     * Step 5 of the hierarchy, and deliberately strict about *who*: the Deezer
     * result's name has to be the same act by the matcher's own reading, so a
     * search for one artist cannot come back with a photograph of another. It is
     * still only a photograph, which is why the result carries
     * [ArtworkSource.ARTIST_IMAGE].
     */
    private fun fetchArtistImageFromDeezer(artist: String): String? {
        if (artist.isBlank()) return null
        try {
            val encoded = java.net.URLEncoder.encode(artist, "UTF-8")
            val request = Request.Builder()
                .url("https://api.deezer.com/search/artist?q=$encoded")
                .header("User-Agent", "MyataRadio/1.0")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Deezer HTTP ${response.code}")

                val body = response.body?.string() ?: return null
                val json = Gson().fromJson(body, Map::class.java) ?: return null

                @Suppress("UNCHECKED_CAST")
                val data = json["data"] as? List<Map<String, Any>> ?: return null

                for (item in data) {
                    val name = item["name"] as? String ?: continue
                    if (!ArtworkMatcher.sameArtist(artist, name)) continue
                    val picture = item["picture_xl"] as? String
                        ?: item["picture_big"] as? String
                        ?: continue
                    // Deezer answers with a placeholder path when it holds no photo.
                    if (picture.contains("/artist//")) continue
                    return picture
                }
            }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            Log.e("ArtworkRepo", "Deezer artist image failed: ${e.message}")
        }
        return null
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
}
