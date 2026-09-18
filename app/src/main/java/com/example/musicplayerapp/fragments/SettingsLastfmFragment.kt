package com.example.musicplayerapp.fragments

import android.content.ActivityNotFoundException
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.example.musicplayerapp.R
import com.example.musicplayerapp.databinding.FragmentSettingsLastfmBinding
import com.example.musicplayerapp.ui.lastfm.LastfmCard
import com.example.musicplayerapp.ui.lastfm.LastfmCardState
import com.example.musicplayerapp.ui.lastfm.LastfmAuthLauncher
import com.example.musicplayerapp.ui.lastfm.LastfmCountText
import com.example.musicplayerapp.ui.lastfm.LastfmEvent
import com.example.musicplayerapp.ui.lastfm.LastfmViewModel

/**
 * settings-lastfm 2523:131 / 2522:3992 - the screen the `Интеграции` row opens.
 *
 * ## What drives it (G6b P3b)
 *
 * [LastfmViewModel], which derives every card from `lastfm_session` - so the right
 * card comes back after recreation, process death or a trip to the browser without
 * anything saved here. This fragment only forwards three things: the two buttons,
 * and [onResume], which is the **only** trigger for exchanging a pending token. No
 * deep link, no callback: the listener returns from the browser on their own.
 *
 * ## Why rendering is a function of a state
 *
 * [render] takes the state rather than reading one, so the layout tests can draw
 * all four cards against a real inflated layout with no fake account reaching a
 * shipped build.
 */
class SettingsLastfmFragment : Fragment() {

    private var _binding: FragmentSettingsLastfmBinding? = null
    private val binding get() = _binding!!

    private val viewModel: LastfmViewModel by viewModels()

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

        binding.lastfmAction.setOnClickListener { viewModel.onPrimaryAction() }
        binding.lastfmActionSecondary.setOnClickListener { viewModel.onSecondaryAction() }

        viewModel.state.observe(viewLifecycleOwner) { state -> _binding?.let { render(it, state) } }
        viewModel.event.observe(viewLifecycleOwner) { event ->
            event ?: return@observe
            // Consumed before it is handled: handling a browser that will not open
            // raises the next event, which must not be the one this line clears.
            viewModel.consumeEvent()
            when (event) {
                is LastfmEvent.OpenBrowser -> try {
                    LastfmAuthLauncher.launch(requireContext(), event.url)
                } catch (e: ActivityNotFoundException) {
                    viewModel.onBrowserUnavailable()
                }
                is LastfmEvent.Message ->
                    Toast.makeText(requireContext(), event.text, Toast.LENGTH_LONG).show()
            }
        }

        return binding.root
    }

    override fun onResume() {
        super.onResume()
        // The one trigger for finishing an authorisation: the listener coming back.
        viewModel.onResume()
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

            // The second, outlined button - pending and re-auth only. GONE otherwise,
            // which is what keeps the other two cards at exactly 172.
            val secondary = content.secondaryLabelRes
            if (secondary == null) {
                binding.lastfmActionSecondary.visibility = View.GONE
            } else {
                binding.lastfmActionSecondary.setText(secondary)
                binding.lastfmActionSecondary.visibility = View.VISIBLE
            }
        }
    }
}
