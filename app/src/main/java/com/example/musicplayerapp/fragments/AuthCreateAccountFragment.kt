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
import com.example.musicplayerapp.databinding.FragmentAuthCreateAccountBinding
import com.example.musicplayerapp.ui.auth.AuthFormState
import com.example.musicplayerapp.ui.auth.AuthViewModel
import com.example.musicplayerapp.ui.auth.applyAuthInsets
import com.example.musicplayerapp.ui.auth.setInlineError

/**
 * auth-create-account 2517:2624 / 2517:3591.
 *
 * The same integration as [AuthSignInFragment] and the same rules: one repository
 * call, no knowledge of identities, nothing cleared, nothing minted. Three fields
 * instead of two, and `Имя` goes to `user_metadata.display_name` - there is no
 * profiles table and no uniqueness rule, so two listeners called the same thing is
 * not a problem anybody needs solving.
 *
 * ## Registration sends no mail
 *
 * A successful `Создать аккаунт` returns a session immediately, because the project
 * runs with Confirm Email off. There is therefore no "check your inbox" screen after
 * this one and deliberately no place for one: if the setting ever drifts, the domain
 * layer reports `SessionNotEstablished` and the listener is told the attempt failed
 * rather than sent to a mailbox with nothing in it.
 */
class AuthCreateAccountFragment : Fragment() {

    private var _binding: FragmentAuthCreateAccountBinding? = null
    private val binding get() = _binding!!

    private val viewModel: AuthViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentAuthCreateAccountBinding.inflate(inflater, container, false)

        applyAuthInsets(binding.authRoot, binding.authScroll)

        binding.authBack.setOnClickListener { findNavController().popBackStack() }

        binding.authSubmit.setOnClickListener { submit() }

        binding.authPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else {
                false
            }
        }

        binding.authHaveAccount.setOnClickListener {
            // `isEnabled = false` stops a *touch*, and only a touch:
            // View.performClick runs the listener whatever the flag says, which is
            // the path an accessibility action takes and the path a test takes. So
            // the guard is here as well as on the view - the view state is what a
            // listener sees, and this is what is actually true.
            if (viewModel.isBusy) return@setOnClickListener
            findNavController().navigate(R.id.action_auth_create_account_to_auth_sign_in)
        }

        viewModel.state.observe(viewLifecycleOwner) { render(it) }

        return binding.root
    }

    private fun submit() {
        viewModel.register(
            binding.authName.text?.toString().orEmpty(),
            binding.authEmail.text?.toString().orEmpty(),
            binding.authPassword.text?.toString().orEmpty(),
        )
    }

    private fun render(state: AuthFormState) {
        binding.authNameError.setInlineError(state.nameError)
        binding.authEmailError.setInlineError(state.emailError)
        binding.authPasswordError.setInlineError(state.passwordError)
        binding.authFormError.setInlineError(state.formError)

        // `Минимум 8 символов` is the resting hint, and the password error says the
        // same sentence in red. Showing both stacks one rule twice and reads like a
        // bug, so the error replaces the hint rather than joining it - which also
        // keeps the row count, and therefore everything below, exactly where the
        // frame puts it.
        binding.authPasswordRule.visibility =
            if (state.passwordError == null) View.VISIBLE else View.GONE

        val idle = !state.loading

        binding.authSubmitLabel.visibility = if (idle) View.VISIBLE else View.INVISIBLE
        binding.authSubmitProgress.visibility = if (idle) View.GONE else View.VISIBLE

        binding.authSubmit.isEnabled = idle
        binding.authName.isEnabled = idle
        binding.authEmail.isEnabled = idle
        binding.authPassword.isEnabled = idle

        binding.authHaveAccount.isEnabled = idle
        binding.authHaveAccount.isClickable = idle

        if (state.succeeded) {
            viewModel.consumeSuccess()
            // Not popBackStack: that would land on the guest profile, which is now
            // false about this listener - the exact state G-A5a exists to remove. The
            // action replaces this screen and the guest profile both.
            findNavController().navigate(R.id.action_auth_create_account_to_profile_authenticated)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
