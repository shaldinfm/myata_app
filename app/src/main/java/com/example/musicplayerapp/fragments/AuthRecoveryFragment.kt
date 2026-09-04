package com.example.musicplayerapp.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.example.musicplayerapp.R
import com.example.musicplayerapp.databinding.FragmentAuthRecoveryBinding
import com.example.musicplayerapp.ui.auth.RecoveryFormState
import com.example.musicplayerapp.ui.auth.RecoveryStage
import com.example.musicplayerapp.ui.auth.RecoveryViewModel
import com.example.musicplayerapp.ui.auth.applyAuthInsets
import com.example.musicplayerapp.ui.auth.setInlineError

/**
 * auth-recovery (G-A4c2): the three states of getting back into an account.
 *
 * One destination, because the three are one errand. Splitting them across three
 * fragments would put the address on a back stack and make "which mailbox was asked"
 * a navigation argument, when it is really one value owned by one ViewModel.
 *
 * ## It calls the repository and knows nothing else
 *
 * Like the other two auth screens, this does not read `IdentityStore`, does not know
 * whether this install is anonymous, and does not know that verifying a code from an
 * anonymous install performs an X-to-Y handoff. That is settled below by G-A4b1 and
 * G-A4b2 and routed by `EmailAuthRepository.verifyRecoveryCode` exactly as a sign-in is.
 *
 * ## This is the one auth screen that refuses Back mid-request
 *
 * auth-sign-in deliberately leaves `Назад` live while a request runs: cancelling a
 * sign-in costs a round trip, and the identity layer survives a process death at any
 * point in it. Recovery is not that. Its CODE submit is **two** calls, and between them
 * the account has already been handed to this install:
 *
 * ```
 * verifyRecoveryCode  -> session established, identity committed as Registered(Y)
 * updatePassword      -> the password the listener actually came here to change
 * ```
 *
 * A Back in that window cancels the coroutine after the first has committed and before
 * the second has run, leaving somebody signed in with the password they could not
 * remember and no screen explaining it. So every route out - the band control, the
 * system Back button and the predictive-back gesture, which all arrive through
 * `OnBackPressedDispatcher` - is closed for exactly as long as a request is in flight.
 * The button already shows why: it is the same spinner as everywhere else.
 *
 * This does not make the window disappear; it removes the one cause of it the app
 * controls. See the residual-crash-window note in `docs/SUPABASE-FOUNDATION.md`.
 */
class AuthRecoveryFragment : Fragment() {

    private var _binding: FragmentAuthRecoveryBinding? = null
    private val binding get() = _binding!!

    private val viewModel: RecoveryViewModel by viewModels()

    /**
     * Swallows Back while a request is in flight.
     *
     * Registered against `viewLifecycleOwner`, so it is gone with the view, and its
     * `isEnabled` is driven by the rendered state rather than by a flag of its own -
     * one source of truth for "busy", the same one the spinner reads.
     */
    private val backWhileBusy = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            // Deliberately nothing. The gesture is consumed and the screen stays.
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentAuthRecoveryBinding.inflate(inflater, container, false)

