package com.example.musicplayerapp.ui

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.PopupWindow
import android.widget.TextView
import com.example.musicplayerapp.R
import com.example.musicplayerapp.data.report.ReportConfig
import com.example.musicplayerapp.ui.sleeptimer.SleepTimerState
import com.example.musicplayerapp.ui.sleeptimer.SleepTimerText

/**
 * The frozen `Menu / Плеер`: all four rows as of G4b.
 *
 * A [PopupWindow] over an inflated layout rather than a platform `PopupMenu`: the
 * frozen menu is a 260-wide r20 `menuSurface` card whose row carries a trailing
 * value in `primary`, and a platform menu can draw none of that.
 *
 * ## What is computed when the menu opens
 *
 * Two things, both read once, here, at the moment the menu opens - a popup is a
 * transient surface measured in seconds, so neither needs a ticker:
 *
 *  - `sleep-timer-menu-active`'s trailing value, `24 мин` in `primary`, from the
 *    timer's deadline. Minute-grained, so it cannot go stale inside one opening.
 *  - whether `Найти трек` has a track to search for. With the placeholder pair on
 *    screen or no metadata yet there is none, and the row is drawn disabled rather
 *    than removed: it keeps its frozen place, so the menu is the same shape on
 *    every opening, and it cannot be tapped into an empty sheet. The track itself
 *    is re-read when the row is tapped, by the caller, so the sheet always opens
 *    for what is playing at the tap and not at the opening.
 *
 * ## One row can be absent
 *
 * `Сообщить о проблеме` is drawn only when this build has a report endpoint: a
 * form that cannot post is a feature that does not exist, and a row opening it
 * would be the dead control the whole rollout rule exists to prevent. See
 * [ReportConfig]. Its gap goes with it, so three rows are 172 and four are 224 -
 * both sums of the same row dimens, never a height of their own.
 */
class PlayerOverflowMenu(
    private val onFindTrack: () -> Unit,
    private val onSleepTimer: () -> Unit,
    private val onReportProblem: () -> Unit,
    private val onBroadcastHistory: () -> Unit,
) {

    private var window: PopupWindow? = null

    /**
     * Opens the menu under [anchor], showing [timer]'s remaining time when there is
     * one, with `Найти трек` available only when [canFindTrack].
     *
     * Anchored to the trailing header control and pulled to its end, so the menu
     * hangs off the same edge the control sits on rather than off the screen.
     */
    fun show(anchor: View, timer: SleepTimerState.Armed?, canFindTrack: Boolean) {
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

        content.findViewById<View>(R.id.player_overflow_find_track).apply {
            isEnabled = canFindTrack
            alpha = if (canFindTrack) 1f else UNAVAILABLE_ALPHA
            setOnClickListener {
                popup.dismiss()
                onFindTrack()
            }
        }

        content.findViewById<View>(R.id.player_overflow_sleep_timer).setOnClickListener {
            popup.dismiss()
            onSleepTimer()
        }

        val reportRow = content.findViewById<View>(R.id.player_overflow_report_problem)
        if (ReportConfig.isConfigured) {
            reportRow.setOnClickListener {
                popup.dismiss()
                onReportProblem()
            }
        } else {
            // GONE, not disabled: the row must not be drawn at all, and the menu
            // must close up around it rather than leave a gap where it was.
            reportRow.visibility = View.GONE
        }

        content.findViewById<View>(R.id.player_overflow_history).setOnClickListener {
            popup.dismiss()
            onBroadcastHistory()
        }

        window = popup
        popup.showAsDropDown(anchor, 0, 0, Gravity.END)
    }

    fun dismiss() {
        window?.dismiss()
        window = null
    }

    /**
     * The open menu's content view, or null when nothing is showing.
     *
     * A PopupWindow is a window of its own and is not in the activity's view tree,
     * so a test that searched the decor view for a row of this menu would find
     * nothing. `ReportEntryPointsTest` and `PlayerMissingFlowsTest` measure the live
     * surface, and this is how they reach it.
     */
    @androidx.annotation.VisibleForTesting
    fun contentForTest(): View? = window?.contentView

    private companion object {
        /**
         * The Material disabled-content opacity. The frozen file draws no
         * unavailable row, so this is the platform's convention rather than a
         * design value - noted as such in the G4b report.
         */
        const val UNAVAILABLE_ALPHA = 0.38f
    }
}
