package com.example.musicplayerapp

import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.example.musicplayerapp.data.report.ReportConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The two frozen doors, and the one they open.
 *
 *  - `Menu / Плеер` > `Сообщить о проблеме`, the third of the frozen four.
 *  - `Settings > Прочее > Сообщить о проблеме`.
 *
 * What no single screen's test can hold is that they are the *same* screen in the
 * *same* state - they share no view, no fragment argument and no code path beyond
 * the destination id, and it is that identity which is asserted here.
 *
 * The other half of this file is the gate: in a build with no report endpoint,
 * neither door exists. G3 ships before its endpoint is deployed, so that is not a
 * hypothetical state - it is the state every build is in today, and the one a
 * fresh clone and CI are always in.
 */
@RunWith(AndroidJUnit4::class)
class ReportEntryPointsTest {

    private val configured = "https://example.invalid/report"

    @Before
    fun grantNotifications() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.executeShellCommand(
                "pm grant ${instrumentation.targetContext.packageName} " +
                    "android.permission.POST_NOTIFICATIONS"
            ).close()
        }
    }

    @After
    fun clearEndpointOverride() {
        ReportConfig.endpointOverrideForTest = null
    }

    // ==================== both doors reach the same screen ====================

    @Test
    fun the_settings_row_opens_the_report_screen_with_the_bar_hidden() {
        ReportConfig.endpointOverrideForTest = configured
        withMainActivity {
            openSettingsAndSettle()

            on { activity ->
                val row = activity.findViewById<View>(R.id.settings_row_report_problem)
                assertEquals("the frozen row must be drawn", View.VISIBLE, row.visibility)
                assertTrue(row.isClickable)
                assertEquals(
                    "Прочее",
                    activity.string(R.id.settings_section_other),
                )
            }

            tap(R.id.settings_row_report_problem)
            awaitDestination(R.id.report_problem)

            on { activity ->
                assertEquals(R.id.report_problem, activity.destination())
                assertEquals("Сообщить о проблеме", activity.string(R.id.report_title))
                assertEquals(
                    "the five frozen frames have no bottom bar",
                    View.GONE,
                    activity.findViewById<View>(R.id.bottomNavView).visibility,
                )
            }

            // Back returns to the door it came through, not to a named destination.
            tap(R.id.report_back)
            on { assertEquals(R.id.settings, it.destination()) }
        }
    }

    @Test
    fun the_player_menu_row_opens_the_same_screen() {
        ReportConfig.endpointOverrideForTest = configured
        withMainActivity {
            openPlayerAndSettle()

            // The overflow is a PopupWindow over an inflated layout rather than a
            // platform menu, so its rows are ordinary views in a window this test
            // can reach - which is the whole reason G2 built it that way.
            on { it.findViewById<View>(R.id.player_header_action).performClick() }
            sync()

            val row = awaitMenuRow(R.id.player_overflow_report_problem)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { row.performClick() }
            awaitDestination(R.id.report_problem)

            on { activity ->
                assertEquals(R.id.report_problem, activity.destination())
                assertEquals("Сообщить о проблеме", activity.string(R.id.report_title))
                assertEquals(
                    View.GONE,
                    activity.findViewById<View>(R.id.bottomNavView).visibility,
                )
            }

            tap(R.id.report_back)
            on { assertEquals(R.id.player, it.destination()) }
        }
    }

    /**
     * The same screen in the same state, whichever door was used.
     *
     * Not "both navigate somewhere" - the resting state is compared field by field,
     * because the failure this guards against is a door that opens the form with
     * something preselected, or with a category the other door does not offer.
     */
    @Test
    fun both_doors_open_the_same_screen_in_the_same_state() {
        ReportConfig.endpointOverrideForTest = configured

        val throughSettings = captureRestingState { openSettingsAndSettle(); tap(R.id.settings_row_report_problem) }
        val throughPlayer = captureRestingState {
            openPlayerAndSettle()
            on { it.findViewById<View>(R.id.player_header_action).performClick() }
            sync()
            val row = awaitMenuRow(R.id.player_overflow_report_problem)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { row.performClick() }
        }

        assertEquals(
            "the two frozen doors must open the same screen in the same state",
            throughSettings, throughPlayer,
        )
        // And it is the empty form: nothing chosen, nothing typed, Send inert.
        assertEquals("Отправить", throughSettings.sendLabel)
        assertEquals(false, throughSettings.sendEnabled)
        assertEquals("", throughSettings.message)
        assertEquals(-1, throughSettings.selectedCategory)
    }

    // ==================== the live menu, with the compact geometry ====================

    /**
     * The live menu, not the inflated layout.
     *
     * `SleepTimerSurfacesTest` measures the layout in both themes and at four
     * widths; what it cannot see is the popup [com.example.musicplayerapp.ui.PlayerOverflowMenu]
     * actually opens. This opens the real one and measures what a listener gets:
     * the frozen four rows (G4b) under the compact 10 / 10 padding the owner chose
     * over the frozen 10 / 50 in the G4a review.
     */
    @Test
    fun the_live_menu_has_the_frozen_four_rows_and_the_compact_bottom_padding() {
        ReportConfig.endpointOverrideForTest = configured
        withMainActivity {
            openPlayerAndSettle()
            on { it.findViewById<View>(R.id.player_header_action).performClick() }
            sync()

            val reportRow = awaitMenuRow(R.id.player_overflow_report_problem)
            on { activity ->
                val menu = reportRow.rootView.findViewById<View>(R.id.player_overflow_root)
                val timerRow = menu.findViewById<View>(R.id.player_overflow_sleep_timer)
                val density = activity.resources.displayMetrics.density
                val dp = { px: Int -> px / density }

                val findRow = menu.findViewById<View>(R.id.player_overflow_find_track)
                val historyRow = menu.findViewById<View>(R.id.player_overflow_history)
                val ordered = listOf(findRow, timerRow, reportRow, historyRow)

                assertEquals("menu width", 260f, dp(menu.width), 1.5f)
                // The compact menu (owner override, G4a review): 10 / 10 padding,
                // so the frozen four are 10 + 4*48 + 3*4 + 10 = 224.
                assertEquals("menu height", 224f, dp(menu.height), 1.5f)
                assertEquals("top padding", 10f, dp(menu.paddingTop), 1.5f)
                assertEquals("bottom padding", 10f, dp(menu.paddingBottom), 1.5f)

                ordered.forEach { assertEquals(View.VISIBLE, it.visibility) }
                // Найти трек, Таймер сна, Сообщить о проблеме, История эфира - top
                // to bottom, at the frozen 52 pitch.
                ordered.zipWithNext().forEach { (a, b) ->
                    assertTrue("rows must be in the frozen order", a.top < b.top)
                    assertEquals("row pitch", 52f, dp(b.top - a.top), 1.5f)
                }

                // The sleep timer is still what it was: G3 added a row beside it and
                // changed nothing about it.
                assertTrue(timerRow.isClickable)
                assertEquals(
                    "Таймер сна",
                    menu.findViewById<TextView>(R.id.player_overflow_sleep_timer_label)
                        .text.toString(),
                )
                // G4b built the two rows G2 and G3 left absent: all four now.
                assertEquals(
                    "the live menu must carry the frozen four",
                    4, (menu as android.view.ViewGroup).childCount,
                )
            }
        }
    }

    /**
     * The sleep timer still works from beside the new row, and its trailing slot is
     * untouched.
     *
     * G3's row is next to the one G2 shipped, so the cheapest way to break the timer
     * would be to add a sibling that swallows its taps or its trailing `primary`
     * value. Whether the value *formats* correctly is `SleepTimerSurfacesTest`'s
     * claim and is not repeated here; what is new, and therefore asserted here, is
     * that a second row did not disturb either.
     */
    @Test
    fun the_sleep_timer_row_still_opens_its_sheet_from_beside_the_new_row() {
        ReportConfig.endpointOverrideForTest = configured
        withMainActivity {
            openPlayerAndSettle()
            on { it.findViewById<View>(R.id.player_header_action).performClick() }
            sync()

            val timerRow = awaitMenuRow(R.id.player_overflow_sleep_timer)
            on {
                val trailing = timerRow.rootView
                    .findViewById<TextView>(R.id.player_overflow_sleep_timer_trailing)
                assertNotNull("the trailing slot must survive the new row", trailing)
                // Nothing is armed in a fresh launch, and G2's rule is that the slot
                // is GONE rather than blank in that state - so the label takes the
                // row's full remaining width.
                assertEquals(View.GONE, trailing.visibility)
                assertTrue("the timer row must still be tappable", timerRow.isClickable)
            }

            InstrumentationRegistry.getInstrumentation().runOnMainSync { timerRow.performClick() }
            sync()

            on { activity ->
                val host = activity.supportFragmentManager
                    .findFragmentById(R.id.navHostFragment) as androidx.navigation.fragment.NavHostFragment
                val player = host.childFragmentManager.fragments
                    .filterIsInstance<com.example.musicplayerapp.fragments.PlayerFragment>()
                    .first()
                assertNotNull(
                    "the sleep timer sheet must still open from the menu",
                    player.childFragmentManager.fragments
                        .firstOrNull { it is com.example.musicplayerapp.fragments.SleepTimerSheet },
                )
            }
        }
    }

    // ==================== the gate ====================

    /**
     * With no endpoint, neither door exists - and the menu closes up around the
     * missing row rather than leaving a gap where it was.
     *
     * This is the state of every build today, so it is the one a reviewer will
     * actually see if they install this branch.
     */
    @Test
    fun an_unconfigured_build_offers_no_way_into_the_form() {
        // An **empty** override, not a cleared one. Clearing it falls through to
        // whatever `report.properties` this machine happens to have, so the test
        // would assert the opposite thing on a developer's tree from the one it
        // asserts in CI - which is exactly what happened the first time it ran on a
        // machine that had an endpoint. Empty states the condition instead of
        // hoping for it. `ReportConfigTest` covers the fall-through separately.
        ReportConfig.endpointOverrideForTest = ""
        assertEquals(
            "this test must run against a build with no usable endpoint",
            false, ReportConfig.isConfigured,
        )

        withMainActivity {
            openSettingsAndSettle()
            on { activity ->
                assertEquals(
                    "a row that opens a form which cannot post is a dead control",
                    View.GONE,
                    activity.findViewById<View>(R.id.settings_row_report_problem).visibility,
                )
                assertEquals(
                    "and its section goes with it rather than standing empty",
                    View.GONE,
                    activity.findViewById<View>(R.id.settings_section_other).visibility,
                )
                // The rest of Settings is untouched.
                assertEquals(
                    View.VISIBLE,
                    activity.findViewById<View>(R.id.settings_row_sleep_timer).visibility,
                )
            }
        }

        withMainActivity {
            openPlayerAndSettle()
            on { it.findViewById<View>(R.id.player_header_action).performClick() }
            sync()

            val timerRow = awaitMenuRow(R.id.player_overflow_sleep_timer)
            on { activity ->
                val menu = timerRow.rootView.findViewById<View>(R.id.player_overflow_root)
                val density = activity.resources.displayMetrics.density
                assertEquals(
                    View.GONE,
                    menu.findViewById<View>(R.id.player_overflow_report_problem).visibility,
                )
                // Three rows - the report row and its gap gone - under the same
                // compact 10 / 10 padding: 10 + 3*48 + 2*4 + 10 = 172.
                assertEquals("menu height", 172f, menu.height / density, 1.5f)
                assertEquals("bottom padding", 10f, menu.paddingBottom / density, 1.5f)
            }
        }
    }

    // ==================== harness ====================

    private data class RestingState(
        val destination: Int?,
        val title: String,
        val prompt: String,
        val sendLabel: String,
        val sendEnabled: Boolean,
        val message: String,
        val selectedCategory: Int,
        val bannerVisible: Boolean,
        val successVisible: Boolean,
        val categories: List<String>,
        val diagnosticsTitle: String,
        val notice: String,
    )

    private fun captureRestingState(open: () -> Unit): RestingState {
        var captured: RestingState? = null
        withMainActivity {
            open()
            awaitDestination(R.id.report_problem)
            on { activity ->
                captured = RestingState(
                    destination = activity.destination(),
                    title = activity.string(R.id.report_title),
                    prompt = activity.string(R.id.report_prompt),
                    sendLabel = activity.string(R.id.report_send_label),
                    sendEnabled = activity.findViewById<View>(R.id.report_send).isClickable,
                    message = activity
                        .findViewById<android.widget.EditText>(R.id.report_message).text.toString(),
                    selectedCategory = checkIds.indexOfFirst {
                        activity.findViewById<View>(it).visibility == View.VISIBLE
                    },
                    bannerVisible =
                        activity.findViewById<View>(R.id.report_error_banner).visibility == View.VISIBLE,
                    successVisible =
                        activity.findViewById<View>(R.id.report_success).visibility == View.VISIBLE,
                    categories = labelIds.map { activity.string(it) },
                    diagnosticsTitle = activity.string(R.id.report_diagnostics_title),
                    notice = activity.string(R.id.report_diagnostics_notice),
                )
            }
        }
        return captured ?: throw AssertionError("the report screen never opened")
    }

    private val checkIds = listOf(
        R.id.report_category_0_check, R.id.report_category_1_check,
        R.id.report_category_2_check, R.id.report_category_3_check,
        R.id.report_category_4_check,
    )

    private val labelIds = listOf(
        R.id.report_category_0_label, R.id.report_category_1_label,
        R.id.report_category_2_label, R.id.report_category_3_label,
        R.id.report_category_4_label,
    )

    /** The bottom bar's PLAYER tab, then a wait for the destination. */
    private fun openPlayerAndSettle(timeoutMs: Long = 15_000) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            resumed().findViewById<View>(R.id.nav_item_player).performClick()
        }
        awaitDestination(R.id.player, timeoutMs)
    }

    /**
     * Waits for a row of the popup to exist and be laid out.
     *
     * The menu is a window of its own, so it is reached through the row's own
     * `rootView` rather than through the activity's content view - and it needs a
     * wait, because showing a PopupWindow is not the frame the tap happened in.
     */
    private fun awaitMenuRow(id: Int, timeoutMs: Long = 10_000): View {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            var found: View? = null
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                found = menuRootOf(resumed())?.findViewById(id)
            }
            val row = found
            if (row != null && row.width > 0) return row
            Thread.sleep(25)
        }
        throw AssertionError("the overflow menu never appeared")
    }

    /**
     * The popup's content view.
     *
     * A PopupWindow is not in the activity's view tree, so this asks the fragment
     * for the menu it is holding rather than searching the decor view - which would
     * find nothing.
     */
    private fun menuRootOf(activity: MainActivity): View? {
        val host = activity.supportFragmentManager.findFragmentById(R.id.navHostFragment)
            as? androidx.navigation.fragment.NavHostFragment ?: return null
        val player = host.childFragmentManager.fragments
            .filterIsInstance<com.example.musicplayerapp.fragments.PlayerFragment>()
            .firstOrNull() ?: return null
        return player.overflowContentForTest()
    }

    private fun tap(id: Int) {
        on { it.findViewById<View>(id).performClick() }
        sync()
    }

    private fun on(block: (MainActivity) -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { block(resumed()) }
    }

    private fun resumed(): MainActivity =
        ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(Stage.RESUMED)
            .filterIsInstance<MainActivity>()
            .lastOrNull() ?: error("no resumed MainActivity")

    private fun sync() = InstrumentationRegistry.getInstrumentation().runOnMainSync { }

    private fun awaitDestination(id: Int, timeoutMs: Long = 10_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            var there = false
            on { there = it.destination() == id }
            if (there) return
            Thread.sleep(25)
        }
        throw AssertionError("never reached destination $id")
    }

    private fun MainActivity.destination(): Int? = currentDestinationIdOrNull()

    private fun MainActivity.string(id: Int): String =
        findViewById<TextView>(id).text.toString()

    private fun assertEquals(what: String, expected: Float, actual: Float, tolerance: Float) {
        org.junit.Assert.assertEquals(what, expected.toDouble(), actual.toDouble(), tolerance.toDouble())
    }
}
