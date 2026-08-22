package com.example.musicplayerapp.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.musicplayerapp.R
import com.example.musicplayerapp.databinding.FragmentProfileGuestBinding

/**
 * profile-guest 2517:2644 / 2517:3611 - the first Accounts surface.
 *
 * ## It reaches nothing
 *
 * This screen performs **no auth call and no network call of any kind**, and that
 * is a property to preserve rather than a stage it is passing through. In
 * particular it does not call [com.example.musicplayerapp.data.supabase.ListenerSession.identity],
 * which is the one function in the app that can mint an anonymous `auth.users`
 * row. Opening the profile is somebody looking at a screen; it is not a reason for
 * them to start existing in a database, and an install at
 * [com.example.musicplayerapp.data.supabase.IdentityState.None] must still be
 * `NONE` after opening this and going back. `ProfileGuestIdentityTest` holds that.
 *
 * Nothing here reads the identity state either, because nothing here varies with
 * it: G-A3 is the guest screen only. When G-A4 adds the signed-in variant it will
 * read [com.example.musicplayerapp.data.supabase.IdentityStore] - the persisted
 * local state - and still not the session boundary.
 *
 * ## The two buttons are inert on purpose
 *
 * `Войти` and `Создать аккаунт` are drawn exactly as designed and do nothing.
 * There is no destination to send them to until G-A4 builds one, and deliberately
 * no placeholder toast: a control that acknowledges a tap it did not act on is a
 * worse lie than one that visibly waits. They are not disabled either - the frame
 * draws them in their normal state, and dimming them would be inventing a state
 * the design does not have.
 */
class ProfileGuestFragment : Fragment() {

    private var _binding: FragmentProfileGuestBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentProfileGuestBinding.inflate(inflater, container, false)

        // The 64dp band sits *below* the status bar, exactly as it does on HOME,
        // ABOUT US and COLLECTION - the root takes the inset as padding so the band
        // keeps its full 64 and the title lands on the same baseline as the other
        // three. Without this the heading is drawn over the system clock.
        //
        // systemBars() OR displayCutout(), not systemBars() alone: the status bar
        // normally covers a top cutout, but that is the platform being helpful
        // rather than a guarantee. Same union ABOUT US uses, and on every cutout
        // emulation measured there it resolves to the same number anyway.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.profileRoot) { v, insets ->
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, v.paddingBottom)

            // This screen hides the bottom bar, so nothing else is reserving the
            // system navigation inset for it - the scroll has to clear that itself
            // or the last row sits under the gesture pill.
            val scroll = binding.profileScroll
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
        binding.profileBack.setOnClickListener { findNavController().popBackStack() }

        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
