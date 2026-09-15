package com.example.musicplayerapp.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `Settings > Аккаунт > Профиль`: the account's display name, never its address (G6a).
 */
class SettingsProfileRowTest {

    @Test
    fun `signed out ignores whatever the session said`() {
        assertEquals(
            SettingsProfileRow.Value.SignedOut,
            SettingsProfileRow.value(signedIn = false, accountFound = true, rawDisplayName = "Денис"),
        )
    }

    @Test
    fun `signed in with a display name shows the name`() {
        assertEquals(
            SettingsProfileRow.Value.Name("Денис"),
            SettingsProfileRow.value(signedIn = true, accountFound = true, rawDisplayName = "Денис"),
        )
    }

    @Test
    fun `the name is trimmed the same way the account card trims it`() {
        assertEquals(
            SettingsProfileRow.Value.Name("Денис"),
            SettingsProfileRow.value(signedIn = true, accountFound = true, rawDisplayName = "  Денис  "),
        )
    }

    @Test
    fun `an account with no usable name gets the account model's fallback, not an address`() {
        for (blank in listOf(null, "", "   ", "\t", "\n")) {
            assertEquals(
                "display name [$blank] must fall back to Пользователь",
                SettingsProfileRow.Value.Unnamed,
                SettingsProfileRow.value(signedIn = true, accountFound = true, rawDisplayName = blank),
            )
        }
    }

    @Test
    fun `signed in but the account is not readable right now falls back to the weaker claim`() {
        assertEquals(
            SettingsProfileRow.Value.SignedIn,
            SettingsProfileRow.value(signedIn = true, accountFound = false, rawDisplayName = null),
        )
    }

}
