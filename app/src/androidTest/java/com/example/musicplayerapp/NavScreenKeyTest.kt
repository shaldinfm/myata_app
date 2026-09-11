package com.example.musicplayerapp

import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayerapp.ui.NavScreen
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The screen key follows the destination, on every destination.
 *
 * This is the regression test for the Mini Player staying up over Profile and
 * Settings. `MiniPlayerVisibilityScopeTest` holds the rule; this holds its
 * **input**, which is the half that was actually broken - the rule was being
 * asked about HOME while Settings was on screen, because the four bottom-bar
 * fragments wrote the key about themselves and the pushed screens wrote nothing.
 *
 * So this walks to a pushed destination and back and asserts the key each time.
 * It does not assert the pill's visibility directly: the pill also requires a
 * playback session, and starting real playback to prove a navigation property
 * would make this a network test. The two files together cover it.
 */
@RunWith(AndroidJUnit4::class)
class NavScreenKeyTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    /**
     * Grant POST_NOTIFICATIONS before launching anything.
     *
     * MainActivity asks for it in `onCreate` on API 33+, and the system dialog
     * takes focus - which leaves the activity PAUSED, not RESUMED, so
     * `resumedMainActivity` finds nothing and the suite fails on the harness
     * instead of on what it measures. Same guard `SettingsEntryTest` opens with,
     * and for the same reason.
     */
    @Before
    fun grantNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            val context = instrumentation.targetContext
            ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(
                    "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS"
                ),
            ).use { it.readBytes() }
        }
    }

    @Test
    fun the_key_is_the_destination_on_home_and_on_a_pushed_screen() {
        withActivity {
            await("HOME") { it.currentDestinationIdOrNull() == R.id.home }
            assertEquals("HOME must publish its own key", NavScreen.HOME, key())

            openSettingsAndSettle()
            assertEquals(
                "a pushed destination must publish the pushed key, not the one it came from",
                NavScreen.PUSHED,
                key(),
            )

            tap(R.id.settings_back)
            await("HOME") { it.currentDestinationIdOrNull() == R.id.home }
            assertEquals("Back must restore HOME's key", NavScreen.HOME, key())
        }
    }

    @Test
    fun the_settings_subpage_is_pushed_too() {
        withActivity {
            await("HOME") { it.currentDestinationIdOrNull() == R.id.home }
            openSettingsAndSettle()

            tap(R.id.settings_row_theme)
            await("appearance") { it.currentDestinationIdOrNull() == R.id.settings_appearance }
            assertEquals(
                "every Settings subpage is a pushed destination",
                NavScreen.PUSHED,
                key(),
            )
        }
    }

    @Test
    fun the_player_publishes_its_own_key_and_not_the_pushed_one() {
        // PLAYER hides the pill for its own reason - it is the same content full
        // size - so it must stay distinguishable from a pushed screen rather than
        // being folded into PUSHED.
        withActivity {
            await("HOME") { it.currentDestinationIdOrNull() == R.id.home }
            tap(R.id.nav_item_player)
            await("PLAYER") { it.currentDestinationIdOrNull() == R.id.player }
            assertEquals(NavScreen.PLAYER, key())
        }
    }

    private fun key(): String? {
        var out: String? = null
        instrumentation.runOnMainSync {
            out = resumedMainActivity().viewModel.currentFragmentLiveData.value
        }
        return out
    }

    /** `awaitDestination` in AuthTestDoubles is file-private, so this is its twin. */
    private fun await(what: String, timeoutMs: Long = 20_000, check: (MainActivity) -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            var ok = false
            runCatching { instrumentation.runOnMainSync { ok = check(resumedMainActivity()) } }
            if (ok) return
            Thread.sleep(25)
        }
        error("timed out after ${timeoutMs}ms waiting for $what")
    }

    private fun tap(id: Int) {
        instrumentation.runOnMainSync { resumedMainActivity().findViewById<View>(id).performClick() }
        instrumentation.runOnMainSync { }
    }

    /** See `SettingsEntryTest.withActivity` for why this is not `use {}`. */
    private fun withActivity(body: () -> Unit) {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            body()
        } finally {
            runCatching { scenario.close() }
        }
    }
}
