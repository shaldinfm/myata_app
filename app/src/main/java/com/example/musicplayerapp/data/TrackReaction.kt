package com.example.musicplayerapp.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * What one listener thinks of one track, and the app's only reaction storage.
 *
 * This replaces the `favorites` table, which could record exactly one thing - that
 * a track was saved - and so had no way to hold a dislike, and no way to tell a
 * track nobody has reacted to from one whose Like was withdrawn. Three states, one
 * row per track:
 *
 *  - [Reaction.NEUTRAL] - no opinion, or an opinion withdrawn. Not in the Collection.
 *  - [Reaction.LIKED] - saved. **This is what the Collection shows.**
 *  - [Reaction.DISLIKED] - explicit negative. Not in the Collection either.
 *
 * A row is kept when a reaction goes back to NEUTRAL rather than deleted, so
 * [updatedAt] still says when that happened. Nothing outside this package reads
 * NEUTRAL rows today; the reason to keep them is that the sync work will need
 * something to point at.
 *
 * [trackKey] is [TrackKey] v1 - the deterministic hash of the normalised artist and
 * title, which is the identity contract in `docs/TRACKKEY-V1.md`. It is the primary
 * key, which is what makes "one listener, one opinion per track" structural rather
 * than something the callers have to remember. [artist] and [title] are kept beside
 * it because the key cannot be reversed and every screen, export and analytics
 * report needs the words back.
 *
 * @property stream where the track was last reacted to. Metadata only: a reaction
 *   is to a track, not to a track-on-a-stream, per the owner decision. The old
 *   unique index ignored it too, so this changes nothing.
 * @property likedAt when the track last entered [Reaction.LIKED], and null if it
 *   never has. The Collection is ordered by it, so it carries a row's position in
 *   the list - which is how Undo puts a removed row back where it was instead of at
 *   the top. It is the old `favorites.addedAt` under a name that says what it means.
 * @property updatedAt when the reaction last changed, whatever it changed to.
 */
@Entity(tableName = "track_reaction")
data class TrackReaction(

    @PrimaryKey
    @ColumnInfo(name = "track_key")
    val trackKey: String,

    @ColumnInfo(name = "artist")
    val artist: String,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "stream")
    val stream: String,

    @ColumnInfo(name = "reaction")
    val reaction: Reaction,

    @ColumnInfo(name = "liked_at")
    val likedAt: Long?,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

/**
 * The three states a listener + track can be in.
 *
 * Stored by name - Room persists an enum as its constant name - so the column reads
 * as itself in a schema dump or a sqlite shell, and so adding a state later cannot
 * silently renumber the existing ones.
 *
 * The transitions between them are [ReactionEvent]. Note what is missing: there is
 * no state for "un-liked", because that *is* [NEUTRAL]. Withdrawing a Like and
 * disliking a track are different things, which is the bug the whole reaction model
 * exists to stop repeating.
 */
enum class Reaction {
    NEUTRAL,
    LIKED,
    DISLIKED,
}
