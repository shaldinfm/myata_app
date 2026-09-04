package com.example.musicplayerapp.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.example.musicplayerapp.R
import com.example.musicplayerapp.databinding.FragmentAuthSignInBinding
import com.example.musicplayerapp.ui.auth.AuthFormState
import com.example.musicplayerapp.ui.auth.AuthViewModel
import com.example.musicplayerapp.ui.auth.applyAuthInsets
import com.example.musicplayerapp.ui.auth.setInlineError

/**
 * auth-sign-in 2517:2603 / 2517:3570.
 *
 * ## It calls the repository once and knows nothing else
 *
 * `Войти` hands two strings to [com.example.musicplayerapp.data.supabase.EmailAuthRepository]
 * and waits for a typed result. That is the entire integration. This fragment does
 * not read `IdentityStore`, does not know whether this install is anonymous, does not
 * know that an anonymous one performs an X→Y handoff, and never clears a Collection,
 * a reaction or a marker. All of it is settled below by G-A4b1 and G-A4b2, and a
 * screen reaching in to help would be a second implementation of the most delicate
 * code in the app - one that a listener's data would pay for.
 *
 * ## `Забыли пароль?` opens auth-recovery
 *
 * Live since G-A4c2, and a push rather than a swap: the two cross-links between the
 * auth forms pop what they came from, because somebody who decides they meant to
 * register does not want the sign-in form back. Recovery is not a change of mind about
 * which form to fill in - it is an errand inside this one - so Back returns here with
 * whatever was typed.
 *
 * ## `Продолжить без аккаунта` creates nothing
 *
 * It pops back, and that is all it does. It is emphatically not a "sign in as guest"
 * button: no identity is minted, none is cleared, and an install at
 * [com.example.musicplayerapp.data.supabase.IdentityState.None] is still `NONE`
 * afterwards. Being a guest is the absence of an account, not another kind of one.
 */
class AuthSignInFragment : Fragment() {

    private var _binding: FragmentAuthSignInBinding? = null
    private val binding get() = _binding!!

    private val viewModel: AuthViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentAuthSignInBinding.inflate(inflater, container, false)

        applyAuthInsets(binding.authRoot, binding.authScroll)

        binding.authBack.setOnClickListener { findNavController().popBackStack() }

        binding.authSubmit.setOnClickListener { submit() }

        // Done on the password field submits, which is what an IME with an "actionDone"
        // key promises. Without it the key dismisses the keyboard and the listener has
        // to find the button again.
        binding.authPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else {
                false
            }
        }

        // A swap rather than a push: there is never more than one auth screen on the
        // stack, so ping-ponging between the two cannot build a pile of them and Back
        // returns to the profile from either. See the nav graph's popUpTo.
        binding.authCreateAccount.setOnClickListener {
            // `isEnabled = false` stops a *touch*, and only a touch:
            // View.performClick runs the listener whatever the flag says, which is
            // the path an accessibility action takes and the path a test takes. So
            // the guard is here as well as on the view - the view state is what a
            // listener sees, and this is what is actually true.
            if (viewModel.isBusy) return@setOnClickListener
            findNavController().navigate(R.id.action_auth_sign_in_to_auth_create_account)
        }

        binding.authContinueAsGuest.setOnClickListener {
            if (viewModel.isBusy) return@setOnClickListener
            findNavController().popBackStack()
        }

        // A push rather than a swap, unlike the create-account link: recovery is an
        // errand inside signing in, so Back from it returns to this form with whatever
        // was typed in it. See the nav graph.
        binding.authForgotPassword.setOnClickListener {
            if (viewModel.isBusy) return@setOnClickListener
            findNavController().navigate(R.id.action_auth_sign_in_to_auth_recovery)
        }

        viewModel.state.observe(viewLifecycleOwner) { render(it) }

        return binding.root
    }

    private fun submit() {
        viewModel.signIn(
            binding.authEmail.text?.toString().orEmpty(),
            binding.authPassword.text?.toString().orEmpty(),
        )
    }

    /**
     * One state in, one screen out.
     *
     * Every branch is set on every pass, including back to its resting value. A
     * render that only turned things on would leave the previous attempt's error
     * under the new one's spinner.
     */
    private fun render(state: AuthFormState) {
        binding.authEmailError.setInlineError(state.emailError)
        binding.authPasswordError.setInlineError(state.passwordError)
        binding.authFormError.setInlineError(state.formError)

        val idle = !state.loading

        // The label and the indicator share the button's centre, so this swaps one
        // for the other without moving the button or anything below it.
        binding.authSubmitLabel.visibility = if (idle) View.VISIBLE else View.INVISIBLE
        binding.authSubmitProgress.visibility = if (idle) View.GONE else View.VISIBLE

        binding.authSubmit.isEnabled = idle
        binding.authEmail.isEnabled = idle
        binding.authPassword.isEnabled = idle

        // Anything that could start a second request or leave mid-request is closed,
        // and `Назад` deliberately is not: an unresponsive screen with no way out is
        // worse than a cancelled request, and the identity layer survives a process
        // death at any point in this call - a cancelled coroutine is strictly less.
        binding.authCreateAccount.isEnabled = idle
        binding.authContinueAsGuest.isEnabled = idle
        binding.authCreateAccount.isClickable = idle
        binding.authContinueAsGuest.isClickable = idle
        binding.authForgotPassword.isEnabled = idle
        binding.authForgotPassword.isClickable = idle

        if (state.succeeded) {
            // Consume first. The identity is already committed by the repository, so
            // this only decides whether the navigation happens twice.
            viewModel.consumeSuccess()
            // Not popBackStack: that would land on the guest profile, which is now
            // false about this listener - the exact state G-A5a exists to remove. The
            // action replaces this screen and the guest profile both.
            findNavController().navigate(R.id.action_auth_sign_in_to_profile_authenticated)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
