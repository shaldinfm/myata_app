package com.example.musicplayerapp.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.musicplayerapp.data.AppDatabase
import com.example.musicplayerapp.data.ArtworkRepository
import com.example.musicplayerapp.data.FavoriteTrack
import com.example.musicplayerapp.data.FeedbackRepository
import com.example.musicplayerapp.data.ReactionEvent
import com.example.musicplayerapp.data.supabase.ReactionSyncScheduler
import com.example.musicplayerapp.data.TrackKey
import com.example.musicplayerapp.SecureNetModule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * ViewModel for managing favorite tracks.
 */
class FavoritesViewModel(application: Application) : AndroidViewModel(application) {
    
    private val appContext = application.applicationContext
    private val database = AppDatabase.getDatabase(application)
    private val reactionDao = database.reactionDao()
    private val httpClient = SecureNetModule.getOkHttpClient(application)
    private val feedbackRepository = FeedbackRepository(httpClient)
    private val artworkRepository = ArtworkRepository(httpClient)

    /**
     * The Collection: every LIKED track, most recently liked first.
     *
     * NEUTRAL and DISLIKED rows are not in it. A track whose Like was withdrawn is
     * not in the Collection, and neither is one that was disliked.
     */
    val favorites: Flow<List<FavoriteTrack>> = reactionDao.likedTracks()

    /**
     * Whether this track is in the Collection. False for a track nobody has reacted
     * to, and false for a disliked one.
     *
     * A track [TrackKey] refuses to key - an empty field, or the jingle sentinel -
     * can hold no reaction at all, so it is never a favourite.
     */
    fun isFavorite(artist: String, track: String): Flow<Boolean> {
        val trackKey = TrackKey.of(artist, track) ?: return flowOf(false)
        return reactionDao.isLiked(trackKey)
    }
    
    /**
     * Removes a track from favorites.
     *
     * The report is [ReactionEvent.UNLIKE] - the Like is withdrawn and the reaction
     * is back to neutral. It used to be DISLIKE, which recorded every listener who
     * tidied their Collection as having disliked the track.
     *
     * A delete that matched nothing (the row was already gone, or two removals
     * raced) reports nothing at all: no state changed, so no opinion was expressed.
     */
    fun removeFavorite(track: FavoriteTrack) {
        viewModelScope.launch {
            ReactionEvent.forUnlike(reactionDao.unlike(track.trackKey))?.let { event ->
                feedbackRepository.reportFeedback(track.artist, track.track, track.stream, event)
                ReactionSyncScheduler.onReactionCommitted(appContext)
            }
        }
    }
    
    /**
     * Puts a removed track back exactly as it was.
     *
     * This is the whole of `Отменить`, and it still needs no undo store: the row
     * that came out of the list IS the undo record. [FavoriteTrack] carries the
     * track key, artist, track, stream and addedAt, so liking it again with its
     * original `addedAt` as `liked_at` puts it back where it was - the list is
     * `ORDER BY liked_at DESC`, so that value is the row's position, and a fresh
     * save (which would use `now`) would land it at the top instead.
     *
     * What changed under the reaction model is what "putting it back" means: the
     * removal set the row to NEUTRAL rather than deleting it, so this returns the
     * same row to LIKED. The identity is [TrackKey] v1 either way, so the row cannot
     * come back as a duplicate of itself.
     *
     * The feedback report mirrors [removeFavorite]'s: removal reports
     * [ReactionEvent.UNLIKE], so undoing it reports [ReactionEvent.LIKE] - and only
     * if the state really changed. Undoing something that is in the Collection again
     * already (a second tap on a stale snackbar, a re-Like from the PLAYER while the
     * snackbar was still up) changes nothing and must not report a second LIKE for
     * one opinion.
     */
    fun restoreFavorite(track: FavoriteTrack) {
        viewModelScope.launch {
            val restored = reactionDao.like(
                trackKey = track.trackKey,
                artist = track.artist,
                title = track.track,
                stream = track.stream,
                likedAt = track.addedAt,
            )
            ReactionEvent.forLike(restored)?.let { event ->
                feedbackRepository.reportFeedback(track.artist, track.track, track.stream, event)
                ReactionSyncScheduler.onReactionCommitted(appContext)
            }
        }
    }

    /**
     * A cover for one Collection row, or null if none can be found.
     *
     * [FavoriteTrack] stores artist and track and has nowhere to put artwork,
     * while the FINAL row draws a 64x64 cover, so it is derived from the artist
     * and track by [ArtworkRepository] - the app's single source of truth for
     * artwork, and the same route the PLAYER history rows take. Nothing about
     * the schema changes: this is a view of what the collection already stores.
     *
     * No dispatcher is stated here, for the reason StreamsViewModel records on
     * its own artwork lookup: `fetchArtwork` switches to IO itself around its
     * blocking body, and reads its cache ahead of that switch, so a wrapper here
     * would only cost a cache hit a round trip.
     */
    suspend fun artworkUrl(track: FavoriteTrack): String? =
        runCatching { artworkRepository.fetchArtwork(track.artist, track.track).coverUrl }
            .getOrNull()

    /**
     * Exports favorites to TXT format.
     */
    fun exportToTxt(tracks: List<FavoriteTrack>): String {
        return tracks.joinToString("\r\n") { "${it.artist} - ${it.track}" }
    }
    
    /**
     * Exports favorites to CSV format.
     */
    fun exportToCsv(tracks: List<FavoriteTrack>): String {
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        val header = "\"Artist\";\"Track\";\"Stream\";\"Added\""
        
        val rows = tracks.map { track ->
            val date = dateFormat.format(java.util.Date(track.addedAt))
            val escapedArtist = track.artist.replace("\"", "\"\"")
            val escapedTrack = track.track.replace("\"", "\"\"")
            val escapedStream = track.stream.replace("\"", "\"\"")
            "\"$escapedArtist\";\"$escapedTrack\";\"$escapedStream\";\"$date\""
        }
        
        return (listOf(header) + rows).joinToString("\r\n")
    }
}
