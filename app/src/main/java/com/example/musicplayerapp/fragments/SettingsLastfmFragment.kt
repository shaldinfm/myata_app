package com.example.musicplayerapp.fragments

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.musicplayerapp.R
import com.example.musicplayerapp.databinding.FragmentSettingsLastfmBinding
import com.example.musicplayerapp.ui.lastfm.LastfmCard
import com.example.musicplayerapp.ui.lastfm.LastfmCardState
import com.example.musicplayerapp.ui.lastfm.LastfmCountText

/**
 * settings-lastfm 2523:131 / 2522:3992 - the screen the `Интеграции` row opens.
 *
 * ## P3a draws the states; it does not produce them
 *
 * There is no session store, no network and no browser launch in this slice, so
 * the only state this fragment can be in is [LastfmCardState.Disconnected], and
 * the `Подключить` button is deliberately inert. The other three states are fully
 * rendered and fully tested through [render] - what is missing is the auth flow
 * that decides which one is true, and that arrives in P3b.
 *
 * That split is the point: the fidelity work can be reviewed against the frozen
 * frames without a state machine, a token or a network request in the same diff.
 *
 * ## Why the state is a parameter and not a field
 *
 * [render] takes the state rather than reading one, so the instrumentation tests
 * can draw all four states against a real inflated layout with no fake account
 * reaching a shipped build. Nothing in `src/main` ever calls it with anything but
 * [LastfmCardState.Disconnected].
 */
class SettingsLastfmFragment : Fragment() {

    private var _binding: FragmentSettingsLastfmBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSettingsLastfmBinding.inflate(inflater, container, false)

        // The 64dp band sits below the status bar, as on every pushed destination.
        // systemBars() OR displayCutout() for the reason profile-guest gives: the
        // status bar normally covers a top cutout, but that is the platform being
        // helpful rather than a guarantee.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.lastfmRoot) { v, insets ->
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, v.paddingBottom)

            // This screen hides the bottom bar, so nothing else is reserving the
            // system navigation inset - the scroll clears it itself.
            val scroll = binding.lastfmScroll
            scroll.setPadding(
                scroll.paddingLeft,
                scroll.paddingTop,
                scroll.paddingRight,
                resources.getDimensionPixelSize(R.dimen.content_bottom_clearance) + bars.bottom,
            )
            insets
        }

        // Back returns to Settings, which is the only thing that opens this.
        binding.lastfmBack.setOnClickListener { findNavController().popBackStack() }

        render(binding, LastfmCardState.Disconnected)

        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {

        /**
         * Draws [state] into [binding].
         *
         * Every difference between the four states passes through here, and
         * [LastfmCard.contentFor] decides all of it, so a state cannot be drawn
         * with another state's button or tint. The geometry never moves: the second
         * body line is hidden with `INVISIBLE`, not `GONE`, so the button stays at
         * the y the frozen frame puts it at.
         */
        @VisibleForTesting
        fun render(binding: FragmentSettingsLastfmBinding, state: LastfmCardState) {
            val res = binding.root.resources
            val context = binding.root.context
            val content = LastfmCard.contentFor(state)

            binding.lastfmCardHeading.setText(content.headingRes)

            // The connected card's two lines come from the account rather than from
            // resources - the username as it is, and the lifetime total underneath.
            if (state is LastfmCardState.Connected) {
                binding.lastfmBody1.text = state.username
                val count = state.playcount
                if (count == null) {
                    // A count that could not be read is left out, never shown as 0
                    // or as an error: the username alone is still true, and this
                    // line is an enrichment.
                    binding.lastfmBody2.text = ""
                    binding.lastfmBody2.visibility = View.INVISIBLE
                } else {
                    binding.lastfmBody2.text = res.getString(
                        R.string.lastfm_total_scrobbles,
                        LastfmCountText.scrobbles(
                            count,
                            res.getString(R.string.lastfm_scrobbles_one),
                            res.getString(R.string.lastfm_scrobbles_few),
                            res.getString(R.string.lastfm_scrobbles_many),
                        ),
                    )
                    binding.lastfmBody2.visibility = View.VISIBLE
                }
            } else {
                binding.lastfmBody1.setText(content.bodyFirstRes)
                val second = content.bodySecondRes
                if (second == null) {
                    binding.lastfmBody2.text = ""
                    binding.lastfmBody2.visibility = View.INVISIBLE
                } else {
                    binding.lastfmBody2.setText(second)
                    binding.lastfmBody2.visibility = View.VISIBLE
                }
            }

            // The mark's tint is the frozen file's own state indicator: it is
            // `primary` only while a session is actually linked. Set through the
            // image tint list - the same mechanism the layout's `android:tint` uses -
            // rather than a colour filter layered on top of it, so there is one tint
            // on this view and it can be read back.
            ImageViewCompat.setImageTintList(
                binding.lastfmLogo,
                ColorStateList.valueOf(
                    ContextCompat.getColor(
                        context,
                        if (content.logoTintPrimary) R.color.primary else R.color.text_secondary,
                    )
                ),
            )

            binding.lastfmCheck.visibility =
                if (content.showCheck) View.VISIBLE else View.GONE

            binding.lastfmAction.setText(content.buttonLabelRes)
            binding.lastfmAction.setBackgroundResource(
                if (content.buttonPrimary) R.drawable.bg_profile_button_primary
                else R.drawable.bg_profile_button_secondary
            )
            binding.lastfmAction.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (content.buttonPrimary) R.color.profile_primary_button_label
                    else R.color.text_heading,
                )
            )
        }
    }
}
