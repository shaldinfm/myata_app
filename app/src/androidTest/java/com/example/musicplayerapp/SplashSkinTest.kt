package com.example.musicplayerapp

import android.content.Context
import android.util.TypedValue
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * That every launch artwork has an icon appearance, and that the two cannot drift.
 *
 * [SplashSkin] declares the pair together precisely so a swapped artwork carries its
 * status-bar appearance with it. That only holds while the declaration is complete
 * and correctly ordered, and nothing in the compiler checks either - so this does.
 *
 * The artwork is read back from the theme rather than assumed: each skin's theme is
 * asked what `android:windowBackground` it actually resolves to, which is the same
 * exact technique `RandomWindowBackgroundTest` uses, and for the same reason.
 *
 * Test-only. Compiled into the androidTest APK and never shipped.
 */
@RunWith(AndroidJUnit4::class)
class SplashSkinTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun drawable(name: String): Int =
        context.resources.getIdentifier(name, "drawable", context.packageName)
            .also { assertTrue("drawable/$name is missing", it != 0) }

    /** What a skin's theme really resolves `android:windowBackground` to. */
    private fun SplashSkin.windowBackground(): Int {
        val themed = android.view.ContextThemeWrapper(context, theme)
        val tv = TypedValue()
        assertTrue(
            "$name did not resolve android:windowBackground",
            themed.theme.resolveAttribute(android.R.attr.windowBackground, tv, true),
        )
        return tv.resourceId
    }

    @Test
    fun everyArtworkHasAnExplicitIconMode() {
        // Ten artworks, ten skins, and the enum is the only place either is listed.
        assertEquals(10, SplashSkin.entries.size)

        // No skin left on a default: each one names its own answer. Reading the
        // property is what proves it exists; the values themselves are pinned below.
        SplashSkin.entries.forEach { it.topIsLight }
    }

    @Test
    fun eachSkinCarriesTheArtworkItWasMeasuredAgainst() {
        // The drift guard. `SplashSkin.SCREEN_4.topIsLight` is only meaningful while
        // SCREEN_4's theme still draws screen4; if the themes are ever renumbered or
        // an artwork is repointed, the icon mode measured for the old image would
        // silently be applied to the new one.
        SplashSkin.entries.forEachIndexed { index, skin ->
            assertEquals(
                "${skin.name} must draw drawable/screen$index",
                drawable("screen$index"),
                skin.windowBackground(),
            )
        }
    }

    @Test
    fun theSkinsAreDistinctThemesAndDistinctArtworks() {
        assertEquals(10, SplashSkin.entries.map { it.theme }.toSet().size)
        assertEquals(10, SplashSkin.entries.map { it.windowBackground() }.toSet().size)
    }

    /**
     * The measured verdicts, pinned.
     *
     * These come from the WCAG contrast of white against dark icons over the two
     * regions the status-bar icons occupy, worst case (10th percentile) rather than
     * mean - the table is in [SplashSkin]'s KDoc. Six artworks are dark enough to
     * need white icons and four are light enough to need dark ones; a change here
     * should be a deliberate re-measurement, not a drive-by.
     */
    @Test
    fun theMeasuredIconModesAreWhatShips() {
        val expected = listOf(
            false, // screen0  L 0.112  white icons
            true,  // screen1  L 0.462  dark icons
            false, // screen2  L 0.183  white icons
            true,  // screen3  L 0.676  dark icons
            false, // screen4  L 0.229  white icons
            false, // screen5  L 0.119  white icons
            false, // screen6  L 0.229  white icons
            true,  // screen7  L 0.669  dark icons
            false, // screen8  L 0.315  white icons
            true,  // screen9  L 0.839  dark icons
        )
        assertEquals(expected, SplashSkin.entries.map { it.topIsLight })

        // Both appearances are actually used - a mapping that collapsed to one value
        // would mean the measurement had stopped saying anything.
        assertNotEquals(1, SplashSkin.entries.map { it.topIsLight }.toSet().size)
    }
}