        applyAuthInsets(binding.authRoot, binding.authScroll)

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backWhileBusy)

        binding.authBack.setOnClickListener { leave() }
        binding.authSubmit.setOnClickListener { submit() }

        // Done on the last field of each stage submits, which is what an IME with an
        // "actionDone" key promises.
        binding.authEmail.setOnEditorActionListener { _, actionId, _ -> onDone(actionId) }
        binding.authRecoveryPassword.setOnEditorActionListener { _, actionId, _ -> onDone(actionId) }

        viewModel.state.observe(viewLifecycleOwner) { render(it) }

        return binding.root
    }

    private fun onDone(actionId: Int): Boolean =
        if (actionId == EditorInfo.IME_ACTION_DONE) {
            submit()
            true
        } else {
            false
        }

    /**
     * The band's `Назад`, and every other way out.
     *
     * Refused outright while busy - the same rule the dispatcher callback enforces for
     * the system gesture, stated here as well because `isEnabled = false` stops a touch
     * and only a touch: `View.performClick` runs the listener whatever the flag says,
     * which is the path an accessibility action takes and the path a test takes.
     */
    private fun leave() {
        if (viewModel.isBusy) return

        val state = viewModel.state.value ?: return

        // A mistyped address is fixable without leaving, and only while the code has
        // not yet been accepted.
        if (state.stage == RecoveryStage.CODE && !state.codeAccepted) {
            viewModel.backToRequest()
            return
        }

        // Everything else pops. When the code *has* been accepted this install is
        // already Registered(Y), and popping lands on the guest profile - which reads
        // the persisted identity and forwards to the account card by itself. No
        // special case here, because ProfileRoute already owns that decision.
        findNavController().popBackStack()
    }

    private fun submit() {
        val state = viewModel.state.value ?: return

        when (state.stage) {
            RecoveryStage.REQUEST ->
                viewModel.requestCode(binding.authEmail.text?.toString().orEmpty())

            RecoveryStage.CODE -> viewModel.submitNewPassword(
                binding.authRecoveryCode.text?.toString().orEmpty(),
                binding.authRecoveryPassword.text?.toString().orEmpty(),
            )

            // The stage is its own completion signal: there is no separate "succeeded"
            // event to consume, so pressing the button here is simply the navigation.
            RecoveryStage.DONE -> {
                if (viewModel.isBusy) return
                findNavController().navigate(R.id.action_auth_recovery_to_profile_authenticated)
            }
        }
    }

    /**
     * One state in, one screen out.
     *
     * Every branch is set on every pass, including back to its resting value. A render
     * that only turned things on would leave the previous attempt's error under the new
     * one's spinner.
     */
    private fun render(state: RecoveryFormState) {
        val request = state.stage == RecoveryStage.REQUEST
        val code = state.stage == RecoveryStage.CODE
        val done = state.stage == RecoveryStage.DONE

        binding.authRecoveryRequestGroup.visibility = if (request) View.VISIBLE else View.GONE
        binding.authRecoveryCodeGroup.visibility = if (code) View.VISIBLE else View.GONE
        binding.authRecoveryDoneGroup.visibility = if (done) View.VISIBLE else View.GONE

        binding.authEmailError.setInlineError(state.emailError)
        binding.authRecoveryCodeError.setInlineError(state.codeError)
        binding.authRecoveryPasswordError.setInlineError(state.passwordError)
        binding.authFormError.setInlineError(state.formError)

        // The rule and its error are the same sentence, so the error replaces the hint
        // rather than joining it - auth-create-account's rule, for its reason.
        binding.authRecoveryPasswordRule.visibility =
            if (state.passwordError == null) View.VISIBLE else View.GONE

        binding.authSubmitLabel.setText(
            when (state.stage) {
                RecoveryStage.REQUEST -> R.string.auth_recovery_request_action
                RecoveryStage.CODE -> R.string.auth_recovery_save_action
                RecoveryStage.DONE -> R.string.auth_recovery_done_action
            }
        )

        val idle = !state.loading

        binding.authSubmitLabel.visibility = if (idle) View.VISIBLE else View.INVISIBLE
        binding.authSubmitProgress.visibility = if (idle) View.GONE else View.VISIBLE

        binding.authSubmit.isEnabled = idle
        binding.authEmail.isEnabled = idle
        binding.authRecoveryCode.isEnabled = idle && !state.codeAccepted
        binding.authRecoveryPassword.isEnabled = idle

        // The band control follows the same rule as the system gesture below it, so the
        // screen never shows a way out it will not honour.
        binding.authBack.isEnabled = idle
        binding.authBack.isClickable = idle
        backWhileBusy.isEnabled = state.loading
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
