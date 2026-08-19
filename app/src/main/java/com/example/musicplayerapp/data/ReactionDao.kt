package com.example.musicplayerapp.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.musicplayerapp.data.ReactionOutboxEntry.Companion.newEventId
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
 *
 * ## The transaction boundary
 *
 * Each of [like], [unlike], [dislike] and [undislike] is **the** boundary: read the
 * current state, write the new one, and append the [ReactionOutboxEntry] for the
 * transition - all three inside one transaction, or none of them. Nothing above
 * this class may split the pair, which is why the enqueue lives here rather than in
 * a ViewModel that would have to remember to do it.
 *
 * The two failures this rules out are the ones that matter:
 *
 *  - a reaction the listener can see but that will never reach the backend, because
 *    the process died between the two writes;
 *  - a pending event for a transition that did not happen, which would apply a
 *    stranger's Like to somebody's account.
 *
 * The "nothing changed" return is what keeps the second one true for repeated taps:
 * no state change, no event, so tapping Like four times enqueues one LIKE. See
 * [ReactionEvent].
 *
 * Draining the queue is [ReactionOutboxDao]'s, and nothing drains it yet.
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
     * Both routes in are one LIKE: NEUTRAL -> LIKED and DISLIKED -> LIKED. Changing
     * your mind is not withdrawing an opinion and then expressing another, so no
     * UNDISLIKE is invented on the way through.
     *
     * @param likedAt what the row's Collection position should be. `now` for a fresh
     *   Like; the removed row's original value when Undo puts one back, which is how
     *   it returns to where it was in the list.
     * @param eventId the identity of the outbox event this will enqueue. Defaulted;
     *   passed explicitly only by tests, which need it to be predictable.
     * @return true if this changed anything. False when the track was already LIKED:
     *   nothing moves, no second LIKE is reported for one opinion, and nothing is
     *   queued for the backend either.
     */
    @Transaction
    open suspend fun like(
        trackKey: String,
        artist: String,
        title: String,
        stream: String,
        likedAt: Long,
        now: Long = System.currentTimeMillis(),
        eventId: String = newEventId(),
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
        enqueue(
            eventId = eventId,
            trackKey = trackKey,
            artist = artist,
            title = title,
            stream = stream,
            event = ReactionEvent.LIKE,
            occurredAt = now,
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
     * The row is read before it is changed because the outbox event has to carry the
     * words - artist, title and stream - and the key is a hash that cannot give them
     * back. That read is inside the same transaction as the write, so the row cannot
     * change underneath it.
     *
     * @return true if a LIKED row was actually neutralised.
     */
    @Transaction
    open suspend fun unlike(
        trackKey: String,
        now: Long = System.currentTimeMillis(),
        eventId: String = newEventId(),
    ): Boolean {
        val existing = find(trackKey)
        if (existing?.reaction != Reaction.LIKED) return false

        neutraliseLiked(trackKey, now)
        enqueue(
            eventId = eventId,
            trackKey = existing.trackKey,
            artist = existing.artist,
            title = existing.title,
            stream = existing.stream,
            event = ReactionEvent.UNLIKE,
            occurredAt = now,
        )
        return true
    }

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
    open suspend fun undislike(
        trackKey: String,
        now: Long = System.currentTimeMillis(),
        eventId: String = newEventId(),
    ): Boolean {
        val existing = find(trackKey)
        if (existing?.reaction != Reaction.DISLIKED) return false

        neutraliseDisliked(trackKey, now)
        enqueue(
            eventId = eventId,
            trackKey = existing.trackKey,
            artist = existing.artist,
            title = existing.title,
            stream = existing.stream,
            event = ReactionEvent.UNDISLIKE,
            occurredAt = now,
        )
        return true
    }

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
        eventId: String = newEventId(),
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
        enqueue(
            eventId = eventId,
            trackKey = trackKey,
            artist = artist,
            title = title,
            stream = stream,
            event = ReactionEvent.DISLIKE,
            occurredAt = now,
        )
        return true
    }

    @Query("UPDATE track_reaction SET reaction = 'NEUTRAL', updated_at = :now WHERE track_key = :trackKey AND reaction = 'LIKED'")
    protected abstract suspend fun neutraliseLiked(trackKey: String, now: Long): Int

    @Query("UPDATE track_reaction SET reaction = 'NEUTRAL', updated_at = :now WHERE track_key = :trackKey AND reaction = 'DISLIKED'")
    protected abstract suspend fun neutraliseDisliked(trackKey: String, now: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun upsert(reaction: TrackReaction)

    /**
     * Appends the pending sync event for a transition that has just been written.
     *
     * Only ever called from inside one of the four [Transaction] methods above, and
     * only on the branch where the state really changed - which is what makes "a
     * pending event exists if and only if the transition happened" a property of the
     * database rather than a convention somebody has to keep.
     */
    private suspend fun enqueue(
        eventId: String,
        trackKey: String,
        artist: String,
        title: String,
        stream: String,
        event: ReactionEvent,
        occurredAt: Long,
    ) {
        insertOutboxEvent(
            ReactionOutboxEntry(
                eventId = eventId,
                trackKey = trackKey,
                artist = artist,
                title = title,
                stream = stream,
                eventType = event,
                occurredAt = occurredAt,
            )
        )
    }

    /**
     * The append itself. [OnConflictStrategy.ABORT] - the default - on purpose: an
     * event id that already exists means two different acts were minted one identity,
     * and silently replacing the first with the second would drop a reaction. It
     * throws instead, and the transaction that was writing the state change rolls
     * back with it, leaving neither half behind.
     */
    @Insert
    protected abstract suspend fun insertOutboxEvent(entry: ReactionOutboxEntry)
}
