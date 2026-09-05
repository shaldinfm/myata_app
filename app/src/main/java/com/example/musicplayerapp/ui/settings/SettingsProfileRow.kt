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
 * ## Where the address comes from
 *
 * `IdentityStore` does not have it. `markRegistered` persists a uid and nothing
 * else on purpose - an identity is a uid, and a name and an address belong to
 * whoever is holding the token - so there is no email on disk to read and this
 * screen must not invent somewhere to put one.
 *
 * The address the app already shows comes from the **session**:
 * `EmailAuthBackend.api(context).currentAccount()` returns an `AccountInfo`, and
 * `ProfileAuthenticatedFragment` renders its `email` through
 * [ProfileAccount.email]. That is the canonical presentation path and this reuses
 * it verbatim - the same call, the same trimming, the same treatment of blank as
 * absent. No auth contract moves for a settings row.
 *
 * ## Why there is a third outcome
 *
 * A session can exist with no address on it: `AccountInfo.email` is nullable, and
 * an install that is offline or whose session has not restored yet genuinely has
 * none. The account card answers that with `Email недоступен`, which is the right
 * sentence on a card whose whole subject is the account. On a one-line settings
 * row it is noise about a field the row was not promising - so the row falls back
 * to [Value.SignedIn] (`Вошли`), which is the weaker claim and still the true one.
 */
object SettingsProfileRow {

    /**
     * @param signedIn what the routing already concluded - `ProfileRoute.destination`
     *   answering `profile_authenticated`. Deliberately not a second identity read:
     *   the value and the row's destination come from one answer, so they cannot
     *   disagree with each other on screen.
     * @param rawEmail `AccountInfo.email` as the session gave it, or null.
     */
    fun value(signedIn: Boolean, rawEmail: String?): Value {
        if (!signedIn) return Value.SignedOut
        val email = ProfileAccount.email(rawEmail)
        return if (email == null) Value.SignedIn else Value.Address(email)
    }

    sealed interface Value {
        /** Guest, anonymous, signed out, or a deletion in flight. `Не вошли`. */
        object SignedOut : Value

        /** Registered, but this device cannot say as whom right now. `Вошли`. */
        object SignedIn : Value

        /** Registered, and the session named the address. */
        data class Address(val email: String) : Value
    }
}
