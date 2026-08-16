package com.example.musicplayerapp

import android.os.Build
import android.os.SystemClock
import androidx.media3.session.MediaController
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

/**
 * What `StreamsViewModel` does with its `MediaController` across UI lifecycles.
 *
 * A controller is a binder connection to the session service, so "did the UI let
 * go of it?" is not a question about references: it is asked of the controller
 * object itself, whose `isConnected` goes false only when something released it.
 * The test therefore keeps every controller the app ever built during the run and
 * checks them all, which is exactly the accumulation the leak produced - each
 * finished UI used to leave one more connected controller, and one more listener,
 * on the same session.
 *
 * Nothing here starts a stream. The leak is in the connect/release path and shows
 * up without playback, and a test that started one would leave a live session
 * behind for whatever runs next - `MiniPlayerContractTest` opens by asserting
 * there is none.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class MediaControllerLifecycleTest {

    /**
     * A configuration change keeps the ViewModel, so it must keep its one
     * controller: not release it, and not connect a second one beside it.
     */
    @Test
    fun t1_aConfigurationChangeKeepsTheSameControllerConnected() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val before = awaitConnectedController(scenario)

            scenario.recreate()

            val after = awaitConnectedController(scenario)
            assertSame(
                "a configuration change built a second controller; the retained " +
                    "ViewModel must keep the one it already had",
                before,
                after,
            )
            assertTrue(
                "the controller was released on a configuration change; the UI is " +
                    "coming straight back and playback has to keep running",
                onMain { before.isConnected },
            )
        } finally {
            close(scenario)
        }
    }

    /**
     * Finishing the Activity clears the ViewModel, and that has to hand the
     * connection back. Three rounds, because one release could be luck and the
     * failure being guarded against is accumulation.
     */
    @Test
    fun t2_finishingTheActivityReleasesTheControllerEveryTime() {
        val released = mutableListOf<MediaController>()

        repeat(ROUNDS) { round ->
            val scenario = ActivityScenario.launch(MainActivity::class.java)
            val controller = try {
                val connected = awaitConnectedController(scenario)

                // Nothing from an earlier round may still be attached to the
                // session while this one is live.
                val stragglers = onMain { released.count { it.isConnected } }
                if (stragglers > 0) {
                    fail(
                        "$stragglers controller(s) from an earlier UI are still " +
                            "connected in round ${round + 1}: connections are " +
                            "accumulating instead of being released",
                    )
                }
                connected
            } catch (t: Throwable) {
                close(scenario)
                throw t
            }

            close(scenario)

            assertFalse(
                "the controller of round ${round + 1} is still connected after its " +
                    "Activity was finished; StreamsViewModel.onCleared did not " +
                    "release it",
                onMain { controller.isConnected },
            )
            released += controller
        }

        // Each round also had to connect after the previous one was released, so
        // reaching here means releasing a controller never cost the next UI its
        // session - the service and its session outlived all $ROUNDS of them.
        assertFalse(
            "a released controller reported itself connected again",
            onMain { released.any { it.isConnected } },
        )
    }

    /* ---------------------------------------------------------------- infra -- */

    private fun awaitConnectedController(scenario: ActivityScenario<MainActivity>): MediaController {
        val deadline = SystemClock.uptimeMillis() + CONNECT_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val controller = read(scenario) { it.viewModel.controllerForTest }
            if (controller != null && onMain { controller.isConnected }) return controller
            Thread.sleep(250)
        }
        fail("timed out on API ${Build.VERSION.SDK_INT} waiting for the controller to connect")
        error("unreachable")
    }

    private fun <T> read(scenario: ActivityScenario<MainActivity>, block: (MainActivity) -> T): T {
        var out: T? = null
        scenario.onActivity { out = block(it) }
        @Suppress("UNCHECKED_CAST")
        return out as T
    }

    /**
     * Controller state is read on the application thread, which is the thread that
     * releases it - the answer is only reliable from there.
     */
    private fun <T> onMain(block: () -> T): T {
        var out: T? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { out = block() }
        @Suppress("UNCHECKED_CAST")
        return out as T
    }

    private fun close(scenario: ActivityScenario<MainActivity>) {
        // See MiniPlayerContractTest: close() reports a teardown timeout by
        // throwing on the software-rendered API 24 image, long after the Activity
        // is finished and the ViewModel cleared.
        try {
            scenario.close()
        } catch (e: Throwable) {
            android.util.Log.w("CONTROLLERQA", "activity close timed out; the ViewModel is cleared", e)
        }
    }

    private companion object {
        const val ROUNDS = 3
        const val CONNECT_TIMEOUT_MS = 20_000L
    }
}
