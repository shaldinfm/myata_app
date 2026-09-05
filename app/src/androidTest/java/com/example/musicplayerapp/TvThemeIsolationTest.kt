package com.example.musicplayerapp

import android.content.res.Configuration
import android.util.TypedValue
import android.view.ContextThemeWrapper
import androidx.appcompat.app.AppCompatDelegate
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayerapp.data.ThemeStore
import com.example.musicplayerapp.ui.settings.ThemeMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android TV cannot be reached by the phone's appearance choice.
 *
 * A0 made it impossible for the 3.6.6 mobile token migration to change TV, and G1
 * is the first change that could plausibly undo that: it adds a screen whose whole
 * purpose is to switch the app between two palettes. This is the proof that the
 * switch does not carry.
 *
 * ## Three independent reasons, asserted separately
 *
 * 1. **`TvTheme` is not a DayNight tree.** Its parent is spelled out as
 *    `Theme.MaterialComponents.Light.NoActionBar` precisely so the mobile tree
 *    could become DayNight without reaching TV, and its colours come from
 *    `colors_tv.xml`, which has no `values-night` counterpart. So even under a
 *    night configuration it resolves the same values.
 * 2. **The night mode is never process-wide.** `MainActivity` sets
 *    `delegate.localNightMode`, which is scoped to one activity;
 *    `AppCompatDelegate.setDefaultNightMode` - which would reach `TvMainActivity`,
 *    an `AppCompatActivity` in this same process - is never called.
 * 3. **TV draws no shared themed colour.** The TV layouts reference drawables
 *    only, none of which has a `-night` variant.
 *
 * ## Why TvMainActivity is not launched here
 *
 * Launching it starts `TvSplashFragment` and the TV playback path, which is a
 * live surface this suite has no business starting on a phone emulator to answer
 * a question about resource resolution. Everything above is a property of the
 * resource table and of one API call, and all of it is checkable without it.
 */
