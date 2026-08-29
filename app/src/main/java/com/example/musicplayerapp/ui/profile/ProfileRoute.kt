package com.example.musicplayerapp.ui.profile

import android.content.Context
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.musicplayerapp.R
import com.example.musicplayerapp.data.supabase.EmailAuthBackend
import com.example.musicplayerapp.data.supabase.IdentityReconciler
import com.example.musicplayerapp.data.supabase.IdentityState
import com.example.musicplayerapp.data.supabase.IdentityStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Which profile a tap on the 40x40 control should open.
 *
 * ## Local state alone is not enough
 *
 * `REGISTERED(X)` on disk says this install *believes* it is an account. It is not
 * evidence of a session, and the two come apart for ordinary reasons: a token revoked
 * on another device, an account deleted, a logout that died before its commit, or
 * simply an install that has not restored yet.
 *
 * So the decision is made **before** navigating, not after arriving. An earlier
 * version of this routed on the persisted state and let the destination check itself
 * and leave - which meant the authenticated screen was entered and painted from stale
 * state, with the fallback name and address on the card, for as long as the check
 * took. Rendering an account card for somebody who is not authenticated is the same
 * class of lie as telling a registered listener `Вы не вошли`, which is the lie this
 * whole phase exists to remove.
 *
 * The rule is therefore:
 *
 * ```
 * REGISTERED(X)  and  restored session uid == X   ->  profile-authenticated
 * REGISTERED(X)  and  no session                  ->  profile-guest
 * REGISTERED(X)  and  session uid Y != X          ->  reconcile, then re-decide
 * anything else                                   ->  profile-guest
 * ```
 *
 * ## What the check costs, and what it cannot do
 *
 * `currentUid()` reads what the Auth plugin already holds - `currentUserOrNull()` -
 * and makes **no request**. Reconciliation is the same call `MyataApplication` makes
 * at startup, so there is one reconciliation algorithm in the app rather than two.
 *
 * Neither can mint: the only function in the app that creates an identity is
 * `ListenerSession.identity`, and nothing on this path reaches it. Nothing here
 * starts a handoff, writes to Room, or touches the Collection. Opening a profile
 * remains something a listener can do without existing in a database, which
 * `ProfileEntryTest` has asserted since G-A3 and still does.
 */
object ProfileRoute {

    /**
     * Decides, having first established who this device actually is.
     *
     * Suspending because the truth is not in preferences. It is still cheap - a
     * plugin field read and a reconciliation that usually concludes immediately -
     * but it is not free, which is why [open] runs it off the tap.
     */
    suspend fun destination(context: Context): Int {
        // First, and before any session read or reconciliation. An install with an
        // unresolved deletion may still hold `REGISTERED(X)` and a live session, so
        // every check below would pass and open the authenticated screen for an
        // account that is being destroyed - offering `Выйти` and a sync row for
        // something that may no longer exist.
        //
        // It comes before `IdentityReconciler.reconcile` deliberately, not merely
        // early: reconciliation repairs an identity around whatever session restored,
        // and repairing an identity that a deletion is in the middle of removing is
        // two algorithms writing the same state. Whatever resolves a deletion owns
        // that, and opening a screen must not pre-empt it.
        //
        // Guest is the presentation that claims least - the same rule this file
        // already applies to a state it cannot prove. What the screen should
        // eventually *say* about a deletion in progress is a later phase's decision;
        // asserting nothing is the honest interim.
        if (IdentityStore.deletionInFlight(context)) return R.id.profile

        if (IdentityStore.state(context) !is IdentityState.Registered) return R.id.profile

        // Local, no network: whatever session the Auth plugin is already holding.
        val sessionUid = EmailAuthBackend.api(context).currentUid()

        // The existing contract decides what a disagreement means - including the
        // case where the session belongs to somebody else, which it resolves in the
        // session's favour because the session is what RLS will actually enforce.
        IdentityReconciler.reconcile(context, sessionUid)

        // Re-read: reconciliation may have changed the answer, and it is the answer
        // *after* reconciliation that this routes on.
        val settled = IdentityStore.state(context)
        return if (settled is IdentityState.Registered && settled.uid == sessionUid) {
            R.id.profile_authenticated
        } else {
            // No session, or one that still does not match after reconciliation. The
            // guest screen is the presentation that claims least: it offers a way
            // forward and asserts nothing this install cannot prove.
            R.id.profile
        }
    }

    /**
     * Opens the right profile from a tap.
     *
     * The check is off the main thread because everything under it reads
     * `SharedPreferences` with `commit()`, and the navigation is back on it. A view
     * that has gone away in between is left alone rather than navigated.
     */
    fun open(fragment: Fragment) {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val destination = withContext(Dispatchers.IO) { destination(fragment.requireContext()) }
            if (fragment.view == null) return@launch
            fragment.findNavController().navigate(destination)
        }
    }
}
