package com.example.musicplayerapp.data.supabase

import android.util.Log
import com.example.musicplayerapp.data.Reaction
import com.example.musicplayerapp.data.ReactionDao
import com.example.musicplayerapp.data.ReactionOutboxDao
import com.example.musicplayerapp.data.ReactionMigration
import com.example.musicplayerapp.data.ReactionWriteGate
import com.example.musicplayerapp.data.Streams

/**
 * Reading the account back. The whole algorithm, and no Android in it beyond a log
 * tag.
 *
 * This is the half of G-A7 that makes an account mean something on a second device.
 * The push has been complete since G-A7b, so a listener's reactions reach the cloud -
 * and until this exists they only ever go one way, which is why the authenticated
 * profile must not ship claiming cloud sync before it does.
 *
 * Separated from any trigger on purpose. Nothing calls it yet: when it runs is
 * G-A7d's decision, and a primitive that schedules itself is one that cannot be
 * tested by asking it to run.
 *
 * ## `rev`, and nothing else
 *
 * Ordering is the server's `rev` - assigned from a global sequence by a trigger on
 * every insert and update since migration 0003. `updated_at` is **never** consulted
 * for a decision here. It is the device wall clock of whoever wrote the row, kept
 * only because pre-cutover clients still guard their pushes with it, and using it to
 * decide a winner is the correctness failure the whole revision scheme replaced: a
 * phone running ten minutes fast could otherwise beat a genuinely later action
 * indefinitely.
 *
 * ## Every run starts at zero
 *
 * There is no durable cursor, and that is deliberate rather than unfinished. A
 * sequence hands out values before the transactions holding them commit, so two
 * concurrent writers can take revisions 10 and 11 and commit in the other order - and
 * a scan passing 11 in that window would never see 10 again if it had written a
 * watermark. A full scan from zero cannot miss it: the next run reads the row like
 * any other. An incremental cursor would turn a microsecond race into permanent
 * silent data loss, which is a poor trade for a few hundred rows.
 *
 * ## An absent remote stream, and one honest normalisation
 *
 * `reactions.stream` is nullable; `track_reaction.stream` is not. So a remote row
 * with no stream has to become *something* locally, and the rule is:
 *
 * ```
 * remote stream present     ->  use it
 * absent, local row exists  ->  keep the local stream
 * absent, no local row      ->  Streams.DEFAULT
 * ```
 *
 * The middle case is the important one: the server not having recorded a stream is
 * weaker evidence than what this device already knows, so a restore must not erase
 * it.
 *
 * The last case is a **legacy-compatibility normalisation, and not a faithful
 * representation of the remote NULL** - the local column cannot hold absence, so
 * something has to be chosen. `Streams.DEFAULT` is that something because this
 * project already answered the same question the same way: [ReactionMigration] maps
 * an absent legacy `favorites.stream` to `Streams.DEFAULT` when it builds a reaction
 * row. Following the existing convention keeps one answer in the codebase, not two.
 *
 * The empty string is deliberately **not** used and means nothing here: no production
 * code reads `track_reaction.stream == ""` as "unknown", and a value with no meaning
 * would travel back out through the outbox on the listener's next tap.
 *
 * ## Two locks, two jobs, as everywhere else in this package
 *
 * [SyncLease] excludes the push drain and the identity handoff for the whole run,
 * network included - a pull that interleaved with a handoff could read rows belonging
 * to an identity that is being retired. [ReactionWriteGate] is taken **per page**,
 * around local work only, and released before the next fetch. A tap never waits on a
 * network round trip.
 */