@RunWith(AndroidJUnit4::class)
class TvThemeIsolationTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private companion object {
        const val TAG = "TVQA"
    }

    @After
    fun tidy() = ThemeStore.clearForTest(context)

    /**
     * TvTheme resolves identically under night and day, whatever the phone chose.
     *
     * The attributes sampled are the ones `TvTheme` actually sets. If any of them
     * ever started resolving to a `values-night` value, TV would change colour
     * because somebody picked Тёмная on their phone - which is the whole failure
     * this guards.
     */
    @Test
    fun tv_theme_resolves_the_same_colours_under_night_and_day() {
        for (mode in ThemeMode.entries) {
            ThemeStore.write(context, mode)

            val day = tvAttrs(night = false)
            val night = tvAttrs(night = true)

            assertEquals(
                "TvTheme resolved different colours under a night configuration " +
                    "with the phone set to $mode: day=$day night=$night",
                day,
                night,
            )
        }
    }

    /**
     * The mobile theme *does* change, which is what makes the test above meaningful.
     *
     * Without this, a resource table in which nothing at all resolved per-theme
     * would pass the TV assertion for the wrong reason.
     */
    @Test
    fun the_mobile_theme_does_change_under_night() {
        val day = mobileAttrs(night = false)
        val night = mobileAttrs(night = true)
        assertTrue(
            "AppTheme resolved identically under night and day; the DayNight tree " +
                "is not working, so the TV assertion proves nothing",
            day != night,
        )
    }

    @Test
    fun no_tv_drawable_has_a_night_variant() {
        // The TV layouts reference these and no `@color/` at all. A `-night` copy of
        // any of them would be a TV asset that changes with the phone's choice.
        val tvDrawables = listOf(
            "bg_tv_stream_button_new", "btn_back_tv", "btn_pause_tv", "card_gold_tv",
            "card_myata_tv", "card_xtra_tv", "logo_tv", "myata_bg_tv",
            "myata_bg_load_tv", "gradient_scrim_bottom", "zaglushka_logo", "tv_banner",
        )

        for (name in tvDrawables) {
            val id = context.resources.getIdentifier(name, "drawable", context.packageName)
            if (id == 0) continue

            val day = resolvedPath(id, night = false)
            val night = resolvedPath(id, night = true)
            assertEquals(
                "$name resolves to a different file under a night configuration",
                day,
                night,
            )
        }
    }

    /**
     * The process default is untouched, which is the API-level half of the isolation.
     *
     * Asserted here as well as in `AppearanceSelectionTest` on purpose: this file is
     * where somebody looking for the TV guarantee will read, and the guarantee is
     * only as good as this call's answer.
     *
     * ## What "untouched" is, measured rather than assumed
     *
     * The first version of this asserted `MODE_NIGHT_FOLLOW_SYSTEM` (-1) and failed:
     * an AppCompat that nobody has called `setDefaultNightMode` on reports
     * **`MODE_NIGHT_UNSPECIFIED` (-100)**, and resolves it to the system at the point
     * of use. So the invariant is not one specific value.
     *
     * It is that the default is never a **forced** mode. `MODE_NIGHT_YES` and
     * `MODE_NIGHT_NO` are the only two values that would impose an appearance on
     * every activity in the process, `TvMainActivity` included, and they are the only
     * two that `setDefaultNightMode` would ever be called with here. Asserting their
     * absence is the claim; asserting -1 was asserting an implementation detail of a
     * library version.
     */
    @Test
    fun the_process_default_night_mode_is_never_forced() {
        val actual = AppCompatDelegate.getDefaultNightMode()
        android.util.Log.i(TAG, "AppCompatDelegate.getDefaultNightMode() = $actual")

        assertTrue(
            "the process-wide night mode has been forced to $actual; that reaches " +
                "TvMainActivity, which is an AppCompatActivity in this same process",
            actual != AppCompatDelegate.MODE_NIGHT_YES &&
                actual != AppCompatDelegate.MODE_NIGHT_NO,
        )
    }

    // ==================== helpers ====================

    /** The six colour attributes `TvTheme` actually sets. */
    private fun tvAttrs(night: Boolean): List<String> =
        attrs(R.style.TvTheme, night, listOf(
            "colorPrimary", "colorPrimaryVariant", "colorOnPrimary",
            "colorSecondary", "colorSecondaryVariant", "colorOnSecondary",
        ))

    /** Three roles the mobile tree maps to day/night tokens in `Theme.Myata.Base`. */
    private fun mobileAttrs(night: Boolean): List<String> =
        attrs(R.style.AppTheme, night, listOf(
            "colorSurface", "colorOnSurface", "colorOnBackground",
        ))

    /**
     * Resolved by name rather than through `R.attr`.
     *
     * These attributes are declared by appcompat and Material, and the app is built
     * with non-transitive R classes - so `R.attr.colorPrimary` does not exist in this
     * module even though the merged resource table does contain it. `getIdentifier`
     * asks the table, which is the thing being tested anyway.
     */
    private fun attrs(style: Int, night: Boolean, which: List<String>): List<String> {
        val base = configured(night)
        val themed = ContextThemeWrapper(base, style)
        val out = TypedValue()
        return which.map { name ->
            val attr = base.resources.getIdentifier(name, "attr", context.packageName)
            check(attr != 0) { "no such attribute in the merged table: $name" }
            if (themed.theme.resolveAttribute(attr, out, true)) {
                String.format("%s=#%08X", name, out.data)
            } else {
                "$name=unset"
            }
        }
    }

    /**
     * Which file the id actually resolves to under this configuration.
     *
     * `TypedValue.string` is the resource path the table selected - `res/drawable/x`
     * against `res/drawable-night/x` - so a `-night` copy of a TV asset shows up here
     * as a different string. Comparing the loaded Drawable's class would not: both
     * would be a VectorDrawable.
     */
    private fun resolvedPath(id: Int, night: Boolean): String {
        val out = TypedValue()
        configured(night).resources.getValue(id, out, true)
        return out.string?.toString() ?: "#${out.data}"
    }

    private fun configured(night: Boolean): android.content.Context {
        val cfg = Configuration(context.resources.configuration)
        cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        return context.createConfigurationContext(cfg)
    }
}
