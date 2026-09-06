package com.example.musicplayerapp

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayerapp.data.BootIdentity
import com.example.musicplayerapp.data.SleepTimerStore
import com.example.musicplayerapp.data.ThemeStore
import com.example.musicplayerapp.service.MediaPlayerService
import com.example.musicplayerapp.service.SleepTimerContract
import com.example.musicplayerapp.ui.settings.ThemeMode
import com.example.musicplayerapp.ui.sleeptimer.SleepTimerState
import com.example.musicplayerapp.utils.ServiceUtils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The owner, exercised as the owner: through the service, not around it.
 *
 * Every assertion here goes through `MediaPlayerService` - arming, replacing,
 * cancelling, undoing, reconciling and expiring - because that is where the
 * decisions are, and a test that reached into the store directly would prove
 * nothing about the object that actually holds the deadline.
 *
 * ## Sub-minute deadlines, without a sub-minute duration
 *
 * The shortest duration a listener can choose is one minute, and a test suite that
 * waited a minute per expiry case would be unusable. So the expiry cases plant a
 * record on disk with a deadline a second or two out and then ask the service to
 * reconcile: the service adopts it exactly as it adopts a timer that survived a
 * process death, schedules it, and fires it. That is the same code path a real
 * expiry takes - it is the *restore* path that a killed process uses - so nothing
 * is being faked except how long the test has to wait.
 *
 * ## What is not covered here
 *
 * Expiry that actually stops audio needs the live stream, and pinning a network
 * radio into a test is how a suite becomes flaky. What is pinned instead is that
 * expiry with nothing playing issues no playback command and raises no completion
 * (owner decision D5), and that the code path taken when something *is* playing is
 * the app's existing pause - `onPlaybackNoLongerWanted` then `stop()` without
 * clearing the playlist - which `PlaybackLog` records as `PLAYER_STOP source=sleep_timer`.
 * The stop-and-resume behaviour itself is the pause behaviour that already shipped.
 */
