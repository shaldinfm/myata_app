package com.example.musicplayerapp

import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.musicplayerapp.data.Streams
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

/**
 * The Mini Player's visibility and interaction contract, against the real service.
 *
 * The session half of this cannot be faked and is not: nothing here writes
 * `hasPlaybackSession`. The test starts a stream through `switchStream`, the
 * service loads its media item, the player's timeline changes, and the pill
 * reacts to that - which is the same chain a user's tap goes through. A media
 * item is set before the stream is ever reached over the network, so this holds
 * on an image that cannot reach the stream host as well as on one that can.
 *
 * The *screen* half is set directly through `currentFragmentLiveData` rather than
 * by navigating. Reaching HOME for real means clearing the splash, which waits on
 * a network load and does not complete on an offline image - it would make the
 * test a network test. The screen rule itself is covered exhaustively by
 * `MiniPlayerVisibilityTest`, and by real navigation in
 * `tools/qa/phone/capture-mini-player.mjs`, which confirms on device that PLAYER
 * shows no pill.
 *
 * Methods are ordered, because step 1 is the only one that can assert a clean
 * launch and it stops being able to the moment another step starts a stream.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class MiniPlayerContractTest {

    private lateinit var scenario: ActivityScenario<MainActivity>

    /* ------------------------------------------------ 1-8, the walk itself -- */

    @Test
    fun t1_theWholeVisibilityContract() {
        launch()
        try {
            /* 1. clean launch: no session, so no pill - on HOME included. */
            onScreen("main")
            if (read { it.viewModel.hasPlaybackSession.value == true }) {
                fail(
                    "the device already had a playback session before this test started; " +
                        "the clean-launch step cannot be asserted. Force-stop the app and re-run."
                )
            }
            assertHidden("clean launch, nothing started yet")

            /* 2. the user starts a stream: the pill appears and reflects it. */
            runOnUi { it.viewModel.switchStream(Streams.MYATA) }
            awaitSession("the service to load a stream")
            assertShown("a stream has been started")
            assertEquals(
                "the pill follows the stream that was started",
                Streams.MYATA,
                read { it.viewModel.currentStreamLive.value },
            )

            /* 3. pause: still the user's stream, so still up. */
            runOnUi { it.viewModel.togglePlayPause() }
            settle()
            assertShown("paused - a paused stream is still a session")
            assertTrue(
                "pausing must not clear the session",
                read { it.viewModel.hasPlaybackSession.value == true },
            )

            /* 4. resume: unchanged. */
            runOnUi { it.viewModel.togglePlayPause() }
            settle()
            assertShown("resumed")

            /* 5. navigation across the three screens preserves pill and stream. */
            for (screen in listOf("favorites", "info", "main")) {
                onScreen(screen)
                assertShown("after navigating to \"$screen\"")
            }
            assertEquals(
                "the selected stream survives navigation",
                Streams.MYATA,
                read { it.viewModel.currentStreamLive.value },
            )

            /* 6. PLAYER hides it. */
            onScreen("player")
            assertHidden("on PLAYER")

            /* 7. leaving PLAYER brings it back. */
            onScreen("main")
            assertShown("back on HOME from PLAYER")
        } finally {
            close()
        }

        /* 8. a new UI over a service that still has the session.
         *
         * The scenario above is closed, so its ViewModel and its MediaController
         * are gone: this launch builds both from nothing, exactly as a process
         * recreation would, and the pill has to come back from the service's own
         * state. Nothing was persisted for it to read. */
        launch()
        try {
            onScreen("main")
            awaitSession("the reconnected controller to report the live session")
            assertShown("a rebuilt UI over a service that still holds the session")
        } finally {
            runOnUi { if (it.viewModel.isPlaying.value == true) it.viewModel.togglePlayPause() }
            close()
        }
    }

    /* --------------------------------------------------------- interaction -- */

    @Test
    fun t2_theBodyOpensPlayerAndPlayPauseDoesNot() {
        launch()
        try {
            runOnUi { it.viewModel.switchStream(Streams.MYATA) }
            awaitSession("a session, so the pill is on screen to be tapped")
            onScreen("main")
            assertShown("before tapping")

            /* The play/pause button: playback only, never navigation.
             *
             * A real touch through the pill, not performClick() on the button -
             * performClick calls the button's own listener and would prove nothing
             * about whether the parent's also fires. This dispatches at the
             * button's centre and lets the hierarchy route it. */
            val before = destination()
            runOnUi { activity ->
                val pill = activity.findViewById<View>(R.id.mini_player)
                val button = activity.findViewById<View>(R.id.mini_player_play_pause)
                tap(pill, centreOf(button, pill))
            }
            settle()
            assertEquals(
                "tapping play/pause navigated; it must only control playback",
                before,
                destination(),
            )
            assertShown("after tapping play/pause")

            /* Connecting: the control declines the tap and still does not
             * navigate.
             *
             * Worth its own step because the obvious way to stop a control
             * firing - clearing `clickable` - would have made this worse rather
             * than better here: a view that is not clickable does not consume its
             * touch, so the tap would travel up to the pill, whose listener opens
             * PLAYER. A control that is disabled while a stream connects must be
             * inert, not a shortcut. */
            runOnUi { it.viewModel.isBuffering.value = true }
            settle()
            val beforeConnecting = destination()
            runOnUi { activity ->
                val pill = activity.findViewById<View>(R.id.mini_player)
                val button = activity.findViewById<View>(R.id.mini_player_play_pause)
                tap(pill, centreOf(button, pill))
            }
            settle()
            assertEquals(
                "tapping the control while connecting opened PLAYER",
                beforeConnecting,
                destination(),
            )
            runOnUi { it.viewModel.isBuffering.value = false }
            settle()

            /* The body: opens PLAYER. */
            runOnUi { it.findViewById<View>(R.id.mini_player).performClick() }
            settle()
            assertEquals(
                "tapping the pill body did not open PLAYER",
                R.id.player,
                destination(),
            )

            /* The artwork and the metadata are inside that target, not separate
             * ones: neither has a listener of its own, so a tap on either is a tap
             * on the body. */
            for (id in listOf(R.id.mini_player_artwork, R.id.mini_player_title, R.id.mini_player_artist)) {
                assertFalse(
                    "${nameOf(id)} has its own click listener and would not open PLAYER",
                    read { it.findViewById<View>(id).hasOnClickListeners() },
                )
            }
        } finally {
            runOnUi { if (it.viewModel.isPlaying.value == true) it.viewModel.togglePlayPause() }
            close()
        }
    }

    /* ---------------------------------------------------------------- infra -- */

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    private fun close() {
        // See TypographyWidthSweepTest: close() reports a teardown timeout by
        // throwing an Error on the software-rendered API 24 image, long after the
        // checks are done.
        try { scenario.close() } catch (e: Throwable) {
            android.util.Log.w("MINIQA", "activity close timed out; checks already complete", e)
        }
    }

    private fun runOnUi(block: (MainActivity) -> Unit) = scenario.onActivity(block)

    private fun <T> read(block: (MainActivity) -> T): T {
        var out: T? = null
        scenario.onActivity { out = block(it) }
        @Suppress("UNCHECKED_CAST")
        return out as T
    }

    /** Lets posted work - a click, a LiveData delivery, a service round trip - run. */
    private fun settle() {
        Thread.sleep(1_200)
        scenario.onActivity { }
    }

    private fun onScreen(screen: String) {
        runOnUi { it.viewModel.currentFragmentLiveData.value = screen }
        scenario.onActivity { }
    }

    private fun awaitSession(what: String) {
        val deadline = SystemClock.uptimeMillis() + 30_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (read { it.viewModel.hasPlaybackSession.value == true }) return
            Thread.sleep(250)
        }
        fail("timed out on API ${Build.VERSION.SDK_INT} waiting for $what")
    }

    private fun visibility(): Int = read { it.findViewById<View>(R.id.mini_player).visibility }

    private fun assertShown(why: String) =
        assertEquals("the pill should be visible: $why", View.VISIBLE, visibility())

    private fun assertHidden(why: String) =
        assertEquals("the pill should be hidden: $why", View.GONE, visibility())

    private fun destination(): Int = read {
        val host = it.supportFragmentManager.findFragmentById(R.id.navHostFragment) as NavHostFragment
        host.navController.currentDestination?.id ?: 0
    }

    /** [child]'s centre in [ancestor]'s coordinates. */
    private fun centreOf(child: View, ancestor: View): Pair<Float, Float> {
        var x = child.width / 2f
        var y = child.height / 2f
        var v: View = child
        while (v !== ancestor) {
            x += v.left
            y += v.top
            v = v.parent as View
        }
        return x to y
    }

    private fun tap(target: View, at: Pair<Float, Float>) {
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, at.first, at.second, 0)
        val up = MotionEvent.obtain(t, t + 40, MotionEvent.ACTION_UP, at.first, at.second, 0)
        target.dispatchTouchEvent(down)
        target.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    private fun nameOf(id: Int): String = read { it.resources.getResourceEntryName(id) }
}
