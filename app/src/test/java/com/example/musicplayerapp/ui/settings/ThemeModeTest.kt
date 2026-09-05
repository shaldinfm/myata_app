package com.example.musicplayerapp.ui.settings

import androidx.appcompat.app.AppCompatDelegate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The appearance enum, its stored form and the AppCompat constant behind it.
 *
 * All of this is what a corrupt or absent preference resolves to, which is the part
 * that never shows up in a screenshot and is the whole reason the mapping lives
 * outside the fragment.
 */
class ThemeModeTest {

    @Test
    fun `the app offers exactly three appearances`() {
        // A true-black variant was considered for this screen and dropped. A fourth
        // entry here would mean a row the frozen frame does not have.
        assertEquals(3, ThemeMode.entries.size)
        assertEquals(
            listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK),
            ThemeMode.entries.toList(),
        )
    }

    @Test
    fun `every mode round-trips through its stored form`() {
        for (mode in ThemeMode.entries) {
            assertEquals(mode, ThemeMode.fromStored(mode.stored()))
        }
    }

    @Test
    fun `the stored forms are stable words, not ordinals`() {
        // Reordering the enum must not silently repoint everybody's stored choice,
        // which is exactly what storing the ordinal would do.
        assertEquals("system", ThemeMode.SYSTEM.stored())
        assertEquals("light", ThemeMode.LIGHT.stored())
        assertEquals("dark", ThemeMode.DARK.stored())
    }

    @Test
    fun `an absent key is SYSTEM`() {
        // The migration for every existing install, and it is this line: nothing is
        // written at upgrade, so everybody arrives here.
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStored(null))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.DEFAULT)
    }

    @Test
    fun `anything unrecognised is SYSTEM rather than a failure`() {
        for (junk in listOf("", " ", "SYSTEM", "Light", "amoled", "true", "0", "null")) {
            assertEquals(
                "[$junk] must resolve to the default",
                ThemeMode.SYSTEM,
                ThemeMode.fromStored(junk),
            )
        }
    }

    @Test
    fun `the stored form is case sensitive on purpose`() {
        // Only the app writes this file, and it writes lower case. Accepting
        // "Dark" would mean accepting a value nothing produces, which hides a
        // mis-write instead of defaulting cleanly.
        assertNotEquals(ThemeMode.DARK, ThemeMode.fromStored("Dark"))
    }

    @Test
    fun `each mode asks the delegate for the right local night mode`() {
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, ThemeMode.LIGHT.localNightMode())
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, ThemeMode.DARK.localNightMode())
    }

    @Test
    fun `SYSTEM installs no local override at all`() {
        // MODE_NIGHT_UNSPECIFIED is AppCompat's "there is no local override": the
        // delegate falls back to the process default, which nothing in this app sets,
        // and that follows the system. So the default is not merely equivalent to the
        // pre-G1 behaviour, it is the identical state.
        //
        // Deliberately NOT MODE_NIGHT_FOLLOW_SYSTEM, which says the same thing in
        // words and costs a recreation on every cold start to say it - see
        // ThemeMode.localNightMode and
        // AppearanceSelectionTest.the_default_appearance_does_not_recreate_the_activity_on_launch.
        assertEquals(
            AppCompatDelegate.MODE_NIGHT_UNSPECIFIED,
            ThemeMode.SYSTEM.localNightMode(),
        )
        assertEquals(
            AppCompatDelegate.MODE_NIGHT_UNSPECIFIED,
            ThemeMode.DEFAULT.localNightMode(),
        )
    }

    @Test
    fun `only the two explicit modes force an appearance`() {
        // The inverse of the rule above, stated so a fourth mode cannot quietly
        // arrive with a forcing value.
        val forcing = ThemeMode.entries.filter {
            it.localNightMode() == AppCompatDelegate.MODE_NIGHT_YES ||
                it.localNightMode() == AppCompatDelegate.MODE_NIGHT_NO
        }
        assertEquals(listOf(ThemeMode.LIGHT, ThemeMode.DARK), forcing)
    }

    @Test
    fun `every mode names a label`() {
        for (mode in ThemeMode.entries) {
            assertTrue("${mode.name} has no label", mode.labelRes() != 0)
        }
        // Three rows, three distinct labels.
        assertEquals(3, ThemeMode.entries.map { it.labelRes() }.toSet().size)
    }
}