class ReactionPullEngine(
    private val reactions: ReactionDao,
    private val outbox: ReactionOutboxDao,
    private val api: ReactionSyncApi,
    private val eligibility: suspend () -> PullIdentity,

    /**
     * Runs one page's application as a single database transaction.
     *
     * Injected rather than reached for, so the algorithm can be driven against a real
     * Room database without this class knowing what a database is - the same reason
     * [ReactionSyncEngine] takes daos rather than a connection.
     */
    private val transaction: suspend (suspend () -> Unit) -> Unit,
    private val pageSize: Int = PAGE_SIZE,
) {

    /**
     * One full scan, under [SyncLease].
     *
     * `tryAcquire`, never waited on: a pull that cannot have the lease is one a drain
     * or a handoff is deliberately excluding, and queueing behind a registration to
     * do work that can simply happen later is how a background job becomes a hang.
     */
    suspend fun pull(): PullResult =
        SyncLease.tryAcquire { pullHoldingLease() } ?: PullResult.Busy

    private suspend fun pullHoldingLease(): PullResult {
        // Identity first, and fail closed. Reading somebody else's account into this
        // device's Collection is the worst thing this file could do, so it is the
        // first thing it refuses to do.
        val listenerId = when (val who = eligibility()) {
            is PullIdentity.Eligible -> who.uid
            is PullIdentity.NotEligible -> return PullResult.NotEligible(who.reason)
            is PullIdentity.Unavailable -> return PullResult.AuthUnavailable(who.reason)
        }

        var cursor = 0L
        var pages = 0
        var fetched = 0
        var applied = 0
        var skippedPending = 0
        var skippedStale = 0

        while (true) {
            when (val page = api.fetchReactionsPage(listenerId, cursor, pageSize)) {
                is PullPage.Failed -> return when (val outcome = page.outcome) {
                    // Earlier pages stay applied and stay valid. Nothing records how
                    // far this got, because the next run starts at zero and the
                    // watermark makes the replay a no-op.
                    is SyncOutcome.Transient -> PullResult.Transient(outcome.reason)
                    is SyncOutcome.AuthUnavailable -> PullResult.AuthUnavailable(outcome.reason)
                    is SyncOutcome.Permanent -> PullResult.Permanent(outcome.status, outcome.reason)
                    is SyncOutcome.Success -> PullResult.Transient("empty success")
                }

                is PullPage.Rows -> {
                    val rows = page.rows
                    pages++
                    fetched += rows.size

                    if (rows.isNotEmpty()) {
                        // The gate is taken here and nowhere near the fetch above.
                        val counts = ReactionWriteGate.withDeliveryStep {
                            var page = PageCounts()
                            transaction { page = applyPage(rows) }
                            page
                        }
                        applied += counts.applied
                        skippedPending += counts.skippedPending
                        skippedStale += counts.skippedStale

                        cursor = rows.last().rev
                    }

                    // A short page is the end of the account. Note this is decided on
                    // the fetched size, not the applied size: rows skipped locally are
                    // still rows the server returned.
                    if (rows.size < pageSize) {
                        Log.d(
                            TAG,
                            "pull complete: $pages page(s), $fetched row(s), $applied applied, " +
                                "$skippedPending held by pending local work, $skippedStale already current"
                        )
                        return PullResult.Completed(
                            uid = listenerId,
                            pages = pages,
                            fetched = fetched,
                            applied = applied,
                            skippedPending = skippedPending,
                            skippedStale = skippedStale,
                        )
                    }
                }
            }
        }
    }

    /**
     * One page, inside one database transaction and one hold of [ReactionWriteGate].
     *
     * Three decisions per row, in this order, and the order is the contract:
     *
     * 1. **A pending local mutation wins, and it wins by policy.** Any outbox row for
     *    this track - either protocol, due or parked, retryable or poisoned - means
     *    the listener did something this device has not managed to publish, and it is
     *    what they are looking at right now.
     *
     *    Deliberately **not** justified as "the remote row is older". Across devices
     *    that is not knowable: the remote row may carry a numerically higher `rev`
     *    written seconds ago on somebody's tablet, and this rule still holds. The
     *    reason is the same one the push side settles conflicts by - a genuine local
     *    act the server has not seen is not something a background read may quietly
     *    undo - and it is a choice about whose intent survives, not a claim about
     *    chronology. Nothing is touched: not the reaction, not the watermark, not the
     *    outbox.
     * 2. **The watermark.** With nothing pending, the row applies only if its
     *    revision is above what this device has already recorded for that track. That
     *    is what makes a page fetched before a local push harmless, and what makes a
     *    row visited twice in one scan - legal, since an update mid-scan moves a row
     *    ahead of the cursor - either an upgrade or a no-op, never a regression.
     * 3. **Otherwise adopt**, through a path that cannot enqueue anything.
     *
     * ## Absence is not a state
     *
     * Only rows the server actually returned are considered. A local track missing
     * from the whole scan is left exactly as it is - it is not inferred to be
     * NEUTRAL, and it is not deleted. Absence means the server has no opinion, which
     * is a different thing from the server holding the opinion "no reaction": that
     * one is a stored NEUTRAL row with its own revision, and it is what clears a
     * stale local LIKED.
     */
    private suspend fun applyPage(rows: List<RemoteReaction>): PageCounts {
        var applied = 0
        var skippedPending = 0
        var skippedStale = 0

        for (row in rows) {
            if (outbox.countForTrack(row.trackKey) > 0) {
                skippedPending++
                continue
            }

            val local = reactions.find(row.trackKey)
            val watermark = local?.remoteRev
            if (watermark != null && row.rev <= watermark) {
                skippedStale++
                continue
            }

            reactions.applyRemote(
                trackKey = row.trackKey,
                artist = row.artist,
                title = row.title,
                // The stream, normalised for a non-null column. See the header.
                stream = row.stream ?: local?.stream ?: Streams.DEFAULT,
                reaction = row.reaction,
                // The server's own value, never derived. `liked_at` is what orders a
                // restored Collection, and inventing it from `updated_at` or this
                // device's clock would put somebody's tracks in an order they never
                // chose. The schema guarantees LIKED iff liked_at is present; this
                // holds to that rather than trusting it.
                likedAt = if (row.reaction == Reaction.LIKED) row.likedAt else null,
                updatedAt = row.updatedAt,
                rev = row.rev,
            )
            applied++
        }

        return PageCounts(applied, skippedPending, skippedStale)
    }

    private data class PageCounts(
        val applied: Int = 0,
        val skippedPending: Int = 0,
        val skippedStale: Int = 0,
    )

    companion object {

        private const val TAG = "ReactionPull"

        /**
         * Rows per request.
         *
         * Large enough that an ordinary account is one round trip, small enough that
         * a page is a few tens of kilobytes. The value is also the end-of-scan test:
         * a page shorter than this is the last one.
         */
        const val PAGE_SIZE = 500
    }
}

