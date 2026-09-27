package com.example.musicplayerapp.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Whose Collection the active reaction tables hold, and where every other owner's
 * rows wait.
 *
 * `track_reaction` and `reaction_outbox` are what the Collection screen, the PLAYER,
 * the drain and the pull all read, and they deliberately keep their shape: they hold
 * **one scope at a time**. This file adds the two things that make that safe:
 *
 *  - [CollectionScopeRow] - the scope the active tables belong to;
 *  - [ParkedReaction] / [ParkedOutboxEntry] - every *other* scope's rows and pending
 *    acts, each stamped with its owner, untouched until that owner is active again.
 *
 * Scopes are strings so one column can hold all three kinds:
 *
 * | key | whose |
 * |---|---|
 * | `device` | this install's own guest / anonymous Collection |
 * | `account:<uid>` | exactly one account's Collection and unsynced acts |
 * | `legacy` | restored or ambiguous pre-v5 data whose owner cannot be proven; never active, never uploaded |
 *
 * Moving between scopes is `CollectionScope.enterAccount` and nothing else. Nothing
 * here deletes a scope's rows: switching accounts parks the outgoing one and brings
 * the incoming one back.
 */
@Entity(tableName = "collection_scope")
data class CollectionScopeRow(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: Int = 0,

    @ColumnInfo(name = "scope")
    val scope: String,
)

/** One `track_reaction` row belonging to a scope that is not active. */
@Entity(tableName = "parked_reaction", primaryKeys = ["scope", "track_key"])
data class ParkedReaction(
    @ColumnInfo(name = "scope") val scope: String,
    @ColumnInfo(name = "track_key") val trackKey: String,
    @ColumnInfo(name = "artist") val artist: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "stream") val stream: String,
    @ColumnInfo(name = "reaction") val reaction: String,
    @ColumnInfo(name = "liked_at") val likedAt: Long?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "remote_rev") val remoteRev: Long?,
)

/**
 * One pending `reaction_outbox` row belonging to a scope that is not active.
 *
 * [ord] is the row's `rowid` when it was parked, so bringing a scope back restores
 * its acts in the order they were made - the drain's FIFO contract.
 */
@Entity(tableName = "parked_outbox", indices = [Index(value = ["scope"])])
data class ParkedOutboxEntry(
    @PrimaryKey @ColumnInfo(name = "event_id") val eventId: String,
    @ColumnInfo(name = "scope") val scope: String,
    @ColumnInfo(name = "ord") val ord: Long,
    @ColumnInfo(name = "track_key") val trackKey: String,
    @ColumnInfo(name = "artist") val artist: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "stream") val stream: String,
    @ColumnInfo(name = "event_type") val eventType: String,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long,
    @ColumnInfo(name = "attempts") val attempts: Int,
    @ColumnInfo(name = "next_attempt_at") val nextAttemptAt: Long,
    @ColumnInfo(name = "sync_protocol") val syncProtocol: String,
)

/**
 * The scope row and the parking moves. Set-based SQL throughout: a Collection of a
 * thousand rows moves in a handful of statements, and every caller runs these inside
 * one transaction so a death cannot leave a scope half-moved.
 */
@Dao
interface CollectionScopeDao {

    /** The active scope, or null for a database created at v5 that has not settled one. */
    @Query("SELECT scope FROM collection_scope WHERE id = 0")
    suspend fun scope(): String?

    @Query("INSERT OR REPLACE INTO collection_scope (id, scope) VALUES (0, :scope)")
    suspend fun setScope(scope: String)

