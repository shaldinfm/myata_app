package com.example.musicplayerapp.ui.profile

import android.app.Application
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.musicplayerapp.R
import com.example.musicplayerapp.data.supabase.AccountDeletion
import com.example.musicplayerapp.data.supabase.AccountDeletionResult
import com.example.musicplayerapp.data.supabase.DeletionStage
import com.example.musicplayerapp.data.supabase.IdentityStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The one request the delete-account row can make, and what the screen shows around it.
 *
 * Modelled on [com.example.musicplayerapp.ui.auth.AuthViewModel], deliberately: the
 * three properties that matter here are the three that one already solves - a single
 * request at a time, a loading state a rotation cannot lose, and no exception escaping
 * `viewModelScope`. What is different is the stakes. An auth request that fires twice
 * costs a duplicate round trip; a deletion that fires twice would mint a second
 * request token for one deletion and orphan the first, whose receipt is the only thing
 * that can resolve it if a response is lost.
 *
 * ## Why the guard is the job, not the button
 *
 * [inFlight] is asked before anything else, exactly as the auth form does it. A
 * disabled button is a fact about a view, and a view is rebuilt by a rotation while
 * the coroutine underneath carries on - so the disabled state is how this *looks*
 * correct and the job is how it *is* correct.
 *
 * ## What it does not do
 *
 * No retry loop, no scheduling, no state repair. `AccountDeletion` owns the ordering
 * and `IdentityReconciler` owns everything that outlives this screen; this is a
 * button, a spinner and five outcomes.
 */
class DeleteAccountViewModel(application: Application) : AndroidViewModel(application) {

    /**
     * What the row and its dialogs render.
     *
     * @property loading a request is running: the row is disabled and shows its inline
     *   spinner.
     * @property outcome the result to act on, once. Null until one arrives, and set
     *   back to null by [consumeOutcome] so a rotation does not replay a navigation or
     *   a message the listener has already seen.
     */
    data class State(
        val loading: Boolean = false,
        val outcome: Outcome? = null,
    )

    /**
     * The five results, reduced to what the screen actually has to do.
     *
     * The mapping lives here rather than in the fragment because it is the part worth
     * asserting: which outcomes may claim success, which may offer a retry, and which
     * must leave the listener on a screen that still says they have an account.
     *
     * **No internal detail crosses this boundary.** `Refused` carries a SQLSTATE and
     * `Unresolved` a failure description; neither reaches a string a person reads.
     */
    sealed interface Outcome {

        /**
         * Gone, and this device has finished with it. The identity is already `None`.
         *
         * The only outcome that may say so - `CleanupDeferred` is a confirmed server
         * deletion whose local half is unfinished, and reporting that as complete
         * would claim something nobody has checked.
         */
        data object Deleted : Outcome

        /**
         * The account is gone; this device is still clearing up.
         *
         * Leaves the authenticated screen, because the account really has been
         * deleted, but says nothing about being finished and offers no retry: there
         * is nothing left to delete, and the reconciler completes the cleanup.
         */
        data object CleanupDeferred : Outcome

        /**
         * No definitive answer. **Not a failure.**
         *
         * The deletion may well have committed with its response lost - that is the
         * case the receipt exists for - so the listener is moved to the pending guest
         * presentation rather than told to try again. A retry here would be a second
         * token for one deletion.
         */
        data object Unresolved : Outcome

        /**
         * The server refused and nothing was deleted, provably.
         *
         * The install is untouched and the deletion marker has already been retracted
         * by the orchestrator, so the row simply becomes available again.
         */
        data object Refused : Outcome

        /** A precondition was not met. Nothing was attempted and no marker was written. */
        data object NotEligible : Outcome
    }

    private val _state = MutableLiveData(State())
    val state: LiveData<State> get() = _state

    /** The request in flight, or null. The double-submit guard. */
    private var inFlight: Job? = null

    /** True while a request is running. Read by tests; the screen reads [state]. */
    val isBusy: Boolean get() = inFlight?.isActive == true

