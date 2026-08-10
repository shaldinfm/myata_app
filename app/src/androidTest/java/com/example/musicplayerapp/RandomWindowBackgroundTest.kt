package com.example.musicplayerapp

import android.app.Activity
import android.content.Intent
import android.util.TypedValue
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Deterministic evidence that the A3 theme refactor preserved the random window
 * background, and that AppTheme0..9 did not inherit AppTheme's window flags.
 *
 * This exists because the first attempt at proving it was wrong: it hashed whole
 * screenshots across launches and counted distinct results. That produced 11
 * distinct results from 10 possible backgrounds, which is impossible - the hash
 * was picking up album art, playlists and the clock, not the theme. Screen
 * uniqueness cannot prove anything about the theme.
 *
 * Here the theme itself is asked what it resolved to, which is exact.
 *
 * Test-only. It is compiled into the androidTest APK and never ships.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class RandomWindowBackgroundTest {

    private val expectedBackgrounds: List<Int> by lazy {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        (0..9).map { i ->
            ctx.resources.getIdentifier("screen$i", "drawable", ctx.packageName)
                .also { assertTrue("drawable/screen$i is missing", it != 0) }
        }
    }

    private val themeIds: List<Int> by lazy {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        (0..9).map { i ->
            ctx.resources.getIdentifier("AppTheme$i", "style", ctx.packageName)
                .also { assertTrue("style/AppTheme$i is missing", it != 0) }
        }
    }

    /** The windowBackground the activity's theme actually resolved to. */
    private fun Activity.resolvedWindowBackground(): Int {
        val tv = TypedValue()
        assertTrue(
            "theme did not resolve android:windowBackground",
            theme.resolveAttribute(android.R.attr.windowBackground, tv, true)
        )
        return tv.resourceId
    }

    private fun Activity.flag(attr: Int): Boolean {
        val tv = TypedValue()
        return if (theme.resolveAttribute(attr, tv, true)) tv.data != 0 else false
    }

    private fun launchAndRead(): Int {
        var picked = 0
        ActivityScenario.launch<MainActivity>(
            Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
        ).use { scenario ->
            scenario.onActivity { activity -> picked = activity.resolvedWindowBackground() }
        }
        return picked
    }

    @Test
    fun allTenThemesExistAndMapToTheirOwnScreenDrawable() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        for (i in 0..9) {
            val tv = TypedValue()
            val theme = ctx.resources.newTheme()
            theme.applyStyle(themeIds[i], true)
            assertTrue(
                "AppTheme$i does not define android:windowBackground",
                theme.resolveAttribute(android.R.attr.windowBackground, tv, true)
            )
            assertEquals(
                "AppTheme$i must point at drawable/screen$i",
                expectedBackgrounds[i], tv.resourceId
            )
        }
    }

    /**
     * AppTheme carries windowFullscreen, windowIsTranslucent and
     * windowDisablePreview; AppTheme0..9 must not, or reparenting them would have
     * silently changed window behaviour.
     */
    @Test
    fun theTenThemesDidNotInheritAppThemeWindowFlags() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        for (i in 0..9) {
            val theme = ctx.resources.newTheme()
            theme.applyStyle(themeIds[i], true)
            for ((name, attr) in listOf(
                "windowFullscreen" to android.R.attr.windowFullscreen,
                "windowIsTranslucent" to android.R.attr.windowIsTranslucent,
                "windowDisablePreview" to android.R.attr.windowDisablePreview
            )) {
                val tv = TypedValue()
                val set = theme.resolveAttribute(attr, tv, true) && tv.data != 0
                assertFalse("AppTheme$i must not have $name set", set)
            }
        }
    }

    /**
     * Every launch must resolve to one of the ten, and across enough launches more
     * than one must appear - otherwise the selection has been collapsed to a
     * constant. Twenty launches give roughly a 1-in-10^19 chance of a false alarm
     * if the randomness is intact.
     */
    @Test
    fun launchesPickFromTheTenAtRandom() {
        val seen = mutableSetOf<Int>()
        repeat(20) {
            val picked = launchAndRead()
            assertTrue(
                "launch resolved windowBackground $picked, which is not one of screen0..screen9",
                picked in expectedBackgrounds
            )
            seen += picked
        }
        assertTrue(
            "all 20 launches resolved the same background - random selection is gone",
            seen.size > 1
        )
    }
}
