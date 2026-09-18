package com.example.musicplayerapp

import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayerapp.data.lastfm.LastfmConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The `Интеграции > Last.fm` door, and the gate in front of it.
 *
 * Two halves, and the second is the one that matters today: G6b ships before its
 * credentials exist, so **every** build in existence is the unconfigured one, and
 * without the override there would be no way to reach the row on a device at all.
 *
 * The override sets the *decision*, never a credential. Nothing in this slice can
 * sign anything, and a test that handed it a fake key and secret would be a test
 * that could.
 */
@RunWith(AndroidJUnit4::class)
class LastfmEntryPointTest {

    @Before
    fun clearOverrideAndGrantNotifications() {
        LastfmConfig.configuredOverrideForTest = null
        // A fresh install re-arms the API 33+ notification prompt, which pauses
        // MainActivity and turns every wait below into a bare timeout. Granted the
        // way ReportEntryPointsTest does it.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.executeShellCommand(
                "pm grant ${instrumentation.targetContext.packageName} " +
                    "android.permission.POST_NOTIFICATIONS"
            ).close()
        }
    }

    @After
    fun restoreOverride() {
        // Left set, this would leak into every later suite in the run and make the
        // row appear in tests that assert it does not.
        LastfmConfig.configuredOverrideForTest = null
    }

    @Test
    fun a_configured_build_draws_the_row_and_opens_the_screen() {
        LastfmConfig.configuredOverrideForTest = true

        withMainActivity {
            openSettingsAndSettle()

            on { activity ->
                assertEquals(
                    "the row is drawn when the build has credentials",
                    View.VISIBLE,
                    activity.findViewById<View>(R.id.settings_row_lastfm).visibility,
                )
                assertEquals(
                    "and its section with it",
                    View.VISIBLE,
                    activity.findViewById<View>(R.id.settings_section_integrations).visibility,
                )
                assertEquals(
                    "the value states the only fact P3a can state",
                    activity.getString(R.string.settings_lastfm_not_connected),
                    activity.findViewById<TextView>(R.id.settings_row_lastfm_value).text.toString(),
                )
            }

            tap(R.id.settings_row_lastfm)
            awaitDestination(R.id.settings_lastfm)

            on { activity ->
                // The screen is the frozen one, in its only reachable state.
                val heading = activity.findViewById<TextView>(R.id.lastfm_card_heading)
                assertNotNull("the card is drawn", heading)
                assertEquals(
                    activity.getString(R.string.lastfm_card_disconnected_heading),
                    heading.text.toString(),
                )
                assertEquals(
                    activity.getString(R.string.lastfm_action_connect),
                    activity.findViewById<TextView>(R.id.lastfm_action).text.toString(),
                )
                assertEquals(
                    "the check belongs to the connected card alone",
                    View.GONE,
                    activity.findViewById<View>(R.id.lastfm_check).visibility,
                )
                // A pushed destination draws no bottom bar. Unlike the Mini Player,
                // which NavScreen's PUSHED default hides for any new destination,
                // the bar is hidden by an explicit opt-in list in MainActivity - so a
                // new screen shows it unless someone adds it there. This assertion
                // is the one that caught settings_lastfm missing from that list.
                assertEquals(
                    "no frozen pushed frame draws the bottom bar",
                    View.GONE,
                    activity.findViewById<View>(R.id.bottomNavView).visibility,
                )
            }
        }
    }

    @Test
    fun an_unconfigured_build_offers_no_way_in() {
        // An explicit false, not a cleared override. Clearing falls through to
        // whatever `lastfm.properties` this machine happens to have, so the test
        // would assert one thing in CI and the opposite on a tree with credentials.
        // `LastfmConfigTest` covers the fall-through separately.
        LastfmConfig.configuredOverrideForTest = false
        assertEquals(
            "this test must run against a build with no credentials",
            false, LastfmConfig.isConfigured,
        )

        withMainActivity {
            openSettingsAndSettle()
            on { activity ->
                assertEquals(
                    "a row that opens a screen which cannot connect is a dead control",
                    View.GONE,
                    activity.findViewById<View>(R.id.settings_row_lastfm).visibility,
                )
                assertEquals(
                    "and its section goes with it rather than standing empty",
                    View.GONE,
                    activity.findViewById<View>(R.id.settings_section_integrations).visibility,
                )
                // The rest of Settings is untouched - in particular the section that
                // follows closes up behind the hidden one rather than leaving a gap.
                assertEquals(
                    View.VISIBLE,
                    activity.findViewById<View>(R.id.settings_row_sleep_timer).visibility,
                )
                // `Прочее` is itself gated on the report endpoint, so its visibility
                // is not this test's to assert either way - what matters is that
                // hiding Интеграции did not disturb the row above it.
                assertTrue(
                    "the sleep timer row keeps its position when Интеграции is hidden",
                    activity.findViewById<View>(R.id.settings_row_sleep_timer).top > 0,
                )
            }
        }
    }

    // ==================== harness ====================

    private fun on(block: (MainActivity) -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { block(resumedMainActivity()) }
    }

    private fun tap(id: Int) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            resumedMainActivity().findViewById<View>(id).performClick()
        }
    }

    /**
     * Waits for [id] to become the current destination.
     *
     * The fragment transaction is not the frame the tap happened in, so a suite
     * that taps and asserts in one breath reads the destination it started on -
     * the trap `openSettingsAndSettle` documents. Polled on the main thread with
     * `runOnMainSync`, never `onActivity`, because a screen with a spinner up can
     * make the idling-based waits block forever on API 24.
     */
    private fun awaitDestination(id: Int, timeoutMs: Long = 10_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            var current: Int? = null
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                current = resumedMainActivity().currentDestinationIdOrNull()
            }
            if (current == id) return
            Thread.sleep(50)
        }
        throw AssertionError("destination $id was not reached within ${timeoutMs}ms")
    }
}