    /**
     * Asks for the account to be deleted. At most one at a time.
     *
     * Returns immediately and silently when a request is already running - the second
     * tap of a double-tap, or the same button pressed again after a rotation rebuilt
     * the dialog. Nothing is queued: one deletion is one request.
     */
    fun delete() {
        if (isBusy) return

        _state.value = State(loading = true)

        inFlight = viewModelScope.launch {
            try {
                // Off the main thread, and not optionally. Everything under
                // AccountDeletion writes SharedPreferences with commit(), runs Room
                // transactions and makes a network call.
                val result = withContext(Dispatchers.IO) {
                    AccountDeletion.request(getApplication())
                }
                _state.value = State(outcome = map(result))
            } catch (cancellation: CancellationException) {
                // The screen is going away. Rethrown after the finally below, so the
                // scope's own bookkeeping stays intact.
                throw cancellation
            } catch (failure: Exception) {
                // Anything the layers below did not turn into a result. Reported as
                // the generic unavailable message rather than allowed to escape:
                // an exception out of viewModelScope takes the process with it, and
                // this row would be left spinning on the way down.
                //
                // `Exception` and not `Throwable`, for the reason AuthViewModel gives:
                // an `Error` means the process or the build is already broken, and
                // hiding that behind "попробуйте позже" helps nobody.
                Log.w(TAG, "account deletion failed unexpectedly: ${failure.javaClass.simpleName}")
                _state.value = State(outcome = outcomeAfterFailure())
            } finally {
                inFlight = null
            }
        }
    }

    /**
     * Marks the current outcome as acted on.
     *
     * Without it a rotation would re-deliver the same value and navigate, or show the
     * same message, a second time.
     */
    fun consumeOutcome() {
        if (_state.value?.outcome != null) _state.value = State()
    }

    /**
     * What an unexpected exception means, decided by the durable marker rather than by
     * the exception.
     *
     * **The marker is authoritative and the exception is not.** By the time anything
     * can throw, the orchestrator may already have committed `REQUESTED` - it is
     * written before the destructive call, deliberately - or `CONFIRMED`, after the
     * server confirmed the deletion. Reporting either as "not available, try later"
     * would tell somebody their account is intact and the row is safe to press again,
     * when in fact the install is sync-dead and a second press would mint a second
     * token for one deletion.
     *
     * So the failure is mapped to what is actually owed:
     *
     * | marker | outcome |
     * |---|---|
     * | none | [Outcome.NotEligible] - nothing was started, the row is safe again |
     * | `REQUESTED` | [Outcome.Unresolved] - outcome unknown, no retry, pending screen |
     * | `CONFIRMED` | [Outcome.CleanupDeferred] - the account is gone, cleanup is owed |
     *
     * Nothing about the exception itself reaches the user; only which of the three
     * states this install is in.
     */
    private fun outcomeAfterFailure(): Outcome =
        when (IdentityStore.deletion(getApplication())?.stage) {
            null -> Outcome.NotEligible
            DeletionStage.REQUESTED -> Outcome.Unresolved
            DeletionStage.CONFIRMED -> Outcome.CleanupDeferred
        }

    private fun map(result: AccountDeletionResult): Outcome = when (result) {
        is AccountDeletionResult.Deleted -> Outcome.Deleted
        is AccountDeletionResult.CleanupDeferred -> Outcome.CleanupDeferred
        is AccountDeletionResult.Unresolved -> Outcome.Unresolved
        is AccountDeletionResult.Refused -> Outcome.Refused
        is AccountDeletionResult.NotEligible -> Outcome.NotEligible
    }

    private companion object {
        const val TAG = "SupabaseAuth"
    }
}

/**
 * The message an outcome shows, or null when it navigates instead of speaking.
 *
 * Kept beside the outcome rather than in the fragment so the copy decisions are
 * assertable without inflating a screen: which outcomes are allowed to sound like
 * success, and which must not.
 */
@StringRes
fun DeleteAccountViewModel.Outcome.message(): Int? = when (this) {
    DeleteAccountViewModel.Outcome.Deleted -> R.string.delete_account_done
    DeleteAccountViewModel.Outcome.Refused -> R.string.delete_account_refused
    DeleteAccountViewModel.Outcome.NotEligible -> R.string.delete_account_unavailable

    // Both leave the screen for the guest presentation, which says everything there is
    // to say about them. A toast on top of it would either duplicate that copy or -
    // worse - phrase an unresolved deletion as an error.
    DeleteAccountViewModel.Outcome.CleanupDeferred,
    DeleteAccountViewModel.Outcome.Unresolved,
    -> null
}

/** Whether this outcome leaves the authenticated profile. */
fun DeleteAccountViewModel.Outcome.leavesTheAccountScreen(): Boolean = when (this) {
    DeleteAccountViewModel.Outcome.Deleted,
    DeleteAccountViewModel.Outcome.CleanupDeferred,
    DeleteAccountViewModel.Outcome.Unresolved,
    -> true

    // The account still exists and this install still owns it.
    DeleteAccountViewModel.Outcome.Refused,
    DeleteAccountViewModel.Outcome.NotEligible,
    -> false
}
