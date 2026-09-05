package com.example.musicplayerapp

import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The settings shell against its frames, measured rather than eyeballed.
 *
 * 2517:2758 / 2517:3725. The frozen frame is 390x792 and draws five sections; G1
 * draws the two whose features exist, so the anchors asserted here are the ones
 * that belong to those two and to the band above them:
 *
 *     Header - TopAppBar    0..64
 *     Section Аккаунт      84..104
 *     Row Профиль         112..176   358x64, r12
 *     Section Внешний вид 196..216
 *     Row Тема            224..288   358x64, r12
 *
 * Built the way `ProfileAuthenticatedLayoutTest` is, and for the same reasons: the
 * chain of gaps and boxes is asserted rather than the absolute offsets, because
 * Android rounds each dp to a whole pixel independently and the drift that
 * accumulates says nothing about the layout. The one absolute anchor is the first
 * content row, which has two rounded boundaries above it and is therefore exact.
 *
 * Colour is checked alongside geometry, per theme, because a screen drawn entirely
 * in the wrong palette would pass a purely dimensional sweep - and this screen is
 * the one whose whole subject is which palette is drawn.
 *
 * ## Density
 *
 * Measured at the emulator's own density, whatever it is. Everything asserted is a
 * dp relationship converted through the same `displayMetrics`, so a 420dpi device
 * and a 443dpi one agree. A run pinned to 443dpi has produced 1px findings on other
 * screens that are an artefact of the pin rather than of the layout; there is
 * nothing here that needs it.
 */
@RunWith(AndroidJUnit4::class)
class SettingsLayoutTest {

    private val widthsDp = listOf(320, 360, 390, 412)
    private val designWidthDp = 390

    private val findings = mutableListOf<String>()
    private val log = mutableListOf<String>()

    @Test
    fun settingsReproducesTheFrozenFrame() {
        onMainActivity { activity ->
            for (night in listOf(false, true)) {
                sweep(inflaterFor(activity, night), if (night) "dark" else "light")
            }
        }

        android.util.Log.i(TAG, "==== SETTINGS (API ${Build.VERSION.SDK_INT}) ====")
        log.forEach { android.util.Log.i(TAG, "  $it") }
        findings.forEach { android.util.Log.e(TAG, "  FINDING $it") }

        assertTrue(
            "SETTINGS findings on API ${Build.VERSION.SDK_INT}:\n" +
                findings.joinToString("\n") { "  $it" },
            findings.isEmpty(),
        )
    }

    /**
     * The three sections G1 does not draw are genuinely not drawn.
     *
     * Not a geometry claim - a scope one. The owner's decision was that stream
     * quality, the sleep timer, Last.fm, the report form and the about-app row each
     * arrive with the slice that makes them true, rather than appearing here inert
     * with a value like `Выключен` beside a feature that does not exist. A later
     * edit that quietly adds one back would otherwise be invisible in review.
     */
    @Test
    fun theUnbuiltSectionsAreAbsentRatherThanInert() {
        onMainActivity { activity ->
            val root = measured(inflaterFor(activity, night = false), dp390(activity))
            val absentStrings = listOf(
                "Воспроизведение", "Интеграции", "Прочее",
                "Качество потока", "Таймер сна", "Last.fm",
                "Сообщить о проблеме", "О приложении",
            )
            val present = collectText(root)
            val leaked = absentStrings.filter { s -> present.any { it.contains(s) } }
            assertTrue(
                "settings must not draw rows for features that do not exist yet: $leaked",
                leaked.isEmpty(),
            )
        }
    }

