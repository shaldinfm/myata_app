package com.example.musicplayerapp.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentManager
import com.example.musicplayerapp.MainActivity
import com.example.musicplayerapp.R
import com.example.musicplayerapp.StreamsViewModel
import com.example.musicplayerapp.ui.sleeptimer.SleepTimerDuration
import com.example.musicplayerapp.ui.sleeptimer.SleepTimerState
import com.example.musicplayerapp.ui.sleeptimer.SleepTimerText
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * The sleep timer sheet - five frozen frames, one surface.
 *
 * `sleep-timer-select` when nothing is armed, `sleep-timer-active` /
 * `sleep-timer-active-custom` when something is, and `sleep-timer-custom` /
 * `sleep-timer-custom-invalid` behind `Своё время`. They share a card, a handle
 * and a two-line head, so they are one dialog in two modes rather than two
 * dialogs, which is what the select frame's note asks for: *"'Своё время' opens
 * the picker instead."*
 *
 * ## It shows the timer; it does not keep one
 *
 * Every value on this sheet is computed from [StreamsViewModel.sleepTimer], which
 * is the service's own state relayed unchanged. Nothing here counts down and
 * nothing here decides when the timer expires - the ticker below only asks the
 * same deadline what time it is now, once a second, and re-renders. Dismissing
 * the sheet, rotating the phone or killing the Activity therefore cannot affect a
 * running timer in any way.
 *
 * This is also why the two entry points cannot disagree. The PLAYER overflow and
 * the Settings row open *this* sheet, reading *that* LiveData; there is no second
 * copy of the state for them to diverge over.
 *
 * ## Remaining minutes are read, never accumulated
 *
 * `sleep-timer-active`'s note is the rule: *"The remaining time is computed from
 * the stored absolute end time, never from a counter that restarts."* A ticker
 * that decremented its own number would drift, would reset every time the sheet
 * was reopened, and would be wrong after the phone slept. Recomputing from the
 * deadline is correct at every tick by construction.
 */
class SleepTimerSheet : BottomSheetDialogFragment() {

    private lateinit var vm: StreamsViewModel

    /** LIST or the custom picker. Survives rotation; deliberately not persisted further. */
    private var showingCustom = false

    private var customHours = DEFAULT_CUSTOM_HOURS
    private var customMinutes = DEFAULT_CUSTOM_MINUTES

