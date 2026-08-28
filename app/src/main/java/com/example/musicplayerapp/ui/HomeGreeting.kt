package com.example.musicplayerapp.ui

import com.example.musicplayerapp.data.supabase.AccountInfo
import com.example.musicplayerapp.data.supabase.IdentityState
import com.example.musicplayerapp.ui.profile.ProfileAccount

/**
 * Whom HOME is greeting, decided without a Context, a View or a device.
 *
 * The frozen header has always said `Привет!` to everybody, including the listener
 * whose name the authenticated profile was rendering correctly two taps away. That
 * is the same class of untruth `Вы не вошли` was: nothing was broken underneath, the
 * greeting simply never asked.
 *
 * ## One naming contract, not two
 *
 * The name itself is **not** decided here. [ProfileAccount.displayName] already owns
 * "what counts as a usable display name", down to the trimming and the blank case,
 * and the account card has been shipping that answer since G-A4b. A second copy of
 * that rule living on HOME would drift the first time one of them was edited, and
 * the drift would be two screens disagreeing about somebody's name.
 *
 * So this adds exactly one rule on top: **who may be named at all.**
 *
 * ## Who may be named
 *
 * ```
 * REGISTERED(X)  and  session account X  with a usable name  ->  that name
 * REGISTERED(X)  and  session account X  with no usable name ->  null
 * REGISTERED(X)  and  session account Y != X                 ->  null
 * REGISTERED(X)  and  no session                             ->  null
 * anything else                                              ->  null
 * ```
 *
 * Null means the caller draws the plain `Привет!`, which is the greeting that claims
 * nothing and is therefore always safe.
 *
 * The `X` match is the same rule
 * [com.example.musicplayerapp.ui.profile.ProfileRoute] routes on, and it is here for
 * the same reason: `REGISTERED(X)` on disk is a belief, not a session. An install
 * whose token was revoked on another device still has that row in preferences, and
 * greeting it by name would put the previous person's name on a header they are no
 * longer behind. Unlike `ProfileRoute` this does **not** reconcile - a greeting is
 * not a reason to write to the identity store - so a disagreement is simply not
 * named rather than repaired.
 *
 * ## What it must never do
 *
 * Not mint, not sign in, not write. [account] is a supplier rather than a value so
 * that it is only ever consulted once the persisted state already says an account is
 * expected: `IdentityState.None` never reaches the auth boundary at all. The caller
 * passes `EmailAuthApi.currentAccount`, which reads the session the Auth plugin is
 * already holding and makes no request - the same call the authenticated profile
 * makes, for the same reason.
 */
object HomeGreeting {

    /**
     * The name to greet, or null for the plain greeting.
     *
     * @param state what this install persists about itself.
     * @param account the live session's account, asked for only when [state] warrants
     *   it. Suspending because the auth boundary is.
     */
    suspend fun name(state: IdentityState, account: suspend () -> AccountInfo?): String? {
        if (state !is IdentityState.Registered) return null
        val info = account() ?: return null
        if (info.uid != state.uid) return null
        return ProfileAccount.displayName(info.displayName)
    }
}
