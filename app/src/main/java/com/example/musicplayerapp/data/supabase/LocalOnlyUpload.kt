package com.example.musicplayerapp.data.supabase

import android.util.Log
import com.example.musicplayerapp.data.ReactionDao
import com.example.musicplayerapp.data.TrackReaction

/**
 * Publishes the opinions this device holds that no server has ever confirmed.
 *
 * ## Why it is needed
 *
 * The push is driven by outbox events, and an outbox event exists only for a
 * transition this build observed. Favourites carried over from the old `favorites`
 * table by `ReactionMigration.MIGRATION_1_2` have none - deliberately, since
 * synthesising a LIKE for each would claim acts nobody performed - so they reached the
 * cloud only when an anonymous identity was handed off (whose adoption writes every
 * row). A listener who signed in directly kept them on the phone for good, and a
 * reinstall deleted them. That was the P1.
 *
 * ## The rules
 *
 *  - **Only rows of the account being pulled.** The caller has already brought the
 *    local tables into this account's scope (`CollectionScope.enterAccountHoldingLease`),
 *    so a row still here is either the account's own or the listener's device
 *    Collection that the account adopted. Another account's rows were parked at
 *    that boundary and can never reach this call.
 *  - **Only rows with no revision and no pending act** ([ReactionDao.localOnly]). A
 *    pending act is published by the drain, with its history; a revision means a
 *    server already holds the row.
 *  - **Insert-if-absent, never update** ([ReactionSyncApi.insertIfAbsent]). Whatever
 *    the account already holds for a track wins, whatever its revision and whatever
 *    this device's clock says - a favourite that sat unpublished is older information
 *    than anything the account recorded since.
 *  - **Current state only.** No `reaction_events` row is written: none of this is an
 *    act the listener performed now.
 *
 * ## Idempotence
 *
 * Nothing is recorded locally. The pull that runs straight after (under the same
 * lease) adopts every row this inserted, with its revision, which takes it out of
 * [ReactionDao.localOnly]; if that pull fails, the next run inserts again and the
 * account already holds every row, so nothing is written. A retry, a crash anywhere,
 * or a second run changes nothing.
 */
class LocalOnlyUpload(
    private val reactions: ReactionDao,
    private val api: ReactionSyncApi,
) {

    /** What one run did. Diagnostics; nothing branches on it except the log. */
    data class Result(
        val candidates: Int,
        val inserted: Int,
        val alreadyPresent: Int,
        val refused: Int,
        /** Set when the run stopped early on the network or the session. */
        val stoppedBy: SyncOutcome? = null,
    )

    suspend fun run(listenerId: String): Result = publish(reactions.localOnly(), listenerId)

    /**
     * Inserts [rows] into [listenerId]'s account where it holds nothing for the track,
     * with the batch-then-row-by-row handling [run] uses. Shared with the anonymous ->
     * account handoff, whose adoption follows the same rule: the account's existing
     * reactions win over the device's.
     */
    suspend fun publish(rows: List<TrackReaction>, listenerId: String): Result {
        if (rows.isEmpty()) return Result(0, 0, 0, 0)

        val tally = Tally()
        for (batch in rows.chunked(BATCH_SIZE)) {
            val stopped = send(batch, listenerId, tally)
            if (stopped != null) {
                return Result(rows.size, tally.inserted, tally.present, tally.refused, stopped).also(::log)
            }
        }
        return Result(rows.size, tally.inserted, tally.present, tally.refused).also(::log)
    }

    private class Tally {
        var inserted = 0
        var present = 0
        var refused = 0
    }

    /**
     * One statement for [batch]. Returns the outcome that should stop the run, or null.
     *
     * A permanent refusal of a batch is one row's fault as often as not - a migrated
     * favourite with an empty artist, which the server's length check refuses - so a
     * refused batch is retried one row at a time, and only the row actually refused
     * stays behind. It is kept locally and offered again next run: nothing here ever
     * deletes a favourite.
     */
    private suspend fun send(batch: List<TrackReaction>, listenerId: String, tally: Tally): SyncOutcome? {
        when (val outcome = api.insertIfAbsent(batch, listenerId)) {
            is InsertOutcome.Written -> {
                tally.inserted += outcome.inserted.size
                tally.present += batch.size - outcome.inserted.size
                return null
            }

            is InsertOutcome.Failed -> return when (val why = outcome.outcome) {
                is SyncOutcome.Permanent ->
                    if (batch.size == 1) {
                        tally.refused++
                        null
                    } else {
                        batch.firstNotNullOfOrNull { send(listOf(it), listenerId, tally) }
                    }

                // The network or the session. Nothing further will get through, and
                // every row is still here for the next run.
                is SyncOutcome.Transient,
                is SyncOutcome.AuthUnavailable,
                -> why

                // Not produced for a failure; read as the safe direction.
                SyncOutcome.Success -> SyncOutcome.Transient("an insert failed without a reason")
            }
        }
    }

    private fun log(result: Result) {
        Log.d(
            TAG,
            "local-only upload: ${result.candidates} candidate(s), ${result.inserted} inserted, " +
                "${result.alreadyPresent} already held by the account, ${result.refused} refused" +
                (result.stoppedBy?.let { "; stopped: $it" } ?: ""),
        )
    }

    companion object {
        private const val TAG = "ReactionPull"

        /** Rows per statement. One round trip for an ordinary Collection. */
        const val BATCH_SIZE = 200
    }
}
