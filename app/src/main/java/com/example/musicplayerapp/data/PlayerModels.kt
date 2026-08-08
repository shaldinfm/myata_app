package com.example.musicplayerapp.data

import android.net.Uri

data class PlayerState(
    var artist: String?,
    var song: String?,
    var img: String?,
    var backgroundImg: String? = null,
    var placeholderIndex: Int = 1
)

data class MyataPlaylist(
    val uri: String,
    val img: Uri
)

/**
 * Load state of the playlist list, which the splash screen waits on before
 * entering the app. ERROR is terminal until something asks for a retry.
 */
enum class PlaylistsState {
    LOADING,
    READY,
    ERROR
}
