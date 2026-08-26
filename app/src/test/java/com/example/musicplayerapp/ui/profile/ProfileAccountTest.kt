package com.example.musicplayerapp.ui.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the account card says when the data is not what the frame assumed.
 *
 * 2517:2671 draws a name, an address and the letter `Д`. Every one of those can be
 * absent in a shipped build - the session may not have restored, the account may
 * have been created without a display name - and what happens then is invisible in
 * a screenshot and trivial here.
 */
class ProfileAccountTest {

    // ==================== display name ====================

    @Test
    fun `a name is trimmed`() {
        assertEquals("Денис", ProfileAccount.displayName("  Денис  "))
    }

    @Test
    fun `nothing usable is null, so the caller can fall back`() {
        // Null rather than the Russian string, because this file holds no copy: the
        // fallback is a resource, and a screen in another locale gets its own.
        assertNull(ProfileAccount.displayName(null))
        assertNull(ProfileAccount.displayName(""))
        assertNull(ProfileAccount.displayName("   "))
        assertNull(ProfileAccount.displayName("\t\n"))
    }

    // ==================== email ====================

    @Test
    fun `an address is trimmed and otherwise untouched`() {
        assertEquals("name@example.com", ProfileAccount.email(" name@example.com "))
        // Case is preserved: local parts are case-sensitive by the standard, and this
        // is what the account is keyed by.
        assertEquals("Name@Example.COM", ProfileAccount.email("Name@Example.COM"))
    }

    @Test
    fun `a session with no address reports none`() {
        assertNull(ProfileAccount.email(null))
        assertNull(ProfileAccount.email("  "))
    }

    // ==================== the avatar initial ====================

    @Test
    fun `the initial is the first letter, upper cased`() {
        assertEquals("Д", ProfileAccount.initial("Денис", "Пользователь"))
        assertEquals("Д", ProfileAccount.initial("денис", "Пользователь"))
        assertEquals("A", ProfileAccount.initial("anne", "Пользователь"))
    }

    @Test
    fun `the fallback name supplies П`() {
        // The case the owner named. A blank display name must not produce a blank
        // circle, and must never produce a uid.
        assertEquals("П", ProfileAccount.initial(null, "Пользователь"))
        assertEquals("П", ProfileAccount.initial("   ", "Пользователь"))
        assertEquals("П", ProfileAccount.initial("", "Пользователь"))
    }

    @Test
    fun `punctuation is skipped rather than drawn`() {
        // A name stored with quotes, or as a handle, would otherwise put a quote mark
        // or an at-sign on the circle.
        assertEquals("Д", ProfileAccount.initial("\"Денис\"", "Пользователь"))
        assertEquals("D", ProfileAccount.initial("@denis", "Пользователь"))
        assertEquals("7", ProfileAccount.initial("7even", "Пользователь"))
    }

    @Test
    fun `a name with no letters at all still yields the fallback letter`() {
        assertEquals("П", ProfileAccount.initial("!!!", "Пользователь"))
    }

    // ==================== last sync ====================

    private val now = 1_700_000_000_000L
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    @Test
    fun `never synced is its own answer`() {
        assertEquals(ProfileAccount.Relative.Never, ProfileAccount.relativeSync(null, now))
    }

    @Test
    fun `the boundaries`() {
        val cases = listOf(
            0L to ProfileAccount.Relative.JustNow,
            minute - 1 to ProfileAccount.Relative.JustNow,
            minute to ProfileAccount.Relative.Minutes(1),
            2 * minute to ProfileAccount.Relative.Minutes(2),
            59 * minute to ProfileAccount.Relative.Minutes(59),
            hour to ProfileAccount.Relative.Hours(1),
            23 * hour to ProfileAccount.Relative.Hours(23),
            day to ProfileAccount.Relative.Days(1),
            6 * day to ProfileAccount.Relative.Days(6),
        )
        for ((elapsed, expected) in cases) {
            assertEquals("$elapsed ms ago", expected, ProfileAccount.relativeSync(now - elapsed, now))
        }
    }

    @Test
    fun `past a week it hands back the timestamp instead`() {
        // "43 дн назад" is not something anybody parses, so the screen formats a date
        // in the device's own locale rather than this inventing one.
        val old = now - 8 * day
        assertEquals(ProfileAccount.Relative.Older(old), ProfileAccount.relativeSync(old, now))
    }

    @Test
    fun `a clock that went backwards does not render a negative age`() {
        // A timezone change, an NTP correction, somebody setting the date. "-3 мин
        // назад" would be the alternative.
        assertEquals(
            ProfileAccount.Relative.JustNow,
            ProfileAccount.relativeSync(now + 5 * minute, now),
        )
    }
}
