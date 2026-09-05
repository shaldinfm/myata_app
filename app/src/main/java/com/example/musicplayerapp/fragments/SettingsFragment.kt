package com.example.musicplayerapp.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.musicplayerapp.R
import com.example.musicplayerapp.data.ThemeStore
import com.example.musicplayerapp.data.supabase.EmailAuthBackend
import com.example.musicplayerapp.databinding.FragmentSettingsBinding
import com.example.musicplayerapp.ui.profile.ProfileRoute
import com.example.musicplayerapp.ui.settings.SettingsProfileRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * settings 2517:2758 / 2517:3725 - the shell the 40x40 header control now opens.
 *
 * ## Why this is the parent of the profile
 *
 * Nothing in the FINAL Figma file shows how `settings` is reached, and there is
 * no spare control to reach it with: the second 40x40 node in the HOME and
 * ABOUT US headers is `visible: false` and sits at exactly the same coordinates
 * as the one that ships. What the file *does* say is that `settings` draws
 * `Row / Профиль` as its first row, with a value and a chevron - so the profile
 * is a destination inside settings rather than a sibling of it. G1 follows that:
 * one control, pointing at the parent, and the profile one tap further in.
 *
 * ## Two rows, and both of them do something
 *
 * The frozen frame has five sections. This draws the two whose features exist.
 * See `fragment_settings.xml` for why the other three are absent rather than
 * inert - the short version is that an inert row here would have to state a fact
 * about a feature with no implementation to make the fact true.
 *
 * ## What it reads, and when
 *
 * [ProfileRoute.destination] on every resume, off the main thread. That is not a
 * new cost: it is the same call the header control made on every tap before G1,
 * including its reconciliation, so the identity work per visit is unchanged - it
 * has only moved from the tap to the screen the tap now opens. Nothing here can
 * mint an identity, for the reasons `ProfileRoute` sets out.
 *
 * On resume rather than on create, because both of the values this screen shows
 * can change while it is on the back stack: the appearance is chosen one
 * destination deeper, and the profile row's value changes when somebody signs in
 * or out and comes back.
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)

        // The 64dp band sits below the status bar, as on every pushed destination.
        // systemBars() OR displayCutout() for the reason profile-guest gives: the
        // status bar normally covers a top cutout, but that is the platform being
        // helpful rather than a guarantee.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.settingsRoot) { v, insets ->
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, v.paddingBottom)

            // This screen hides the bottom bar, so nothing else is reserving the
            // system navigation inset - the scroll clears it itself.
            val scroll = binding.settingsScroll
            scroll.setPadding(
                scroll.paddingLeft,
                scroll.paddingTop,
                scroll.paddingRight,
                resources.getDimensionPixelSize(R.dimen.content_bottom_clearance) + bars.bottom,
            )
            insets
        }

        // Back returns to whatever opened this - HOME, ABOUT US or the empty
        // COLLECTION - which is what popBackStack does and what a hardcoded
        // destination would get wrong for two of the three.
        binding.settingsBack.setOnClickListener { findNavController().popBackStack() }

        // The same routing the header control used to run on its own. The row and
        // its value therefore agree by construction: onResume asks the question
        // once and uses the one answer for both.
        binding.settingsRowProfile.setOnClickListener { ProfileRoute.open(this) }

        binding.settingsRowTheme.setOnClickListener {
            findNavController().navigate(R.id.action_settings_to_settings_appearance)
        }

        return binding.root
    }

    override fun onResume() {
        super.onResume()

        // Synchronous: one SharedPreferences lookup, which is a hash-map read after
        // the first load. Nothing about the appearance is remote.
        binding.settingsRowThemeValue.setText(ThemeStore.read(requireContext()).labelRes())

        renderProfileValue()
    }

    /**
     * Fills in the `Row / Профиль` value.
     *
     * The address is the session's, reached through the path the account card
     * already uses - `currentAccount()`, then [SettingsProfileRow] over
     * `ProfileAccount.email`. It is deliberately not read from `IdentityStore`,
     * which does not have one: `markRegistered` persists a uid and nothing else,
     * and adding an address to it for a settings row would be moving an identity
     * contract to suit a label.
     *
     * Ordering is `ProfileAuthenticatedFragment.verifySession`'s exactly -
     * routing (which reconciles) first, the session read second - so the two
     * screens cannot disagree about who this device is.
     *
     * A failure to reach the session is not an error state here. The row falls
     * back to `Вошли` or `Не вошли`, both of which are true statements, and
     * neither of which needs a retry.
     */
    private fun renderProfileValue() {
        viewLifecycleOwner.lifecycleScope.launch {
            val value = withContext(Dispatchers.IO) {
                val signedIn =
                    ProfileRoute.destination(requireContext()) == R.id.profile_authenticated
                val email = if (!signedIn) {
                    null
                } else {
                    runCatching { EmailAuthBackend.api(requireContext()).currentAccount() }
                        .getOrNull()?.email
                }
                SettingsProfileRow.value(signedIn, email)
            }

            if (_binding == null) return@launch

            binding.settingsRowProfileValue.text = when (value) {
                SettingsProfileRow.Value.SignedOut ->
                    getString(R.string.settings_profile_signed_out)

                SettingsProfileRow.Value.SignedIn ->
                    getString(R.string.settings_profile_signed_in)

                is SettingsProfileRow.Value.Address -> value.email
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
