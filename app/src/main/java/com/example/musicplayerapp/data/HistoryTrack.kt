package com.example.musicplayerapp.data

import com.google.gson.annotations.SerializedName

/**
 * Data class representing a track from the history API.
 */
data class HistoryTrack(
    val artist: String,
    val track: String,
    @SerializedName("played_at")
    val playedAt: Long,
    @SerializedName("played_at_formatted")
    val playedAtFormatted: String
) {
    /**
     * Returns the title (track name).
     */
    val title: String get() = track
    
    /**
     * Returns formatted time string from API.
     */
    fun getFormattedTime(): String {
        return playedAtFormatted
    }
}

/**
 * Wrapper for API response.
 */
data class HistoryResponse(
    val success: Boolean,
    val data: List<HistoryTrack>?,
    val stream: String?
)
