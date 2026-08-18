package com.example.musicplayerapp.data

/**
 * One row of the Collection, as the list draws it.
 *
 * This used to be the Room entity for the `favorites` table. Storage now belongs to
 * [TrackReaction], and this is what [ReactionDao.likedTracks] projects LIKED rows
 * into: the same four fields the Collection has always drawn, plus the key that
 * identifies the row.
 *
 * It stays a type of its own rather than the screens taking [TrackReaction]
 * directly, because the Collection shows exactly one reaction state and has no use
 * for the other two: a row here is a liked track by construction, so no screen has
 * to check, and no screen can accidentally draw a disliked one.
 *
 * @property trackKey [TrackKey] v1, the row's identity. The adapter's DiffCallback
 *   compares it, and removal and undo carry it.
 * @property addedAt when the track entered the Collection - `TrackReaction.likedAt`.
 *   The list is ordered by it, so it is also the row's position: re-inserting a
 *   removed row with its original value is the whole of Undo.
 */
data class FavoriteTrack(
    val trackKey: String,
    val artist: String,
    val track: String,
    val stream: String,
    val addedAt: Long,
)
