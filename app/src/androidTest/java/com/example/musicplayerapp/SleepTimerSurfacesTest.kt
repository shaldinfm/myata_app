package com.example.musicplayerapp

import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.musicplayerapp.ui.sleeptimer.SleepTimerDuration
import com.example.musicplayerapp.ui.sleeptimer.SleepTimerState
import com.example.musicplayerapp.ui.sleeptimer.SleepTimerText
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The three surfaces the timer appears on, and the one formatter behind them.
 *
 *  - `Menu / Плеер` - the frozen overflow, at the frozen 260, with one row.
 *  - `sleep-timer-select` / `-active` - the sheet, in both of its list states.
 *  - `Row / Таймер сна` - the Settings row, whose geometry `SettingsLayoutTest`
 *    now measures alongside the two rows it already did.
 *
 * What is held here that no single screen's test can hold is that the PLAYER menu
 * and the Settings row say the *same thing* about the same timer. They do not
 * share a view, a fragment or a string resource - they share
 * [SleepTimerText.remaining], and that is the thing asserted.
 */
@RunWith(AndroidJUnit4::class)
class SleepTimerSurfacesTest {

    private val widthsDp = listOf(320, 360, 390, 412)
    private val findings = mutableListOf<String>()

    // ==================== the frozen menu ====================

    @Test
    fun theOverflowMenuReproducesTheFrozenSurface() {
        onMainActivity { activity ->
            for (night in listOf(false, true)) {
                val inflater = inflaterFor(activity, night)
                val dm = inflater.context.resources.displayMetrics
                val dp = { v: Number -> v.toFloat() * dm.density }
                val where = if (night) "menu/dark" else "menu/light"

                // Measured in the armed state, which is the state the frozen frame
                // draws: the trailing value only has a box when there is a value.
                val menu = inflate(inflater, R.layout.menu_player_overflow, dp(260).roundToInt()) {
                    it.findViewById<TextView>(R.id.player_overflow_sleep_timer_trailing).apply {
                        text = "24 \u043c\u0438\u043d"
                        visibility = View.VISIBLE
                    }
                }
                val row = menu.findViewById<View>(R.id.player_overflow_sleep_timer)
                val trailing = menu.findViewById<TextView>(R.id.player_overflow_sleep_timer_trailing)

                // The proposal's whole change to the canonical menu: 206 -> 260,
                // because 206 cannot hold a label plus a trailing value.
                expect(where, "menu width", menu.width, dp(260))
                expect(where, "row height", row.height, dp(48))
                expect(where, "row width", row.width, dp(238))
                expect(where, "row x", leftIn(row, menu), dp(11))
                expect(where, "first row y", topIn(row, menu), dp(10))
                expect(where, "trailing value width", trailing.width, dp(70))

                // One row. The other three frozen entries are absent rather than
                // inert - the whole rollout decision, held where a later edit that
                // "just adds them greyed out" would trip over it.
                // One row, and it is the sleep timer's. The trailing value the
                // frozen active frame puts on that row is not a second entry, so
                // the count is of rows rather than of text.
                assertEquals(
                    "the overflow must carry exactly the actions that exist",
                    1, menuRows(menu).size,
                )
                assertEquals(
                    "Таймер сна",
                    menu.findViewById<TextView>(R.id.player_overflow_sleep_timer_label).text.toString(),
                )
                val labels = collectText(menu).filter { it.isNotBlank() }
                for (absent in listOf("Найти трек", "Сообщить о проблеме", "История эфира")) {
                    if (labels.any { it.contains(absent) }) {
                        findings += "$where: `$absent` has no implementation and must not be drawn"
                    }
                }
            }
        }
        report("MENU")
    }

    @Test
    fun theTrailingValueIsGoneRatherThanBlankWhenNothingIsArmed() {
        onMainActivity { activity ->
            val menu = inflate(
                inflaterFor(activity, night = false), R.layout.menu_player_overflow, 1000,
            )
            // The layout's own default. A blank-but-present value would still take
            // its 70 out of the label, narrowing a row that has nothing to show.
            assertEquals(
                View.GONE,
                menu.findViewById<View>(R.id.player_overflow_sleep_timer_trailing).visibility,
            )
        }
    }

    // ==================== the sheet ====================

