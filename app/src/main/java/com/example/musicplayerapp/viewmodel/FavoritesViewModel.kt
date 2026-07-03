package com.example.musicplayerapp.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.musicplayerapp.data.AppDatabase
import com.example.musicplayerapp.data.FavoriteTrack
import com.example.musicplayerapp.data.FeedbackRepository
import com.example.musicplayerapp.UnsafeNetModule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * ViewModel for managing favorite tracks.
 */
class FavoritesViewModel(application: Application) : AndroidViewModel(application) {
    
    private val database = AppDatabase.getDatabase(application)
    private val favoriteDao = database.favoriteDao()
    private val feedbackRepository = FeedbackRepository(UnsafeNetModule.getUnsafeOkHttpClient())
    
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
