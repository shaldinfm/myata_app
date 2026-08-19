package com.example.musicplayerapp.data.supabase

import com.example.musicplayerapp.data.Reaction
import com.example.musicplayerapp.data.ReactionOutboxEntry
import com.example.musicplayerapp.data.TrackReaction
import java.io.IOException
import java.time.Instant

/**
 * The two remote writes one outbox row turns into, and nothing else.
 *
 * An interface rather than a class because the drain algorithm is the part worth
 * testing hard - every transition, every failure, every restart - and none of that
 * should need a network or a live project. [SupabaseReactionSyncApi] is the real
 * one; the tests supply a fake that can fail on demand in each of the ways
 * [SyncOutcome] names.
 *
 * The split between the two methods is the data contract, not a convenience:
 *
 *  - [deliverEvent] appends to `reaction_events`, immutable history, carrying the
 *    row's **original** `event_id`, `occurred_at`, `artist`, `title` and `stream`.
 *    What happened does not get rewritten on its way out.
 *  - [reconcileCurrentState] writes `reactions`, the current opinion, from the
 *    **current** local [TrackReaction] read at send time - never from the event.
 *
 * That second one is the whole reason a delayed event cannot corrupt anything. A
 * row that has been sitting in the outbox for a week still delivers its week-old
 * history entry, but the state it reconciles is what the listener thinks *now*.
 */
interface ReactionSyncApi {

    /**
     * Appends one transition to the immutable history.
     *
     * Must be idempotent on `event_id`: a retry of an event that already landed is
     * [SyncOutcome.Success], not a second row and not an error.
     */
    suspend fun deliverEvent(entry: ReactionOutboxEntry, listenerId: String): SyncOutcome

    /**
     * Makes the remote current state match [current], which is the local row as it
     * is right now, or null when the track has no local row at all.
     *
     * LIKED and DISLIKED upsert a row; NEUTRAL - and a missing row, which means the
     * same thing - delete it. Absence is how the schema spells NEUTRAL.
     */
    suspend fun reconcileCurrentState(
        trackKey: String,
        current: TrackReaction?,
        listenerId: String,
    ): SyncOutcome
}

/**
 * What one remote write did, classified so the drain can decide what to do next.
 *
 * The classification is the retry policy. Getting it wrong in either direction is
 * expensive: treat a transient failure as permanent and a listener's reaction is
 * parked for a day over a lift; treat a permanent one as transient and one poison
 * row retries forever.
 */
sealed interface SyncOutcome {

    /**
     * Landed, or had already landed. The two are deliberately one case: an
     * `ignore-duplicates` insert of an event that is already there reports nothing
     * inserted, and that is exactly as good as inserting it.
     */
    data object Success : SyncOutcome

    /**
     * Nothing is wrong with the row - the network or the server is unavailable.
     * Timeouts, DNS, connection resets, 5xx, 429. Retry soon, keep the row, and
     * stop the batch: if one call could not reach the server the next one will not
     * either.
     */
    data class Transient(val reason: String) : SyncOutcome

    /**
     * There is no usable session. The row is untouched and not penalised - it did
     * nothing wrong, and a 401 means the token needs refreshing, not that the
     * listener's Like is invalid.
     */
    data class AuthUnavailable(val reason: String) : SyncOutcome

    /**
     * The server refused this row and will refuse it again: an RLS violation, a
     * CHECK constraint, a malformed payload. Park it for a long time and **move on
     * to the next row** - one bad row must never hold up the ones behind it.
     *
     * It is still never deleted. A row that cannot sync is evidence.
     */
    data class Permanent(val status: Int, val reason: String) : SyncOutcome
}

/**
 * Wire-format helpers shared by the real API and the tests that assert on it.
 */
object ReactionSyncWire {

    /** `reaction_events` - immutable history. */
    const val TABLE_EVENTS = "reaction_events"

    /** `reactions` - current opinion. */
    const val TABLE_REACTIONS = "reactions"

    /**
     * Device epoch millis as a `timestamptz` PostgREST accepts.
     *
     * `Instant.toString()` renders UTC with a `Z`, and the `Z` is not cosmetic: an
     * offset written `+00:00` has a `+` in it, and when such a value is used as a
     * filter in a query string the `+` decodes as a space server-side and Postgres
     * rejects the timestamp outright. A live probe produced exactly that - `invalid
     * input syntax for type timestamp with time zone: "…T08:32:26 00:00"` - so the
     * `Z` form is the one that is safe in both a body and a filter.
     *
     * `java.time` on API 24 is core library desugaring's job, which is enabled and
     * gated by `SupabaseFoundationTest`.
     */
    fun timestamp(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).toString()

    /** The remote spelling of a local reaction, or null for NEUTRAL, which is absence. */
    fun remoteReaction(reaction: Reaction): String? = when (reaction) {
        Reaction.LIKED -> "LIKED"
        Reaction.DISLIKED -> "DISLIKED"
        Reaction.NEUTRAL -> null
    }
}

/**
 * Which kind of failure an exception is.
 *
 * The status codes are not guesses; they are what the live project actually
 * returned when each case was provoked:
 *
 * | provoked | status | postgres code |
 * |---|---|---|
 * | event owned by another listener (RLS) | 403 | 42501 |
 * | malformed `track_key` (CHECK) | 400 | 23514 |
 * | unknown `event_type` (CHECK) | 400 | 23514 |
 * | garbage bearer token | 401 | PGRST301 |
 * | duplicate `event_id`, plain insert | 409 | 23505 |
 *
 * 401 and 403 land in different buckets on purpose. A 401 is *this request* having
 * no valid token, which the next refresh fixes; a 403 is the policy refusing this
 * *row*, which no retry fixes.
 */
fun classifyFailure(t: Throwable): SyncOutcome {
    val rest = generateSequence(t) { it.cause }
        .filterIsInstance<io.github.jan.supabase.exceptions.RestException>()
        .firstOrNull()

    if (rest != null) {
        return classifyStatus(rest.statusCode, rest.error.ifBlank { rest.message.orEmpty() })
    }

    if (generateSequence(t) { it.cause }.any { it is IOException }) {
        return SyncOutcome.Transient(t.javaClass.simpleName)
    }

    // Anything unrecognised is treated as transient. An unknown failure that is
    // really permanent costs some pointless retries; an unknown failure treated as
    // permanent would park a listener's reaction for a day over a bug of ours.
    return SyncOutcome.Transient(t.javaClass.simpleName + ": " + t.message.orEmpty().take(80))
}

/**
 * The status-code half of [classifyFailure], split out so the taxonomy can be
 * asserted exhaustively without constructing a Ktor `HttpResponse` to wrap in a
 * `RestException`. The table above is what this encodes.
 */
internal fun classifyStatus(status: Int, detail: String): SyncOutcome = when {
    // Already there. Only reachable if an insert somehow ran without
    // ignore-duplicates; it still means the history is correct.
    status == 409 -> SyncOutcome.Success
    status == 401 -> SyncOutcome.AuthUnavailable("401 $detail")
    status == 429 -> SyncOutcome.Transient("429 rate limited")
    status >= 500 -> SyncOutcome.Transient("$status server error")
    else -> SyncOutcome.Permanent(status, detail)
}
