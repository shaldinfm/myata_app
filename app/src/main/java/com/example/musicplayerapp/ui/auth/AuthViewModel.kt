package com.example.musicplayerapp.ui.auth

import android.app.Application
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.musicplayerapp.R
import com.example.musicplayerapp.data.supabase.AuthResult
import com.example.musicplayerapp.data.supabase.EmailAuthRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Everything the two auth screens display, as one value.
 *
 * A single immutable state rather than four independent fields, because the states
 * are mutually exclusive in ways separate flags would let drift: a form cannot be
 * loading *and* showing an error, and a new attempt must clear every message from the
 * last one. Replacing the whole value makes that structural instead of remembered.
 *
 * The error fields are string resources, not strings. That keeps the ViewModel free
 * of a Context for text, keeps the mapping testable as data, and means a
 * configuration change re-resolves the message in whatever locale is now current.
 */
data class AuthFormState(

    /** A request is in flight. Every input and every action is inert while true. */
    val loading: Boolean = false,

    @StringRes val nameError: Int? = null,
    @StringRes val emailError: Int? = null,
    @StringRes val passwordError: Int? = null,

    /** Remote failures, which belong to the form rather than to any one field. */
    @StringRes val formError: Int? = null,

    /**
     * The identity is committed and the screen is done.
     *
     * Survives a configuration change on purpose: if the process is rotated in the
     * instant between the repository returning and the fragment navigating, the new
     * fragment sees this and finishes the job. It is cleared by [consumeSuccess] the
     * moment a fragment acts on it, so it can only be acted on once.
     */
    val succeeded: Boolean = false,
)

/**
 * The auth screens' single owner of a request in flight.
 *
 * ## Why a ViewModel and not a coroutine in the fragment
 *
 * Three of this PR's requirements are the same requirement seen from different
 * angles - a double tap must not produce two accounts, a rotation must not produce
 * two accounts, and a screen that is showing a spinner must be showing one because a
 * request really is running. All three are answered by the in-flight [Job] living
 * somewhere that outlives the view, and a fragment does not.
 *
 * ## It knows nothing about identities
 *
 * It calls [EmailAuthRepository] once and waits for a typed result. It does not
 * touch `IdentityStore`, does not know whether this install is anonymous, does not
 * know that an anonymous one performs a handoff, and does not clear a Collection or
 * a reaction. All of that is settled below it by G-A4b1 and G-A4b2, and a screen
 * that reached in to help would be a second implementation of the most delicate
 * logic in the app.
 *
 * ## Cancellation is safe
 *
 * Backing out mid-request cancels [viewModelScope] and with it whatever the
 * repository was doing. That is deliberate rather than tolerated: the domain layer
 * is built to survive a *process death* at any point in the same call - a durable
 * attempt marker before the remote request, a durable handoff stage before the first
 * destructive one - and a cancelled coroutine is strictly less damaging than a
 * killed process. Whatever was interrupted is repaired by `IdentityReconciler` at
 * the next start.
 */
class AuthViewModel(application: Application) : AndroidViewModel(application) {

    private companion object {
        const val TAG = "SupabaseAuth"
    }

    private val _state = MutableLiveData(AuthFormState())
    val state: LiveData<AuthFormState> get() = _state

    /**
     * The request in flight, or null.
     *
     * The double-submit guard, and it is checked rather than relying on the buttons
     * being disabled: the disabled state is a fact about a view that a rotation
     * rebuilds, while this is a fact about the work.
     */
    private var inFlight: Job? = null

    /** True while a request is running. Read by tests; the screens read [state]. */
    val isBusy: Boolean get() = inFlight?.isActive == true

    /**
     * auth-sign-in's `Войти`.
     *
     * The password is checked for presence only - see [AuthInput.isPasswordPresent]
     * for why the create-account minimum deliberately does not apply here.
     */
    fun signIn(rawEmail: String, rawPassword: String) {
        if (isBusy) return

        val emailError = if (AuthInput.isEmailValid(rawEmail)) null else R.string.auth_error_email_format
        val passwordError =
            if (AuthInput.isPasswordPresent(rawPassword)) null else R.string.auth_error_password_blank

        if (emailError != null || passwordError != null) {
            // Nothing is sent. The repository is not called at all, which is the
            // point of validating: a round trip for a value we already know is wrong
            // costs the listener time and tells them less than this does.
            _state.value = AuthFormState(emailError = emailError, passwordError = passwordError)
            return
        }

        submit { EmailAuthRepository.signIn(getApplication(), AuthInput.email(rawEmail), rawPassword) }
    }