    private val ticker = android.os.Handler(android.os.Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            render()
            ticker.postDelayed(this, TICK_MS)
        }
    }

    override fun getTheme(): Int = R.style.Theme_Myata_CollectionTrackSheet

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.sheet_sleep_timer, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        vm = (requireActivity() as MainActivity).viewModel

        savedInstanceState?.let {
            showingCustom = it.getBoolean(STATE_CUSTOM, false)
            customHours = it.getInt(STATE_HOURS, DEFAULT_CUSTOM_HOURS)
            customMinutes = it.getInt(STATE_MINUTES, DEFAULT_CUSTOM_MINUTES)
        }

        // Expanded and never collapsed, for the reason CollectionTrackSheet gives:
        // a 9/16 peek height puts the last row - here the destructive one - below
        // the fold on a short window, and this sheet is a fixed list of choices
        // rather than a scrolling feed.
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }

        SleepTimerDuration.PRESETS.forEachIndexed { index, minutes ->
            view.findViewById<View>(PRESET_ROWS[index]).setOnClickListener {
                vm.setSleepTimer(minutes, isCustom = false)
                dismiss()
            }
        }

        view.findViewById<View>(R.id.sleep_timer_row_custom).setOnClickListener {
            // Seed the picker from the running custom timer when there is one, so
            // reopening it shows what was chosen rather than a default.
            (vm.sleepTimer.value as? SleepTimerState.Armed)
                ?.takeIf { it.isCustom }
                ?.let { armed ->
                    val (h, m) = SleepTimerDuration.split(armed.durationMinutes)
                    customHours = h
                    customMinutes = m
                }
            showingCustom = true
            render()
        }

        view.findViewById<View>(R.id.sleep_timer_row_cancel).setOnClickListener {
            vm.cancelSleepTimer()
            (activity as? MainActivity)?.showSleepTimerCancelled()
            dismiss()
        }

        // The steppers. Hours carry the whole range; minutes wrap at 59 rather than
        // rolling into the hours field, because a stepper that changes a number the
        // finger is not on is a surprise.
        view.findViewById<View>(R.id.sleep_timer_hours_minus).setOnClickListener {
            if (customHours > 0) { customHours--; render() }
        }
        view.findViewById<View>(R.id.sleep_timer_hours_plus).setOnClickListener {
            if (customHours < MAX_HOURS) { customHours++; render() }
        }
        view.findViewById<View>(R.id.sleep_timer_minutes_minus).setOnClickListener {
            if (customMinutes > 0) { customMinutes--; render() }
        }
        view.findViewById<View>(R.id.sleep_timer_minutes_plus).setOnClickListener {
            if (customMinutes < 59) { customMinutes++; render() }
        }

        view.findViewById<View>(R.id.sleep_timer_custom_cancel).setOnClickListener {
            showingCustom = false
            render()
        }
        view.findViewById<View>(R.id.sleep_timer_custom_confirm).setOnClickListener {
            val minutes = customTotalMinutes()
            // Установить is drawn disabled at 0 ч 0 мин and refuses here too. The
            // frozen note is that it is disabled "rather than accepting it and
            // failing later"; the guard is what makes the drawing true.
            if (!SleepTimerDuration.isValid(minutes)) return@setOnClickListener
            vm.setSleepTimer(minutes, isCustom = true)
            dismiss()
        }

        // The service's state, relayed. The sheet redraws on every change, so an
        // expiry that happens while it is open closes the armed state out from
        // under it correctly instead of leaving a stale countdown on screen.
        vm.sleepTimer.observe(viewLifecycleOwner) { render() }
    }

    override fun onResume() {
        super.onResume()
        // Ask the owner to reconcile before anything is drawn: an expired timer
        // must not appear as an armed one on a sheet that has just been opened.
        vm.syncSleepTimer()
        ticker.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ticker.removeCallbacks(tick)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_CUSTOM, showingCustom)
        outState.putInt(STATE_HOURS, customHours)
        outState.putInt(STATE_MINUTES, customMinutes)
    }

    private fun customTotalMinutes(): Int = customHours * 60 + customMinutes

    private fun render() {
        val root = view ?: return
        val ctx = requireContext()
        val timer = vm.sleepTimer.value as? SleepTimerState.Armed
        val now = android.os.SystemClock.elapsedRealtime()

        root.findViewById<View>(R.id.sleep_timer_list).visibility =
            if (showingCustom) View.GONE else View.VISIBLE
        root.findViewById<View>(R.id.sleep_timer_custom_panel).visibility =
            if (showingCustom) View.VISIBLE else View.GONE

        val title = root.findViewById<TextView>(R.id.sleep_timer_title)
        val subtitle = root.findViewById<TextView>(R.id.sleep_timer_subtitle)

        if (showingCustom) {
            title.setText(R.string.sleep_timer_custom_title)
            subtitle.setText(R.string.sleep_timer_custom_subtitle)
            renderCustom(root, ctx)
            return
        }

        title.setText(R.string.sleep_timer_title)
        subtitle.text = if (timer == null) {
            ctx.getString(R.string.sleep_timer_subtitle)
        } else {
            // Derived here, at draw time, from the wall clock plus the monotonic
            // time that is left - so a timezone or DST change moves this string and
            // does not move the deadline it describes.
            ctx.getString(
                R.string.sleep_timer_active_subtitle,
                SleepTimerText.endTime(ctx, timer, now),
            )
        }

        val remaining = timer?.let { ctx.getString(R.string.sleep_timer_trailing, SleepTimerText.remaining(ctx, it, now)) }

        SleepTimerDuration.PRESETS.forEachIndexed { index, minutes ->
            val ticked = timer != null && !timer.isCustom && timer.durationMinutes == minutes
            markRow(root, PRESET_ICONS[index], PRESET_TRAILING[index], ticked, remaining)
        }
        markRow(
            root, R.id.sleep_timer_icon_custom, R.id.sleep_timer_trailing_custom,
            ticked = timer != null && timer.isCustom, remaining = remaining,
        )

        val armed = timer != null
        root.findViewById<View>(R.id.sleep_timer_divider).visibility =
            if (armed) View.VISIBLE else View.GONE
        root.findViewById<View>(R.id.sleep_timer_row_cancel).visibility =
            if (armed) View.VISIBLE else View.GONE
    }

    /** The check-and-value treatment the frozen active frames give exactly one row. */
    private fun markRow(root: View, iconId: Int, trailingId: Int, ticked: Boolean, remaining: String?) {
        root.findViewById<ImageView>(iconId).setImageResource(
            if (ticked) R.drawable.ic_sleep_timer_check else R.drawable.ic_sleep_timer_clock
        )
        root.findViewById<TextView>(trailingId).apply {
            if (ticked && remaining != null) {
                text = remaining
                visibility = View.VISIBLE
            } else {
                visibility = View.GONE
            }
        }
    }

    private fun renderCustom(root: View, ctx: android.content.Context) {
        root.findViewById<TextView>(R.id.sleep_timer_hours_value).text = customHours.toString()
        root.findViewById<TextView>(R.id.sleep_timer_minutes_value).text = customMinutes.toString()

        val total = customTotalMinutes()
        val valid = SleepTimerDuration.isValid(total)

        val footer = root.findViewById<TextView>(R.id.sleep_timer_custom_footer)
        if (valid) {
            footer.text = ctx.getString(
                R.string.sleep_timer_custom_preview,
                SleepTimerText.endTimeIn(SleepTimerDuration.toMs(total)),
            )
            footer.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
        } else {
            footer.setText(R.string.sleep_timer_custom_error)
            footer.setTextColor(ContextCompat.getColor(ctx, R.color.error))
        }

        val confirm = root.findViewById<TextView>(R.id.sleep_timer_custom_confirm)
        confirm.setBackgroundResource(
            if (valid) R.drawable.bg_sleep_timer_button_primary
            else R.drawable.bg_sleep_timer_button_disabled
        )
        confirm.setTextColor(
            ContextCompat.getColor(
                ctx,
                if (valid) R.color.profile_primary_button_label else R.color.sleep_timer_disabled_content,
            )
        )

        // A control at a bound it cannot cross is drawn as unavailable rather than
        // silently doing nothing - which is what the invalid frame draws minus as.
        setStepEnabled(root, R.id.sleep_timer_hours_minus, customHours > 0)
        setStepEnabled(root, R.id.sleep_timer_hours_plus, customHours < MAX_HOURS)
        setStepEnabled(root, R.id.sleep_timer_minutes_minus, customMinutes > 0)
        setStepEnabled(root, R.id.sleep_timer_minutes_plus, customMinutes < 59)
    }

    private fun setStepEnabled(root: View, id: Int, enabled: Boolean) {
        root.findViewById<ImageView>(id).apply {
            isEnabled = enabled
            setBackgroundResource(
                if (enabled) R.drawable.bg_sleep_timer_stepper_control
                else R.drawable.bg_sleep_timer_stepper_control_disabled
            )
            setColorFilter(
                ContextCompat.getColor(
                    context,
                    if (enabled) R.color.primary else R.color.sleep_timer_disabled_content,
                )
            )
        }
    }

    companion object {
        const val TAG = "SleepTimerSheet"

        private const val STATE_CUSTOM = "showing_custom"
        private const val STATE_HOURS = "custom_hours"
        private const val STATE_MINUTES = "custom_minutes"

        /**
         * Where the picker opens when there is no custom timer to seed it from.
         *
         * One hour, because the four presets already cover 15 to 60: somebody who
         * has opened `Своё время` at all is asking for something the presets do not
         * offer, and that is almost always longer.
         */
        private const val DEFAULT_CUSTOM_HOURS = 1
        private const val DEFAULT_CUSTOM_MINUTES = 0

        /** Owner decision D1: 12 hours is the ceiling. */
        private val MAX_HOURS = SleepTimerDuration.MAX_MINUTES / 60

        private const val TICK_MS = 1_000L

        private val PRESET_ROWS = intArrayOf(
            R.id.sleep_timer_row_15, R.id.sleep_timer_row_30,
            R.id.sleep_timer_row_45, R.id.sleep_timer_row_60,
        )
        private val PRESET_ICONS = intArrayOf(
            R.id.sleep_timer_icon_15, R.id.sleep_timer_icon_30,
            R.id.sleep_timer_icon_45, R.id.sleep_timer_icon_60,
        )
        private val PRESET_TRAILING = intArrayOf(
            R.id.sleep_timer_trailing_15, R.id.sleep_timer_trailing_30,
            R.id.sleep_timer_trailing_45, R.id.sleep_timer_trailing_60,
        )

        fun show(fm: FragmentManager) {
            if (fm.findFragmentByTag(TAG) != null) return
            SleepTimerSheet().show(fm, TAG)
        }
    }
}
