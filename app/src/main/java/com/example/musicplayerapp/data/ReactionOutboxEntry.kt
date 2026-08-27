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
 * @property syncProtocol which delivery protocol owns this row - see [SyncProtocol].
 *   Deliberately **not** defaulted in Kotlin: a row minted without a deliberate
 *   choice is the bug this whole cutover exists to prevent, so a missing argument
 *   should be a compile error rather than a silent LEGACY.
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

    // The SQL default is what back-fills rows that already existed at migration 3->4.
    // It never applies to a row this build inserts: Room names every entity column in
    // its generated INSERT, so the value always comes from the caller.
    @ColumnInfo(name = "sync_protocol", defaultValue = "LEGACY")
    val syncProtocol: SyncProtocol,
) {
    companion object {

        /** A fresh event identity. Random, so two devices can never mint the same one. */
        fun newEventId(): String = UUID.randomUUID().toString()
    }
}

/**
 * Which delivery protocol owns one outbox row.
 *
 * The device half of the G-A7 cutover. Migration 0003 gave the backend an atomic
 * apply function that makes a push exactly-once; the rows already sitting in a
 * listener's outbox when they update the app were written under the old two-call
 * protocol, which makes no such guarantee. The two cannot be mixed on one track, so
 * each row records which one it belongs to.
 *
 * Stored by name, like [ReactionEvent] and [Reaction], so the column reads as itself
 * in a sqlite shell.
 *
 * ## Why a row, and not a global flag
 *
 * A flag would have to say "this install has cut over", and that is not a fact any
 * install can state: one track can still owe a legacy delivery while another has
 * none. Worse, a row parked by a permanent failure never settles, so a global flag
 * gated on "the outbox is empty" would never flip. The pending rows **are** the
 * durable epoch, per track, and they need no second copy of themselves.
 */
enum class SyncProtocol {

    /**
     * The pre-G-A7 path: deliver the event, reconcile current state, delete the row.
     *
     * Two calls, so a death between them is indistinguishable from a death after
     * both - which is exactly why these rows must never reach the atomic RPC. The
     * server refuses an event it has seen but never marked, and it is right to.
     */
    LEGACY,

    /**
     * `apply_reaction_event_batch`: one call, one transaction, exactly-once.
     *
     * The server records which events a state application represents, so a retry
     * after a lost response settles instead of replaying.
     */
    ATOMIC_RPC,
}
