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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * settings-appearance against its frames, measured rather than eyeballed.
 *
 * 2517:2817 / 2517:3784, a fixed 390x420:
 *
 *     Header - TopAppBar    0..64
 *     Row Системная        92..164   358x72, r12, 2dp primary, check
 *     Row Светлая         172..236   358x64, r12, 1dp outline
 *     Row Тёмная          244..308   358x64, r12, 1dp outline
 *     note                336..356
 *
 * The tall first row is the point of half of this file. Системная is 72 rather than
 * 64 because it carries a sub-label, and it keeps that height in every selection
 * state - a row that grew and shrank as the choice moved would shift the two rows
 * under it each time. The frozen frame only ever draws Системная selected, so that
 * is a decision rather than a measurement, and this is where it is written down.
 */
@RunWith(AndroidJUnit4::class)
class AppearanceLayoutTest {

    private val widthsDp = listOf(320, 360, 390, 412)
    private val designWidthDp = 390

    private val findings = mutableListOf<String>()
    private val log = mutableListOf<String>()

    @Test
    fun appearanceReproducesTheFrozenFrame() {
        onMainActivity { activity ->
            for (night in listOf(false, true)) {
                sweep(inflaterFor(activity, night), if (night) "dark" else "light")
            }
        }

        android.util.Log.i(TAG, "==== APPEARANCE (API ${Build.VERSION.SDK_INT}) ====")
        log.forEach { android.util.Log.i(TAG, "  $it") }
        findings.forEach { android.util.Log.e(TAG, "  FINDING $it") }

        assertTrue(
            "APPEARANCE findings on API ${Build.VERSION.SDK_INT}:\n" +
                findings.joinToString("\n") { "  $it" },
            findings.isEmpty(),
        )
    }

    /**
     * Three rows, and there is no fourth.
     *
     * A true-black / AMOLED variant was considered for this screen and dropped: the
     * canonical dark background is a navy, and a second dark theme follows from
     * nothing in the file. A row appearing here would be a design decision made in
     * a code change.
     */
    @Test
    fun thereAreExactlyThreeOptions() {
        onMainActivity { activity ->
            val root = measured(inflaterFor(activity, night = false), dp390(activity))
            val labels = collectText(root)

            for (expected in listOf("Системная", "Светлая", "Тёмная")) {
                assertTrue("$expected is missing", labels.any { it == expected })
            }
            assertTrue(
                "the appearance screen has grown a fourth option: $labels",
                labels.none { it.contains("AMOLED", true) || it.contains("Чёрная") },
            )

            // The rows themselves, counted rather than inferred from the copy.
            val rows = listOf(
                R.id.appearance_row_system,
                R.id.appearance_row_light,
                R.id.appearance_row_dark,
            ).mapNotNull { root.findViewById<View>(it) }
            assertEquals("exactly three option rows", 3, rows.size)
        }
    }

    /**
     * The check slot is reserved on all three rows, not just the chosen one.
     *
     * `INVISIBLE`, not `GONE`: the 24dp at x=318 stays occupied whichever row is
     * selected, so the labels beside them do not reflow when the selection moves.
     * A `GONE` check would pass every geometry assertion in the sweep below and
     * still make the screen twitch on every tap.
     */
    @Test
    fun theCheckSlotIsReservedOnEveryRow() {
        onMainActivity { activity ->
            val root = measured(inflaterFor(activity, night = false), dp390(activity))
            val dm = activity.resources.displayMetrics

            for (id in listOf(
                R.id.appearance_check_system,
                R.id.appearance_check_light,
                R.id.appearance_check_dark,
            )) {
                val check = root.findViewById<View>(id)
                assertTrue(
                    "a check slot is GONE; it must be INVISIBLE so the row keeps its shape",
                    check.visibility != View.GONE,
                )
                assertTrue(
                    "check slot is ${check.width}px, expected ~${24 * dm.density}px",
                    abs(check.width - 24 * dm.density) <= 1f,
                )
            }
        }
    }

    private fun sweep(inflater: LayoutInflater, theme: String) {
        val dm = inflater.context.resources.displayMetrics
        val dp = { v: Number -> v.toFloat() * dm.density }
        val ctx = inflater.context

        for (widthDp in widthsDp) {
            val root = measured(inflater, dp(widthDp).roundToInt())
            val where = "appearance/$theme@${widthDp}dp"

            val band = root.find(R.id.appearance_header)
            val title = root.text(R.id.appearance_title)
            val back = root.find(R.id.appearance_back)
            val rowSystem = root.find(R.id.appearance_row_system)
            val rowLight = root.find(R.id.appearance_row_light)
            val rowDark = root.find(R.id.appearance_row_dark)
            val checkSystem = root.find(R.id.appearance_check_system)
            val note = root.text(R.id.appearance_note)

            /* ---- the band ---- */
            expect(where, "header band height", band.height, dp(64))
            expect(where, "back icon size", back.width, dp(24))
            expect(where, "back icon x", leftInRoot(back), dp(12))
            expect(
                where, "heading gap after the back icon",
                leftInRoot(title) - (leftInRoot(back) + back.width), dp(20),
            )

            /* ---- the tall row, and the two that are not ---- */
            expect(where, "Системная row height", rowSystem.height, dp(72))
            expect(where, "Светлая row height", rowLight.height, dp(64))
            expect(where, "Тёмная row height", rowDark.height, dp(64))

            for ((label, row) in listOf(
                "Системная" to rowSystem, "Светлая" to rowLight, "Тёмная" to rowDark,
            )) {
                expect(where, "$label row x", leftInRoot(row), dp(16))
                expect(where, "$label row width", row.width, dp(widthDp - 32))
            }

            /* ---- the check, at x=318 in a 390 frame: 16 + 358 - 16 - 24 ---- */
            expect(where, "check slot size", checkSystem.width, dp(24))
            expect(
                where, "check trailing inset",
                (leftInRoot(rowSystem) + rowSystem.width) - (leftInRoot(checkSystem) + checkSystem.width),
                dp(16),
            )

            /* ---- the chain of gaps ---- */
            expect(where, "band to Системная", gap(band, rowSystem), dp(28))
            expect(where, "Системная to Светлая", gap(rowSystem, rowLight), dp(8))
            expect(where, "Светлая to Тёмная", gap(rowLight, rowDark), dp(8))
            expect(where, "Тёмная to note", gap(rowDark, note), dp(28))

            if (widthDp == designWidthDp) {
                expect(where, "Системная row y", topInRoot(rowSystem), dp(92))
                expect(where, "note box", note.height, dp(20))

                log += "$where content ends at " +
                    "${"%.1f".format((topInRoot(note) + note.height) / dm.density)}dp " +
                    "in a 420dp frame"
            }

            /* ---- type ---- */
            type(where, "heading", title, 24f, 32f, dm)
            type(where, "note", note, 14f, 20f, dm)

            /* ---- colour ---- */
            colour(where, "heading", title, ctx.tone(R.color.text_heading))
            colour(where, "note", note, ctx.tone(R.color.text_secondary))
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
        val root = inflater.inflate(R.layout.fragment_settings_appearance, null) as ViewGroup
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
        const val TAG = "APPEARANCEQA"
    }
}
