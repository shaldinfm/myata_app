package com.example.musicplayerapp.ui.sleeptimer

import android.content.Context
import com.example.musicplayerapp.R
import java.util.Calendar
import java.util.Locale

/**
 * The one place a remaining time becomes words.
 *
 * The Player menu's trailing value, the sheet's trailing value, the sheet
 * subtitle and the Settings row all come through here, so «24 мин» cannot mean
 * one thing on one surface and something else on another - which is the whole
 * point of two entry points sharing one timer.
 *
 * The units are abbreviations - `ч`, `мин` - for the reason
 * `profile_account_sync_*` gives: `1 мин` and `5 мин` are both correct Russian, so
 * no plural agreement is needed and no quantity strings are involved.
 */
object SleepTimerText {

    /** `24 мин`, `1 ч 24 мин`, `12 ч`. Rounded up - see [SleepTimerDuration.remainingMinutes]. */
    fun duration(context: Context, minutes: Int): String {
        val (h, m) = SleepTimerDuration.split(minutes)
        return when {
            h == 0 -> context.getString(R.string.sleep_timer_minutes, m)
            m == 0 -> context.getString(R.string.sleep_timer_hours, h)
            else -> context.getString(R.string.sleep_timer_hours_minutes, h, m)
        }
    }

    /** What is left on an armed timer, as the menu and the sheet draw it. */
    fun remaining(context: Context, timer: SleepTimerState.Armed, nowElapsedMs: Long): String =
        duration(context, SleepTimerDuration.remainingMinutes(timer.remainingMs(nowElapsedMs)))

    /**
     * The time of day the stream will stop, **derived rather than stored**.
     *
     * Wall clock now, plus the monotonic time that is left. A clock change, a
     * timezone move or a DST step therefore changes this string - correctly, it is
     * a statement about the listener's clock - while the deadline it is describing
     * has not moved at all.
     */
    fun endTime(context: Context, timer: SleepTimerState.Armed, nowElapsedMs: Long): String =
        endTimeIn(timer.remainingMs(nowElapsedMs))

    /** The same rendering for a duration that has not been committed yet - the custom preview. */
    fun endTimeIn(remainingMs: Long): String {
        val at = Calendar.getInstance().apply {
            timeInMillis = System.currentTimeMillis() + remainingMs
        }
        return String.format(
            Locale.ROOT, "%02d:%02d",
            at.get(Calendar.HOUR_OF_DAY), at.get(Calendar.MINUTE),
        )
    }
}
