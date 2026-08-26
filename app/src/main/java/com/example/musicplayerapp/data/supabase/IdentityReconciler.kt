package com.example.musicplayerapp.data.supabase

import android.content.Context
import android.util.Log
import com.example.musicplayerapp.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Startup repair for an identity that a process death caught mid-change.
 *
 * ## The direction that was missing
 *
 * G-A4b1 recovers *downward*: a handoff record on disk says something irreversible
 * may have happened remotely, and recovery decides whether to finish it or undo it.
 * That covers every crash inside a handoff, and it is the only thing that could
 * exist at the time, because nothing could authenticate.
 *
 * Authentication adds the opposite gap. The remote call succeeds - the server has
 * issued a session, the account exists, RLS will enforce Y from this moment - and the
 * process dies before this device writes down that it is Y. Nothing is corrupt and
 * nothing is lost, but the two halves of the same install now disagree, and the disk
 * is the half that is wrong. Repairing that is repairing *upward*: taking the
 * session's word for who this is and committing it.
 *
 * ## Why a marker rather than a rule
 *
 * One arrangement of facts has two possible histories, and they want opposite
 * repairs:
 *
 * | on disk | session | history | repair |
 * |---|---|---|---|
 * | `SIGNED_OUT(u)` | `u` | a sign-in that died before its commit | commit `REGISTERED(u)` |
 * | `SIGNED_OUT(u)` | `u` | a logout that died before clearing the token | clear the token |
 *
 * Nothing about the two states distinguishes them, so the intent is written down
 * before the remote call instead - see the auth-attempt section of [IdentityStore] -
 * and this class reads it rather than guessing. Guessing would be wrong half the
 * time, and one of those halves silently signs somebody back in after they asked not
 * to be, which is the exact thing rule 7 of the frozen logout contract in
 * [IdentityState.SignedOut] exists to forbid.
 *
 * ## It never mints
 *
 * No path through this file calls [ListenerSession.identity], and therefore none can
 * reach `signInAnonymously`. Reconciliation reads a session that already exists and
 * writes down what it says; an install with no session leaves here exactly as it
 * arrived. A repair routine that could create an identity would be a second way to
 * split a listener in two, on the one code path that runs on every cold start.
 */
object IdentityReconciler {

    private const val TAG = "SupabaseAuth"

    /** What reconciliation concluded. Returned so a test can assert on it. */
    sealed interface Outcome {

        /** Everything already agreed. The overwhelmingly common answer. */
        data object Consistent : Outcome

        /** The session's identity has been committed as [IdentityState.Registered]. */
        data class PromotedToRegistered(val uid: String) : Outcome

        /** A handoff record was found, and G-A4b1's recovery resolved it. */
        data class HandoffResolved(val result: IdentityHandoff.Result) : Outcome

        /** A logout that died before clearing its token has been finished. */
        data class LogoutCompleted(val lastUid: String) : Outcome

        /**
         * Not enough was known to act, and nothing was written.
         *
         * Always a safe place to stop: the durable state is untouched and local Room
         * is intact, so the next start decides with better information.
         */
        data class Deferred(val why: String) : Outcome
    }

