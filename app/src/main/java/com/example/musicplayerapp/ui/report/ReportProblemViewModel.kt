package com.example.musicplayerapp.ui.report

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.musicplayerapp.data.report.HttpReportTransport
import com.example.musicplayerapp.data.report.ReportCategory
import com.example.musicplayerapp.data.report.ReportDiagnostics
import com.example.musicplayerapp.data.report.ReportPayload
import com.example.musicplayerapp.data.report.ReportTransport
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The four states `Сообщить о проблеме` can be in.
 *
 * One value rather than several flags, for the reason `AuthFormState` gives: the
 * states are mutually exclusive, and the one that matters most here - "an error
 * banner is showing **and** everything the listener typed is still there" - is
 * only safe if a new attempt cannot half-clear the last one.
 */
data class ReportFormState(

    /** Nothing is preselected. This is what keeps Send disabled on `report-empty`. */
    val category: ReportCategory? = null,

    /** Optional, and never a gate. */
    val message: String = "",

    /** A request is in flight: `report-sending`. */
    val sending: Boolean = false,

    /**
     * The last attempt failed: `report-error`.
     *
     * A boolean and not a message. The frozen banner has exactly one sentence, and
     * a taxonomy of failures on that line would be an invention - the listener can
     * do the same thing about a timeout as about a 500.
     */
    val failed: Boolean = false,

    /** Delivered: `report-success`. Terminal - the form is not returned to. */
    val sent: Boolean = false,
) {
    /** `Отправить` is enabled by a category alone, and never while a send is running. */
    val canSend: Boolean get() = category != null && !sending && !sent
}

/**
 * Owns the one report attempt.
 *
 * ## Why the attempt lives here
 *
 * The same three requirements `AuthViewModel` was built for apply, and one more:
 *
 *  - a double tap on `Отправить` must not post two reports
 *  - a rotation mid-send must not post a second one, and must come back to the
 *    spinner rather than to a form that looks idle
 *  - the error state must still hold the chosen category and the typed words,
 *    which is the one thing `report-error`'s frame note insists on: "The chosen
 *    category and the typed description are preserved. Nothing is cleared on
 *    failure."
 *
 * All four are answered by the in-flight [Job] and the form fields living
 * somewhere that outlives the view.
 *
 * ## Diagnostics are read once, at send time
 *
 * Not when the screen opens. The card is a promise about what is *about to be*
 * sent, so the values are collected in [send] and the same [ReportDiagnostics.Snapshot]
 * is both posted and - through [diagnostics] - drawn. A snapshot taken at open
 * time would show a network state, or a "last error", that had since changed.
 *
 * The card the listener reads before tapping is rendered from a preview snapshot
 * taken on open; if anything moved between the two, the report carries the newer
 * values and the difference is at most one line of a diagnostic. What cannot
 * happen is the payload carrying a *field* the card never showed - both come from
 * the same [ReportDiagnostics.Snapshot] type, and `ReportPayloadTest` holds it.
 */
class ReportProblemViewModel(application: Application) : AndroidViewModel(application) {

    /**
     * Swapped in instrumentation, which must exercise sending, failure, retry and
     * success without a live endpoint - and G3 ships before one exists.
     */
    var transport: ReportTransport = HttpReportTransport(application)

    private val _state = MutableLiveData(ReportFormState())
    val state: LiveData<ReportFormState> = _state

    /** The six lines the card draws, refreshed when the screen opens. */
    private val _diagnostics = MutableLiveData<ReportDiagnostics.Snapshot>()
    val diagnostics: LiveData<ReportDiagnostics.Snapshot> = _diagnostics

    private var inFlight: Job? = null

    /** Which stream the report is about. Set by the fragment from the player's state. */
    private var streamKey: String? = null

    fun refreshDiagnostics(streamKey: String?) {
        this.streamKey = streamKey
        _diagnostics.value = ReportDiagnostics.collect(getApplication(), streamKey)
    }

    /**
     * Chooses a category, or re-chooses the same one.
     *
     * Selecting is not deselecting: tapping the chosen row again leaves it chosen.
     * The frozen form has no "no category" state to return to once one is picked,
     * and a listener who tapped twice meant to confirm rather than to disable the
     * button they were reaching for.
     */
    fun select(category: ReportCategory) {
        val current = _state.value ?: ReportFormState()
        if (current.sending || current.sent) return
        _state.value = current.copy(category = category)
    }

    fun message(text: String) {
        val current = _state.value ?: ReportFormState()
        if (current.sending || current.sent) return
        if (current.message == text) return
        _state.value = current.copy(message = text)
    }

    /**
     * Sends, once.
     *
     * The guard is the live [Job] rather than the `sending` flag alone: the flag is
     * set on the main thread by the same statement that starts the coroutine, so
     * the two agree, and checking the job as well means a second tap that somehow
     * arrives before the state has been observed still cannot start a second post.
     *
     * A missing category returns without doing anything. The button is disabled in
     * that state, so this is unreachable through the UI - and it is checked anyway,
     * because "the button was disabled" is a claim about a view and this is where
     * the rule actually lives.
     */
    fun send() {
        val current = _state.value ?: ReportFormState()
        val category = current.category ?: return
        if (current.sending || current.sent) return
        if (inFlight?.isActive == true) return

        // Fresh, at send time - see the class comment.
        val snapshot = ReportDiagnostics.collect(getApplication(), streamKey)
        _diagnostics.value = snapshot

        // `failed` clears here rather than on the result, so the banner goes the
        // moment the retry starts instead of sitting over a running spinner.
        _state.value = current.copy(sending = true, failed = false)

        inFlight = viewModelScope.launch {
            val payload = ReportPayload(
                category = category,
                message = current.message,
                diagnostics = snapshot,
            )
            val result = transport.send(payload)

            val afterwards = _state.value ?: current
            _state.value = when (result) {
                is ReportTransport.Result.Sent ->
                    afterwards.copy(sending = false, failed = false, sent = true)

                is ReportTransport.Result.Failed ->
                    // Everything the listener chose and typed is carried through
                    // untouched. This copy is the whole of `report-error`'s promise.
                    afterwards.copy(sending = false, failed = true, sent = false)
            }
            inFlight = null
        }
    }
}
