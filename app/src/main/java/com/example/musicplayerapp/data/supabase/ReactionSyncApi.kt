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
     * **All three states upsert a row**, NEUTRAL included. A withdrawal is a state
     * the listener holds, not a gap where a state used to be, and storing it is what
     * gives it an `updated_at` to be compared against - see [ReactionSyncWire.remoteReaction].
     *
     * [current] being null is a different thing and the only remaining delete: the
     * local row is *gone*, which is what data removal looks like, and there are no
     * words left to write a row with. Normal sync never produces it - a reaction
     * that returns to neutral keeps its Room row.
     */
    suspend fun reconcileCurrentState(
        trackKey: String,
        current: TrackReaction?,
        listenerId: String,
    ): SyncOutcome

    /**
     * Applies one track's whole pending batch in a single server transaction.
     *
     * The G-A7 protocol, and the reason it exists: the pre-cutover path delivered an
     * event and reconciled current state as two calls, so a death between them and a
     * death after both are indistinguishable afterwards. `apply_reaction_event_batch`
     * appends the immutable events, writes the current state, and records which
     * events that state application represents - all or nothing. A retry of a batch
     * the server already committed answers [BatchOutcome.AlreadyApplied] and writes
     * nothing, which is what stops a delivered act being replayed over whatever
     * another device has since done.
     *
     * ## Why the whole track, not one event
     *
     * [current] is the cumulative result of every transition applied to the track, so
     * a state application made on behalf of one event also carries the effect of
     * every other pending event for it. Marking only the sent one would under-report
     * what the revision represents, and a sibling would later look genuinely
     * unapplied. [events] is therefore every pending atomic row for the track,
     * captured with [current] in one local transaction.
     *
     * ## [listenerId] is not sent, and is not decoration either
     *
     * The function takes its identity from `auth.uid()` in the caller's JWT, so
     * nothing about ownership is asserted by the client. That is right server-side,
     * and it removes something the old path had for free.
     *
     * The direct writes carried `listener_id` in the body, and every policy's
     * `with check` compared it against `auth.uid()`. So a batch assembled while this
     * device believed it was **X**, sent on a session that had since become **Y**,
     * was refused by the database. The RPC has no such column to disagree with: it
     * would store X's reactions and X's history under Y, legitimately, because Y is
     * who asked.
     *
     * So the implementation must restore that fail-closed property itself. Before
     * the call it reads the session it actually holds and refuses unless that uid is
     * exactly [listenerId] - see [SupabaseReactionSyncApi.applyBatch]. A mismatch is
     * [SyncOutcome.AuthUnavailable], never [SyncOutcome.Permanent]: the batch is
     * blameless, the session is simply not the one it was built for, and the identity
     * machinery is what resolves that.
     */
    suspend fun applyBatch(
        trackKey: String,
        events: List<ReactionOutboxEntry>,
        current: TrackReaction,
        listenerId: String,
    ): BatchOutcome

    /**
     * One page of [listenerId]'s current remote state, oldest revision first.
     *
     * The read half of G-A7. Keyset on `rev`, never OFFSET: `rev` is assigned from a
     * global sequence by a trigger on every insert and update, so a row written or
     * changed during a scan gets a value **above** anything already read and lands
     * ahead of the cursor rather than shifting rows underneath it. That is the
     * property offset pagination does not have, and the reason a mid-scan insert
     * cannot be skipped.
     *
     * A row updated during a scan may therefore be visited twice - once at its old
     * revision, once at its new one. That is allowed and safe: the local watermark
     * makes the second visit either a no-op or an upgrade, never a regression.
     *
     * @param afterRev exclusive lower bound; a run always starts at 0.
     * @param limit page size; a short page means the scan is finished.
     */
    suspend fun fetchReactionsPage(listenerId: String, afterRev: Long, limit: Int): PullPage

    /**
     * Removes **every** current-state row [listenerId] owns, in one call.
     *
     * The retirement half of an identity handoff, and it has to happen while that
     * listener's session is still the live one: RLS scopes deletes to `auth.uid()`,
     * so once the device has switched identities these rows are unreachable forever.
     *
     * Idempotent, and the handoff depends on that. Deleting rows that are already
     * gone is success - the desired state is "none", and none already being there is
     * that state - which is what lets one `PREPARED` stage cover a crash before,
     * during or after the delete with a single recovery.
     *
     * `reaction_events` is untouched. History stays with the identity that made it.
     */
    suspend fun retireAllCurrentState(listenerId: String): SyncOutcome
}

/**
 * What `apply_reaction_event_batch` did with one track's batch.
 *
 * The two successes are deliberately distinct, because the drain does different
 * things with them. Both settle the rows; only one of them may write state locally.
 */
sealed interface BatchOutcome {

    /**
     * The batch was genuinely unapplied and has now been applied and marked, at
     * [row]'s revision. Every represented event is recorded against it.
     */
    data class Applied(val row: RemoteReaction) : BatchOutcome

