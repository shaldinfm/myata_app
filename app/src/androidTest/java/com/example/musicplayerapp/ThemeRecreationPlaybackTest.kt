package com.example.musicplayerapp

import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.media3.session.MediaController
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.example.musicplayerapp.data.ThemeStore
import com.example.musicplayerapp.ui.settings.ThemeMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ten appearance changes in a row, against the playback connection.
 *
 * ## What this tests, and what it deliberately does not
 *
 * A theme change recreates `MainActivity` - `uiMode` is not in its `configChanges`
 * - and before G1 the only thing that did that was the listener flipping the
 * system's own dark mode. G1 turns it into a one-tap operation over a live
 * `MediaController` binding, which is exactly the shape of the leak PR #47 fixed on
 * API 24: each finished UI leaving one more connected controller and one more
 * listener on the same session.
 *
 * So the property asserted is the one that owns playback across a recreation:
 *
 *  - the retained `StreamsViewModel` keeps **the same** controller across all ten,
 *    never releasing it (a released controller is a dropped session) and never
 *    connecting a second one beside it (that is the accumulation);
 *  - the controller is still connected at the end;
 *  - the session service is never left with strays.
 *
 * **No stream is started here, and that is on purpose** - the same decision
 * `MediaControllerLifecycleTest` records. Starting one reaches a real network host
 * from a test suite whose whole network boundary is replaced, and leaves a live
 * session behind for whatever runs next; `MiniPlayerContractTest` opens by
 * asserting there is none. The audible half of "playback survives" - that the
 * stream does not stutter or stop across ten toggles - is a manual QA step against
 * `adb logcat | grep MyataPlayback`, and is recorded in
 * `docs/SETTINGS-APPEARANCE-3.6.6.md` rather than faked here.
 */
