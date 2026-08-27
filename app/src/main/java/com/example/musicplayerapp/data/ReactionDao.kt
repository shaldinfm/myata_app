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

    /**
     * Every reaction this install holds, in no particular order.
     *
     * Added for the identity handoff, which adopts the whole current state into a new
     * identity and therefore needs the rows themselves - not the Collection projection
     * [likedTracks] returns, which is LIKED only and drops the DISLIKED and NEUTRAL
     * rows the remote schema has held since migration 0002.
     *
     * A list rather than a Flow: adoption is a one-shot pass over a snapshot, and a
     * stream changing underneath it would be a source of surprises, not freshness.
     */
    @Query("SELECT * FROM track_reaction")
    abstract suspend fun allReactions(): List<TrackReaction>

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
    /**
     * Takes [ReactionWriteGate] **around** the transaction below, never inside it.
     *
     * The gate is what gives the identity handoff an ownership boundary: a reaction
     * either commits before the handoff observes the outbox empty, or after PREPARED
     * is on disk, and never between. Outside rather than inside because holding a
     * lock inside would keep a SQLite write transaction open while suspended, which
     * blocks every other writer behind a lock they cannot see.
     *
     * Uncontended in normal use - the only other holder is a cutover measured in
     * microseconds - so a tap waits on nothing it was not already waiting on.
     */
    open suspend fun like(
        trackKey: String,
        artist: String,
        title: String,
        stream: String,
        likedAt: Long,
        now: Long = System.currentTimeMillis(),
        eventId: String = newEventId(),
    ): Boolean = ReactionWriteGate.withReactionWrite {
        likeWithinTransaction(
            trackKey = trackKey,
            artist = artist,
            title = title,
            stream = stream,
            likedAt = likedAt,
            now = now,
            eventId = eventId,
        )
    }

    /** The transactional body of [like]. Do not call directly - it takes no lock. */
    @Transaction
    open suspend fun likeWithinTransaction(
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
    /**
     * Takes [ReactionWriteGate] **around** the transaction below, never inside it.
     *
     * The gate is what gives the identity handoff an ownership boundary: a reaction
     * either commits before the handoff observes the outbox empty, or after PREPARED
     * is on disk, and never between. Outside rather than inside because holding a
     * lock inside would keep a SQLite write transaction open while suspended, which
     * blocks every other writer behind a lock they cannot see.
     *
     * Uncontended in normal use - the only other holder is a cutover measured in
     * microseconds - so a tap waits on nothing it was not already waiting on.
     */
    open suspend fun unlike(
        trackKey: String,
        now: Long = System.currentTimeMillis(),
        eventId: String = newEventId(),
    ): Boolean = ReactionWriteGate.withReactionWrite {
        unlikeWithinTransaction(
            trackKey = trackKey,
            now = now,
            eventId = eventId,
        )
    }

    /** The transactional body of [unlike]. Do not call directly - it takes no lock. */
    @Transaction
    open suspend fun unlikeWithinTransaction(
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
    /**
     * Takes [ReactionWriteGate] **around** the transaction below, never inside it.
     *
     * The gate is what gives the identity handoff an ownership boundary: a reaction
     * either commits before the handoff observes the outbox empty, or after PREPARED
     * is on disk, and never between. Outside rather than inside because holding a
     * lock inside would keep a SQLite write transaction open while suspended, which
     * blocks every other writer behind a lock they cannot see.
     *
     * Uncontended in normal use - the only other holder is a cutover measured in
     * microseconds - so a tap waits on nothing it was not already waiting on.
     */
    open suspend fun undislike(
        trackKey: String,
        now: Long = System.currentTimeMillis(),
        eventId: String = newEventId(),
    ): Boolean = ReactionWriteGate.withReactionWrite {
        undislikeWithinTransaction(
            trackKey = trackKey,
            now = now,
            eventId = eventId,
        )
    }

    /** The transactional body of [undislike]. Do not call directly - it takes no lock. */
    @Transaction
    open suspend fun undislikeWithinTransaction(
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
    /**
     * Takes [ReactionWriteGate] **around** the transaction below, never inside it.
     *
     * The gate is what gives the identity handoff an ownership boundary: a reaction
     * either commits before the handoff observes the outbox empty, or after PREPARED
     * is on disk, and never between. Outside rather than inside because holding a
     * lock inside would keep a SQLite write transaction open while suspended, which
     * blocks every other writer behind a lock they cannot see.
     *
     * Uncontended in normal use - the only other holder is a cutover measured in
     * microseconds - so a tap waits on nothing it was not already waiting on.
     */
    open suspend fun dislike(
        trackKey: String,
        artist: String,
        title: String,
        stream: String,
        now: Long = System.currentTimeMillis(),
        eventId: String = newEventId(),
    ): Boolean = ReactionWriteGate.withReactionWrite {
        dislikeWithinTransaction(
            trackKey = trackKey,
            artist = artist,
            title = title,
            stream = stream,
            now = now,
            eventId = eventId,
        )
    }

    /** The transactional body of [dislike]. Do not call directly - it takes no lock. */
    @Transaction
    open suspend fun dislikeWithinTransaction(
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
                syncProtocol = protocolFor(trackKey),
            )
        )
    }

    /**
     * Which delivery protocol a brand-new row for [trackKey] belongs to.
     *
     * ```
     * a pending LEGACY row exists for this track  ->  LEGACY
     * otherwise                                   ->  ATOMIC_RPC
     * ```
     *
     * Read inside the same [Transaction] as the state change and the outbox insert,
     * which is what makes the choice atomic with the row it describes. Under
     * [ReactionWriteGate] too, because every caller of this holds it.
     *
     * ## Why the epoch has to extend
     *
     * The legacy path publishes the **current** `track_reaction` row, not the event's
     * own state. So a legacy delivery still owed for track K can carry the effect of
     * any reaction committed before it runs - including one made after the cutover.
     * If that later reaction were tagged ATOMIC_RPC, its effect could reach the cloud
     * through the legacy write, which creates no application marker, and the server
     * would later see a genuinely unapplied atomic event whose effect had already
     * been published. That is the stale-replay hole wearing a different costume.
     *
     * Inheriting LEGACY closes it: while K owes a legacy delivery, everything on K is
     * legacy, so nothing atomic can have its effect published unmarked. The moment
     * the last legacy row for K settles and is deleted, the next reaction on K is
     * atomic. Per track, one way, and no flag anywhere.
     *
     * A consequence worth naming: the pending rows for one track are therefore always
     * homogeneous. A row can only be LEGACY when a LEGACY row already exists, so a
     * mixed set cannot be constructed, and once a track has crossed over no LEGACY
     * row for it can ever be created again.
     */
    private suspend fun protocolFor(trackKey: String): SyncProtocol =
        if (hasPendingLegacy(trackKey)) SyncProtocol.LEGACY else SyncProtocol.ATOMIC_RPC

    /** Whether [trackKey] still owes a pre-cutover delivery. See [protocolFor]. */
    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM reaction_outbox
            WHERE track_key = :trackKey AND sync_protocol = 'LEGACY'
        )
        """
    )
    protected abstract suspend fun hasPendingLegacy(trackKey: String): Boolean

    /**
     * Records the server revision this device has observed for one track.
     *
     * Written only by the atomic push's settlement, and only when nothing else is
     * pending for that track - see `ReactionSyncEngine`. It touches no other column,
     * so it cannot disturb what the listener currently thinks.
     */
    @Query("UPDATE track_reaction SET remote_rev = :rev WHERE track_key = :trackKey")
    abstract suspend fun recordRemoteRev(trackKey: String, rev: Long): Int

    /**
     * Writes one remote row into local state, and **enqueues nothing**.
     *
     * Spelled as an explicit INSERT OR REPLACE rather than routed through one of the
     * four transitions, because those four exist to record that a listener *did*
     * something: each writes an outbox row in the same transaction as the state, and
     * that pairing is a property of the database rather than a convention. Adopting
     * what the server already holds is not an act. Pushing it back would be telling
     * the cloud what it just told us, and - since the events would be synthetic -
     * telling it in a vocabulary that describes acts nobody performed.
     *
     * So this is a separate path by construction: there is no branch in it that could
     * reach [ReactionEvent], and no way for a future edit to add one without moving
     * the statement.
     *
     * Every column comes from the remote row except [stream], which the caller
     * resolves - see `ReactionPullEngine`, where a NULL remote stream keeps whatever
     * the local row already had rather than erasing it.
     */
    @Query(
        """
        INSERT OR REPLACE INTO track_reaction
            (track_key, artist, title, stream, reaction, liked_at, updated_at, remote_rev)
        VALUES (:trackKey, :artist, :title, :stream, :reaction, :likedAt, :updatedAt, :rev)
        """
    )
    abstract suspend fun applyRemote(
        trackKey: String,
        artist: String,
        title: String,
        stream: String,
        reaction: Reaction,
        likedAt: Long?,
        updatedAt: Long,
        rev: Long,
    )

    /**
     * Forgets every server revision this device has recorded.
     *
     * A rev identifies one row belonging to one `auth.users` id, so it is a fact
     * about a listener and not about a track. The identity handoff calls this because
     * both of its outcomes invalidate every value at once: the source identity's rows
     * are deleted outright before the switch, and whatever is written afterwards -
     * into the destination on success, back into the source on rollback - gets fresh
     * revisions the device is never told. Carrying the old numbers across would leave
     * this install asserting a match with rows that no longer exist.
     *
     * Only the revisions. Not one reaction, not one word, not one timestamp: the
     * Collection is never touched by a handoff, and this must not become the
     * exception.
     */
    @Query("UPDATE track_reaction SET remote_rev = NULL WHERE remote_rev IS NOT NULL")
    abstract suspend fun clearRemoteRevs(): Int

    /**
     * Adopts remote current state for one track, and its revision, in one statement.
     *
     * The settlement half of an ALREADY_APPLIED answer: the events this device was
     * holding turned out to have been delivered already, and the row that came back
     * is newer than anything it can still contribute. Only ever called when the track
     * has no other pending mutation, because a pending mutation is a local act that
     * has not been published and must not be overwritten by state that predates it.
     *
     * **No outbox event is written.** Adopting what the server already holds is not
     * something the listener did, and `reaction_events` is a record of acts.
     */
    @Query(
        """
        UPDATE track_reaction
        SET reaction = :reaction, artist = :artist, title = :title, stream = :stream,
            liked_at = :likedAt, updated_at = :updatedAt, remote_rev = :rev
        WHERE track_key = :trackKey
        """
    )
    abstract suspend fun adoptRemote(
        trackKey: String,
        reaction: Reaction,
        artist: String,
        title: String,
        stream: String,
        likedAt: Long?,
        updatedAt: Long,
        rev: Long,
    ): Int

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
