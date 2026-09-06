package com.example.musicplayerapp.ui

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.PopupWindow
import android.widget.TextView
import com.example.musicplayerapp.R
import com.example.musicplayerapp.ui.sleeptimer.SleepTimerState
import com.example.musicplayerapp.ui.sleeptimer.SleepTimerText

/**
 * The frozen `Menu / Плеер`, as far as it currently exists.
 *
 * A [PopupWindow] over an inflated layout rather than a platform `PopupMenu`: the
 * frozen menu is a 260-wide r20 `menuSurface` card whose row carries a trailing
 * value in `primary`, and a platform menu can draw none of that. The COLLECTION
 * overflow's own note already records the platform menu there as "deliberate and
 * temporary"; this one starts at the frozen surface instead of arriving at it.
 *
 * ## What the trailing value is, and when it is computed
 *
 * `sleep-timer-menu-active` puts the remaining time on the row - `24 мин`, in
 * `primary`. It is computed once, here, from the deadline at the moment the menu
 * opens. A popup is a transient surface measured in seconds and a minute-grained
 * value cannot go stale inside one, so there is no ticker: the number is right
 * when it is drawn, and the menu is gone long before it could stop being.
 */
class PlayerOverflowMenu(
    private val onSleepTimer: () -> Unit,
) {

    private var window: PopupWindow? = null

    /**
     * Opens the menu under [anchor], showing [timer]'s remaining time when there is
     * one.
     *
     * Anchored to the trailing header control and pulled to its end, so the menu
     * hangs off the same edge the control sits on rather than off the screen.
     */
    fun show(anchor: View, timer: SleepTimerState.Armed?) {
        dismiss()

        val ctx = anchor.context
        val content = LayoutInflater.from(ctx).inflate(R.layout.menu_player_overflow, null, false)

        content.findViewById<TextView>(R.id.player_overflow_sleep_timer_trailing).apply {
            if (timer == null) {
                visibility = View.GONE
            } else {
                text = SleepTimerText.remaining(ctx, timer, android.os.SystemClock.elapsedRealtime())
                visibility = View.VISIBLE
            }
        }

        val popup = PopupWindow(
            content,
            ctx.resources.getDimensionPixelSize(R.dimen.player_overflow_menu_width),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            true,
        ).apply {
            // The menu draws its own r20 surface and its own 1px outline, so the
            // window behind it must not add a second background: an opaque default
            // would square off the corners the frozen frame rounds.
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            elevation = ctx.resources.getDimension(R.dimen.player_overflow_elevation)
        }

        content.findViewById<View>(R.id.player_overflow_sleep_timer).setOnClickListener {
            popup.dismiss()
            onSleepTimer()
        }

        window = popup
        popup.showAsDropDown(anchor, 0, 0, Gravity.END)
    }

    fun dismiss() {
        window?.dismiss()
        window = null
    }
}
