package com.example.musicplayerapp.data.report

import android.content.Context
import com.example.musicplayerapp.SecureNetModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * How a report reaches us.
 *
 * An interface with one production implementation, because the whole flow -
 * validation, the in-flight state, the error banner that keeps what was typed,
 * the retry and the success screen - has to be testable without a live endpoint,
 * and G3 ships before one exists. `FakeReportTransport` in androidTest is the
 * other implementation.
 */
interface ReportTransport {

    /** Sends [payload]. Returns [Result.Sent] only when the report is genuinely delivered. */
    suspend fun send(payload: ReportPayload): Result

    sealed interface Result {
        /** The endpoint accepted the report **and** the onward delivery succeeded. */
        data object Sent : Result

        /**
         * Anything else - offline, DNS, TLS, timeout, a non-2xx, a 2xx whose body
         * says the onward delivery failed.
         *
         * One failure rather than a taxonomy, because the frozen design has one
         * error state and one retry. [detail] is for logcat during development and
         * never reaches the screen; the listener sees the frozen sentence.
         */
        data class Failed(val detail: String) : Result
    }
}

/**
 * The real transport: one form POST to [ReportConfig.endpoint].
 *
 * ## Why a POST and not a mail or share intent
 *
 * The frozen design has a `report-sending` state, a `report-error` banner that
 * preserves what the listener typed, and a `report-success` screen. Handing the
 * message to another app produces none of the three - the app would never learn
 * whether anything was sent, and all three screens would be claims it cannot
 * make. `report-success`'s own frame note settles it: "The app posts to our own
 * endpoint."
 *
 * ## Why this shape
 *
 * Form-encoded UTF-8 over the shared [SecureNetModule] client, which is the exact
 * call `FeedbackRepository` has been making to a Google Apps Script Web App for
 * reactions since long before this. That is the precedent this reuses; what it
 * does **not** reuse is the reactions deployment itself - reports carry free text
 * and diagnostics and go to their own endpoint.
 *
 * Unlike the reactions call this one is **awaited**, not fire-and-forget: the
 * listener is watching a spinner that has to resolve into either the success
 * screen or the error banner.
 *
 * ## No credentials, in either direction
 *
 * No Authorization header, no `apikey`, no cookie, no Supabase client anywhere on
 * this path. The Telegram bot token the endpoint eventually uses lives in that
 * endpoint's server-side storage and never in this app - a token in an APK is
 * extractable by anyone who downloads it, and would let a stranger post as the
 * bot and read its updates.
 *
 * ## Success means delivered
 *
 * A 2xx alone is not success. Apps Script answers 200 to almost anything,
 * including its own uncaught exceptions, so the endpoint contract is that the body
 * carries `"ok": true` only after the onward Telegram send has itself succeeded,
 * and [ReportAck] checks for exactly that. Without it a Telegram outage would show
 * the listener the terminal "Спасибо!" screen for a message nobody received.
 */
class HttpReportTransport(
    context: Context,
    private val client: OkHttpClient = SecureNetModule.getOkHttpClient(context),
) : ReportTransport {

    override suspend fun send(payload: ReportPayload): ReportTransport.Result =
        withContext(Dispatchers.IO) {
            if (!ReportConfig.isConfigured) {
                // Unreachable from the UI - both entry points are absent in an
                // unconfigured build - and still handled, because "the form opened
                // anyway" must not become a request to a URL that is not one.
                return@withContext ReportTransport.Result.Failed("no endpoint configured")
            }

            val body = FormBody.Builder(Charsets.UTF_8).apply {
                payload.fields().forEach { (name, value) -> add(name, value) }
            }.build()

            val request = Request.Builder()
                .url(ReportConfig.endpoint)
                .addHeader("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .post(body)
                .build()

            runCatching {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@use ReportTransport.Result.Failed("http ${response.code}")
                    }
                    // Bounded read: the endpoint's answer is a word, and a proxy or a
                    // captive portal can put a whole page here.
                    val answer = response.body?.source()?.let { source ->
                        source.request(MAX_ANSWER_BYTES.toLong())
                        source.buffer.snapshot(
                            minOf(source.buffer.size, MAX_ANSWER_BYTES.toLong()).toInt()
                        ).utf8()
                    }.orEmpty()

                    if (ReportAck.accepted(answer)) {
                        ReportTransport.Result.Sent
                    } else {
                        // A 200 whose body does not say the onward send worked. The
                        // listener must see the error state, not a false thank-you.
                        // See ReportAck - matching on the key rather than the value
                        // made every `{"ok":false}` read as a success.
                        ReportTransport.Result.Failed("unacknowledged")
                    }
                }
            }.getOrElse { t ->
                // Offline, DNS, TLS, timeout - all one failure to the listener, and
                // the type name only, never the message: an exception message from a
                // library we do not control can carry a URL or a host.
                ReportTransport.Result.Failed(t.javaClass.simpleName)
            }
        }

    private companion object {
        const val MAX_ANSWER_BYTES = 512
    }
}
