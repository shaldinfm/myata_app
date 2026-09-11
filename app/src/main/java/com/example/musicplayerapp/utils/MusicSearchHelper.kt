package com.example.musicplayerapp.utils

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Helper object for opening music search in various streaming services.
 *
 * Every search is an https link handed to `ACTION_VIEW`, so the service's own app
 * answers it when it is installed and verified for that host, and the browser does
 * otherwise. Nothing here knows which one it will be.
 */
object MusicSearchHelper {

    /**
     * Opens Spotify search for the given artist and track.
     */
    fun openSpotify(context: Context, artist: String, track: String) {
        val uri = Uri.parse("https://open.spotify.com/search/${dashQuery(artist, track)}")
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
        val uri = Uri.parse("https://music.yandex.ru/search?text=${dashQuery(artist, track)}")
        openUrl(context, uri)
    }

    /**
     * Opens YouTube Music search for the given artist and track.
     *
     * G4b owner decision: the row is labelled «YouTube Music», as both frozen
     * sheets label it, and it opens what it names. It used to search youtube.com
     * under a «YouTube» label. `music.youtube.com` is the host the YouTube Music app
     * claims, so the app answers where it is installed and the browser where it is
     * not - the same hand-off every other service here uses.
     */
    fun openYouTubeMusic(context: Context, artist: String, track: String) {
        val uri = Uri.parse("https://music.youtube.com/search?q=${dashQuery(artist, track)}")
        openUrl(context, uri)
    }

    /**
     * `artist - track`, encoded - the query Spotify, Yandex Music and YouTube Music
     * all search for, built in one place. Apple Music's search reads better
     * without the dash and keeps its own.
     */
    private fun dashQuery(artist: String, track: String): String = Uri.encode("$artist - $track")

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
