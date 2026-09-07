package com.example.musicplayerapp.data.report

/**
 * Whether the endpoint's answer says the report was actually delivered.
 *
 * ## Why this is its own thing, and tested on its own
 *
 * The whole reason the client reads the body at all is that a 200 is not evidence:
 * Apps Script answers 200 to almost anything, including its own uncaught
 * exceptions, and the success screen is terminal - a listener shown "Спасибо!" for
 * a message nobody received has no way to send it again.
 *
 * The first version of this check looked for the substring `"ok"`, which is
 * present in **every** answer the endpoint can give:
 *
 *     {"ok":true}                            -> delivered
 *     {"ok":false,"error":"rate_limited"}    -> NOT delivered
 *     {"ok":false,"error":"send_failed"}     -> NOT delivered
 *
 * So every server-side failure read as a success, which is the exact failure the
 * design was written to prevent, and it would have made the forced-failure
 * validation gate pass while proving nothing. The check is now the value, not the
 * key, and it lives here so it can be tested against those answers directly rather
 * than only through a live endpoint.
 */
object ReportAck {

    /**
     * True only for an answer that says `ok` is `true`.
     *
     * Whitespace-tolerant, because the pretty-printer on the other side is not
     * this client's business: `{"ok":true}`, `{"ok": true}` and `{ "ok" : true }`
     * are the same answer. Everything else - `ok:false`, a truncated body, an HTML
     * error page, a captive-portal interstitial, an empty response - is not a
     * delivery, and the listener sees `report-error`.
     */
    fun accepted(body: String): Boolean = DELIVERED.containsMatchIn(body)

    private val DELIVERED = Regex("\"ok\"\\s*:\\s*true")
}
