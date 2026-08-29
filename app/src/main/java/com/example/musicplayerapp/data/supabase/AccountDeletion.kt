package com.example.musicplayerapp.data.supabase

import android.content.Context
import android.util.Log
import java.util.UUID

/**
 * Permanently deleting the account this install is signed in as.
 *
 * The client half of `docs/ACCOUNT-DELETION.md`. Nothing in `src/main` calls this
 * yet - the screen is a later phase - but the flow is complete and exercised offline.
 *
 * ## The ordering, and why each step is where it is
 *
 * ```
 * SyncLease.withExclusive {
 *     read the identity INSIDE the section        <- it is this read that decides
 *     Registered(X), session uid == X, no handoff, no deletion already in flight
 *     mint R once
 *     markDeletionRequested(R, X)                 <- durable, BEFORE the RPC
 *     deleteAccount(R)                            <- network, still under the lease
 *     Deleted / AlreadyDeleted -> markDeletionConfirmed, then local cleanup
 *     Refused                  -> clear the marker; the install is untouched
 *     Failed                   -> leave REQUESTED exactly where it is
 * }
 * ```
 *
 * **The lease is held across the network call.** That is what stops a drain built and
 * ownership-checked as X from being dispatched while the account is being destroyed;
 * `IdentityHandoff.finish` holds it across its own remote calls for the same reason.
 * Only [SyncLease] is taken here - [com.example.musicplayerapp.data.ReactionWriteGate]
 * is taken inside the cleanup, around local work only, so a tap never waits on a round
 * trip.
 *
 * **The marker is committed before the call, not after.** Deleting `auth.users`
 * invalidates the refresh credentials at once, so a response that is lost may be the
 * last thing this device ever learns about the request. What cannot be inferred
 * afterwards has to be recorded beforehand - the same rule `markHandoffPrepared`
 * follows, and the reason the token exists at all.
 *
 * ## What a retry does, and does not
 *
 * A retry re-sends the **same** `R`, read back from the marker. A fresh token would
 * ask the server about a deletion it holds no receipt for, and the status route would
 * answer `UNKNOWN` forever. [IdentityReconciler] owns retrying; this object never
 * loops.
 *
 * ## Not implemented here
 *
 * Resolving an unresolved deletion by **explicitly signing in again as X** is part of
 * the frozen contract and is deliberately absent: authentication from
 * [IdentityState.Registered] is not a defined transition in the frozen G-A4 routing,
 * and making it one is a change to the auth contract rather than to this one. It is an
 * optional resolution - the primary one, the session-less status route, needs no
 * sign-in - and it is deferred to its own slice.
 */
object AccountDeletion {

    private const val TAG = "SupabaseAuth"

    /**
     * Asks for this install's account to be deleted, permanently.
     *
     * @return what actually happened. [AccountDeletionResult.Deleted] is returned
     *   **only** once local cleanup has reached `forgetDeletedAccount`; a confirmed
     *   server deletion whose cleanup could not finish is
     *   [AccountDeletionResult.CleanupDeferred], which is not a completed deletion to
     *   report to anybody.
     */
    suspend fun request(context: Context): AccountDeletionResult =
        SyncLease.withExclusive { requestHoldingLease(context.applicationContext) }

    private suspend fun requestHoldingLease(context: Context): AccountDeletionResult {
        val api = EmailAuthBackend.api(context)

        // Read inside the critical section, and it is this read that decides. Deciding
        // outside and acting inside is the stale-state bug EmailAuthRepository.route
        // documents; the same argument applies here, one destructive step further on.
        val state = IdentityStore.state(context)
        if (state !is IdentityState.Registered) {
            return refuse("this install is ${state.javaClass.simpleName}, not an account")
        }
        val uid = state.uid

        // Already in flight. Starting again would mint a second token for one deletion
        // and orphan the first, whose receipt is the only thing that can resolve it.
        IdentityStore.deletion(context)?.let {
            return refuse("a deletion is already unresolved at ${it.stage}")
        }

        // A handoff record on disk means this install is mid-identity-change and its
        // remote state may already be partly retired. Whatever resolves that owns the
        // device first; deleting into it would be two algorithms writing one identity.
        IdentityStore.handoff(context)?.let {
            return refuse("a handoff is unresolved at ${it.stage}")
        }

        // The session is what the server will actually enforce. `auth.uid()` inside
        // the function decides whose account dies, so a device whose session is not X
        // must not send the request at all - not because it would delete the wrong
        // account, but because it would delete an account this install did not mean.
        val session = runCatching { api.currentUid() }.getOrNull()
        if (session == null || session != uid) {
            return refuse("no live session for this account")
        }

        // One token, minted once, reused by every retry from here on.
        val requestId = UUID.randomUUID().toString()
        IdentityStore.markDeletionRequested(context, requestId, uid)
        Log.d(TAG, "account deletion requested")

        return settleHoldingLease(context, api.deleteAccount(requestId), requestId, uid)
    }