    private fun sweep(inflater: LayoutInflater, theme: String) {
        val dm = inflater.context.resources.displayMetrics
        val dp = { v: Number -> v.toFloat() * dm.density }
        val ctx = inflater.context

        for (widthDp in widthsDp) {
            val root = measured(inflater, dp(widthDp).roundToInt())
            val where = "settings/$theme@${widthDp}dp"

            val band = root.find(R.id.settings_header)
            val title = root.text(R.id.settings_title)
            val back = root.find(R.id.settings_back)
            val sectionAccount = root.text(R.id.settings_section_account)
            val rowProfile = root.find(R.id.settings_row_profile)
            val profileValue = root.text(R.id.settings_row_profile_value)
            val sectionAppearance = root.text(R.id.settings_section_appearance)
            val rowTheme = root.find(R.id.settings_row_theme)
            val themeValue = root.text(R.id.settings_row_theme_value)

            /* ---- the band, identical to the profile and auth bands ---- */
            expect(where, "header band height", band.height, dp(64))
            expect(where, "back icon size", back.width, dp(24))
            expect(where, "back icon x", leftInRoot(back), dp(12))
            expect(
                where, "heading gap after the back icon",
                leftInRoot(title) - (leftInRoot(back) + back.width), dp(20),
            )

            /* ---- rows: fixed box at every width ---- */
            for ((label, row) in listOf("Профиль" to rowProfile, "Тема" to rowTheme)) {
                expect(where, "$label row height", row.height, dp(64))
                expect(where, "$label row x", leftInRoot(row), dp(16))
                expect(where, "$label row width", row.width, dp(widthDp - 32))
            }

            for ((label, v) in listOf(
                "Аккаунт" to sectionAccount, "Внешний вид" to sectionAppearance,
            )) {
                expect(where, "$label section x", leftInRoot(v), dp(16))
            }

            /* ---- the chain of gaps ---- */
            expect(where, "band to Аккаунт", gap(band, sectionAccount), dp(20))
            expect(where, "Аккаунт to Профиль", gap(sectionAccount, rowProfile), dp(8))
            expect(where, "Профиль to Внешний вид", gap(rowProfile, sectionAppearance), dp(20))
            expect(where, "Внешний вид to Тема", gap(sectionAppearance, rowTheme), dp(8))

            if (widthDp == designWidthDp) {
                expect(where, "Профиль row y", topInRoot(rowProfile), dp(112))
                expect(where, "Аккаунт box", sectionAccount.height, dp(20))
                expect(where, "Внешний вид box", sectionAppearance.height, dp(20))

                log += "$where content ends at " +
                    "${"%.1f".format((topInRoot(rowTheme) + rowTheme.height) / dm.density)}dp " +
                    "in a 792dp frame"
            }

            /* ---- type ---- */
            type(where, "heading", title, 24f, 32f, dm)
            type(where, "Аккаунт", sectionAccount, 14f, 20f, dm)
            type(where, "Внешний вид", sectionAppearance, 14f, 20f, dm)
            type(where, "Профиль value", profileValue, 14f, 20f, dm)
            type(where, "Тема value", themeValue, 14f, 20f, dm)

            /* ---- colour ---- */
            colour(where, "heading", title, ctx.tone(R.color.text_heading))
            colour(where, "Аккаунт", sectionAccount, ctx.tone(R.color.profile_section_label))
            colour(
                where, "Внешний вид", sectionAppearance,
                ctx.tone(R.color.profile_section_label),
            )
            colour(where, "Профиль value", profileValue, ctx.tone(R.color.text_secondary))
            colour(where, "Тема value", themeValue, ctx.tone(R.color.text_secondary))
        }
    }

    // ==================== helpers ====================

    private fun collectText(v: View): List<String> = when (v) {
        is TextView -> listOf(v.text.toString())
        is ViewGroup -> (0 until v.childCount).flatMap { collectText(v.getChildAt(it)) }
        else -> emptyList()
    }

    private fun dp390(activity: MainActivity): Int =
        (390 * activity.resources.displayMetrics.density).roundToInt()

    private fun ViewGroup.find(id: Int): View = findViewById(id)
    private fun ViewGroup.text(id: Int): TextView = findViewById(id)
    private fun android.content.Context.tone(id: Int): Int = resources.getColor(id, theme)
    private fun Int.hex(): String = String.format("#%08X", this)
    private fun gap(a: View, b: View): Int = topInRoot(b) - (topInRoot(a) + a.height)

    private fun type(
        where: String, what: String, tv: TextView,
        sizeSp: Float, lineSp: Float, dm: android.util.DisplayMetrics,
    ) {
        expect(where, "$what text size", tv.textSize.roundToInt(), sizeSp * dm.scaledDensity)
        expect(where, "$what line height", tv.lineHeight, lineSp * dm.density)
    }

    private fun colour(where: String, what: String, tv: TextView, expected: Int) {
        if (tv.currentTextColor != expected) {
            findings += "$where: $what is ${tv.currentTextColor.hex()}, frame says ${expected.hex()}"
        }
    }

    private fun measured(inflater: LayoutInflater, widthPx: Int): ViewGroup {
        val root = inflater.inflate(R.layout.fragment_settings, null) as ViewGroup
        root.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(widthPx * 2, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        return root
    }

    private fun inflaterFor(activity: MainActivity, night: Boolean): LayoutInflater {
        val cfg = Configuration(activity.resources.configuration)
        cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        val themed = activity.createConfigurationContext(cfg)
        themed.setTheme(R.style.AppTheme)
        return activity.layoutInflater.cloneInContext(themed)
    }

    private fun onMainActivity(block: (MainActivity) -> Unit) {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity(block)
        } finally {
            try {
                scenario.close()
            } catch (e: Throwable) {
                android.util.Log.w(TAG, "activity close timed out; checks already complete", e)
            }
        }
    }

    private fun offsetToRoot(v: View, r: Rect) {
        var p = v.parent
        while (p is View) { r.offset(p.left, p.top); p = p.parent }
    }

    private fun topInRoot(v: View): Int =
        Rect(v.left, v.top, v.right, v.bottom).also { offsetToRoot(v, it) }.top

    private fun leftInRoot(v: View): Int =
        Rect(v.left, v.top, v.right, v.bottom).also { offsetToRoot(v, it) }.left

    private fun expect(where: String, what: String, actual: Int, expected: Float) {
        if (abs(actual - expected) > 1f) {
            findings += "$where: $what is ${actual}px, frozen design says ${expected.roundToInt()}px"
        }
    }

    private companion object {
        const val TAG = "SETTINGSQA"
    }
}
