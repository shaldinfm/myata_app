package com.example.musicplayerapp.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Reads and writes of reaction state.
 *
 * The two write methods return **whether anything actually changed**, and that is
 * the point of their shape. Liking a track that is already liked, or removing a row
 * that has already gone, changes nothing, and the callers turn "nothing changed"
 * into "report nothing" - which is what stops one listener's repeated tapping being
 * counted as several independent opinions. See [ReactionEvent].
 *
 * The state transitions are done in a [Transaction] because each is a read followed
 * by a conditional write, and the PLAYER control and the Collection screen can both
 * reach the same track.
 */
@Dao
abstract class ReactionDao {

    /**
     * The Collection: LIKED rows, newest first.
     *
     * Ordered by `liked_at`, which is where the old `favorites.addedAt` ordering
     * went, so the list looks exactly as it did - and an undone removal comes back
     * in its old position rather than at the top.
     */
    @Query(
        """
        SELECT track_key AS trackKey, artist, title AS track, stream, liked_at AS addedAt
        FROM track_reaction
        WHERE reaction = 'LIKED'
        ORDER BY liked_at DESC
        """
    )
    abstract fun likedTracks(): Flow<List<FavoriteTrack>>

    /** Whether this track is in the Collection. NEUTRAL and DISLIKED are both false. */
    @Query("SELECT EXISTS(SELECT 1 FROM track_reaction WHERE track_key = :trackKey AND reaction = 'LIKED')")
    abstract fun isLiked(trackKey: String): Flow<Boolean>

    /**
     * This track's reaction as it changes, or null while there is no row for it.
     *
     * What the PLAYER's two controls draw. A track nobody has reacted to has no
     * row at all, so null and [Reaction.NEUTRAL] mean the same thing to a reader
     * and the caller maps one to the other.
     */
    @Query("SELECT reaction FROM track_reaction WHERE track_key = :trackKey")
    abstract fun observeReaction(trackKey: String): Flow<Reaction?>

    @Query("SELECT * FROM track_reaction WHERE track_key = :trackKey LIMIT 1")
    abstract suspend fun find(trackKey: String): TrackReaction?

    /**
     * Saves a track to the Collection.
     *
     * @param likedAt what the row's Collection position should be. `now` for a fresh
     *   Like; the removed row's original value when Undo puts one back, which is how
     *   it returns to where it was in the list.
     * @return true if this changed anything. False when the track was already LIKED:
     *   nothing moves, and no second LIKE is reported for one opinion.
     */
    @Transaction
    open suspend fun like(
        trackKey: String,
        artist: String,
        title: String,
        stream: String,
        likedAt: Long,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val existing = find(trackKey)
        if (existing?.reaction == Reaction.LIKED) return false

        upsert(
            TrackReaction(
                trackKey = trackKey,
                artist = artist,
                title = title,
                stream = stream,
                reaction = Reaction.LIKED,
                likedAt = likedAt,
                updatedAt = now,
            )
        )
        return true
    }

    /**
     * Withdraws a Like: out of the Collection, back to [Reaction.NEUTRAL].
     *
     * Never sets [Reaction.DISLIKED] - that is a separate, explicit act, and
     * conflating the two is the bug this model exists to prevent. The row is kept so
     * `updated_at` still records when the Like went.
     *
     * @return true if a LIKED row was actually neutralised.
     */
    @Transaction
    open suspend fun unlike(trackKey: String, now: Long = System.currentTimeMillis()): Boolean =
        neutraliseLiked(trackKey, now) > 0

    /**
     * Withdraws a Dislike: back to [Reaction.NEUTRAL].
     *
     * The mirror of [unlike], and separate from it for the same reason the two
     * events are separate - which opinion is being withdrawn is the whole content
     * of the act. Nothing enters the Collection here; NEUTRAL is not LIKED.
     *
     * @return true if a DISLIKED row was actually neutralised.
     */
    @Transaction
    open suspend fun undislike(trackKey: String, now: Long = System.currentTimeMillis()): Boolean =
        neutraliseDisliked(trackKey, now) > 0

    /**
     * Sets an explicit negative reaction.
     *
     * A dislike takes the track out of the Collection by construction, because the
     * Collection is the LIKED rows - so LIKED -> DISLIKED needs no separate
     * removal, and reports one DISLIKE rather than an invented UNLIKE first.
     *
     * @return true if the state changed.
     */
    @Transaction
    open suspend fun dislike(
        trackKey: String,
        artist: String,
        title: String,
        stream: String,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val existing = find(trackKey)
        if (existing?.reaction == Reaction.DISLIKED) return false

        upsert(
            TrackReaction(
                trackKey = trackKey,
                artist = artist,
                title = title,
                stream = stream,
                reaction = Reaction.DISLIKED,
                // A dislike leaves the Collection, but keeps where it once sat, so
                // liking it again later does not have to invent a position.
                likedAt = existing?.likedAt,
                updatedAt = now,
            )
        )
        return true
    }

    @Query("UPDATE track_reaction SET reaction = 'NEUTRAL', updated_at = :now WHERE track_key = :trackKey AND reaction = 'LIKED'")
    protected abstract suspend fun neutraliseLiked(trackKey: String, now: Long): Int

    @Query("UPDATE track_reaction SET reaction = 'NEUTRAL', updated_at = :now WHERE track_key = :trackKey AND reaction = 'DISLIKED'")
    protected abstract suspend fun neutraliseDisliked(trackKey: String, now: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun upsert(reaction: TrackReaction)
}
