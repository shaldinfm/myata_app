package com.example.musicplayerapp.ui.profile

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.example.musicplayerapp.data.supabase.AccountInfo
import com.example.musicplayerapp.data.supabase.AccountRefresh
import com.example.musicplayerapp.data.supabase.KnownAccount
import com.example.musicplayerapp.data.supabase.AuthFailure
import com.example.musicplayerapp.data.supabase.AvatarUpdateResult
import com.example.musicplayerapp.data.supabase.EmailAuthBackend
import com.example.musicplayerapp.data.supabase.IdentityState
import com.example.musicplayerapp.data.supabase.IdentityStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The avatar picker's state, scoped to the fragment so a rotation neither loses the
 * ringed cell nor starts a second save.
 *
 * The choice is also kept in [SavedStateHandle]: a process death while the picker is
 * open brings the listener back to the cell they had ringed, not to the account's.
 */
class ProfileAvatarViewModel(
    application: Application,
    private val handle: SavedStateHandle,
) : AndroidViewModel(application) {

    data class State(
        /** Null until the account has been read. The grid stays inert until then. */
        val account: AccountInfo? = null,
        val selected: String? = null,
        val saving: Boolean = false,
        val outcome: Outcome? = null,
    )

    sealed interface Outcome {
        /** Nothing is left to do here: saved, or nothing needed saving. Return to the profile. */
        data object Done : Outcome

        /** The write did not happen. The choice stays ringed so Save can be tried again. */
        data object SaveFailed : Outcome

        /** This install is not the account the profile showed. The profile sorts that out. */
        data object NotAnAccount : Outcome
    }

    private val _state = MutableLiveData(State())
    val state: LiveData<State> get() = _state

    private var loading: Job? = null
    private var inFlight: Job? = null

    /**
     * Reads the account the picker is choosing for.
     *
     * The same questions the authenticated profile asks, without its reconciliation:
     * that screen has just run it, and this one only needs to know the session is
     * still the registered identity - which is also the condition for writing to it.
     */
    fun load() {
        if (_state.value?.account != null || loading?.isActive == true) return

        loading = viewModelScope.launch {
            val account = try {
                withContext(Dispatchers.IO) { registeredAccount() }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                Log.w(TAG, "account read failed: ${failure.javaClass.simpleName}")
                null
            }

            if (account == null) {
                _state.value = State(outcome = Outcome.NotAnAccount)
                return@launch
            }
            val selected = AvatarSelection.initial(account.avatarId, handle[KEY_SELECTED])
            _state.value = State(account = account, selected = selected)
        }
    }

    /** Rings [key] and moves the preview to it. Nothing is written. */
    fun select(key: String) {
        val current = _state.value ?: return
        if (current.account == null || current.saving) return
        val avatar = ProfileAvatars.resolve(key) ?: return
        handle[KEY_SELECTED] = avatar.key
        _state.value = current.copy(selected = avatar.key)
    }

    /** `Сохранить`. At most one request at a time, however often it is tapped. */
    fun save() {
        val current = _state.value ?: return
        val account = current.account ?: return
        if (current.saving || inFlight?.isActive == true) return

        if (!AvatarSelection.needsWrite(account.avatarId, current.selected)) {
            _state.value = current.copy(outcome = Outcome.Done)
            return
        }
        val chosen = current.selected ?: return

        _state.value = current.copy(saving = true, outcome = null)
        inFlight = viewModelScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    // Asked again at the moment of writing: a sign-out on another
                    // screen, or a session that ended while this one sat open, must
                    // not become a write to whichever session happens to be live.
                    val still = registeredAccount()
                    if (still == null || still.uid != account.uid) null
                    // Under the refresh lock: a refresh that started before this save cannot
                    // land after it and store the pre-save user back in the session.
                    else AccountRefresh.write { EmailAuthBackend.api(getApplication()).updateAvatar(chosen) }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                Log.w(TAG, "avatar save failed unexpectedly: ${failure.javaClass.simpleName}")
                AvatarUpdateResult.Failed(AuthFailure.Unknown(detail = failure.javaClass.simpleName))
            } finally {
                inFlight = null
            }

            val latest = _state.value ?: current
            _state.value = when (result) {
                null -> latest.copy(saving = false, outcome = Outcome.NotAnAccount)
                is AvatarUpdateResult.Updated -> {
                    KnownAccount.avatarSaved(account.uid, result.avatarId)
                    latest.copy(
                        account = account.copy(avatarId = result.avatarId),
                        saving = false,
                        outcome = Outcome.Done,
                    )
                }
                is AvatarUpdateResult.Failed -> {
                    Log.w(TAG, "avatar not saved: ${result.failure.javaClass.simpleName}")
                    latest.copy(saving = false, outcome = Outcome.SaveFailed)
                }
            }
        }
    }

    /** Marks the current outcome handled, so a rotation does not replay a toast or a pop. */
    fun consumeOutcome() {
        val current = _state.value ?: return
        if (current.outcome != null) _state.value = current.copy(outcome = null)
    }

    private suspend fun registeredAccount(): AccountInfo? {
        val context = getApplication<Application>()
        val registered = IdentityStore.state(context) as? IdentityState.Registered ?: return null
        val account = EmailAuthBackend.api(context).currentAccount() ?: return null
        return account.takeIf { it.uid == registered.uid }
    }

    private companion object {
        const val TAG = "ProfileAvatar"
        const val KEY_SELECTED = "selected_avatar"
    }
}
