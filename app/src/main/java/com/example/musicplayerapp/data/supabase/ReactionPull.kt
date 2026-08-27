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
 * **Nothing calls this yet.** When a pull runs - after a sign-in, on an authenticated
 * app start, behind a manual refresh - is G-A7d's decision, and wiring a trigger here
 * would make the primitive impossible to exercise without one.
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

        val result = ReactionPullEngine(
            reactions = database.reactionDao(),
            outbox = database.reactionOutboxDao(),
            api = ReactionSyncBackend.api(app),
            eligibility = { eligibility(app) },
            transaction = { block -> database.withTransaction(block) },
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
        if (result is PullResult.Completed) LastSyncStore.recordPullSuccess(app)

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

        val session = runCatching { EmailAuthBackend.api(context).currentUid() }.getOrNull()
            ?: return PullIdentity.Unavailable("no restored session for the account")

        if (session != state.uid) {
            return PullIdentity.Unavailable("the restored session is not this install's account")
        }

        return PullIdentity.Eligible(state.uid)
    }
}
