package com.example.musicplayerapp.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for FavoriteTrack entity.
 */
@Dao
interface FavoriteDao {
    
    @Query("SELECT * FROM favorites ORDER BY addedAt DESC")
    fun getAll(): Flow<List<FavoriteTrack>>
    
    /**
     * @return the new row id, or [ReactionEvent.NO_ROW_INSERTED] when the unique
     *   `(artist, track)` index already held this track and IGNORE skipped it.
     *   Callers report a LIKE only for a row that was really added.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(track: FavoriteTrack): Long

    /** @return how many rows went, so a delete that matched nothing reports nothing. */
    @Delete
    suspend fun delete(track: FavoriteTrack): Int
    
    @Query("DELETE FROM favorites WHERE artist = :artist AND track = :track")
    suspend fun deleteByArtistAndTrack(artist: String, track: String)
    
    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE artist = :artist AND track = :track)")
    fun isFavorite(artist: String, track: String): Flow<Boolean>
    
    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE artist = :artist AND track = :track)")
    suspend fun isFavoriteSync(artist: String, track: String): Boolean
    
    @Query("SELECT * FROM favorites WHERE artist = :artist AND track = :track LIMIT 1")
    suspend fun findByArtistAndTrack(artist: String, track: String): FavoriteTrack?
}
