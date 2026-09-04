package com.example.musicplayerapp.ui.auth

import android.app.Application
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.musicplayerapp.R
import com.example.musicplayerapp.data.supabase.EmailAuthRepository
import com.example.musicplayerapp.data.supabase.RecoveryResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which of auth-recovery's three states the screen is in. */
enum class RecoveryStage {

    /** Ask for an address. Nothing has been proved and nothing durable is written. */
    REQUEST,

    /** The code from the mail, and the password to replace the old one. */
    CODE,

    /** The password is set and this install is signed in. */
    DONE,
}

/**
 * Everything auth-recovery renders, and the address it is recovering.
 *
 * @property requestedEmail the **normalised** address that reached [RecoveryStage.CODE],
 *   and the only address [RecoveryViewModel.submitNewPassword] will ever send. It is
 *   held here rather than read back off the `EditText` at submit time because that
 *   field belongs to a view: the layout hides the request group at CODE, a rotation
 *   rebuilds every view from scratch, and an address recovered from a rebuilt field is
 *   either stale or empty. The server decides whose session the code buys; this decides
 *   which mailbox was asked, and the two have to agree.
 * @property codeAccepted the recovery code has been exchanged for a session. Once true
 *   the code is spent and must never be sent again - see the class comment.
 */
data class RecoveryFormState(
    val stage: RecoveryStage = RecoveryStage.REQUEST,
    val loading: Boolean = false,
    val requestedEmail: String? = null,
    val codeAccepted: Boolean = false,

    @StringRes val emailError: Int? = null,
    @StringRes val codeError: Int? = null,
    @StringRes val passwordError: Int? = null,

    /** Remote failures, which belong to the form rather than to any one field. */
    @StringRes val formError: Int? = null,
)

/**
 * auth-recovery's one request at a time, and the stage machine around it.
 *
 * Shaped like [AuthViewModel] - one in-flight [Job] that outlives the view, a loading
 * flag a rotation cannot lose, and no exception escaping `viewModelScope` - because the
 * three properties that matter are the same three. What is different is that recovery
 * is three calls across two stages, and two of them run under one tap.
 *
 * ## The two-call submit, and why the code is only ever sent once
 *
 * The CODE stage runs `verifyRecoveryCode` and then `updatePassword`. They fail
 * independently, and the second failing is not a reason to redo the first: verifying a
 * recovery OTP **consumes** it, so re-sending the same code would be refused and the
 * listener told their correct code is wrong - while already holding a session for the
 * account. So a successful verification sets [RecoveryFormState.codeAccepted], and
 * every retry from there runs `updatePassword` alone.
 *
 * ## What this deliberately does not do
 *
 * It does not touch `IdentityStore`, does not know whether this install is anonymous,
 * and does not know that verifying a code from an anonymous install performs an X-to-Y
 * handoff. `EmailAuthRepository.verifyRecoveryCode` routes that exactly as it routes a
 * sign-in, and a screen reaching in to help would be a second implementation of the
 * most delicate code in the app.
 *
 * There is also **no completion event**. The screen is finished when
 * [RecoveryFormState.stage] is [RecoveryStage.DONE] and the listener presses the button
 * on it; a one-shot "succeeded" flag beside the stage would be a second source of truth
 * for one fact, and the two could disagree after a rotation.
 */
class RecoveryViewModel(application: Application) : AndroidViewModel(application) {

    private companion object {
        const val TAG = "SupabaseAuth"
    }

    private val _state = MutableLiveData(RecoveryFormState())
    val state: LiveData<RecoveryFormState> get() = _state

    /**
     * The request in flight, or null.
     *
     * The double-submit guard, and - unlike the two auth forms - also what Back is
     * refused by. See `AuthRecoveryFragment` for why recovery is the one screen that
     * cannot be left mid-request.
     */
    private var inFlight: Job? = null

    /** True while a request is running. Read by the fragment and by tests. */
    val isBusy: Boolean get() = inFlight?.isActive == true

    private val current: RecoveryFormState get() = _state.value ?: RecoveryFormState()

    /**
     * Asks for a recovery mail.
     *
     * On success the screen advances to [RecoveryStage.CODE] **whether or not the
     * address has an account**, because the answer this gets back does not know either
     * - see `RecoveryResult.Requested`. Nothing here, and nothing the screen shows
     * afterwards, varies with account existence.
     */
    fun requestCode(rawEmail: String) {
        if (isBusy) return

        if (!AuthInput.isEmailValid(rawEmail)) {
            _state.value = current.copy(
                loading = false,
                emailError = R.string.auth_error_email_format,
                formError = null,
            )
            return
        }

        val email = AuthInput.email(rawEmail)

        _state.value = current.copy(loading = true, emailError = null, formError = null)

        inFlight = launchGuarded {
            val result = withContext(Dispatchers.IO) {
                EmailAuthRepository.requestPasswordReset(getApplication(), email)
            }

            _state.value = when (result) {
                // The address is recorded here, at the one moment it is known to be the
                // address a mail was asked for.
                is RecoveryResult.Requested -> current.copy(
                    stage = RecoveryStage.CODE,
                    loading = false,
                    requestedEmail = email,
                )

                is RecoveryResult.Failed -> current.copy(
                    loading = false,
                    formError = authFailureMessage(result.failure),
                )

                // Neither of the other two is reachable from this call: they are the
                // shapes the other two recovery steps succeed in.
                else -> current.copy(loading = false, formError = R.string.auth_error_unknown)
            }
        }
    }

