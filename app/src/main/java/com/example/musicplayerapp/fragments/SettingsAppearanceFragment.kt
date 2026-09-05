package com.example.musicplayerapp.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.musicplayerapp.R
import com.example.musicplayerapp.data.ThemeStore
import com.example.musicplayerapp.databinding.FragmentSettingsAppearanceBinding
import com.example.musicplayerapp.ui.settings.ThemeMode

/**
 * settings-appearance 2517:2817 / 2517:3784 - Системная, Светлая, Тёмная.
 *
 * ## How a choice reaches the screen
 *
 * Two steps, in this order, and the order is the point:
 *
 * ```
 * ThemeStore.write(mode)                  the choice is on disk first
 * activity.delegate.localNightMode = ...  then the window is told
 * ```
 *
 * The second call runs `applyDayNight()`, and because `uiMode` is not in
 * MainActivity's `configChanges` the activity is recreated. Writing first means
 * the recreated activity reads the new value in `onCreate` and comes back in the
 * chosen appearance; writing second would race its own recreation. The Navigation
 * back stack survives the recreation, so this screen is rebuilt in place and the
 * listener watches it repaint - which is what the frozen note promises.
 *
 * ## Why the delegate and not AppCompatDelegate.setDefaultNightMode
 *
 * `setDefaultNightMode` is a **static, process-wide** switch. `TvMainActivity` is
 * an `AppCompatActivity` in this same process, and the `<application>` theme it
 * inherits from is now a DayNight tree - so a process-wide night mode is a change
 * that reaches Android TV from a phone screen TV cannot even open.
 * `localNightMode` is scoped to one activity's delegate, which makes "TV is
 * unaffected" structural rather than a claim. Nothing in this app may call
 * `setDefaultNightMode`; `AppearanceSelectionTest` asserts the process default is
 * never forced to `MODE_NIGHT_YES` or `MODE_NIGHT_NO` after a change.
 *
 * ## Two accepted limitations, recorded rather than solved
 *
 *  - **Системная on API 24-28.** Following the system means following a platform
 *    setting that arrived at API 29, and `minSdk` is 24. On 24-28 there is
 *    normally nothing to follow, so Системная resolves to Light there; Светлая
 *    and Тёмная are unaffected, and Тёмная is how a listener on those releases
 *    gets a dark app at all.
 *  - **The starting window.** The platform draws it from
 *    `Theme.Myata.Splash` before any of this code runs, resolving `background`
 *    against the *system* uiMode. Somebody who chooses Тёмная on a light device
 *    sees a light starting window and then a dark first frame. No night-mode API
 *    can reach a window created before the process exists, so this is not a
 *    consequence of the choice above - a process-wide default would flash
 *    identically.
 *
 * Both are in `docs/SETTINGS-APPEARANCE-3.6.6.md`.
 */
class SettingsAppearanceFragment : Fragment() {

    private var _binding: FragmentSettingsAppearanceBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSettingsAppearanceBinding.inflate(inflater, container, false)

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.appearanceRoot) { v, insets ->
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, v.paddingBottom)

            val scroll = binding.appearanceScroll
            scroll.setPadding(
                scroll.paddingLeft,
                scroll.paddingTop,
                scroll.paddingRight,
                resources.getDimensionPixelSize(R.dimen.content_bottom_clearance) + bars.bottom,
            )
            insets
        }

        binding.appearanceBack.setOnClickListener { findNavController().popBackStack() }

        binding.appearanceRowSystem.setOnClickListener { choose(ThemeMode.SYSTEM) }
        binding.appearanceRowLight.setOnClickListener { choose(ThemeMode.LIGHT) }
        binding.appearanceRowDark.setOnClickListener { choose(ThemeMode.DARK) }

        render(ThemeStore.read(requireContext()))

        return binding.root
    }

    /**
     * Records the choice and applies it.
     *
     * Choosing the mode that is already current does nothing at all - no write and
     * no `localNightMode` assignment. AppCompat would no-op the second one anyway,
     * but going through `applyDayNight` for a value that has not changed is a
     * recreation the listener did not ask for, and on this screen a recreation is
     * visible.
     */
    private fun choose(mode: ThemeMode) {
        val current = ThemeStore.read(requireContext())
        if (mode == current) return

        ThemeStore.write(requireContext(), mode)

        // Repaint the check and the borders before handing over. The activity is
        // about to be recreated and this view discarded, so this is not what the
        // listener ends up looking at - but a frame drawn between the tap and the
        // recreation showing the *old* selection is a visible stutter, and the two
        // renders cost nothing.
        render(mode)

        (requireActivity() as AppCompatActivity).delegate.localNightMode = mode.localNightMode()
    }

    /**
     * Which row is shown as chosen.
     *
     * Selection is exactly two things - the 2dp `primary` plate and the check - and
     * `Row / Системная` keeps its 72dp height and its sub-label in every state. See
     * the layout for why that is a decision rather than a measurement.
     *
     * The checks are toggled with `INVISIBLE` rather than `GONE`: the 24dp slot
     * stays reserved at x=318 on all three rows, so the labels beside them do not
     * reflow when the selection moves.
     */
    private fun render(mode: ThemeMode) {
        binding.appearanceRowSystem.setRowBackground(mode == ThemeMode.SYSTEM)
        binding.appearanceRowLight.setRowBackground(mode == ThemeMode.LIGHT)
        binding.appearanceRowDark.setRowBackground(mode == ThemeMode.DARK)

        binding.appearanceCheckSystem.visibility = visibility(mode == ThemeMode.SYSTEM)
        binding.appearanceCheckLight.visibility = visibility(mode == ThemeMode.LIGHT)
        binding.appearanceCheckDark.visibility = visibility(mode == ThemeMode.DARK)
    }

    /**
     * Swaps the plate, and puts the row's own padding back afterwards.
     *
     * `setBackgroundResource` hands the view whatever padding the new drawable
     * declares. Neither of these two shapes declares any, so today nothing is
     * lost - but the rule is a property of the drawables rather than of this call,
     * and a later `<padding>` on one of them would silently collapse the 16dp
     * insets that put the glyph at x=16 and the check at x=318. Restoring
     * explicitly costs four reads and removes the whole class of it.
     */
    private fun View.setRowBackground(selected: Boolean) {
        val start = paddingStart
        val top = paddingTop
        val end = paddingEnd
        val bottom = paddingBottom
        setBackgroundResource(
            if (selected) R.drawable.bg_settings_row_selected else R.drawable.bg_profile_row
        )
        setPaddingRelative(start, top, end, bottom)
    }

    private fun visibility(selected: Boolean) = if (selected) View.VISIBLE else View.INVISIBLE

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
