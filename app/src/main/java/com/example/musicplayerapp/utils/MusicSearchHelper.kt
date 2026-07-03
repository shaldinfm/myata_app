package com.example.musicplayerapp.utils

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Helper object for opening music search in various streaming services.
 */
object MusicSearchHelper {
    
    /**
     * Opens Spotify search for the given artist and track.
     */
    fun openSpotify(context: Context, artist: String, track: String) {
        val query = Uri.encode("$artist - $track")
        val uri = Uri.parse("https://open.spotify.com/search/$query")
        openUrl(context, uri)
    }
    
    /**
     * Opens Apple Music search for the given artist and track.
     */
    fun openAppleMusic(context: Context, artist: String, track: String) {
        val query = Uri.encode("$artist $track")
        val uri = Uri.parse("https://music.apple.com/search?term=$query")
        openUrl(context, uri)
    }
    
    /**
     * Opens Yandex Music search for the given artist and track.
     */
    fun openYandexMusic(context: Context, artist: String, track: String) {
        val query = Uri.encode("$artist - $track")
        val uri = Uri.parse("https://music.yandex.ru/search?text=$query")
        openUrl(context, uri)
    }
    
    /**
     * Opens YouTube search for the given artist and track.
     */
    fun openYouTube(context: Context, artist: String, track: String) {
        val query = Uri.encode("$artist - $track")
        val uri = Uri.parse("https://www.youtube.com/results?search_query=$query")
        openUrl(context, uri)
    }
    
    private fun openUrl(context: Context, uri: Uri) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            // If no browser is available, fail silently
            android.util.Log.e("MusicSearchHelper", "Failed to open URL: $uri", e)
        }
    }
}
