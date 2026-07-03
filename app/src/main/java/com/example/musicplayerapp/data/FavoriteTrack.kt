package com.example.musicplayerapp.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entity for storing favorite tracks in local Room database.
 */
@Entity(
    tableName = "favorites",
    indices = [Index(value = ["artist", "track"], unique = true)]
)
data class FavoriteTrack(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val artist: String,
    val track: String,
    val stream: String,  // "myata", "gold", "myata_hits"
    val addedAt: Long = System.currentTimeMillis()
)
