package com.example.musicplayerapp

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.musicplayerapp.data.BootIdentity
import com.example.musicplayerapp.data.SleepTimerStore
import com.example.musicplayerapp.ui.sleeptimer.SleepTimerState
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
 * The durable record: what is on disk, and what survives what.
 *
 * Two things are being held here, and they are the two the owner called out as
 * mandatory corrections.
 *
 * **The persistence schema.** A wall-clock deadline is not stored, and the test
 * that says so reads the file's keys rather than trusting a comment. If somebody
 * later adds `deadline_wall_ms` "for the subtitle", this fails - which is the
 * point, because the subtitle is derived at render time precisely so that moving
 * the clock cannot move the expiry.
 *
 * **The reboot detector.** `elapsedRealtime` climbs after a reboot exactly as it
 * climbs during a boot, so `now < armedAt` is not a detector: it only fires inside
 * the narrow window before the new uptime overtakes the old one. The regression
 * test below is that exact case - a foreign-boot record whose deadline is
 * numerically *ahead* of the current elapsedRealtime, which a naive comparison
 * would happily restore.
 */
@RunWith(AndroidJUnit4::class)
class SleepTimerStoreTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val boot: Int get() = BootIdentity.read(context) ?: -12345

    @Before
    fun clean() = SleepTimerStore.clearForTest(context)

    @After
    fun cleanUp() = SleepTimerStore.clearForTest(context)

    // ================= the platform boot counter =================

    @Test
    fun the_platform_answers_with_a_boot_count() {
        // Settings.Global.BOOT_COUNT is API 24 and minSdk is 24, so the detector
        // this app depends on has to be readable on the oldest device it runs on.
        // If this ever fails on a real device the store degrades safely - an
        // unknown boot is treated as a foreign one - but the timer would then stop
        // surviving process death, so it is worth knowing.
        assertNotNull(
            "Settings.Global.BOOT_COUNT is unreadable on API ${android.os.Build.VERSION.SDK_INT}",
            BootIdentity.read(context),
        )
    }

    @Test
    fun an_unknown_boot_never_matches_anything() {
        assertFalse(BootIdentity.matches(BootIdentity.UNKNOWN, 7))
        assertFalse(BootIdentity.matches(7, null))
        assertFalse(BootIdentity.matches(BootIdentity.UNKNOWN, null))
        assertTrue(BootIdentity.matches(7, 7))
        assertFalse(BootIdentity.matches(7, 8))
    }

    @Test
    fun the_boot_count_does_not_change_while_the_device_is_up() {
        assertEquals(BootIdentity.read(context), BootIdentity.read(context))
    }

    // ================= the schema =================

    @Test
    fun the_record_holds_no_wall_clock_deadline() {
        val timer = armed(SystemClock.elapsedRealtime() + 30 * 60_000L)
        SleepTimerStore.write(context, timer, boot)

        val keys = context
            .getSharedPreferences("myata_sleep_timer", Context.MODE_PRIVATE)
            .all.keys

        assertEquals(
            "the persisted schema is fixed: a wall-clock deadline is derived, never stored",
            setOf(
                "deadline_elapsed_ms",
                "boot_id",
                "duration_minutes",
                "is_custom",
                "generation",
            ),
            keys,
        )
    }

    @Test
    fun a_record_survives_a_write_and_comes_back_unchanged() {
        val deadline = SystemClock.elapsedRealtime() + 45 * 60_000L
        SleepTimerStore.write(context, armed(deadline, minutes = 45, custom = true, generation = 9L), boot)

        val restored = SleepTimerStore.restore(context, boot, SystemClock.elapsedRealtime())
        assertTrue("$restored", restored is SleepTimerStore.Restored.Armed)
        val timer = (restored as SleepTimerStore.Restored.Armed).timer
        assertEquals(deadline, timer.deadlineElapsedMs)
        assertEquals(45, timer.durationMinutes)
        assertTrue(timer.isCustom)
        assertEquals(9L, timer.generation)
    }

    @Test
    fun an_absent_record_is_none_rather_than_an_empty_timer() {
        assertEquals(
            SleepTimerStore.Restored.None,
            SleepTimerStore.restore(context, boot, SystemClock.elapsedRealtime()),
        )
        assertEquals(SleepTimerState.Off, SleepTimerStore.peek(context, SystemClock.elapsedRealtime()))
        assertFalse(SleepTimerStore.hasRecordForTest(context))
    }

    // ================= reboot detection =================

    @Test
    fun a_record_from_another_boot_is_foreign_even_though_its_deadline_is_in_the_future() {
        // THE REGRESSION CASE.
        //
        // Previous boot: the device had been up for 20 minutes and a 30-minute
        // timer was armed, so its deadline was elapsedRealtime 3_000_000.
        // This boot: the device has been up for 40 minutes - elapsedRealtime
        // 2_400_000 - and the deadline is 10 minutes "away".
        //
        // Every arithmetic check passes. now > armedAt, now < deadline, the
        // remaining time is a sensible positive number. Only the boot counter
        // knows this record belongs to a device that has since restarted.
        val previousBootDeadline = 3_000_000L
        val nowThisBoot = 2_400_000L
        assertTrue("the test's own premise", nowThisBoot < previousBootDeadline)

        SleepTimerStore.writeRawForTest(
            context,
            deadlineElapsedMs = previousBootDeadline,
            bootId = boot - 1,
            durationMinutes = 30,
        )

        assertEquals(
            "a foreign-boot record must be discarded however plausible its arithmetic looks",
            SleepTimerStore.Restored.ForeignBoot,
            SleepTimerStore.restore(context, boot, nowThisBoot),
        )
        assertEquals(SleepTimerState.Off, SleepTimerStore.peek(context, nowThisBoot))
    }

    @Test
    fun a_record_that_cannot_prove_its_boot_is_treated_as_foreign() {
        SleepTimerStore.writeRawForTest(
            context,
            deadlineElapsedMs = SystemClock.elapsedRealtime() + 600_000L,
            bootId = BootIdentity.UNKNOWN,
        )
        assertEquals(
            SleepTimerStore.Restored.ForeignBoot,
            SleepTimerStore.restore(context, boot, SystemClock.elapsedRealtime()),
        )
    }

    @Test
    fun a_record_from_this_boot_is_restorable() {
        SleepTimerStore.writeRawForTest(
            context,
            deadlineElapsedMs = SystemClock.elapsedRealtime() + 600_000L,
            bootId = boot,
        )
        assertTrue(
            SleepTimerStore.restore(context, boot, SystemClock.elapsedRealtime())
                is SleepTimerStore.Restored.Armed
        )
    }

    // ================= expiry is a state of the record, not an absence =================

    @Test
    fun a_passed_deadline_is_expired_rather_than_armed_or_absent() {
        SleepTimerStore.writeRawForTest(
            context,
            deadlineElapsedMs = SystemClock.elapsedRealtime() - 1_000L,
            bootId = boot,
        )
        val restored = SleepTimerStore.restore(context, boot, SystemClock.elapsedRealtime())
        assertTrue(
            "the owner must be able to tell an expired record from no record at all: " +
                "one needs reconciling and the other does not",
            restored is SleepTimerStore.Restored.Expired,
        )
        // The UI sees it as off, so nothing can be drawn as armed that will not fire.
        assertEquals(SleepTimerState.Off, SleepTimerStore.peek(context, SystemClock.elapsedRealtime()))
    }

    @Test
    fun clearing_leaves_nothing_behind() {
        SleepTimerStore.write(context, armed(SystemClock.elapsedRealtime() + 60_000L), boot)
        assertTrue(SleepTimerStore.hasRecordForTest(context))
        SleepTimerStore.clear(context)
        assertFalse(SleepTimerStore.hasRecordForTest(context))
        assertEquals(
            SleepTimerStore.Restored.None,
            SleepTimerStore.restore(context, boot, SystemClock.elapsedRealtime()),
        )
    }

    @Test
    fun the_last_generation_survives_a_clear_of_the_object_but_not_of_the_file() {
        SleepTimerStore.write(context, armed(SystemClock.elapsedRealtime() + 60_000L, generation = 41L), boot)
        assertEquals(41L, SleepTimerStore.lastGeneration(context))
        SleepTimerStore.clear(context)
        assertEquals(0L, SleepTimerStore.lastGeneration(context))
    }

    // ================= the timer store is nobody's account =================

    @Test
    fun the_timer_lives_in_its_own_file() {
        SleepTimerStore.write(context, armed(SystemClock.elapsedRealtime() + 60_000L), boot)
        // Not in the appearance file, not in an identity file. An appearance and a
        // sleep timer both belong to the device; neither belongs to a session.
        assertNull(
            context.getSharedPreferences("myata_appearance", Context.MODE_PRIVATE)
                .getString("deadline_elapsed_ms", null)
        )
        assertTrue(SleepTimerStore.hasRecordForTest(context))
    }

    private fun armed(
        deadline: Long,
        minutes: Int = 30,
        custom: Boolean = false,
        generation: Long = 1L,
    ) = SleepTimerState.Armed(deadline, minutes, custom, generation)
}
