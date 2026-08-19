package com.example.musicplayerapp.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * One reaction transition that has happened locally and has not yet reached the
 * backend.
 *
 * This table is **not** the reaction state. [TrackReaction] is what the listener
 * currently thinks of a track - one row, overwritten in place. This is the log of
 * *transitions* between those states, append-only, and each row exists only until
 * it has been delivered. Reading the Collection from here would be wrong twice
 * over: rows leave once they are sent, and a track's history is several rows.
 *
 * ```
 *   track_reaction    current state    one row per track   mutable
 *   reaction_outbox   what happened    one row per act     immutable, drained
 * ```
 *
 * A row is written in the **same transaction** as the state change it describes -
 * see [ReactionDao] - so the two can never disagree. That is the whole reason the
 * table exists rather than a queue in memory or a retry around the network call:
 * the phone that recorded the Like may be in a lift, and the process may be gone
 * before it comes out.
 *
 * ## No listener_id
 *
 * Deliberately absent. The anonymous Supabase identity is created lazily and may
 * simply not exist at the moment somebody reacts offline - and blocking a Like on
 * a sign-in round trip is exactly the behaviour this design refuses. The identity
 * is attached at send time, when there is a network anyway and the session either
 * exists or can be made. An event is about a track and an act; who it belongs to
 * is a delivery detail.
 *
 * ## Why the words are copied
 *
 * [artist], [title] and [stream] are duplicated from [TrackReaction] rather than
 * joined on [trackKey]. The row has to describe the act *as it happened*: the
 * reaction row can be overwritten (or, later, cleaned up) before this event is
 * delivered, and the key is a one-way hash that cannot give the words back. An
 * event that has to consult mutable state to explain itself is not a log entry.
 *
 * @property eventId the identity of the act, and the primary key. A UUID minted
 *   when the transition commits, so delivery can be retried safely: the backend
 *   sees the same id and can discard the duplicate rather than counting the Like
 *   twice. It is the caller's to supply for exactly that reason, but the default
 *   is the right answer everywhere outside tests.
 * @property eventType which transition this was, as [ReactionEvent] - stored by
 *   name, like [Reaction], so the column reads as itself in a sqlite shell.
 * @property occurredAt when the listener acted, in device wall-clock millis. Not
 *   when it was sent; the gap between the two is the point of the table.
 * @property attempts how many delivery attempts this row has survived. Zero here;
 *   nothing sends anything yet.
 * @property nextAttemptAt the earliest wall-clock millis a sender may try again,
 *   for the backoff the delivery worker will need. Zero means "now", which is what
 *   every row starts as.
 */
@Entity(
    tableName = "reaction_outbox",
    // What the future sender reads by: the due rows, oldest act first. Indexed
    // because that query runs on every connectivity change, on a table whose whole
    // purpose is to usually be empty.
    indices = [Index(value = ["next_attempt_at"])],
)
data class ReactionOutboxEntry(

    @PrimaryKey
    @ColumnInfo(name = "event_id")
    val eventId: String,

    @ColumnInfo(name = "track_key")
    val trackKey: String,

    @ColumnInfo(name = "artist")
    val artist: String,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "stream")
    val stream: String,

    @ColumnInfo(name = "event_type")
    val eventType: ReactionEvent,

    @ColumnInfo(name = "occurred_at")
    val occurredAt: Long,

    @ColumnInfo(name = "attempts")
    val attempts: Int = 0,

    @ColumnInfo(name = "next_attempt_at")
    val nextAttemptAt: Long = 0L,
) {
    companion object {

        /** A fresh event identity. Random, so two devices can never mint the same one. */
        fun newEventId(): String = UUID.randomUUID().toString()
    }
}