    @Test
    fun theSheetReproducesTheFrozenList() {
        onMainActivity { activity ->
            for (night in listOf(false, true)) {
                val inflater = inflaterFor(activity, night)
                val dm = inflater.context.resources.displayMetrics
                val dp = { v: Number -> v.toFloat() * dm.density }
                val where = if (night) "sheet/dark" else "sheet/light"

                for (widthDp in widthsDp) {
                    val sheet = inflate(inflater, R.layout.sheet_sleep_timer, dp(widthDp).roundToInt())
                    val rows = listOf(
                        R.id.sleep_timer_row_15, R.id.sleep_timer_row_30,
                        R.id.sleep_timer_row_45, R.id.sleep_timer_row_60,
                        R.id.sleep_timer_row_custom,
                    ).map { sheet.findViewById<View>(it) }

                    rows.forEachIndexed { i, row ->
                        expect("$where@${widthDp}dp", "row ${i + 1} height", row.height, dp(56))
                    }
                    // 58 pitch: the frozen rows are at 112, 170, 228, 286, 344.
                    for (i in 1 until rows.size) {
                        expect(
                            "$where@${widthDp}dp", "row ${i + 1} pitch",
                            topIn(rows[i], sheet) - topIn(rows[i - 1], sheet), dp(58),
                        )
                    }

                    // The destructive row and its divider belong to the armed states
                    // only, so the layout's default is the select frame.
                    assertEquals(
                        "sleep-timer-select has no `Отключить таймер`",
                        View.GONE, sheet.findViewById<View>(R.id.sleep_timer_row_cancel).visibility,
                    )
                    assertEquals(
                        View.GONE, sheet.findViewById<View>(R.id.sleep_timer_divider).visibility,
                    )
                    // And the picker is behind `Своё время`, not beside the list.
                    assertEquals(
                        View.GONE, sheet.findViewById<View>(R.id.sleep_timer_custom_panel).visibility,
                    )
                }
            }
        }
        report("SHEET")
    }

    @Test
    fun theSheetOffersExactlyTheFrozenPresets() {
        onMainActivity { activity ->
            val sheet = inflate(
                inflaterFor(activity, night = false), R.layout.sheet_sleep_timer, 1000,
            )
            val labels = collectText(sheet).filter { it.isNotBlank() }
            listOf("15 минут", "30 минут", "45 минут", "60 минут", "Своё время").forEach {
                assertTrue("the sheet is missing `$it`: $labels", labels.contains(it))
            }
            assertEquals(
                "the presets the sheet draws and the presets the code offers must agree",
                listOf(15, 30, 45, 60), SleepTimerDuration.PRESETS,
            )
        }
    }

    // ==================== one formatter, two entry points ====================

    @Test
    fun theMenuAndTheSettingsRowSayTheSameThingAboutTheSameTimer() {
        onMainActivity { activity ->
            val ctx = activity
            val now = 1_000_000L
            val timer = SleepTimerState.Armed(
                deadlineElapsedMs = now + 24 * 60_000L + 30_000L,   // 24.5 minutes
                durationMinutes = 30, isCustom = false, generation = 1L,
            )

            val shared = SleepTimerText.remaining(ctx, timer, now)

            // 24.5 minutes rounds up. Both surfaces get that same number because
            // both call the same function - this is the assertion that the two
            // entry points cannot drift apart.
            assertEquals("25 мин", shared)

            val menuValue = shared
            val settingsValue = ctx.getString(R.string.settings_sleep_timer_active, shared)
            val sheetValue = ctx.getString(R.string.sleep_timer_trailing, shared)

            assertTrue(settingsValue.contains(menuValue))
            assertTrue(sheetValue.contains(menuValue))
            assertEquals("Осталось 25 мин", settingsValue)
            assertEquals("осталось 25 мин", sheetValue)
        }
    }

    @Test
    fun longTimersReadInHoursAndMinutes() {
        onMainActivity { activity ->
            val now = 0L
            fun at(minutes: Int) = SleepTimerText.remaining(
                activity,
                SleepTimerState.Armed(minutes * 60_000L, minutes, true, 1L),
                now,
            )
            assertEquals("24 мин", at(24))
            assertEquals("1 ч", at(60))
            assertEquals("1 ч 24 мин", at(84))
            assertEquals("12 ч", at(720))
        }
    }

    @Test
    fun theEndTimeIsDerivedFromTheWallClockAndTheDeadlineIsNot() {
        onMainActivity {
            val now = android.os.SystemClock.elapsedRealtime()
            val timer = SleepTimerState.Armed(now + 90 * 60_000L, 90, true, 1L)

            // The rendered time of day is `wall clock + what is monotonically left`,
            // so it moves when the clock moves. The deadline is a monotonic instant
            // and does not.
            val expected = java.util.Calendar.getInstance().apply {
                timeInMillis = System.currentTimeMillis() + timer.remainingMs(now)
            }
            val rendered = SleepTimerText.endTimeIn(timer.remainingMs(now))
            assertEquals(
                String.format(
                    java.util.Locale.ROOT, "%02d:%02d",
                    expected.get(java.util.Calendar.HOUR_OF_DAY),
                    expected.get(java.util.Calendar.MINUTE),
                ),
                rendered,
            )
            assertEquals(
                "the deadline is monotonic and nothing about rendering may touch it",
                now + 90 * 60_000L, timer.deadlineElapsedMs,
            )
        }
    }

    // ==================== Android TV ====================

