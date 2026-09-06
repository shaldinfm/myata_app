package com.example.musicplayerapp.ui.sleeptimer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rounding rule and the bounds, pinned.
 *
 * Both are owner decisions rather than anything the frozen frames determine - the
 * mocks show `24 мин` against a deadline nobody can reconstruct - so they are held
 * here rather than left to whatever the arithmetic happened to do.
 */
class SleepTimerDurationTest {

    private val minute = SleepTimerDuration.MS_PER_MINUTE

    // ---- D2: remaining minutes round UP ----

    @Test
    fun `a timer just set for thirty minutes reads thirty`() {
        // The exact case the decision names: floor would answer 29 while the
        // listener's finger was still on the row they tapped.
        assertEquals(30, SleepTimerDuration.remainingMinutes(30 * minute))
        assertEquals(30, SleepTimerDuration.remainingMinutes(30 * minute - 1))
    }

    @Test
    fun `a part minute counts as a whole one`() {
        assertEquals(1, SleepTimerDuration.remainingMinutes(1))
        assertEquals(1, SleepTimerDuration.remainingMinutes(minute))
        assertEquals(2, SleepTimerDuration.remainingMinutes(minute + 1))
        assertEquals(25, SleepTimerDuration.remainingMinutes(24 * minute + 1))
    }

    @Test
    fun `an exact minute is that minute and not the next one`() {
        assertEquals(24, SleepTimerDuration.remainingMinutes(24 * minute))
        assertEquals(60, SleepTimerDuration.remainingMinutes(60 * minute))
    }

    @Test
    fun `an expired timer has no minutes left, and cannot have fewer than none`() {
        assertEquals(0, SleepTimerDuration.remainingMinutes(0))
        assertEquals(0, SleepTimerDuration.remainingMinutes(-1))
        assertEquals(0, SleepTimerDuration.remainingMinutes(-90 * minute))
    }

    // ---- D1: 1 minute to 12 hours ----

    @Test
    fun `zero is the only invalid input below the range`() {
        assertFalse(SleepTimerDuration.isValid(0))
        assertFalse(SleepTimerDuration.isValid(-5))
        assertTrue(SleepTimerDuration.isValid(1))
    }

    @Test
    fun `twelve hours is the ceiling and is itself allowed`() {
        assertEquals(720, SleepTimerDuration.MAX_MINUTES)
        assertTrue(SleepTimerDuration.isValid(720))
        assertFalse(SleepTimerDuration.isValid(721))
    }

    @Test
    fun `every preset is inside the range`() {
        assertEquals(listOf(15, 30, 45, 60), SleepTimerDuration.PRESETS)
        SleepTimerDuration.PRESETS.forEach { assertTrue("$it", SleepTimerDuration.isValid(it)) }
    }

    @Test
    fun `clamp brings anything back inside the bounds`() {
        assertEquals(1, SleepTimerDuration.clamp(0))
        assertEquals(720, SleepTimerDuration.clamp(10_000))
        assertEquals(45, SleepTimerDuration.clamp(45))
    }

    // ---- the custom picker's default ----

    @Test
    fun `the custom picker opens on one hour thirty, as the frozen frame draws it`() {
        assertEquals(90, SleepTimerDuration.CUSTOM_DEFAULT_MINUTES)
        assertEquals(1 to 30, SleepTimerDuration.split(SleepTimerDuration.CUSTOM_DEFAULT_MINUTES))
    }

    @Test
    fun `the custom default is not one of the presets`() {
        // The point of the value, not an incidental property of it: 60 is a preset
        // one tap away, so a picker that opened on 60 would be a second route to a
        // choice the listener has just declined to make with one tap.
        assertFalse(
            "the custom picker must not open on a duration the presets already offer",
            SleepTimerDuration.PRESETS.contains(SleepTimerDuration.CUSTOM_DEFAULT_MINUTES),
        )
        assertTrue(SleepTimerDuration.isValid(SleepTimerDuration.CUSTOM_DEFAULT_MINUTES))
    }

    // ---- splitting, which is what the hours/minutes wording reads from ----

    @Test
    fun `split separates hours from the remainder`() {
        assertEquals(0 to 24, SleepTimerDuration.split(24))
        assertEquals(1 to 0, SleepTimerDuration.split(60))
        assertEquals(1 to 24, SleepTimerDuration.split(84))
        assertEquals(12 to 0, SleepTimerDuration.split(720))
    }

    // ---- the state's own arithmetic ----

    @Test
    fun `remaining is measured against the deadline and never goes negative`() {
        val timer = SleepTimerState.Armed(
            deadlineElapsedMs = 100_000L, durationMinutes = 30, isCustom = false, generation = 1L,
        )
        assertEquals(40_000L, timer.remainingMs(60_000L))
        assertEquals(0L, timer.remainingMs(100_000L))
        assertEquals(0L, timer.remainingMs(500_000L))
    }

    @Test
    fun `the deadline instant itself counts as expired`() {
        val timer = SleepTimerState.Armed(
            deadlineElapsedMs = 100_000L, durationMinutes = 30, isCustom = false, generation = 1L,
        )
        assertFalse(timer.hasExpired(99_999L))
        assertTrue(timer.hasExpired(100_000L))
        assertTrue(timer.hasExpired(100_001L))
    }
}
