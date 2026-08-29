package com.example.musicplayerapp.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.musicplayerapp.R
import com.example.musicplayerapp.data.supabase.DeletionStage
import com.example.musicplayerapp.data.supabase.IdentityStore
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
 * ## The two buttons are live as of G-A4c1
 *
 * `Войти` and `Создать аккаунт` were drawn exactly as designed and inert
 * through G-A3, because there was no destination to send them to and a placeholder
 * toast would have been a worse lie than a control that visibly waits.
 * auth-sign-in and auth-create-account exist now, so they navigate - and that is
 * the *only* thing that changed here. This fragment still performs no auth call,
 * still reads no identity state, and still cannot mint anything.
 *
 * ## MUST NOT SHIP BEFORE G-A5
 *
 * Signing in returns here, and this screen still renders the guest variant: there
 * is no `profile-authenticated` implementation to return to, and inventing one
 * inside a UI PR would be building G-A5 without a design review.
 *
 * That makes G-A4c1 **a development milestone rather than a shippable one.** A
 * listener who has just registered lands on a screen that tells them `Вы не вошли`
 * and offers them `Войти` - copy that is now false about them, on the one screen
 * they opened to check. The identity underneath is correct and their Collection is
 * syncing; only this screen is lying, and only until G-A5 replaces it.
 *
 * So: do not put a build carrying these auth screens in front of the public until
 * the authenticated profile lands. The gap is deliberate, visible and temporary,
 * and it is recorded here rather than in a tracker because this file is where
 * somebody would otherwise discover it.
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

        // G-A4c1: the two CTAs the G-A3 KDoc said were waiting for a destination now
        // have one. Nothing else about this screen changed - in particular it still
        // performs no auth call and no network call of its own, and still does not
        // read the identity state.
        binding.profileSignIn.setOnClickListener {
            findNavController().navigate(R.id.action_profile_to_auth_sign_in)
        }

        binding.profileCreateAccount.setOnClickListener {
            findNavController().navigate(R.id.action_profile_to_auth_create_account)
        }

        renderPendingDeletionIfAny()

        return binding.root
    }

    /**
     * What this screen says while an account deletion is unresolved.
     *
     * ## Why it belongs here
     *
     * `ProfileRoute` already sends an install with a deletion marker to this screen -
     * that is G-A8b, and it is right: the account may be gone, so the authenticated
     * card would be asserting something nobody can prove. What it produced until now
     * was a dead end. The install still holds `REGISTERED(X)` on disk, and
     * authentication from that state is not a defined transition in the frozen G-A4
     * routing, so both CTAs failed with a generic error and the listener had no way to
     * understand why.
     *
     * So the copy changes and the CTAs go. **Presentation only**: nothing here reads a
     * session, writes state, authenticates, or resolves anything. The resolution
     * belongs to `IdentityReconciler`, which runs on every start and needs no help
     * from a screen.
     *
     * ## Two stages, two different sentences
     *
     * `REQUESTED` means the outcome is genuinely unknown - the deletion may already
     * have committed with its response lost - so the copy promises a check rather than
     * a result, and offers no retry: a manual one would mint a second token for one
     * deletion and orphan the first, whose receipt is the only thing that can resolve
     * it.
     *
     * `CONFIRMED` means the server has answered and only this device is behind, so it
     * says the account is deleted and that cleanup is finishing.
     *
     * When the marker goes - cleanup done, or a refusal retracted it - this method
     * changes nothing and the ordinary guest screen renders exactly as it always has.
     */
    private fun renderPendingDeletionIfAny() {
        val stage = IdentityStore.deletion(requireContext())?.stage ?: return

        val heading = when (stage) {
            DeletionStage.REQUESTED -> R.string.profile_deletion_pending_heading
            DeletionStage.CONFIRMED -> R.string.profile_deletion_confirmed_heading
        }
        val body = when (stage) {
            DeletionStage.REQUESTED -> R.string.profile_deletion_pending_body
            DeletionStage.CONFIRMED -> R.string.profile_deletion_confirmed_body
        }

        binding.profileGuestHeading.setText(heading)
        binding.profileGuestBodyFirst.setText(body)
        // The frozen guest copy is one sentence split across two nodes where the
        // designer breaks it. The pending copy is a single string, so the second node
        // has nothing to say.
        binding.profileGuestBodySecond.visibility = View.GONE

        // Every way in is closed while this is unresolved. Not merely disabled: a
        // greyed-out `Войти` invites a tap that cannot work, and the reason it cannot
        // is not something this screen can explain.
        binding.profileSignIn.visibility = View.GONE
        binding.profileCreateAccount.visibility = View.GONE

        // "Аккаунт добавляет ..." is an argument for registering, which is not the
        // question in front of the listener right now.
        binding.profileBenefitsSection.visibility = View.GONE
        binding.profileBenefitSync.visibility = View.GONE
        binding.profileBenefitRestore.visibility = View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
