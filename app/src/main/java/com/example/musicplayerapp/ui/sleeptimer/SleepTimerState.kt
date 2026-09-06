package com.example.musicplayerapp.ui.sleeptimer

/**
 * What the sleep timer is, as a value.
 *
 * ## The deadline is monotonic, and only monotonic
 *
 * [Armed.deadlineElapsedMs] is a `SystemClock.elapsedRealtime()` instant. Nothing
 * here knows what time of day it is, and nothing here stores one: a wall-clock
 * deadline would move when the listener crosses a timezone, when DST steps, or
 * when NTP nudges the clock, and «остановить через 30 минут» would then stop the
 * stream somewhere other than 30 minutes away.
 *
 * The frozen sheets do show a time of day - «Воспроизведение остановится в 23:47»
 * - but that is *derived at render time* from the wall clock plus whatever
 * monotonic time is left, so it re-reads correctly after a clock change while the
 * expiry moment itself does not move. See `SleepTimerText.endTime`.
 *
 * elapsedRealtime counts across sleep and resets to zero at boot, which is why a
 * deadline is only meaningful alongside the boot it was armed on - see
 * [com.example.musicplayerapp.data.BootIdentity].
 */
sealed class SleepTimerState {

    /** No timer. The absence of a record is this, and so is every record that cannot be honoured. */
    object Off : SleepTimerState()

    data class Armed(
        /** `SystemClock.elapsedRealtime()` at which playback must stop. */
        val deadlineElapsedMs: Long,
        /** What the listener chose, kept so the sheet can tick the row they picked. */
        val durationMinutes: Int,
        /** Chosen through `Своё время` rather than one of the four presets. */
        val isCustom: Boolean,
        /**
         * Which arming this is. Bumped by every arm and every cancel, captured by
         * the scheduled callback, and compared when it fires - which is what makes
         * a replaced or cancelled timer's outstanding `Runnable` a no-op instead of
         * a second expiry.
         */
        val generation: Long,
    ) : SleepTimerState() {

        fun remainingMs(nowElapsedMs: Long): Long =
            (deadlineElapsedMs - nowElapsedMs).coerceAtLeast(0L)

        fun hasExpired(nowElapsedMs: Long): Boolean = nowElapsedMs >= deadlineElapsedMs
    }

    val armedOrNull: Armed? get() = this as? Armed
}

/**
 * The durations the timer accepts, and the arithmetic the display depends on.
 *
 * Pure, so `SleepTimerDurationTest` can hold the rounding rule without a device.
 */
object SleepTimerDuration {

    /** `sleep-timer-select`: 15 / 30 / 45 / 60 минут, in that order, none preselected. */
    val PRESETS = listOf(15, 30, 45, 60)

    /** Owner decision D1. `sleep-timer-custom-invalid`: 0 ч 0 мин is the only invalid input. */
    const val MIN_MINUTES = 1
    const val MAX_MINUTES = 12 * 60

    const val MS_PER_MINUTE = 60_000L

    /**
     * Where `Своё время` opens when there is no custom timer to seed it from:
     * **1 ч 30 мин**, which is what `sleep-timer-custom` 2517:1969 draws.
     *
     * Deliberately not 60. That is already a preset one tap away, so opening the
     * picker on exactly that value would offer a second route to a choice the
     * listener has just declined to make with one tap - the picker exists for the
     * durations the presets do not cover, and its default should be one of them.
     * `SleepTimerDurationTest` holds both halves of that: the value, and the fact
     * that it is not a preset.
     */
    const val CUSTOM_DEFAULT_MINUTES = 90

    fun isValid(minutes: Int): Boolean = minutes in MIN_MINUTES..MAX_MINUTES

    fun clamp(minutes: Int): Int = minutes.coerceIn(MIN_MINUTES, MAX_MINUTES)

    fun toMs(minutes: Int): Long = minutes * MS_PER_MINUTE

    /**
     * Minutes still to run, **rounded up** (owner decision D2).
     *
     * Ceil rather than floor because the first thing a listener sees after
     * choosing `30 минут` is this number, and floor would answer `29 мин` while
     * the choice was still under their finger. Ceil also never claims less time
     * than is actually left, which is the safer direction for a promise about
     * when the sound stops.
     *
     * 0 is reachable and correct: an expired timer has no minutes left. Negative
     * input cannot produce a negative answer.
     */
    fun remainingMinutes(remainingMs: Long): Int {
        if (remainingMs <= 0L) return 0
        return ((remainingMs + MS_PER_MINUTE - 1) / MS_PER_MINUTE).toInt()
    }

    /** `90 -> 1 to 30`, `60 -> 1 to 0`, `24 -> 0 to 24`. */
    fun split(minutes: Int): Pair<Int, Int> = (minutes / 60) to (minutes % 60)
}