    @Test
    fun androidTvHasNoWayToReachTheTimer() {
        onMainActivity { activity ->
            val inflater = inflaterFor(activity, night = false)
            // Every TV surface, inflated and read. TV keeps the shared playback
            // service, so the guarantee that matters is that no TV screen can arm
            // anything - the service refuses on a TV device as well, which is the
            // belt to this braces.
            for (layout in listOf(
                R.layout.fragment_tv_player,
                R.layout.fragment_tv_stream_selection,
                R.layout.fragment_tv_splash,
                R.layout.activity_tv_main,
            )) {
                val root = inflate(inflater, layout, 1920)
                val text = collectText(root).joinToString(" | ")
                for (word in listOf("Таймер", "таймер", "Sleep")) {
                    assertFalse(
                        "a TV screen must not offer the sleep timer: $text",
                        text.contains(word),
                    )
                }
                assertEquals(
                    "no TV screen may host the sleep timer row",
                    null, root.findViewById<View>(R.id.sleep_timer_row_cancel),
                )
            }
        }
    }

    // ==================== helpers ====================

    /** SettingsLayoutTest's own harness, unchanged - including its close-timeout guard. */
    private fun onMainActivity(body: (MainActivity) -> Unit) {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity(body)
        } finally {
            try {
                scenario.close()
            } catch (e: Throwable) {
                android.util.Log.w("SleepTimerSurfaces", "activity close timed out", e)
            }
        }
    }

    /**
     * A themed inflater, per appearance.
     *
     * `setTheme(AppTheme)` is not optional: these layouts use `?attr/` background
     * references and a MaterialCardView, and a bare configuration context resolves
     * neither - it fails with `Error inflating class <unknown>`, which is what the
     * first run of this test did. Cloning the Activity's own inflater is also what
     * carries `MyataTypography.Factory`, so `android:textAppearance` still means a
     * whole token here.
     */
    private fun inflaterFor(activity: MainActivity, night: Boolean): LayoutInflater {
        val config = android.content.res.Configuration(activity.resources.configuration)
        config.uiMode = (config.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (night) android.content.res.Configuration.UI_MODE_NIGHT_YES
            else android.content.res.Configuration.UI_MODE_NIGHT_NO
        val themed = activity.createConfigurationContext(config)
        themed.setTheme(R.style.AppTheme)
        return activity.layoutInflater.cloneInContext(themed)
    }

    /**
     * Inflates a layout and measures it as its own root would be measured.
     *
     * Through a parent, not `null`: inflating with a null root **discards the
     * layout's own `layout_width`**, and the first run of this test measured the
     * 260dp menu at its content's natural 175dp because of it. The declared width
     * is then honoured as an EXACTLY spec, so what is asserted afterwards - the row
     * inside it, the insets, the trailing value's box - is measured against the
     * width the layout actually declares rather than one the test chose.
     */
    private fun inflate(
        inflater: LayoutInflater,
        layout: Int,
        widthPx: Int,
        prepare: (View) -> Unit = {},
    ): View {
        val parent = android.widget.FrameLayout(inflater.context)
        val root = inflater.inflate(layout, parent, false)
        prepare(root)
        val declared = root.layoutParams?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT
        val widthSpec = if (declared > 0) {
            View.MeasureSpec.makeMeasureSpec(declared, View.MeasureSpec.EXACTLY)
        } else {
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.AT_MOST)
        }
        root.measure(
            widthSpec,
            View.MeasureSpec.makeMeasureSpec(4000, View.MeasureSpec.AT_MOST),
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        return root
    }

    /** The row containers of an inflated menu - its direct children. */
    private fun menuRows(menu: View): List<View> {
        val group = menu as ViewGroup
        return (0 until group.childCount).map { group.getChildAt(it) }
    }

    private fun collectText(v: View): List<String> = when (v) {
        is TextView -> listOf(v.text.toString())
        is ViewGroup -> (0 until v.childCount).flatMap { collectText(v.getChildAt(it)) }
        else -> emptyList()
    }

    private fun topIn(view: View, root: View): Int {
        var y = 0
        var v: View? = view
        while (v != null && v !== root) {
            y += v.top
            v = v.parent as? View
        }
        return y
    }

    private fun leftIn(view: View, root: View): Int {
        var x = 0
        var v: View? = view
        while (v != null && v !== root) {
            x += v.left
            v = v.parent as? View
        }
        return x
    }

    private fun expect(where: String, what: String, actual: Number, expected: Number, tolerance: Float = 1.5f) {
        if (abs(actual.toFloat() - expected.toFloat()) > tolerance) {
            findings += "$where: $what is ${actual.toFloat()}, frozen is ${expected.toFloat()}"
        }
    }

    private fun report(tag: String) {
        findings.forEach { android.util.Log.e("SleepTimerSurfaces", "  FINDING $it") }
        assertTrue(
            "$tag findings on API ${Build.VERSION.SDK_INT}:\n" + findings.joinToString("\n") { "  $it" },
            findings.isEmpty(),
        )
    }
}