    /** auth-create-account's `Создать аккаунт`. */
    fun register(rawName: String, rawEmail: String, rawPassword: String) {
        if (isBusy) return

        val nameError = if (AuthInput.isNameValid(rawName)) null else R.string.auth_error_name_blank
        val emailError = if (AuthInput.isEmailValid(rawEmail)) null else R.string.auth_error_email_format
        val passwordError =
            if (AuthInput.isPasswordLongEnough(rawPassword)) null else R.string.auth_error_password_short

        if (nameError != null || emailError != null || passwordError != null) {
            _state.value = AuthFormState(
                nameError = nameError,
                emailError = emailError,
                passwordError = passwordError,
            )
            return
        }

        submit {
            EmailAuthRepository.register(
                getApplication(),
                AuthInput.email(rawEmail),
                rawPassword,
                AuthInput.name(rawName),
            )
        }
    }

    /** Called by a fragment the instant it acts on [AuthFormState.succeeded]. */
    fun consumeSuccess() {
        if (_state.value?.succeeded == true) _state.value = AuthFormState()
    }

    /**
     * Runs one auth call, and no more than one.
     *
     * `Dispatchers.IO` is not optional. Everything below here writes
     * `SharedPreferences` with `commit()` - synchronous by design, because a marker
     * that `apply()` loses to a process death is the bug the whole identity model
     * exists to prevent - and it drains an outbox and talks to a network. None of
     * that belongs on the thread drawing the spinner.
     */
    private fun submit(call: suspend () -> AuthResult) {
        _state.value = AuthFormState(loading = true)

        inFlight = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { call() }

                _state.value = when (result) {
                    is AuthResult.Success -> AuthFormState(succeeded = true)
                    is AuthResult.Failed -> AuthFormState(formError = authFailureMessage(result.failure))
                }
            } catch (cancellation: CancellationException) {
                // The screen is going away - a Back mid-request, or the ViewModel
                // being cleared. There is nothing to report to a view that is being
                // destroyed, and swallowing it would break the scope's own
                // bookkeeping, so it is rethrown after the `finally` below has run.
                throw cancellation
            } catch (failure: Exception) {
                // Anything the layers below did not turn into an AuthResult: a Room
                // open that failed, a preferences write that failed, a Ktor or
                // PostgREST exception that escaped its runCatching, a bug. Reported
                // as the generic failure rather than allowed to escape, because an
                // exception escaping viewModelScope takes the whole process with it
                // and leaves this screen spinning on the way out.
                //
                // `Exception`, deliberately not `Throwable`. An `Error` - OutOfMemory,
                // StackOverflow, the NoClassDefFoundError a mis-desugared supabase-kt
                // would throw on API 24 - says the process is already broken or the
                // build is wrong, and neither is something to hide behind "попробуйте
                // ещё раз" on a sign-in form. Those keep crashing, loudly, where
                // Crashlytics can see them.
                //
                // CancellationException is caught above rather than here because on
                // the JVM it *is* an IllegalStateException, so this branch would
                // otherwise swallow every cancellation and break the scope's
                // bookkeeping. Order is load-bearing.
                Log.e(TAG, "the auth call threw instead of returning a result", failure)
                _state.value = AuthFormState(formError = R.string.auth_error_unknown)
            } finally {
                // The structural guarantee, and the reason this is a `finally` rather
                // than a line repeated in three branches: **no path may leave the
                // button spinning.** Success, failure, a throw, a cancellation, or
                // some later edit that adds a fourth outcome - all of them pass
                // through here, and any of them that has not already replaced the
                // loading state gets it cleared.
                if (_state.value?.loading == true) _state.value = AuthFormState()
            }
        }
    }
}
