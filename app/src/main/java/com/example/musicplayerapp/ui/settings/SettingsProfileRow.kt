package com.example.musicplayerapp.ui.settings

import com.example.musicplayerapp.ui.profile.ProfileAccount

/**
 * What `Settings > Аккаунт > Профиль` says on its right-hand side.
 *
 * The frozen row 2517:2766 draws the value `Не вошли`, which is the guest case;
 * the frame never shows the signed-in one, so what belongs there is a decision
 * rather than a measurement. This file is that decision, and it is a pure one so
 * the three outcomes are provable without a session.
 *
 * ## The name, not the address (G6a)
 *
 * The row names the account the way the account card does: `user_metadata.display_name`
 * through [ProfileAccount.displayName], so the two can never disagree about somebody's
 * name. It used to show the address; an email is the account's key, not how the listener
 * is addressed, and the owner asked for the name.
 *
 * `IdentityStore` does not have either: `markRegistered` persists a uid and nothing else,
 * so the name comes from the session's `AccountInfo`, which is the path the card uses.
 *
 * ## Three signed-in outcomes
 *
 * - The session names the account → that name.
 * - The session has the account but no usable name → the account model's own fallback,
 *   `Пользователь` ([Value.Unnamed]), exactly what the card shows.
 * - This device cannot read the account right now (offline before the session restored,
 *   or a session for another uid) → [Value.SignedIn], `Вошли`: the weaker claim, and the
 *   true one.
 */
object SettingsProfileRow {

    /**
     * @param signedIn what the routing already concluded - `ProfileRoute.destination`
     *   answering `profile_authenticated`. Deliberately not a second identity read:
     *   the value and the row's destination come from one answer, so they cannot
     *   disagree with each other on screen.
     * @param accountFound whether the session produced the account the routing settled on.
     * @param rawDisplayName `AccountInfo.displayName` as the session gave it, or null.
     */
    fun value(signedIn: Boolean, accountFound: Boolean, rawDisplayName: String?): Value {
        if (!signedIn) return Value.SignedOut
        if (!accountFound) return Value.SignedIn
        val name = ProfileAccount.displayName(rawDisplayName)
        return if (name == null) Value.Unnamed else Value.Name(name)
    }

    sealed interface Value {
        /** Guest, anonymous, signed out, or a deletion in flight. `Не вошли`. */
        object SignedOut : Value

        /** Registered, but this device cannot say as whom right now. `Вошли`. */
        object SignedIn : Value

        /** Registered, and the account has no usable display name. `Пользователь`. */
        object Unnamed : Value

        /** Registered, and the account's display name. */
        data class Name(val name: String) : Value
    }
}
