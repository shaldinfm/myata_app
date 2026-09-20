package com.example.musicplayerapp

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayerapp.data.PlaybackIntentStore
import com.example.musicplayerapp.data.SleepTimerStore
import com.example.musicplayerapp.data.Streams
import com.example.musicplayerapp.service.MediaPlayerService
import com.example.musicplayerapp.service.PlaybackIntentContract
import com.example.musicplayerapp.service.SleepTimerContract
import com.example.musicplayerapp.service.SystemPlaybackEventContract
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The headphones contract, end to end on a device, without Bluetooth hardware.
 *
 * ## The invariant being pinned
 *
 * ```
 * playing
 *   -> AUDIO_BECOMING_NOISY
 *   -> playback stops immediately
 *   -> audio must not migrate to the phone speaker
 *   -> durable wantsPlayback becomes false
 *   -> a process/service restart must NOT resurrect playback
 *   -> reconnecting the headphones alone must NOT resume it
 *   -> an explicit Play starts it again normally
 * ```
 *
 * ## Why the event is simulated rather than sent
 *
 * `android.media.AUDIO_BECOMING_NOISY` is a **protected broadcast**. Neither the app
 * under test nor `adb shell am broadcast` may send it -
 *
 *     SecurityException: Permission Denial: not allowed to send broadcast
 *       android.media.AUDIO_BECOMING_NOISY from pid=..., uid=2000
 *
 * (verified on the API 24 and the API 36 emulator), and an emulator has no Bluetooth
 * A2DP route to disconnect in the first place. So the event is delivered through
 * `SystemPlaybackEventContract.ACTION_BECOMING_NOISY`, which runs what the system's
 * broadcast runs: the player is silenced the way `AudioBecomingNoisyManager` silences
 * it, and the reason the arrival carries is handed to the service's own listener
 * handler. See that contract for exactly what is and is not faked.
 *
 * ## What is real here, and what is not
 *
 * Everything the app does with the event is the shipped path: the listener branch, the
 * durable record, the end of the recovery episode, the restart and the Play that
 * follows. The framework half - that ExoPlayer's own receiver fires on a real unplug -
 * is ExoPlayer's, was verified on a device when issue #13 was fixed, and is not
 * something this suite can re-prove.
 *
 * The restart cases run on a service that is still holding the previous buffer, which
 * a real process death does not: that is *harder* than the real thing rather than
 * easier, because the restore's own "a loaded player must not be prepared again" guard
 * is in the way. Which decision a false record produces on an empty player is pinned
 * by `PlaybackIntentPolicyTest`.
 *
 * ## No audio through the emulator's speaker
 *
 * Arming an episode means the app's real Play, and the app's Play is a connection to
 * the live stream - there is no seam for that and there should not be one. So the
 * emulator's music stream is muted for the whole class and restored afterwards: what is
 * asserted is player and record *state*, not sound. The tests end by stopping the radio
 * and leaving the shared service empty, which is what the classes that run after this
 * one assume.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackNoisyServiceTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    private lateinit var scenario: ActivityScenario<MainActivity>
    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null

    /** Restored in [tidy]; see the class comment for why the stream is muted at all. */
    private var originalMusicVolume: Int = -1

    @Before
    fun setUp() {
        if (Build.VERSION.SDK_INT >= 33) {
            ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(
                    "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS"
                ),
            ).use { it.readBytes() }
        }
        muteMusicStream()

        // A plain startService is only accepted on API 26+ while the app is in front,
        // which is the same condition every real caller meets.
        scenario = ActivityScenario.launch(MainActivity::class.java)
        awaitController()

        // The player belongs to a service shared with every other class in the suite and
        // it does not arrive empty, so establish the baseline rather than assume it -
        // through the app's own `stop`, which is the real path that clears the playlist.
        awaitStoppedAndEmpty("setUp baseline")

        // AFTER the stop, never before: `stop` goes through
        // onPlaybackNoLongerWanted, which writes wantsPlayback=false, and clearing first
        // would leave that write behind.
        PlaybackIntentStore.clearForTest(context)
        SleepTimerStore.clearForTest(context)
        drainService()
    }

    @After
    fun tidy() {
        runCatching { awaitStoppedAndEmpty("tearDown") }
        releaseController()
        PlaybackIntentStore.clearForTest(context)
        SleepTimerStore.clearForTest(context)
        restoreMusicVolume()
        if (this::scenario.isInitialized) scenario.close()
    }

    // ==================== the invariant ====================

    /**
     * `playing -> noisy -> silent`, and the durable half of it in the same move.
     *
     * The pre-state is asserted rather than assumed: `playWhenReady` is what the player
     * is *asking* to do, it is set the moment Play is pressed, and it does not depend on
     * the stream having connected - so this is a real "there was something to stop"
     * control on a machine that may or may not reach the radio server.
     */
    @Test
    fun a_noisy_event_stops_the_radio_and_clears_the_durable_intent() {
        play(Streams.GOLD)
        awaitPlayWhenReady(expected = true)

        noisyEvent()

        awaitPlayWhenReady(expected = false)
        assertFalse("audio was still running", onPlayer { it.isPlaying })
        assertEquals(
            "the buffer is kept, so one Play press resumes without a re-prepare",
            1,
            onPlayer { it.mediaItemCount },
        )
        assertEquals(
            "the durable intent must be false, or a process death resurrects the radio",
            PlaybackIntentStore.Stored.Known(Streams.GOLD, wantsPlayback = false),
            awaitKnown(Streams.GOLD, wantsPlayback = false),
        )
    }

    /**
     * The process-death half: whatever brings the service back, the record it reads says
     * the listener does not want audio, so no station is prepared and nothing plays.
     * `restartService()` runs exactly the method `START_STICKY`'s null intent runs.
     */
    @Test
    fun a_restart_after_a_noisy_event_makes_no_sound() {
        play(Streams.XTRA)
        awaitPlayWhenReady(expected = true)

        noisyEvent()
        awaitKnown(Streams.XTRA, wantsPlayback = false)

        restartService()

        assertSilent("a restart after the headphones came out asked for playback")
        assertEquals(
            "the station is kept so a later Play still knows which one it means",
            PlaybackIntentStore.Stored.Known(Streams.XTRA, wantsPlayback = false),
            PlaybackIntentStore.read(context),
        )
    }

    /**
     * Nothing in the app brings audio back on its own after the output has gone.
     *
     * This is the half of "reconnecting the headphones must not resume it" that the app
     * owns; the other half is the platform's, since the broadcast clears `playWhenReady`
     * and `AudioBecomingNoisyManager` never auto-resumes by itself.
     */
    @Test
    fun a_noisy_event_does_not_bring_playback_back_by_itself() {
        play(Streams.MYATA)
        awaitPlayWhenReady(expected = true)

        noisyEvent()
        awaitPlayWhenReady(expected = false)

        // Long enough for the recovery machinery's first fast retry (1s, plus jitter) to
        // have fired had the episode not been ended.
        val deadline = SystemClock.uptimeMillis() + QUIET_MS
        while (SystemClock.uptimeMillis() < deadline) {
            assertFalse(
                "playback was requested again after the output went away",
                onPlayer { it.playWhenReady },
            )
            assertFalse("audio came back by itself", onPlayer { it.isPlaying })
            assertFalse(
                "the durable intent came back to true by itself",
                (PlaybackIntentStore.read(context) as? PlaybackIntentStore.Stored.Known)
                    ?.wantsPlayback == true,
            )
            Thread.sleep(50)
        }
    }

    /**
     * The listener's own Play still works, through the normal path: the same action the
     * UI sends, the same prepare, the same durable record. A stop that could not be
     * undone would be worse than the bug it fixes.
     */
    @Test
    fun an_explicit_play_after_a_noisy_pause_still_works() {
        play(Streams.GOLD)
        awaitPlayWhenReady(expected = true)
        noisyEvent()
        awaitPlayWhenReady(expected = false)
        awaitKnown(Streams.GOLD, wantsPlayback = false)

        play(Streams.GOLD)

        assertEquals(
            "Play must write the intent back",
            PlaybackIntentStore.Stored.Known(Streams.GOLD, wantsPlayback = true),
            awaitKnown(Streams.GOLD, wantsPlayback = true),
        )
        awaitPlayWhenReady(expected = true)
        assertEquals(
            "Play must still have a station to play",
            1,
            onPlayer { it.mediaItemCount },
        )
    }

    /**
     * Waiting is not a wake-lock state.
     *
     * The app's own `PARTIAL_WAKE_LOCK` is taken only while audio is really running
     * (`onIsPlayingChanged`), and the point of the slow recovery phase is that nothing is
     * held while it waits. The positive control runs only when the stream actually
     * connected - that part depends on the network, and asserting it would make this a
     * network test - but the negative one always runs: after the output has gone, the app
     * holds nothing.
     */
    @Test
    fun a_silenced_radio_holds_no_wake_lock() {
        play(Streams.MYATA)
        awaitPlayWhenReady(expected = true)

        if (onPlayer { it.isPlaying }) {
            assertTrue(
                "a radio that is really playing must be holding its own wake lock, or " +
                    "the assertion below proves nothing",
                wakeLocksHeld().any { it.contains(WAKELOCK_TAG) },
            )
        }

        noisyEvent()
        awaitPlayWhenReady(expected = false)

        val held = awaitNoWakeLockReleased()
        assertFalse(
            "a silent radio is still holding a wake lock: ${held.joinToString()}",
            held.any { it.contains(WAKELOCK_TAG) || it.contains(context.packageName) },
        )
    }

    // ==================== plumbing ====================

    /** The app's own Play action - the one every UI surface sends. */
    private fun play(stream: String) = command("play", stream = stream)

    /** The debug seam that stands in for the system's protected route-loss broadcast. */
    private fun noisyEvent() = command(SystemPlaybackEventContract.ACTION_BECOMING_NOISY)

    private fun restartService() {
        command(PlaybackIntentContract.ACTION_RESTORE)
        drainService()
    }

    private fun command(action: String, stream: String? = null) {
        context.startService(
            Intent(context, MediaPlayerService::class.java).apply {
                putExtra("ACTION", action)
                stream?.let { putExtra("STREAM", it) }
            }
        )
    }

    /**
     * Blocks until the service has drained past everything sent before this.
     *
     * Commands reach the service as intents, so they are handled after the call that
     * sent them returns. `sleep_timer_sync` always answers with exactly one broadcast,
     * and start commands are handled in order - so an answer to this one is proof that
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
     * Stops whatever the shared service was doing and waits until the player is
     * genuinely idle and empty. `stop` is the app's own action: it clears
     * `userWantsPlayback`, stops the player and clears the playlist.
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

    /**
     * Nothing playing and nothing asking to play, and it stays that way.
     *
     * The media item is deliberately *not* part of this: the route-loss path keeps the
     * buffer so that one Play press resumes without a re-prepare, which is issue #13's
     * design. What must not happen is a prepare or a play.
     */
    private fun assertSilent(message: String) {
        val deadline = SystemClock.uptimeMillis() + QUIET_MS
        while (SystemClock.uptimeMillis() < deadline) {
            assertFalse("$message (playback was requested)", onPlayer { it.playWhenReady })
            assertFalse("$message (playback started)", onPlayer { it.isPlaying })
            Thread.sleep(100)
        }
    }

    private fun awaitPlayWhenReady(expected: Boolean, timeoutMs: Long = COMMAND_TIMEOUT_MS) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var seen = false
        while (SystemClock.uptimeMillis() < deadline) {
            seen = onPlayer { it.playWhenReady }
            if (seen == expected) return
            Thread.sleep(50)
        }
        fail("playWhenReady never became $expected (still $seen) after ${timeoutMs}ms")
    }

    private fun awaitStored(
        what: String,
        check: (PlaybackIntentStore.Stored) -> Boolean = { true },
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

    /**
     * Waits for one exact record.
     *
     * Commands reach the service as intents, so the write a command causes lands after
     * the call that sent it returns; a bare read would be a race and a predicate that
     * accepts anything would pass on the record from the previous step.
     */
    private fun awaitKnown(stream: String, wantsPlayback: Boolean): PlaybackIntentStore.Stored =
        awaitStored("$stream with wantsPlayback=$wantsPlayback") {
            it is PlaybackIntentStore.Stored.Known &&
                it.stream == stream &&
                it.wantsPlayback == wantsPlayback
        }

    // ==================== wake locks ====================

    /** Every wake lock the system is currently holding, by tag. */
    private fun wakeLocksHeld(): List<String> {
        val dump = shell("dumpsys power")
        assertTrue("dumpsys power did not report a wake-lock section", dump.contains("Wake Locks:"))
        return dump.lines()
            .dropWhile { !it.contains("Wake Locks:") }
            .drop(1)
            .takeWhile { it.isNotBlank() }
            .map { it.trim() }
    }

    private fun awaitNoWakeLockReleased(timeoutMs: Long = 5_000L): List<String> {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var held = wakeLocksHeld()
        while (held.isNotEmpty() && SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(100)
            held = wakeLocksHeld()
        }
        return held
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)
        ).use { it.readBytes().toString(Charsets.UTF_8) }

    // ==================== audio output ====================

    private fun muteMusicStream() {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        originalMusicVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
    }

    private fun restoreMusicVolume() {
        if (originalMusicVolume < 0) return
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, originalMusicVolume, 0) }
    }

    // ==================== the session ====================

    private fun controller(): MediaController = awaitController()

    private fun awaitController(): MediaController {
        controllerFuture?.let { existing ->
            val ready = runCatching { existing.get(0, TimeUnit.MILLISECONDS) }.getOrNull()
            if (ready != null && onMainThread { ready.isConnected }) return ready
        }
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

    /**
     * One read of the session player, on the thread that owns the controller.
     *
     * The controller has to be resolved *outside* the main-thread block: connecting one
     * is itself done on the application thread, and `runOnMainSync` cannot be entered
     * from there.
     */
    private fun <T> onPlayer(block: (MediaController) -> T): T {
        val controller = controller()
        return onMainThread { block(controller) }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 20_000L
        const val COMMAND_TIMEOUT_MS = 10_000L

        /** Long enough for a retry or a prepare to have shown itself. */
        const val QUIET_MS = 3_000L

        /** The app's own wake lock, as `onCreate` names it. */
        const val WAKELOCK_TAG = "MyataRadio::PlaybackWakeLock"
    }
}
