package com.example.musicplayerapp.ui.profile

import android.content.Context
import com.example.musicplayerapp.R
import com.example.musicplayerapp.data.supabase.IdentityState
import com.example.musicplayerapp.data.supabase.IdentityStore

/**
 * Which profile a tap on the 40x40 control should open.
 *
 * ## It reads storage and nothing else
 *
 * One `SharedPreferences` lookup - a hash-map read after the first load - and no
 * session call, no network and, critically, **no identity boundary**. Opening the
 * profile must never mint an anonymous `auth.users` row, and the surest way to
 * guarantee that is for this decision to be incapable of it. `ProfileEntryTest` has
 * asserted the property since G-A3 and still does.
 *
 * That also makes it safe on the tap path: the three entry points call it from a
 * click listener, and a suspending answer there would either block the frame or
 * leave the control doing nothing for a moment.
 *
 * ## The session is checked later, not here
 *
 * `REGISTERED` on disk is what this routes on, and it is not proof of a live
 * session - an account whose token was revoked elsewhere still reads `REGISTERED`
 * until something asks. So [com.example.musicplayerapp.fragments.ProfileAuthenticatedFragment]
 * verifies the session when it opens and steps aside to the guest screen if
 * reconciliation says the listener is not authenticated after all. Doing it there
 * rather than here is what keeps this synchronous and mint-proof while still making
 * the authenticated screen honest.
 */
object ProfileRoute {

    /**
     * @return the destination id to navigate to.
     *
     * | state | destination |
     * |---|---|
     * | `Registered` | `profile_authenticated` |
     * | `None`, `Anonymous`, `SignedOut` | `profile` (guest) |
     * | `EmailPending`, `EmailVerified` | `profile` (guest) |
     *
     * The last row is deliberate. Neither state is produced by anything that ships -
     * registration with Confirm Email off goes straight to `REGISTERED` - so an
     * install in one of them has arrived by a route nobody designed, and the guest
     * screen is the presentation that claims least. It offers a way forward and
     * asserts nothing false; an authenticated card would assert an account this app
     * has no evidence for.
     */
    fun destination(context: Context): Int =
        when (IdentityStore.state(context)) {
            is IdentityState.Registered -> R.id.profile_authenticated

            is IdentityState.None,
            is IdentityState.Anonymous,
            is IdentityState.SignedOut,
            is IdentityState.EmailPending,
            is IdentityState.EmailVerified,
            -> R.id.profile
        }
}