    /**
     * Restores whatever session exists and repairs the identity around it.
     *
     * The two steps are sequenced deliberately. [ListenerSession.restore] fills a
     * [IdentityState.None] install in from a restored session, which is right when
     * that session is an anonymous one and wrong when it is the account somebody was
     * registering when the process died - so reconciliation runs immediately
     * afterwards, on the same coroutine, before anything can read the interim state.
     */
    fun startupInBackground(context: Context) {
        if (!SupabaseConfig.isConfigured) return

        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val sessionUid = ListenerSession.restore(app)
                reconcile(app, sessionUid)
            }.onFailure { Log.w(TAG, "identity reconciliation failed: ${it.message}") }
        }
    }

    /**
     * Brings the persisted identity into line with [sessionUid], the uid of whatever
     * session actually restored.
     *
     * @param sessionUid null when there is no session - which is not the same as
     *   "there is no identity", and is why nothing here treats it as one.
     */
    suspend fun reconcile(context: Context, sessionUid: String?): Outcome {
        // A handoff record outranks everything else here. It describes remote state
        // that may already have changed, G-A4b1 knows every stage-and-session
        // combination there is - including the SWITCH_PENDING(X)-with-a-session-for-Y
        // case this class was asked to cover - and second-guessing it from outside
        // would be a second recovery algorithm for one problem.
        if (IdentityStore.handoffInProgress(context)) return resolveHandoff(context, sessionUid)

        val attempt = IdentityStore.authAttempt(context)

        if (sessionUid == null) {
            // Nothing to reconcile against, and the marker is deliberately **not**
            // cleared. A session can fail to restore because the storage was empty or
            // because the read failed, and clearing here would throw away the only
            // record of an interrupted sign-in over a transient failure. A marker left
            // behind is harmless: the only thing that can produce a session without
            // passing through this class is an anonymous mint, and that clears it.
            return if (attempt == null) Outcome.Consistent else Outcome.Deferred("no session to reconcile against")
        }

        return when (val state = IdentityStore.state(context)) {

            // Never had an identity, yet a session exists. With an attempt pending
            // this is a direct registration or sign-in that died before its commit.
            is IdentityState.None ->
                if (attempt != null) promote(context, sessionUid, "$attempt was interrupted")
                else Outcome.Consistent

            // Anonymous, and an attempt is pending. This is the same interrupted
            // direct authentication one step later: ListenerSession.restore has
            // already filled the None state in from the session and labelled it
            // anonymous, which is what it should do when there is no marker and
            // exactly wrong when there is. The uids usually match here - the
            // correction is to the *kind*, not the identity.
            is IdentityState.Anonymous ->
                if (attempt != null) promote(context, sessionUid, "$attempt was interrupted")
                else Outcome.Consistent

            is IdentityState.SignedOut -> when {
                attempt != null ->
                    promote(context, sessionUid, "$attempt from a signed-out install was interrupted")

                // A session for an identity this install did not sign out of cannot be
                // a leftover of that sign-out. Something authenticated as somebody
                // else, and only a completed authentication does that.
                sessionUid != state.lastUid ->
                    promote(context, sessionUid, "the session is not the identity that signed out")

                // Same uid, no attempt: the other history. Rule 7 of the frozen logout
                // contract - the state is right and the token is the part that is
                // wrong.
                else -> completeInterruptedLogout(context, state.lastUid)
            }

            is IdentityState.Registered ->
                if (sessionUid == state.uid) {
                    IdentityStore.clearAuthAttempt(context)
                    Outcome.Consistent
                } else {
                    // Should not happen outside a handoff. The session is what RLS
                    // will actually enforce, so it wins - but loudly, because the
                    // other reading is that two identities are live on one install.
                    Log.w(TAG, "stored account uid differs from the live session")
                    promote(context, sessionUid, "the session disagrees with the stored identity")
                }

            // Neither is produced by anything that ships. Left exactly as found rather
            // than swept into one of the states above.
            is IdentityState.EmailPending,
            is IdentityState.EmailVerified,
            -> Outcome.Deferred("nothing produces ${state.javaClass.simpleName}")
        }
    }

    /** Hands an interrupted handoff to the recovery that was built for it. */
    private suspend fun resolveHandoff(context: Context, sessionUid: String?): Outcome {
        val database = AppDatabase.getDatabase(context)

        val result = IdentityHandoff.recover(
            context = context,
            sessionUid = sessionUid,
            reactions = database.reactionDao(),
            api = ReactionSyncBackend.api(context),
        ) ?: return Outcome.Deferred("a handoff with no session cannot be resolved yet")

        // Whatever the handoff concluded is the identity now. A direct attempt marker
        // that survived alongside it describes an older, finished story.
        IdentityStore.clearAuthAttempt(context)

        if (result is IdentityHandoff.Result.Switched) ReactionSyncScheduler.onAppStart(context)

        return Outcome.HandoffResolved(result)
    }

    /**
     * Commits [uid] as this install's account identity.
     *
     * One `commit()` for the state and the uid together - [IdentityStore.markRegistered]
     * writes both - so a death inside this method cannot leave the pair torn.
     */
    private fun promote(context: Context, uid: String, why: String): Outcome {
        Log.d(TAG, "reconciling upward to the session's identity: $why")
        IdentityStore.markRegistered(context, uid)
        IdentityStore.clearAuthAttempt(context)
        // Rows that could not be sent while the identity was unsettled now have an
        // owner, and nothing else is watching for that.
        ReactionSyncScheduler.onAppStart(context)
        return Outcome.PromotedToRegistered(uid)
    }

    /**
     * Finishes a logout whose token outlived its state.
     *
     * `LOCAL` scope, per the frozen contract: this device, and no other. The stored
     * state already says signed out and is authoritative regardless of whether the
     * call reaches the server, so a failure here defers rather than escalates.
     */
    private suspend fun completeInterruptedLogout(context: Context, lastUid: String): Outcome {
        Log.w(TAG, "signed out with a live session; finishing the interrupted logout")

        return if (EmailAuthBackend.api(context).signOutLocal()) {
            Outcome.LogoutCompleted(lastUid)
        } else {
            Outcome.Deferred("the stale session could not be cleared")
        }
    }
}
