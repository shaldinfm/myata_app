package com.example.musicplayerapp.data.supabase

import android.content.Context
import androidx.room.withTransaction
import com.example.musicplayerapp.data.AppDatabase

/**
 * The production wiring for [ReactionPullEngine]: who this install is, and where its
 * database is.
 *
 * Kept apart from the engine so the algorithm never needs a `Context`. What lives
 * here is the part that has to know about Android and about this app's particular
 * seams; what lives there is the part worth testing exhaustively.
 *
 * Called by [ReactionPullTrigger]; see there for when.
 */
object ReactionPull {

    /**
     * Reads the account back, if this install is one.
     *
     * @param context any context; the application one is used.
     */
    suspend fun run(context: Context): PullResult {
        val app = context.applicationContext
        if (!SupabaseConfig.isConfigured) {
            return PullResult.NotEligible("no supabase project configured")
        }

        val database = AppDatabase.getDatabase(app)

        val api = ReactionSyncBackend.api(app)
        val result = ReactionPullEngine(
            reactions = database.reactionDao(),
            outbox = database.reactionOutboxDao(),
            api = api,
            eligibility = { eligibility(app) },
            transaction = { block -> database.withTransaction(block) },
            localOnly = LocalOnlyUpload(database.reactionDao(), api),
            initialRestore = { uid -> !LastSyncStore.isInitialRestoreComplete(app, uid) },
        ).pull()

        // Recorded here rather than inside the engine, so the algorithm never needs a
        // Context - and only for a scan that reached the end. A partial scan keeps the
        // pages it applied, but the account has not been read through, and saying
        // "synchronised" for a read nobody finished would be the kind of claim this
        // whole phase exists to stop making.
        //
        // A second key, never the upload one: an install can have restored without
        // ever pushing, and collapsing the two would make that indistinguishable from
        // having done neither. The profile will show the more recent of them; that
        // rendering is not part of this change.
        if (result is PullResult.Completed) {
            LastSyncStore.recordPullSuccess(app, result.uid)

            // The same condition and the same moment: this account has now been read
            // through at least once on this install. Nothing in the pull reads it back,
            // so it gates nothing - a later app start full-scans exactly as it would
            // have. It exists so that "there is an account" and "this device has
            // actually restored it" stop being the same question.
            LastSyncStore.markInitialRestoreComplete(app, result.uid)

            // Acts the drain held for this restore (CollectionScope.Delivery.AwaitingRestore)
            // are rebased and may go now. Counts the outbox first; schedules nothing
            // when it is empty.
            ReactionSyncScheduler.onAppStart(app)
        }

        return result
    }

    /**
     * Whether this install may read an account back, and whose.
     *
     * Both halves have to agree, and this is the same rule the authenticated profile
     * routes on: `REGISTERED(X)` on disk is what this device *believes*, and a
     * restored session is what the server will actually enforce. They come apart for
     * ordinary reasons - a token revoked on another device, an account deleted, a
     * logout that died before its commit, an install that has not restored yet - and
     * reading an account back on the strength of the belief alone would write one
     * listener's Collection onto another's device.
     *
     * `currentUid()` is `currentUserOrNull()`: a field the Auth plugin already holds,
     * **no request**.
     *
     * What this deliberately does not do: it mints nothing, starts no handoff, repairs
     * no identity and commits no state. A disagreement is reported, not resolved -
     * [IdentityReconciler] owns that, and a background read is the wrong place to
     * change who somebody is.
     */
    private suspend fun eligibility(context: Context): PullIdentity {
        val state = IdentityStore.state(context)
        if (state !is IdentityState.Registered) {
            return PullIdentity.NotEligible("identity is ${state::class.simpleName}, not an account")
        }

        // An unresolved handoff means this install is mid-switch: X may already be
        // retired remotely while the local rows have not been adopted into Y, and the
        // revisions on disk belong to whichever identity is being left behind. Reading
        // an account back into that is reading into a moving target. The record is
        // cleared by the handoff itself or by its recovery, and the pull that follows
        // either of those is the one that should run.
        IdentityStore.handoff(context)?.let {
            return PullIdentity.NotEligible("a handoff is unresolved at ${it.stage}")
        }

        // An unresolved deletion outranks the session check below, and is deliberately
        // NotEligible rather than Unavailable: Unavailable means "ask again shortly",
        // and there is nothing to come back for. Reading the account back would write
        // rows into a Collection that is about to be cleared - or, if the account is
        // already gone, ask a server that has nothing to answer with.
        IdentityStore.deletion(context)?.let {
            return PullIdentity.NotEligible("an account deletion is unresolved at ${it.stage}")
        }

        val session = runCatching { EmailAuthBackend.api(context).currentUid() }.getOrNull()
            ?: return PullIdentity.Unavailable("no restored session for the account")

        if (session != state.uid) {
            return PullIdentity.Unavailable("the restored session is not this install's account")
        }

        // The install is this account - the disk says so and the server's session
        // agrees - so the local tables are brought into its scope before a row is
        // read or published. Normally a no-op: the authentication that made this
        // install the account already did it. It matters after a process death between
        // the two, and it is what guarantees another account's rows are parked before
        // the local-only upload looks for rows to publish. Called holding the lease the
        // engine took, which is why it is the HoldingLease variant.
        CollectionScope.enterAccountHoldingLease(context, state.uid, adoptDevice = false)

        return PullIdentity.Eligible(state.uid)
    }
}