/**
 * Whether this install may read an account back, and whose.
 *
 * Deliberately three answers rather than a nullable uid. "Not eligible" and "the
 * session is unavailable" lead to different behaviour later - one is a settled fact
 * about this install, the other is a condition that resolves itself - and collapsing
 * them would make a signed-out phone look like a broken one.
 */
sealed interface PullIdentity {

    /** REGISTERED locally, and the restored session agrees. */
    data class Eligible(val uid: String) : PullIdentity

    /** This install is not an account, so there is nothing to read back. */
    data class NotEligible(val reason: String) : PullIdentity

    /** There is an account, but no usable session for it right now. */
    data class Unavailable(val reason: String) : PullIdentity
}

/** What one pull concluded. */
sealed interface PullResult {

    /**
     * The account was read to the end.
     *
     * The counts are diagnostics, not a report to anybody: they exist so a failure to
     * converge can be explained without a debugger. [skippedPending] is the
     * interesting one - it is how many rows lost to a local act the server has not
     * seen yet, which is the correct outcome and not an error.
     */
    data class Completed(
        val uid: String,
        val pages: Int,
        val fetched: Int,
        val applied: Int,
        val skippedPending: Int,
        val skippedStale: Int,
    ) : PullResult

    /** This install is not an account. Nothing was read and nothing is owed. */
    data class NotEligible(val reason: String) : PullResult

    /** An account with no usable session. Blameless, and resolves on its own. */
    data class AuthUnavailable(val reason: String) : PullResult

    /** The network or the server. Earlier pages stay applied; the next run restarts. */
    data class Transient(val reason: String) : PullResult

    /** The server refused the read itself. Worth reporting rather than retrying blindly. */
    data class Permanent(val status: Int, val reason: String) : PullResult

    /** A drain or a handoff owns the lease. Nothing was read; try later. */
    data object Busy : PullResult
}