@RunWith(AndroidJUnit4::class)
class ThemeRecreationPlaybackTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Before
    fun grantAndReset() {
        if (Build.VERSION.SDK_INT >= 33) {
            ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(
                    "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS"
                ),
            ).use { it.readBytes() }
        }
        ThemeStore.clearForTest(context)
    }

    @After
    fun tidy() = ThemeStore.clearForTest(context)

    @Test
    fun ten_appearance_changes_keep_one_connected_controller() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val seen = mutableSetOf<MediaController>()

        try {
            val first = awaitConnectedController()
            seen += first

            openAppearance()

            repeat(TOGGLES) { i ->
                // Alternate, so every round is a real change rather than a no-op:
                // choosing the current mode is deliberately inert.
                val next = if (i % 2 == 0) ThemeMode.DARK else ThemeMode.LIGHT
                tap(rowFor(next))

                await("appearance $next to be applied, round ${i + 1}") {
                    ThemeStore.read(context) == next &&
                        it.destination() == R.id.settings_appearance
                }

                val controller = awaitConnectedController()
                seen += controller

                assertSame(
                    "round ${i + 1} of ${TOGGLES}: the appearance change built a " +
                        "second MediaController. The ViewModel is retained across a " +
                        "recreation and must keep the one it already had, or playback " +
                        "is handed to a connection nothing is holding.",
                    first,
                    controller,
                )
                assertTrue(
                    "round ${i + 1} of ${TOGGLES}: the controller was released by an " +
                        "appearance change. The UI is coming straight back and " +
                        "playback has to keep running across it.",
                    onMainThread { first.isConnected },
                )
            }

            assertEquals(
                "an appearance change should never produce a new controller; " +
                    "${seen.size} distinct ones were seen across $TOGGLES changes",
                1,
                seen.size,
            )
            assertTrue(
                "the controller is not connected after $TOGGLES appearance changes",
                onMainThread { first.isConnected },
            )
        } finally {
            close(scenario)
        }

        // And the last one goes when the UI really does finish, exactly as it did
        // before - ten recreations must not have turned a release into a leak.
        assertFalse(
            "a controller survived the activity being finished after $TOGGLES " +
                "appearance changes",
            onMainThread { seen.any { it.isConnected } },
        )
    }

    /**
     * The appearance screen itself survives ten recreations.
     *
     * The back stack is what puts the listener back where they were, and it is
     * restored by Navigation rather than by anything G1 wrote - so this is a check
     * that nothing about the recreation is losing it, not a claim about new code.
     * Back still returns to settings and then to HOME after all of them.
     */
    @Test
    fun the_back_stack_survives_ten_appearance_changes() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            openAppearance()

            repeat(TOGGLES) { i ->
                val next = if (i % 2 == 0) ThemeMode.DARK else ThemeMode.LIGHT
                tap(rowFor(next))
                await("appearance $next, round ${i + 1}") {
                    ThemeStore.read(context) == next &&
                        it.destination() == R.id.settings_appearance
                }
            }

            tap(R.id.appearance_back)
            await("settings") { it.destination() == R.id.settings }

            tap(R.id.settings_back)
            await("HOME") { it.destination() == R.id.home }
        } finally {
            close(scenario)
        }
    }

    // ==================== helpers ====================

    private fun rowFor(mode: ThemeMode) = when (mode) {
        ThemeMode.SYSTEM -> R.id.appearance_row_system
        ThemeMode.LIGHT -> R.id.appearance_row_light
        ThemeMode.DARK -> R.id.appearance_row_dark
    }

    private fun openAppearance() {
        openSettingsAndSettle()
        tap(R.id.settings_row_theme)
        await("the appearance screen") { it.destination() == R.id.settings_appearance }
    }

    private fun awaitConnectedController(): MediaController {
        val deadline = SystemClock.uptimeMillis() + CONNECT_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            // Both reads have to tolerate there being no resumed activity: this is
            // called immediately after a theme change, and for the length of the
            // recreation the old activity is gone and the new one has not resumed.
            val controller = runCatching { onMain { it.viewModel.controllerForTest } }.getOrNull()
            if (controller != null && onMainThread { controller.isConnected }) return controller
            Thread.sleep(100)
        }
        fail("timed out on API ${Build.VERSION.SDK_INT} waiting for a connected controller")
        error("unreachable")
    }

    private fun tap(id: Int) {
        onMain { it.findViewById<android.view.View>(id).performClick() }
        instrumentation.runOnMainSync { }
    }

    private fun MainActivity.destination(): Int? {
        val host = supportFragmentManager.findFragmentById(R.id.navHostFragment)
            as? androidx.navigation.fragment.NavHostFragment
        return host?.navController?.currentDestination?.id
    }

    private fun await(what: String, timeoutMs: Long = 20_000, check: (MainActivity) -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            var ok = false
            runCatching { instrumentation.runOnMainSync { ok = check(resumed()) } }
            if (ok) return
            Thread.sleep(25)
        }
        error("timed out after ${timeoutMs}ms waiting for $what")
    }

    /**
     * `runOnMainSync` and the lifecycle monitor, not `ActivityScenario.onActivity`.
     *
     * `onActivity` waits for an idle looper, and a recreating activity on the
     * software-rendered API 24 image may never hand one out - see `AuthFormTest`.
     * The object is also looked up fresh every call, because a theme change
     * replaces it and holding one across a toggle is holding a destroyed activity.
     */
    private fun <T> onMain(block: (MainActivity) -> T): T = onMainThread { block(resumed()) }

    /**
     * The main thread, with no activity involved.
     *
     * `MediaController.isConnected` is only reliable from the thread that releases
     * it, but it is a property of the controller rather than of any activity - and
     * asking for a resumed activity to read it is what made this fail mid-recreation,
     * when for a moment there is not one.
     */
    private fun <T> onMainThread(block: () -> T): T {
        var out: T? = null
        instrumentation.runOnMainSync { out = block() }
        @Suppress("UNCHECKED_CAST")
        return out as T
    }

    private fun resumed(): MainActivity =
        ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(Stage.RESUMED)
            .filterIsInstance<MainActivity>()
            .firstOrNull() ?: error("no resumed MainActivity")

    private fun close(scenario: ActivityScenario<MainActivity>) {
        // See MiniPlayerContractTest: close() reports a teardown timeout by throwing
        // on the software-rendered API 24 image, long after the Activity is finished.
        try {
            scenario.close()
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "activity close timed out; the ViewModel is cleared", e)
        }
    }

    private companion object {
        const val TOGGLES = 10
        const val CONNECT_TIMEOUT_MS = 20_000L
        const val TAG = "THEMEQA"
    }
}