    /**
     * Every represented event had already been applied, so nothing was written.
     *
     * The stale-replay guard doing its job: this device is holding rows for a batch
     * the server committed before it died, and [row] is whatever the track looks like
     * now - possibly newer, because another device may have moved it since. The rows
     * are settled, and **no new revision was created**.
     */
    data class AlreadyApplied(val row: RemoteReaction?) : BatchOutcome

    /** The call did not land. Classified exactly as every other remote write is. */
    data class Failed(val outcome: SyncOutcome) : BatchOutcome
}

/**
 * One `public.reactions` row as the server currently holds it.
 *
 * Returned by the batch RPC so the drain can settle without a second round trip.
 * [rev] is the server-assigned revision - the cross-device ordering authority since
 * migration 0003, and the watermark G-A7c's pull will compare against.
 */
data class RemoteReaction(
    val trackKey: String,
    val reaction: Reaction,
    val likedAt: Long?,
    val artist: String,
    val title: String,

    /**
     * Nullable, because `reactions.stream` is - and because the empty string is not a
     * sentinel for anything in this app. Absence stays absence all the way to the
     * point where it has to be reconciled with a non-null local column, which is the
     * only place a normalisation is legitimate.
     */
    val stream: String?,
    val updatedAt: Long,
    val rev: Long,
)

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

    /**
     * The remote spelling of a local reaction. Total - every state has one.
     *
     * NEUTRAL used to map to null and mean "delete the row", which read well until
     * two devices had to be reconciled: an absent row carries no `updated_at`, so
     * the last-writer-wins guard that protects LIKED and DISLIKED had nothing to
     * compare a withdrawal against, and the only tie-break left was delivery order -
     * the one thing this design refuses to depend on. Migration 0002 makes NEUTRAL a
     * stored value, so all three travel the same guarded upsert.
     *
     * These names are the schema. `reactions.reaction` has a CHECK admitting exactly
     * these three, so a fourth spelling is a 400, not a silent bad row.
     */
    fun remoteReaction(reaction: Reaction): String = when (reaction) {
        Reaction.LIKED -> "LIKED"
        Reaction.DISLIKED -> "DISLIKED"
        Reaction.NEUTRAL -> "NEUTRAL"
    }

    /** The atomic apply function, live since migration 0003. */
    const val RPC_APPLY_BATCH = "apply_reaction_event_batch"

    /**
     * The remote spelling read back. Unknown is null rather than a guess: a fourth
     * value would mean the schema moved under us, and inventing a mapping would put
     * the wrong opinion in somebody's Collection.
     */
    fun localReaction(remote: String?): Reaction? = when (remote) {
        "LIKED" -> Reaction.LIKED
        "DISLIKED" -> Reaction.DISLIKED
        "NEUTRAL" -> Reaction.NEUTRAL
        else -> null
    }

    /**
     * A `timestamptz` the server rendered, back to device millis.
     *
     * `Instant.parse` handles the offset forms PostgREST emits. Null in, null out -
     * `liked_at` is legitimately absent for every non-LIKED row.
     */
    fun epochMillis(timestamp: String?): Long? =
        timestamp?.let { runCatching { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
}

/**
 * One page of remote current state, or why it could not be read.
 */
sealed interface PullPage {

    /**
     * The rows, in ascending `rev`. Fewer than the requested limit means the scan
     * has reached the end.
     */
    data class Rows(val rows: List<RemoteReaction>) : PullPage

    /** Classified exactly as every other remote call is. */
    data class Failed(val outcome: SyncOutcome) : PullPage
}

/**
 * Whether the session this device actually holds may send a batch built for
 * [expected].
 *
 * The whole of the ownership check, as one total function, for the same reason
 * `sessionVerdict` is one in `AuthResult`: the rule is the part worth testing
 * exhaustively, and it should not need a live project to say what it does.
 *
 * `apply_reaction_event_batch` takes its identity from `auth.uid()` and accepts no
 * `listener_id`. That is correct server-side and it removes a guarantee the direct
 * writes had for free: they sent `listener_id` in the body and every policy compared
 * it against `auth.uid()`, so a batch assembled as **X** and sent on a session that
 * had become **Y** was refused by the database. The RPC has nothing to disagree
 * with, so it would store X's reactions and X's history under Y - legitimately, as
 * far as the server can tell, because Y is who asked.
 *
 * Both failures are [SyncOutcome.AuthUnavailable] and neither is
 * [SyncOutcome.Permanent]. That distinction is the difference between a batch parked
 * for a day and a batch that delivers unchanged the moment the right session is
 * restored: nothing is wrong with these rows, and a wrong session is exactly the
 * condition the identity machinery exists to resolve.
 *
 * @return null when the session may send, or the outcome to answer with.
 */
internal fun ownershipVerdict(session: String?, expected: String): SyncOutcome.AuthUnavailable? =
    when {
        session == null -> SyncOutcome.AuthUnavailable("no restored session")
        session != expected -> SyncOutcome.AuthUnavailable("session is not the batch's listener")
        else -> null
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