    /** Whether the active tables hold anything at all. */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM track_reaction) OR EXISTS(SELECT 1 FROM reaction_outbox)"
    )
    suspend fun hasActiveRows(): Boolean

    /** [scope], as it changes. What the visibility guard watches. */
    @Query("SELECT scope FROM collection_scope WHERE id = 0")
    fun observeScope(): Flow<String?>

    // ---------------------------------------------------------------- park --

    @Query(
        """
        INSERT OR REPLACE INTO parked_reaction
            (scope, track_key, artist, title, stream, reaction, liked_at, updated_at, remote_rev)
        SELECT :scope, track_key, artist, title, stream, reaction, liked_at, updated_at, remote_rev
        FROM track_reaction
        """
    )
    suspend fun parkReactions(scope: String)

    @Query(
        """
        INSERT OR REPLACE INTO parked_outbox
            (event_id, scope, ord, track_key, artist, title, stream, event_type,
             occurred_at, attempts, next_attempt_at, sync_protocol)
        SELECT event_id, :scope, rowid, track_key, artist, title, stream, event_type,
             occurred_at, attempts, next_attempt_at, sync_protocol
        FROM reaction_outbox
        """
    )
    suspend fun parkOutbox(scope: String)

    // -------------------------------------------------------------- unpark --

    @Query(
        """
        INSERT OR REPLACE INTO track_reaction
            (track_key, artist, title, stream, reaction, liked_at, updated_at, remote_rev)
        SELECT track_key, artist, title, stream, reaction, liked_at, updated_at, remote_rev
        FROM parked_reaction WHERE scope = :scope
        """
    )
    suspend fun unparkReactions(scope: String)

    /**
     * As [unparkReactions], for a device Collection being adopted into an account.
     *
     * Its revisions name some other identity's rows, so they go - and are replaced by
     * `CollectionScope.ADOPTED_BASELINE`, never by NULL. NULL means "no baseline yet"
     * and the account's initial restore rebases such a row's pending acts onto the
     * server's revision, which is right for a tap made after signing in and wrong for
     * state made before it: a guest's pre-sign-in Like must not overwrite what the
     * account already holds. The adopted baseline says "the server had no row", so
     * the causal guard applies an adopted act only where that is still true.
     */
    @Query(
        """
        INSERT OR REPLACE INTO track_reaction
            (track_key, artist, title, stream, reaction, liked_at, updated_at, remote_rev)
        SELECT track_key, artist, title, stream, reaction, liked_at, updated_at, 0
        FROM parked_reaction WHERE scope = :scope
        """
    )
    suspend fun unparkReactionsAsAdopted(scope: String)

    @Query(
        """
        INSERT INTO reaction_outbox
            (event_id, track_key, artist, title, stream, event_type,
             occurred_at, attempts, next_attempt_at, sync_protocol)
        SELECT event_id, track_key, artist, title, stream, event_type,
             occurred_at, attempts, next_attempt_at, sync_protocol
        FROM parked_outbox WHERE scope = :scope ORDER BY ord ASC
        """
    )
    suspend fun unparkOutbox(scope: String)

    @Query("DELETE FROM parked_reaction WHERE scope = :scope")
    suspend fun deleteParkedReactions(scope: String): Int

    @Query("DELETE FROM parked_outbox WHERE scope = :scope")
    suspend fun deleteParkedOutbox(scope: String): Int

    // ------------------------------------------------------------- merging --

    /** Re-labels [from]'s rows for tracks the active tables already hold. */
    @Query(
        """
        UPDATE parked_reaction SET scope = :to
        WHERE scope = :from AND track_key IN (SELECT track_key FROM track_reaction)
        """
    )
    suspend fun holdReactionConflicts(from: String, to: String): Int

    @Query(
        """
        UPDATE parked_outbox SET scope = :to
        WHERE scope = :from AND track_key IN (SELECT track_key FROM track_reaction)
        """
    )
    suspend fun holdOutboxConflicts(from: String, to: String): Int

    @Query("UPDATE parked_reaction SET scope = :to WHERE scope = :from")
    suspend fun renameParkedReactions(from: String, to: String): Int

    @Query("UPDATE parked_outbox SET scope = :to WHERE scope = :from")
    suspend fun renameParkedOutbox(from: String, to: String): Int

    // ---------------------------------------------------------- inspection --

    @Query("SELECT COUNT(*) FROM parked_reaction WHERE scope = :scope")
    suspend fun parkedReactionCount(scope: String): Int

    @Query("SELECT COUNT(*) FROM parked_outbox WHERE scope = :scope")
    suspend fun parkedOutboxCount(scope: String): Int

    @Query("SELECT * FROM parked_reaction WHERE scope = :scope")
    suspend fun parkedReactions(scope: String): List<ParkedReaction>
}
