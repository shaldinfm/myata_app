package com.example.musicplayerapp.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.musicplayerapp.data.AppDatabase
import com.example.musicplayerapp.data.ArtworkRepository
import com.example.musicplayerapp.data.FavoriteTrack
import com.example.musicplayerapp.data.FeedbackRepository
import com.example.musicplayerapp.SecureNetModule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * ViewModel for managing favorite tracks.
 */
class FavoritesViewModel(application: Application) : AndroidViewModel(application) {
    
    private val database = AppDatabase.getDatabase(application)
    private val favoriteDao = database.favoriteDao()
    private val httpClient = SecureNetModule.getOkHttpClient(application)
    private val feedbackRepository = FeedbackRepository(httpClient)
    private val artworkRepository = ArtworkRepository(httpClient)

    /**
     * Flow of all favorite tracks, sorted by most recently added.
     */
    val favorites: Flow<List<FavoriteTrack>> = favoriteDao.getAll()
    
    /**
     * Returns a Flow that emits whether the given track is a favorite.
     */
    fun isFavorite(artist: String, track: String): Flow<Boolean> {
        return favoriteDao.isFavorite(artist, track)
    }
    
    /**
     * Toggles the favorite status of a track.
     * If already favorite, removes it. Otherwise, adds it.
     */
    fun toggleFavorite(artist: String, track: String, stream: String) {
        viewModelScope.launch {
            val existing = favoriteDao.findByArtistAndTrack(artist, track)
            if (existing != null) {
                favoriteDao.delete(existing)
                feedbackRepository.reportFeedback(artist, track, stream, "DISLIKE")
            } else {
                favoriteDao.insert(FavoriteTrack(
                    artist = artist,
                    track = track,
                    stream = stream
                ))
                feedbackRepository.reportFeedback(artist, track, stream, "LIKE")
            }
        }
    }
    
    /**
     * Adds a track to favorites.
     */
    fun addFavorite(artist: String, track: String, stream: String) {
        viewModelScope.launch {
            favoriteDao.insert(FavoriteTrack(
                artist = artist,
                track = track,
                stream = stream
            ))
            feedbackRepository.reportFeedback(artist, track, stream, "LIKE")
        }
    }
    
    /**
     * Removes a track from favorites.
     */
    fun removeFavorite(track: FavoriteTrack) {
        viewModelScope.launch {
            favoriteDao.delete(track)
            feedbackRepository.reportFeedback(track.artist, track.track, track.stream, "DISLIKE")
        }
    }
    
    /**
     * Puts a removed track back exactly as it was.
     *
     * This is the whole of `Отменить`, and it needs no undo store: the row that
     * came out of the list IS the undo record. [FavoriteTrack] is the complete
     * entity - id, artist, track, stream and addedAt - so re-inserting the same
     * instance restores its identity and its original addedAt, and the list
     * (`ORDER BY addedAt DESC`) puts it back in the position it was removed
     * from rather than at the top as a fresh save would.
     *
     * The feedback report mirrors [removeFavorite]'s: removal reports DISLIKE,
     * so undoing it reports LIKE, exactly as [addFavorite] does.
     */
    fun restoreFavorite(track: FavoriteTrack) {
        viewModelScope.launch {
            favoriteDao.insert(track)
            feedbackRepository.reportFeedback(track.artist, track.track, track.stream, "LIKE")
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
     * Removes a track by artist and track name.
     */
    fun removeFavorite(artist: String, track: String) {
        viewModelScope.launch {
            // Find the track first to get its stream info for reporting
            val existing = favoriteDao.findByArtistAndTrack(artist, track)
            if (existing != null) {
                favoriteDao.delete(existing)
                feedbackRepository.reportFeedback(artist, track, existing.stream, "💔 DISLIKE")
            }
        }
    }
    
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
