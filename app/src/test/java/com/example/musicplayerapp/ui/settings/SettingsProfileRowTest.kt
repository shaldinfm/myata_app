package com.example.musicplayerapp.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The three outcomes of the `Settings > Профиль` value.
 *
 * The interesting one is the middle: a session that exists but names no address.
 * It is not a hypothetical - `AccountInfo.email` is nullable because an account
 * created by other means can genuinely have none, and an install whose session has
 * not restored yet has none either.
 */
class SettingsProfileRowTest {

    @Test
    fun `signed out ignores whatever the session said`() {
        assertEquals(
            SettingsProfileRow.Value.SignedOut,
            SettingsProfileRow.value(signedIn = false, rawEmail = "denis@example.com"),
        )
    }

    @Test
    fun `signed in with an address shows it`() {
        assertEquals(
            SettingsProfileRow.Value.Address("denis@example.com"),
            SettingsProfileRow.value(signedIn = true, rawEmail = "denis@example.com"),
        )
    }

    @Test
    fun `signed in with no address falls back to the weaker claim`() {
        assertEquals(
            SettingsProfileRow.Value.SignedIn,
            SettingsProfileRow.value(signedIn = true, rawEmail = null),
        )
    }

    @Test
    fun `a blank address is an absent one`() {
        // ProfileAccount.email trims and treats empty as absent; the row inherits
        // that rather than rendering a row with nothing after the label.
        for (blank in listOf("", "   ", "\t", "\n")) {
            assertEquals(
                "blank address [$blank] must not be shown",
                SettingsProfileRow.Value.SignedIn,
                SettingsProfileRow.value(signedIn = true, rawEmail = blank),
            )
        }
    }

    @Test
    fun `an address is trimmed the same way the account card trims it`() {
        assertEquals(
            SettingsProfileRow.Value.Address("denis@example.com"),
            SettingsProfileRow.value(signedIn = true, rawEmail = "  denis@example.com  "),
        )
    }
}
