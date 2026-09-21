package com.example.musicplayerapp

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayerapp.data.BootIdentity
import com.example.musicplayerapp.data.PlaybackIntentStore
import com.example.musicplayerapp.data.SleepTimerStore
import com.example.musicplayerapp.data.Streams
import com.example.musicplayerapp.service.MediaPlayerService
import com.example.musicplayerapp.service.PlaybackIntentContract
import com.example.musicplayerapp.service.SleepTimerContract
import com.example.musicplayerapp.utils.ServiceUtils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Assume.assumeNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The restart path, through the real service.
 *
 * ## Why the restart is simulated rather than staged
 *
 * A process death cannot be staged from a test that lives in the process being
 * killed: `am kill` takes the instrumentation with it. `START_STICKY` then brings
 * the service back with a **null intent**, and no test can send one of those
 * either. So `PlaybackIntentContract.ACTION_RESTORE` runs exactly the method that
 * null intent runs, on a service that really did just come up, and a release
 * build refuses it. Nothing else about the path is faked.
 *
 * ## Why nothing here plays
 *
 * Starting a stream from a suite reaches a real network host and leaves a live
 * session behind for whatever runs next - the decision `ThemeRecreationPlaybackTest`
 * and `MediaControllerLifecycleTest` already record, and `MiniPlayerContractTest`
 * opens by asserting there is no session. So what is pinned here is the half that
 * needs no audio, and it is the half that is dangerous: the cases where a restart
 * must **not** make a sound. A restart that resurrects audio the listener stopped
 * is a worse bug than the silence this mechanism exists to fix.
 *
 * The resume cases - MYATA, GOLD and XTRA coming back by themselves - are pinned
 * as decisions in `PlaybackIntentPolicyTest`, and audibly in the manual
 * kill-the-process pass recorded with this change.
 *
 * ## Mobile and TV
 *
 * There is one `MediaPlayerService` and one store; TV and mobile differ only in
 * which Activity is in front. This file is run on both, which is what makes "TV
 * behaviour is unchanged" an assertion rather than a hope.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackIntentServiceTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    private lateinit var scenario: ActivityScenario<MainActivity>

    /**
     * This test's own connection, not the one the UI holds.
     *
     * Borrowing the ViewModel's controller ties the file to whichever Activity
     * happens to be in front, which is not the same Activity on TV. Building one
     * here is device-agnostic, and it is released in [tidy] - an unreleased
     * controller keeps the session service bound for everything that runs next.
     */
    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null

    @Before
    fun setUp() {
        if (Build.VERSION.SDK_INT >= 33) {
            ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(
                    "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS"
                ),
            ).use { it.readBytes() }
        }
        // A plain startService is only accepted on API 26+ while the app is in
        // front, which is the same condition every real caller meets.
        scenario = ActivityScenario.launch(MainActivity::class.java)
        awaitController()

        // The player belongs to a service shared with every other class in the
        // suite, and it does not arrive empty. Run on its own this class saw an
        // idle player and passed; run after the mini-player classes it saw the
        // media item they had left loaded, and four tests failed on a precondition
        // none of them owns. So establish the baseline rather than assume it -
        // through the app's own `stop`, which is the real path that clears the
        // playlist, not a reach into the service.
        awaitStoppedAndEmpty("setUp baseline")

        // AFTER the stop, never before: `stop` goes through
        // onPlaybackNoLongerWanted, which writes wantsPlayback=false. Clearing
        // first would leave that write behind and hand the first test a record it
        // did not plant.
        PlaybackIntentStore.clearForTest(context)
        SleepTimerStore.clearForTest(context)
        drainService()
    }

    /**
     * Leaves the shared service the way this class would like to have found it.
     *
     * The stop comes first and while the controller is still connected, because
     * both are needed to see it take effect. Whatever ran before this class left
     * a loaded player behind; there is no reason for this class to do the same to
     * whatever runs after it.
     */
    @After
    fun tidy() {
        command(SleepTimerContract.ACTION_CANCEL)
        runCatching { awaitStoppedAndEmpty("tearDown") }
        releaseController()
        PlaybackIntentStore.clearForTest(context)
        SleepTimerStore.clearForTest(context)
        if (this::scenario.isInitialized) scenario.close()
    }

    // ==================== nothing to restore ====================

    @Test
    fun a_restart_with_no_record_starts_nothing() {
        restartService()

        assertSilent("a service with no durable record started audio by itself")
        assertEquals(PlaybackIntentStore.Stored.None, PlaybackIntentStore.read(context))
    }

    @Test
    fun a_restart_with_an_unusable_stream_key_fails_safely() {
        // What a framework intent action looks like once it has been mistaken for
        // a stream key and written down (issue #14). It cannot be acted on, and
        // guessing a station for it would mean a radio starting by itself in
        // somebody's pocket on a station they never chose.
        PlaybackIntentStore.writeRawForTest(context, "android.intent.action.PLAY", wantsPlayback = true)

        restartService()

        assertSilent("an unusable stream key was guessed at instead of discarded")
        assertNull(
            "the unusable record must be removed, not left to be retried",
            PlaybackIntentStore.rawStreamForTest(context),
        )
    }

    // ==================== user intent wins ====================

    @Test
    fun an_explicit_pause_clears_the_intent_and_survives_a_restart() {
        PlaybackIntentStore.writeRawForTest(context, Streams.GOLD, wantsPlayback = true)

        // Through the session, which is where the notification, the media buttons
        // and the in-app control all arrive: the app has no true pause, so this is
        // ForwardingPlayer.pause() taking its stop-as-pause path.
        val controller = controller()
        onMainThread { controller.pause() }

        val stored = awaitStored("a pause that reaches the durable record") {
            it is PlaybackIntentStore.Stored.Known && !it.wantsPlayback
        }
        assertEquals(
            "a pause must not take the station with it",
            PlaybackIntentStore.Stored.Known(Streams.GOLD, false),
            stored,
        )

        restartService()
        assertSilent("a restart resurrected audio after an explicit pause")
    }

    @Test
    fun an_explicit_stop_clears_the_intent_and_survives_a_restart() {
        PlaybackIntentStore.writeRawForTest(context, Streams.MYATA, wantsPlayback = true)

        command("stop")

        val stored = awaitStored("a stop that reaches the durable record") {
            it is PlaybackIntentStore.Stored.Known && !it.wantsPlayback
        }
        assertEquals(
            PlaybackIntentStore.Stored.Known(Streams.MYATA, false),
            stored,
        )

        restartService()
        assertSilent("a restart resurrected audio after an explicit stop")
    }

    @Test
    fun a_sleep_timer_expiry_clears_the_intent_and_survives_a_restart() {
        // BootIdentity is what a planted timer record is judged against; a device
        // that will not say which boot it is on discards every such record, and
        // the whole sleep-timer suite is already unusable there.
        assumeNotNull(BootIdentity.read(context))

        PlaybackIntentStore.writeRawForTest(context, Streams.XTRA, wantsPlayback = true)
        // A deadline that has already gone by, adopted through the same restore
        // path a killed process takes. See SleepTimerServiceTest for why expiry is
        // reached this way rather than by waiting out a one-minute timer.
        SleepTimerStore.writeRawForTest(
            context,
            deadlineElapsedMs = SystemClock.elapsedRealtime() - 1_000L,
            bootId = BootIdentity.read(context)!!,
        )

        command(SleepTimerContract.ACTION_SYNC)

        val stored = awaitStored("a sleep-timer expiry that reaches the durable record") {
            it is PlaybackIntentStore.Stored.Known && !it.wantsPlayback
        }
        assertEquals(
            "the timer reached zero, so audio is not wanted - whether or not " +
                "there was any playing at the time",
            PlaybackIntentStore.Stored.Known(Streams.XTRA, false),
            stored,
        )

        restartService()
        assertSilent("a restart resurrected audio the sleep timer had stopped")
    }

    // ==================== the station is kept ====================

    @Test
    fun a_stopped_station_is_still_remembered_after_a_restart() {
        // The half of a pause that is *not* thrown away. Without it the
        // notification Play after a restart has nothing to prepare and is silent,
        // which is the second half of this bug.
        PlaybackIntentStore.writeRawForTest(context, Streams.GOLD, wantsPlayback = false)

        restartService()

        assertSilent("an adopted station must not start playing")
        assertEquals(
            PlaybackIntentStore.Stored.Known(Streams.GOLD, false),
            PlaybackIntentStore.read(context),
        )
    }

    // ============================ plumbing ============================

    /**
     * Stops whatever the shared service was doing and waits until the player is
     * genuinely idle and empty.
     *
     * The `stop` action is the app's own: it clears `userWantsPlayback`, stops the
     * player and clears the playlist. Asserting the result rather than trusting
     * the command is the point - the intent is handled after the call that sent it
     * returns, so the state has to be observed through the session.
     */
    private fun awaitStoppedAndEmpty(what: String) {
        command("stop")
        val deadline = SystemClock.uptimeMillis() + COMMAND_TIMEOUT_MS
        var count = -1
        var playing = true
        while (SystemClock.uptimeMillis() < deadline) {
            val controller = controller()
            count = onMainThread { controller.mediaItemCount }
            playing = onMainThread { controller.isPlaying }
            if (count == 0 && !playing) return
            Thread.sleep(50)
        }
        fail(
            "timed out on API ${Build.VERSION.SDK_INT} waiting for a stopped, empty " +
                "player at $what - mediaItemCount=$count isPlaying=$playing"
        )
    }

    /** Runs the null-intent restore, then waits until the service has finished it. */
    private fun restartService() {
        command(PlaybackIntentContract.ACTION_RESTORE)
        drainService()
    }

    /**
     * Blocks until the service has drained past everything sent before this.
     *
     * Commands are recorded in the app's durable inbox and run by the service's next
     * start command, so they are handled after the call that sent them returns.
     * `sleep_timer_sync` always answers with exactly one broadcast, and the service
     * runs the commands it drained in order - so an answer to this one is proof that
     * the command before it has been handled too.
     */
    private fun drainService() {
        val latch = CountDownLatch(1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = latch.countDown()
        }
        val manager = LocalBroadcastManager.getInstance(context)
        instrumentation.runOnMainSync {
            manager.registerReceiver(receiver, IntentFilter(SleepTimerContract.BROADCAST_STATE))
        }
        try {
            command(SleepTimerContract.ACTION_SYNC)
            if (!latch.await(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                fail("the service did not answer a sleep_timer_sync within ${COMMAND_TIMEOUT_MS}ms")
            }
        } finally {
            instrumentation.runOnMainSync { manager.unregisterReceiver(receiver) }
        }
    }

    /**
     * Sends one app-private command the way every screen sends one: the command goes
     * into the process-memory channel and the start intent carries nothing. The shape
     * this replaced - `ACTION` extras on a start intent for the exported component -
     * is asserted inert by `PlaybackCommandBoundaryTest`.
     */
    private fun command(action: String) {
        ServiceUtils.sendUiCommand(context, action)
    }

    /**
     * Nothing loaded and nothing playing, and it stays that way.
     *
     * A single read the instant after the restore would pass even if the service
     * were about to prepare a stream, so this watches for a while instead. The
     * player is the honest witness here: a restore that decided to play sets a
     * media item before it prepares, so an empty timeline means no attempt was
     * made rather than one that failed.
     */
    private fun assertSilent(message: String) {
        val deadline = SystemClock.uptimeMillis() + QUIET_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val controller = controller()
            val count = onMainThread { controller.mediaItemCount }
            val playing = onMainThread { controller.isPlaying }
            assertEquals("$message (a media item was loaded)", 0, count)
            assertFalse("$message (playback started)", playing)
            Thread.sleep(100)
        }
    }

    private fun awaitStored(
        what: String,
        check: (PlaybackIntentStore.Stored) -> Boolean,
    ): PlaybackIntentStore.Stored {
        val deadline = SystemClock.uptimeMillis() + COMMAND_TIMEOUT_MS
        var last: PlaybackIntentStore.Stored = PlaybackIntentStore.read(context)
        while (SystemClock.uptimeMillis() < deadline) {
            last = PlaybackIntentStore.read(context)
            if (check(last)) return last
            Thread.sleep(50)
        }
        fail("timed out waiting for $what; the record says $last")
        error("unreachable")
    }

    private fun controller(): MediaController = awaitController()

    private fun awaitController(): MediaController {
        controllerFuture?.let { existing ->
            val ready = runCatching { existing.get(0, TimeUnit.MILLISECONDS) }.getOrNull()
            if (ready != null && onMainThread { ready.isConnected }) return ready
        }
        // Media3 builds a controller on the application thread and answers on it.
        val token = SessionToken(context, ComponentName(context, MediaPlayerService::class.java))
        val future = onMainThread { MediaController.Builder(context, token).buildAsync() }
        controllerFuture = future
        return runCatching { future.get(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS) }.getOrElse {
            fail(
                "timed out on API ${Build.VERSION.SDK_INT} connecting a MediaController " +
                    "to MediaPlayerService: ${it.javaClass.simpleName}"
            )
            error("unreachable")
        }
    }

    private fun releaseController() {
        val future = controllerFuture ?: return
        controllerFuture = null
        instrumentation.runOnMainSync { MediaController.releaseFuture(future) }
    }

    private fun <T> onMainThread(block: () -> T): T {
        var out: T? = null
        instrumentation.runOnMainSync { out = block() }
        @Suppress("UNCHECKED_CAST")
        return out as T
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 20_000L
        const val COMMAND_TIMEOUT_MS = 10_000L

        /** Long enough for a prepare to have shown itself as a loaded media item. */
        const val QUIET_MS = 2_000L
    }
}