    /**
     * Turns one `delete_my_account` answer into durable state.
     *
     * Shared with [IdentityReconciler], which retries the call on a later start and
     * must resolve it identically. **Requires [SyncLease] to be held already** - both
     * callers take it, and the mutex is not reentrant.
     */
    internal suspend fun settleHoldingLease(
        context: Context,
        outcome: DeleteAccountOutcome,
        requestId: String,
        uid: String,
    ): AccountDeletionResult = when (outcome) {

        // Both are proof that the account is gone and that a receipt for this token
        // exists. ALREADY_DELETED is the ordinary answer for the second of two devices
        // and for a retry whose first response was lost, and it is not a lesser
        // success: it is treated exactly as DELETED.
        is DeleteAccountOutcome.Deleted, DeleteAccountOutcome.AlreadyDeleted -> {
            IdentityStore.markDeletionConfirmed(context, requestId, uid)
            finishHoldingLease(context, uid)
        }

        // The function ran and refused, and a plpgsql RAISE aborts the transaction -
        // so nothing was deleted and the install is exactly as it was. This is the one
        // outcome that may retract the request.
        is DeleteAccountOutcome.Refused -> {
            IdentityStore.clearDeletionMarker(context)
            Log.w(TAG, "account deletion refused by the server (${outcome.sqlState})")
            AccountDeletionResult.Refused(outcome.sqlState)
        }

        // Anything else proves nothing in either direction. The marker stays, the
        // install stays sync-dead, and a later start asks again - with the same token.
        is DeleteAccountOutcome.Failed -> {
            Log.w(TAG, "account deletion unresolved: ${outcome.failure.detail}")
            AccountDeletionResult.Unresolved(outcome.failure.detail)
        }
    }

    /**
     * Runs the local cleanup for a confirmed deletion. **Requires [SyncLease] held.**
     *
     * Split out so [IdentityReconciler] can reach it directly for a `CONFIRMED` marker
     * without going near the server, which is the one thing that stage forbids.
     */
    internal suspend fun finishHoldingLease(
        context: Context,
        uid: String,
    ): AccountDeletionResult = when (val cleanup = AccountDeletionCleanup.run(context, uid)) {
        is AccountDeletionCleanup.Outcome.Completed -> AccountDeletionResult.Deleted
        is AccountDeletionCleanup.Outcome.Deferred ->
            AccountDeletionResult.CleanupDeferred(cleanup.why)
    }

    private fun refuse(why: String): AccountDeletionResult {
        Log.d(TAG, "account deletion not started: $why")
        return AccountDeletionResult.NotEligible(why)
    }
}

/**
 * What one deletion attempt concluded.
 *
 * Deliberately five values and no more. Everything a caller needs to decide is here:
 * whether the account is gone, whether anything is still owed, and whether the install
 * is still the account it was.
 */
sealed interface AccountDeletionResult {

    /**
     * The account is gone and this device has finished acting on it.
     *
     * **The only value that may be reported to a person as a completed deletion**, and
     * it is returned only after `forgetDeletedAccount` has run - not merely after the
     * server confirmed. See [CleanupDeferred] for why the distinction is load-bearing.
     */
    data object Deleted : AccountDeletionResult

    /**
     * The server deletion is confirmed; the device has not finished cleaning up.
     *
     * `CONFIRMED` is durable and the install is still sync-dead, so the next
     * reconciliation retries. The account really is gone - but saying "done" while
     * this device still holds a session for it would be a claim nobody has checked.
     */
    data class CleanupDeferred(val why: String) : AccountDeletionResult

    /**
     * The server refused, provably without deleting anything.
     *
     * The deletion marker has been cleared and the install is untouched: same
     * identity, same Collection, same outbox, sync resumed.
     */
    data class Refused(val sqlState: String) : AccountDeletionResult

    /**
     * No definitive answer. **Not a failure to delete** - it may well have happened.
     *
     * `REQUESTED` stands, the install is sync-dead, and resolution is owed to a later
     * start. Nothing local has changed.
     */
    data class Unresolved(val why: String) : AccountDeletionResult

    /**
     * A precondition was not met, so nothing was attempted.
     *
     * **No deletion marker was written**, which is what separates this from
     * [Unresolved]: there is nothing to resolve later and the install is not
     * sync-dead.
     */
    data class NotEligible(val why: String) : AccountDeletionResult
}
