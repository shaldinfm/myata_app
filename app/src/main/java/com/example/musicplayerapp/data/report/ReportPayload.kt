package com.example.musicplayerapp.data.report

/**
 * Everything a problem report contains, and nothing else.
 *
 * ## Two halves, kept apart on purpose
 *
 * **User-authored** - [category] and [message]. The listener chose one and typed
 * the other, and neither is collected behind their back.
 *
 * **Automatic** - [diagnostics], which is exactly the six values drawn on the
 * `Что будет отправлено` card. See [ReportDiagnostics] for the list and for why
 * each one cannot identify anybody.
 *
 * The split is not cosmetic. It is what lets the card be checked against the
 * payload mechanically: everything in the automatic half must appear on the card,
 * and the user half is on the screen by definition because the user put it there.
 *
 * ## The message
 *
 * Sent verbatim - it is the listener's own words and the whole point of the form -
 * but capped at [MAX_MESSAGE_LENGTH]. The cap is not a privacy control; it stops a
 * pasted novel becoming a request no endpoint will accept, and the field enforces
 * the same number so the cap can never surprise anyone at send time.
 *
 * ## What can never be added here
 *
 * No access or refresh token, `apikey` or Authorization header; no `auth.uid()`,
 * email, display name or avatar; no Collection, reaction or listening-history
 * contents; no `ANDROID_ID`, serial, IMEI or advertising id; no SSID, IP or
 * location; no logcat, stack trace or exception message. `ReportPayloadTest`
 * asserts every one of those against the serialised form rather than trusting
 * this comment, and the report path never constructs a Supabase client, so a
 * signed-in and a signed-out listener post byte-identical envelopes apart from
 * their own words.
 */
data class ReportPayload(
    val category: ReportCategory,
    val message: String,
    val diagnostics: ReportDiagnostics.Snapshot,
) {

    /**
     * The exact form fields the endpoint receives.
     *
     * `LinkedHashMap`, so the order is stable and a diff of two reports is
     * readable. `message` is always present, empty when the listener typed
     * nothing - an absent field and an empty one would otherwise be two ways of
     * saying the same thing on the server.
     */
    fun fields(): Map<String, String> = linkedMapOf(
        "category" to category.wire,
        "message" to message.trim().take(MAX_MESSAGE_LENGTH),
    ) + diagnostics.wire()

    companion object {
        const val MAX_MESSAGE_LENGTH = 2000
    }
}
