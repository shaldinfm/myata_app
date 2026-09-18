package com.example.musicplayerapp.ui.lastfm

import android.app.Application
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.musicplayerapp.R
import com.example.musicplayerapp.data.lastfm.LastfmAuth
import com.example.musicplayerapp.data.lastfm.LastfmLink
import kotlinx.coroutines.launch

/**
 * Something the screen has to do once: open the browser, or say something briefly.
 */
sealed class LastfmEvent {

    data class OpenBrowser(val url: String) : LastfmEvent() {
        /** The URL carries the request token. */
        override fun toString(): String = "OpenBrowser(<redacted>)"
    }

    data class Message(@param:StringRes val text: Int) : LastfmEvent()
}

/**
 * The Last.fm screen's state, derived from what is on disk.
 *
 * ## The store is the source of truth, not this ViewModel
 *
 * Every state is recomputed from `lastfm_session` via [LastfmAuth.link] - on
 * creation, on resume and after every action. So a recreated screen, a restored
 * back stack after process death, or a return from the browser all land on the
 * right card with nothing to save or restore here. The only thing held in memory is
 * the playcount, which is deliberately never persisted.
 *
 * ## One lookup per account per ViewModel
 *
 * `user.getInfo` runs when the screen shows a linked account whose count it has not
 * read yet - which covers both opening the screen and the moment an exchange
 * succeeds, through one path. No startup fetch, no Settings-row fetch. A failed
 * lookup leaves the account linked and the count hidden.
 *
 * ## Busy
 *
 * The auth rules already serialise on a process-wide lock; [busy] only stops a
 * second tap from queueing a second browser launch behind the first.
 */
class LastfmViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableLiveData<LastfmCardState>()
    val state: LiveData<LastfmCardState> = _state

    private val _event = MutableLiveData<LastfmEvent?>()
    val event: LiveData<LastfmEvent?> = _event

    private var busy = false

    /** The count, and the username it belongs to - never shown for anyone else. */
    private var playcount: Long? = null
    private var playcountFor: String? = null
    private var fetchingFor: String? = null

    init {
        publish()
    }

    /** The screen resumed: finish a pending authorisation if there is one. */
    fun onResume() {
        if (busy) {
            publish()
            return
        }
        launchAction {
            when (auth().resume()) {
                LastfmAuth.Resume.Unreachable, LastfmAuth.Resume.Refused -> say(R.string.lastfm_error_network)
                // Linked, still pending (14), expired, restart (4/15), idle: the card
                // itself says what happened, and 14 in particular is not an error.
                else -> Unit
            }
        }
    }

    /** The card's filled (or, when connected, outlined) button. */
    fun onPrimaryAction() {
        if (busy) return
        when (current()) {
            LastfmLink.NotLinked, is LastfmLink.ReauthRequired -> launchAction { open(auth().begin()) }
            is LastfmLink.Pending -> launchAction { open(auth().openOrBegin()) }
            is LastfmLink.Linked -> launchAction { auth().disconnect() }
        }
    }

    /** The second button, present only on the pending and re-auth cards. */
    fun onSecondaryAction() {
        if (busy) return
        when (current()) {
            is LastfmLink.Pending -> launchAction { auth().cancelPending() }
            is LastfmLink.ReauthRequired -> launchAction { auth().disconnect() }
            else -> Unit
        }
    }

    /** The browser could not be started. The pending token stays for another try. */
    fun onBrowserUnavailable() = say(R.string.lastfm_error_no_browser)

    /** The screen has handled [event]. */
    fun consumeEvent() {
        _event.value = null
    }

    private fun open(result: LastfmAuth.Begin) {
        when (result) {
            is LastfmAuth.Begin.OpenBrowser -> _event.value = LastfmEvent.OpenBrowser(result.url)
            LastfmAuth.Begin.Unreachable, LastfmAuth.Begin.Refused -> say(R.string.lastfm_error_network)
            // The row is hidden in a build without credentials, so this screen is not
            // reachable there; if it somehow is, there is nothing to say that helps.
            LastfmAuth.Begin.NotConfigured -> Log.w(TAG, "connect tapped in a build with no Last.fm credentials")
        }
    }

    private fun launchAction(block: suspend () -> Unit) {
        busy = true
        viewModelScope.launch {
            try {
                block()
            } finally {
                busy = false
                publish()
            }
        }
    }

    private fun publish() {
        val link = current()
        if (link is LastfmLink.Linked) {
            if (playcountFor != link.username) {
                playcount = null
                fetchPlaycount(link.username)
            }
        } else {
            playcount = null
            playcountFor = null
        }
        val shown = if (link is LastfmLink.Linked && playcountFor == link.username) playcount else null
        _state.value = LastfmScreenState.cardFor(link, shown)
    }

    private fun fetchPlaycount(username: String) {
        if (fetchingFor == username) return
        fetchingFor = username
        viewModelScope.launch {
            val count = auth().fetchPlaycount(username)
            fetchingFor = null
            // Only for the account that is still linked - a disconnect or another
            // account while the lookup was in flight must not inherit this number.
            val link = current()
            if (link is LastfmLink.Linked && link.username == username) {
                playcount = count
                playcountFor = username
                _state.value = LastfmScreenState.cardFor(link, count)
            }
        }
    }

    private fun say(@StringRes text: Int) {
        _event.value = LastfmEvent.Message(text)
    }

    private fun current(): LastfmLink = auth().link()

    /** Fresh per use, so a test's fake transport applies at once; the lock is shared. */
    private fun auth(): LastfmAuth = LastfmAuth.forContext(getApplication())

    private companion object {
        const val TAG = "Lastfm"
    }
}