@RunWith(AndroidJUnit4::class)
class SleepTimerServiceTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val boot: Int get() = BootIdentity.read(context) ?: 0

    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun setUp() {
        SleepTimerStore.clearForTest(context)
        // The app has to be in the foreground for a plain startService to be
        // accepted on API 26+, which is the same condition the real callers meet:
        // a sheet the listener is looking at.
        scenario = ActivityScenario.launch(MainActivity::class.java)
        drainServiceQueue()
    }

    /**
     * Leaves the service holding no timer, and - the part that matters - leaves it
     * having *finished* saying so.
     *
     * A bare cancel is not enough. Commands reach the service as intents, so they
     * are processed after the call that sent them returns, and a setUp that only
     * cleared the store and sent a cancel would let that cancel land *after* the
     * test had planted its own record and wipe it. The first version of this file
     * did exactly that, and five tests failed with a record that had been on disk a
     * moment earlier.
     *
     * So: arm something, wait until it appears, cancel it, wait until it goes. The
     * second wait cannot complete until the service has drained past the cancel,
     * which is the ordering guarantee the rest of the file needs.
     */
    private fun drainServiceQueue() {
        command(SleepTimerContract.ACTION_SET, minutes = 1)
        awaitArmed()
        command(SleepTimerContract.ACTION_CANCEL)
        awaitOff()
    }

    @After
    fun tearDown() {
        command(SleepTimerContract.ACTION_CANCEL)
        SleepTimerStore.clearForTest(context)
        if (this::scenario.isInitialized) scenario.close()
    }

    // ============================ arming ============================

    @Test
    fun arming_writes_a_deadline_and_reaches_the_ui() {
        val before = SystemClock.elapsedRealtime()
        command(SleepTimerContract.ACTION_SET, minutes = 15)

        val timer = awaitArmed()
        assertEquals(15, timer.durationMinutes)
        assertFalse(timer.isCustom)
        assertNear(before + 15 * 60_000L, timer.deadlineElapsedMs)

        // The same object, on the surface that draws it. There is one state.
        assertEquals(timer, viewModelTimer())
    }

    @Test
    fun a_custom_duration_is_recorded_as_one() {
        command(SleepTimerContract.ACTION_SET, minutes = 90, custom = true)
        val timer = awaitArmed()
        assertEquals(90, timer.durationMinutes)
        assertTrue(timer.isCustom)
    }

    @Test
    fun a_duration_outside_the_bounds_is_refused_rather_than_clamped() {
        command(SleepTimerContract.ACTION_SET, minutes = 0)
        awaitOff()
        assertFalse(SleepTimerStore.hasRecordForTest(context))

        command(SleepTimerContract.ACTION_SET, minutes = 721)
        awaitOff()
        assertFalse(
            "12 h is the ceiling; a longer request must be dropped, not silently shortened",
            SleepTimerStore.hasRecordForTest(context),
        )
    }

    @Test
    fun a_second_choice_replaces_the_first() {
        command(SleepTimerContract.ACTION_SET, minutes = 15)
        val first = awaitArmed()

        val before = SystemClock.elapsedRealtime()
        command(SleepTimerContract.ACTION_SET, minutes = 45)
        val second = awaitArmed { it.durationMinutes == 45 }

        assertEquals(45, second.durationMinutes)
        assertNear(before + 45 * 60_000L, second.deadlineElapsedMs)
        assertTrue(
            "a replacement must advance the generation so the old callback is stale",
            second.generation > first.generation,
        )
        // Exactly one record, not two.
        assertEquals(second, restored())
    }

    // ============================ cancel and undo ============================

    @Test
    fun cancel_clears_the_record() {
        command(SleepTimerContract.ACTION_SET, minutes = 30)
        awaitArmed()

        command(SleepTimerContract.ACTION_CANCEL)
        awaitOff()

        assertFalse(SleepTimerStore.hasRecordForTest(context))
        assertEquals(SleepTimerState.Off, viewModelTimer())
        assertTrue("Вернуть must be on offer straight after a cancel", viewModelCanUndo())
    }

    @Test
    fun undo_restores_the_original_deadline_rather_than_the_original_duration() {
        // D4, exactly. A 1-minute timer is cancelled a moment in; undo must give
        // back the ~59 seconds that were left, not a fresh minute.
        command(SleepTimerContract.ACTION_SET, minutes = 1)
        val original = awaitArmed()

        Thread.sleep(1_200L)
        command(SleepTimerContract.ACTION_CANCEL)
        awaitOff()

        command(SleepTimerContract.ACTION_UNDO)
        val restored = awaitArmed()

        assertEquals(
            "the deadline that comes back must be the identical instant",
            original.deadlineElapsedMs, restored.deadlineElapsedMs,
        )
        assertEquals(1, restored.durationMinutes)
        assertTrue(
            "undo must not restart the duration: less than a minute was left",
            restored.remainingMs(SystemClock.elapsedRealtime()) < 59_000L,
        )
        assertTrue(
            "the restored timer is a new arming of the same deadline",
            restored.generation > original.generation,
        )
    }

    @Test
    fun undo_never_extends_a_deadline_that_has_already_passed() {
        // Planted rather than chosen, so the wait is two seconds instead of sixty.
        plant(deadlineIn = 2_000L, minutes = 1)
        command(SleepTimerContract.ACTION_SYNC)
        val armed = awaitArmed()

        command(SleepTimerContract.ACTION_CANCEL)
        awaitOff()
        assertTrue(viewModelCanUndo())

        // Sit past the deadline the snapshot is holding.
        Thread.sleep(2_500L)
        assertTrue(armed.hasExpired(SystemClock.elapsedRealtime()))

        command(SleepTimerContract.ACTION_UNDO)
        awaitOff()

        assertFalse(
            "a timer that would already have fired must not come back at all",
            SleepTimerStore.hasRecordForTest(context),
        )
        assertEquals(SleepTimerState.Off, viewModelTimer())
    }

    @Test
    fun undo_with_nothing_to_restore_does_nothing() {
        command(SleepTimerContract.ACTION_UNDO)
        awaitOff()
        assertFalse(SleepTimerStore.hasRecordForTest(context))
    }

    @Test
    fun arming_again_discards_what_undo_would_have_restored() {
        command(SleepTimerContract.ACTION_SET, minutes = 30)
        awaitArmed()
        command(SleepTimerContract.ACTION_CANCEL)
        awaitOff()
        assertTrue(viewModelCanUndo())

        command(SleepTimerContract.ACTION_SET, minutes = 15)
        awaitArmed { it.durationMinutes == 15 }
        assertFalse(
            "a new choice replaces the cancelled one; Вернуть cannot resurrect it",
            viewModelCanUndo(),
        )
    }

    // ============================ reconciliation ============================

    @Test
    fun an_already_expired_record_is_cleared_when_the_state_is_read() {
        plant(deadlineIn = -5_000L, minutes = 30)
        assertTrue(SleepTimerStore.hasRecordForTest(context))

        command(SleepTimerContract.ACTION_SYNC)
        awaitOff()

        assertFalse(
            "a delayed handler must not be able to leave an expired timer visibly armed",
            SleepTimerStore.hasRecordForTest(context),
        )
        assertEquals(SleepTimerState.Off, viewModelTimer())
    }

    @Test
    fun a_record_from_another_boot_is_discarded_when_the_state_is_read() {
        SleepTimerStore.writeRawForTest(
            context,
            deadlineElapsedMs = SystemClock.elapsedRealtime() + 600_000L,
            bootId = boot - 1,
            durationMinutes = 30,
        )
        command(SleepTimerContract.ACTION_SYNC)
        awaitOff()
        assertFalse(
            "the timer never resumes after a reboot",
            SleepTimerStore.hasRecordForTest(context),
        )
    }

    @Test
    fun a_live_record_is_adopted_and_scheduled() {
        plant(deadlineIn = 600_000L, minutes = 30)
        command(SleepTimerContract.ACTION_SYNC)
        val timer = awaitArmed()
        assertEquals(30, timer.durationMinutes)
        assertNear(SystemClock.elapsedRealtime() + 600_000L, timer.deadlineElapsedMs, tolerance = 3_000L)
    }

    // ============================ expiry ============================

    @Test
    fun expiry_with_nothing_playing_clears_the_timer_and_announces_nothing() {
        plant(deadlineIn = 1_000L, minutes = 1)
        command(SleepTimerContract.ACTION_SYNC)
        awaitArmed()

        awaitOff(timeoutMs = 8_000L)

        assertFalse(SleepTimerStore.hasRecordForTest(context))
        assertEquals(SleepTimerState.Off, viewModelTimer())
        assertFalse(
            "D5: nothing was playing, so there is nothing to report",
            viewModelCompleted(),
        )
    }

    @Test
    fun a_replaced_timer_cannot_still_fire() {
        // The duplicate-expiry defence. The first deadline is a second away and its
        // Runnable is already posted; replacing it must make that Runnable a no-op.
        plant(deadlineIn = 1_500L, minutes = 1)
        command(SleepTimerContract.ACTION_SYNC)
        awaitArmed()

        command(SleepTimerContract.ACTION_SET, minutes = 30)
        val replacement = awaitArmed { it.durationMinutes == 30 }

        Thread.sleep(3_000L)

        val after = restored()
        assertNotNull("the replacement was cancelled by the timer it replaced", after)
        assertEquals(
            "the stale callback fired and took the new timer with it",
            replacement.deadlineElapsedMs, after!!.deadlineElapsedMs,
        )
        assertEquals(30, after.durationMinutes)
    }

    @Test
    fun a_cancelled_timer_cannot_still_fire() {
        plant(deadlineIn = 1_500L, minutes = 1)
        command(SleepTimerContract.ACTION_SYNC)
        awaitArmed()

        command(SleepTimerContract.ACTION_CANCEL)
        awaitOff()

        command(SleepTimerContract.ACTION_SET, minutes = 30)
        awaitArmed { it.durationMinutes == 30 }

        Thread.sleep(3_000L)
        assertEquals(
            "the cancelled timer's callback fired anyway",
            30, restored()?.durationMinutes,
        )
    }

    // ============================ survival ============================

    @Test
    fun an_explicit_stop_leaves_the_timer_armed_and_the_service_recreation_re_adopts_it() {
        // D5 and the service-recreation case in one, because the app's `stop`
        // action is what destroys the service: the record must outlive it, and the
        // next thing that reaches the service must find the same deadline.
        command(SleepTimerContract.ACTION_SET, minutes = 30)
        val armed = awaitArmed()

        context.startService(
            Intent(context, MediaPlayerService::class.java).putExtra("ACTION", "stop")
        )
        Thread.sleep(1_500L)

        assertTrue(
            "an explicit stop must not cancel the timer",
            SleepTimerStore.hasRecordForTest(context),
        )

        command(SleepTimerContract.ACTION_SYNC)
        val after = awaitArmed()
        assertEquals(
            "a recreated service must re-adopt the deadline it left behind",
            armed.deadlineElapsedMs, after.deadlineElapsedMs,
        )
        assertEquals(30, after.durationMinutes)
    }

    @Test
    fun activity_recreation_does_not_disturb_the_timer() {
        command(SleepTimerContract.ACTION_SET, minutes = 30)
        val before = awaitArmed()

        recreateActivity()

        command(SleepTimerContract.ACTION_SYNC)
        val after = awaitArmed()
        assertEquals(before.deadlineElapsedMs, after.deadlineElapsedMs)
        assertEquals(before.deadlineElapsedMs, viewModelTimerArmed().deadlineElapsedMs)
    }

    @Test
    fun a_theme_change_does_not_disturb_the_timer() {
        val original = ThemeStore.read(context)
        try {
            command(SleepTimerContract.ACTION_SET, minutes = 30)
            val before = awaitArmed()

            // What the appearance screen does: write, then let the delegate
            // recreate the Activity. The timer is in neither of those.
            ThemeStore.write(context, ThemeMode.DARK)
            recreateActivity()

            command(SleepTimerContract.ACTION_SYNC)
            assertEquals(before.deadlineElapsedMs, awaitArmed().deadlineElapsedMs)
        } finally {
            ThemeStore.write(context, original)
        }
    }

    @Test
    fun backgrounding_the_app_does_not_disturb_the_timer() {
        command(SleepTimerContract.ACTION_SET, minutes = 30)
        val before = awaitArmed()

        scenario.onActivity { it.moveTaskToBack(true) }
        Thread.sleep(1_500L)

        assertTrue(SleepTimerStore.hasRecordForTest(context))
        assertEquals(before.deadlineElapsedMs, restored()?.deadlineElapsedMs)
    }

    // ============================ no side effects ============================

    @Test
    fun the_timer_touches_no_identity_or_sync_state() {
        val identityBefore = prefsSnapshot("myata_identity")
        val syncBefore = prefsSnapshot("myata_last_sync")

        command(SleepTimerContract.ACTION_SET, minutes = 30)
        awaitArmed()
        command(SleepTimerContract.ACTION_CANCEL)
        awaitOff()

        assertEquals(identityBefore, prefsSnapshot("myata_identity"))
        assertEquals(syncBefore, prefsSnapshot("myata_last_sync"))
    }

    // ============================ helpers ============================

    private fun command(action: String, minutes: Int = 0, custom: Boolean = false) {
        ServiceUtils.sendSleepTimerCommand(context, action, minutes, custom)
    }

    /** Plants a record the service has not seen, the way a dead process leaves one. */
    private fun plant(deadlineIn: Long, minutes: Int) {
        SleepTimerStore.writeRawForTest(
            context,
            deadlineElapsedMs = SystemClock.elapsedRealtime() + deadlineIn,
            bootId = boot,
            durationMinutes = minutes,
        )
    }

    private fun restored(): SleepTimerState.Armed? =
        (SleepTimerStore.restore(context, boot, SystemClock.elapsedRealtime())
            as? SleepTimerStore.Restored.Armed)?.timer

    private fun awaitArmed(
        timeoutMs: Long = 6_000L,
        predicate: (SleepTimerState.Armed) -> Boolean = { true },
    ): SleepTimerState.Armed {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            restored()?.takeIf(predicate)?.let { return it }
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("no armed timer matching the predicate within ${timeoutMs}ms")
    }

    private fun awaitOff(timeoutMs: Long = 6_000L) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (!SleepTimerStore.hasRecordForTest(context)) return
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("a record was still on disk after ${timeoutMs}ms")
    }

    /**
     * Reads a LiveData off the Activity, on the main thread.
     *
     * `ActivityScenario.onActivity` already posts its block to the main thread and
     * blocks the caller until it has run, so it must be called *from the test
     * thread*: wrapping it in `runOnMainSync` deadlocks, because the block would be
     * waiting for the thread it is already on.
     */
    /**
     * Destroys the Activity and brings a new one up.
     *
     * `ActivityScenario.recreate()` would be the closer analogue of a configuration
     * change, but it waits for the replacement to reach RESUMED and gives up if the
     * old one is still PAUSED - which it can be for reasons that have nothing to do
     * with this feature, and did: the emulator's screen timeout paused the Activity
     * partway through the suite.
     *
     * Closing and relaunching asserts strictly more than recreate() does anyway. A
     * configuration change keeps the ViewModel; a close does not, so a timer that
     * survives this has survived the ViewModel being cleared as well as the window
     * being rebuilt - and that is the case the feature actually has to withstand.
     */
    private fun recreateActivity() {
        scenario.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        instrumentation.waitForIdleSync()
    }

    private fun <T> onActivity(read: (MainActivity) -> T): T {
        // Give the broadcast a moment to land; it is posted, not delivered inline.
        Thread.sleep(300L)
        var value: T? = null
        scenario.onActivity { value = read(it) }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun viewModelTimer(): SleepTimerState? = onActivity { it.viewModel.sleepTimer.value }

    private fun viewModelTimerArmed(): SleepTimerState.Armed {
        val v = viewModelTimer()
        assertTrue("the ViewModel does not have the timer: $v", v is SleepTimerState.Armed)
        return v as SleepTimerState.Armed
    }

    private fun viewModelCanUndo(): Boolean =
        onActivity { it.viewModel.sleepTimerCanUndo.value == true }

    private fun viewModelCompleted(): Boolean =
        onActivity { it.viewModel.sleepTimerCompleted.value == true }

    private fun prefsSnapshot(name: String): Map<String, Any?> =
        context.getSharedPreferences(name, Context.MODE_PRIVATE).all

    private fun assertNear(expected: Long, actual: Long, tolerance: Long = 2_000L) {
        assertTrue(
            "expected about $expected but was $actual (${actual - expected}ms out)",
            Math.abs(actual - expected) <= tolerance,
        )
    }

    private companion object {
        const val POLL_MS = 100L
    }
}