    /**
     * Exchanges the code for a session, then sets the password.
     *
     * The address is [RecoveryFormState.requestedEmail] and never a parameter - the
     * caller supplies only what the listener typed on this stage.
     */
    fun submitNewPassword(rawCode: String, rawPassword: String) {
        if (isBusy) return

        val email = current.requestedEmail
        if (email == null) {
            // Unreachable: CODE is only entered by a successful request, which is the
            // one place the address is written. Reported rather than ignored, and it
            // returns the listener to the stage that can fix it.
            Log.w(TAG, "recovery reached CODE with no requested address")
            _state.value = RecoveryFormState(formError = R.string.auth_error_unknown)
            return
        }

        val codeError =
            if (AuthInput.isCodePresent(rawCode)) null else R.string.auth_error_code_blank
        val passwordError =
            if (AuthInput.isPasswordLongEnough(rawPassword)) null
            else R.string.auth_error_password_short

        if (codeError != null || passwordError != null) {
            _state.value = current.copy(
                loading = false,
                codeError = codeError,
                passwordError = passwordError,
                formError = null,
            )
            return
        }

        val code = AuthInput.code(rawCode)

        _state.value = current.copy(
            loading = true,
            codeError = null,
            passwordError = null,
            formError = null,
        )

        inFlight = launchGuarded {
            // Step one, and only if the code has not already been spent.
            if (!current.codeAccepted) {
                val verified = withContext(Dispatchers.IO) {
                    EmailAuthRepository.verifyRecoveryCode(getApplication(), email, code)
                }

                when (verified) {
                    is RecoveryResult.PasswordResetAuthorized ->
                        // Committed to the state before the second call, so a failure
                        // there cannot cost the listener their one code.
                        _state.value = current.copy(codeAccepted = true)

                    is RecoveryResult.Failed -> {
                        _state.value = current.copy(
                            loading = false,
                            codeError = authFailureMessage(verified.failure),
                        )
                        return@launchGuarded
                    }

                    else -> {
                        _state.value = current.copy(
                            loading = false,
                            formError = R.string.auth_error_unknown,
                        )
                        return@launchGuarded
                    }
                }
            }

            // Step two. From here this install already holds a session for the account,
            // so a failure is reported without ever suggesting the password changed.
            val updated = withContext(Dispatchers.IO) {
                EmailAuthRepository.updatePassword(getApplication(), rawPassword)
            }

            _state.value = when (updated) {
                is RecoveryResult.PasswordUpdated ->
                    current.copy(stage = RecoveryStage.DONE, loading = false)

                is RecoveryResult.Failed -> current.copy(
                    loading = false,
                    passwordError = authFailureMessage(updated.failure),
                )

                else -> current.copy(loading = false, formError = R.string.auth_error_unknown)
            }
        }
    }

    /**
     * Back from [RecoveryStage.CODE] to [RecoveryStage.REQUEST], for a mistyped address.
     *
     * Refused once the code has been accepted: by then this install is signed in as the
     * recovered account, and offering to go back and try another address would describe
     * something that can no longer happen.
     */
    fun backToRequest() {
        if (isBusy) return
        if (current.codeAccepted) return
        if (current.stage != RecoveryStage.CODE) return

        _state.value = current.copy(
            stage = RecoveryStage.REQUEST,
            codeError = null,
            passwordError = null,
            formError = null,
        )
    }

    /**
     * One recovery submission, and no more than one.
     *
     * `Dispatchers.IO` is taken at each call site rather than around the whole block:
     * the state writes between the two steps are `LiveData.setValue` and belong on the
     * main thread, while everything under the repository writes `SharedPreferences` with
     * `commit()`, opens Room and talks to a network.
     */
    private fun launchGuarded(block: suspend () -> Unit): Job = viewModelScope.launch {
        try {
            block()
        } catch (cancellation: CancellationException) {
            // The ViewModel is being cleared. Rethrown so the scope's own bookkeeping
            // stays intact. Note the fragment refuses Back while a request is in flight,
            // so this is no longer the ordinary way out of this screen.
            throw cancellation
        } catch (failure: Exception) {
            // Anything the layers below did not turn into a RecoveryResult. Reported as
            // the general message rather than allowed to escape: an exception out of
            // viewModelScope takes the process with it and would leave this screen
            // spinning on the way down.
            //
            // `Exception` and not `Throwable`, for the reason AuthViewModel gives: an
            // `Error` means the process or the build is already broken, and hiding that
            // behind a "try again later" helps nobody.
            Log.w(TAG, "recovery step failed unexpectedly: ${failure.javaClass.simpleName}")
            _state.value = current.copy(loading = false, formError = R.string.auth_error_unknown)
        } finally {
            inFlight = null
        }
    }
}
