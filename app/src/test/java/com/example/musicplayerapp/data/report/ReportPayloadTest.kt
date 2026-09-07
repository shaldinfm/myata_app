package com.example.musicplayerapp.data.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a report contains - and, at greater length, what it does not.
 *
 * The privacy half of this file is the reason it exists. The diagnostics card
 * tells the listener "Личные данные не отправляются.", and that sentence is only
 * true if nothing can quietly join the payload later. Comments do not enforce it;
 * these assertions do, against the actual serialised fields.
 */
class ReportPayloadTest {

    private val diagnostics = ReportDiagnostics.Snapshot(
        appVersion = "3.6.5 (202611)",
        device = "Xiaomi Redmi Note 12",
        android = "14 (API 34)",
        network = "Wi-Fi",
        lastError = "ERROR_CODE_IO_BAD_HTTP_STATUS, 2 мин назад",
        stream = "MYATA",
    )

    private fun payload(
        category: ReportCategory = ReportCategory.STOPPED_BY_ITSELF,
        message: String = "звук пропал",
    ) = ReportPayload(category, message, diagnostics)

    // ==================== the schema ====================

    @Test
    fun `the payload is exactly eight fields`() {
        assertEquals(
            listOf(
                "category", "message",
                "app_version", "device", "android", "network", "last_error", "stream",
            ),
            payload().fields().keys.toList(),
        )
    }

    @Test
    fun `the category travels as its wire key, never as its label`() {
        assertEquals("stopped_by_itself", payload().fields()["category"])
    }

    /**
     * Absent and empty must not be two ways of saying the same thing: the endpoint
     * would then have two shapes of request to handle for one state of the form.
     */
    @Test
    fun `an empty description is sent as an empty field rather than omitted`() {
        val fields = payload(message = "").fields()
        assertTrue(fields.containsKey("message"))
        assertEquals("", fields["message"])
    }

    @Test
    fun `the description is sent verbatim apart from its edges`() {
        val typed = "Играло минут двадцать, потом звук пропал,\nкнопка стала «play» сама."
        assertEquals(typed, payload(message = "  $typed  ").fields()["message"])
    }

    @Test
    fun `an over-long description is capped rather than refused`() {
        val long = "я".repeat(ReportPayload.MAX_MESSAGE_LENGTH + 500)
        val sent = payload(message = long).fields()["message"]!!
        assertEquals(ReportPayload.MAX_MESSAGE_LENGTH, sent.length)
    }

    @Test
    fun `every diagnostics value on the card is on the wire`() {
        val fields = payload().fields()
        assertEquals("3.6.5 (202611)", fields["app_version"])
        assertEquals("Xiaomi Redmi Note 12", fields["device"])
        assertEquals("14 (API 34)", fields["android"])
        assertEquals("Wi-Fi", fields["network"])
        assertEquals("ERROR_CODE_IO_BAD_HTTP_STATUS, 2 мин назад", fields["last_error"])
        assertEquals("MYATA", fields["stream"])
    }

    /**
     * The card and the payload cannot disagree.
     *
     * Both are projections of one [ReportDiagnostics.Snapshot]. This asserts the
     * arity - six values drawn, six values posted - so a seventh field added to the
     * wire without a seventh line on the card fails here rather than shipping as a
     * value the listener was never shown.
     */
    @Test
    fun `the wire carries one value per line of the card and no more`() {
        assertEquals(6, diagnostics.wire().size)
        val automatic = payload().fields().keys - setOf("category", "message")
        assertEquals(
            "every automatic field must have a line on the diagnostics card",
            diagnostics.wire().keys, automatic,
        )
    }

    // ==================== the privacy contract ====================

    /**
     * The forbidden list, asserted against the serialised body rather than against
     * the class.
     *
     * Field names first: nothing on this list may ever be a key, whatever it holds.
     */
    @Test
    fun `no credential, identity or device-identifier field exists`() {
        val forbidden = listOf(
            // credentials
            "token", "access_token", "refresh_token", "apikey", "api_key",
            "authorization", "auth", "bearer", "password", "session", "cookie",
            "jwt", "secret", "key",
            // identity
            "uid", "user_id", "userid", "email", "display_name", "name", "avatar",
            "account",
            // device identifiers
            "android_id", "androidid", "serial", "imei", "ad_id", "advertising_id",
            "gaid", "install_id", "fcm", "push_token",
            // location and network identity
            "lat", "lon", "latitude", "longitude", "location", "ssid", "bssid",
            "ip", "ip_address", "operator", "carrier",
            // dumps
            "log", "logs", "logcat", "stacktrace", "stack_trace", "trace",
            "exception", "cause",
        )
        val keys = payload().fields().keys.map { it.lowercase() }
        val leaked = keys.filter { key -> forbidden.any { it == key } }
        assertTrue("a forbidden field is being posted: $leaked", leaked.isEmpty())
    }

    /**
     * And the values, because a safe key can still carry an unsafe value.
     *
     * The listener's own words are exempt: they are the point of the form, and a
     * listener who chooses to type their address into a free-text field has
     * disclosed it deliberately. Everything else is checked.
     */
    @Test
    fun `no automatic value looks like a credential, an address or a url`() {
        val automatic = payload(message = "").fields() - setOf("category", "message")
        for ((key, value) in automatic) {
            assertFalse("$key must not carry an email address", value.contains("@"))
            assertFalse("$key must not carry a url", value.contains("://"))
            assertFalse("$key must not carry a bearer token", value.contains("Bearer", true))
            assertFalse("$key must not carry a supabase key", value.contains("sb_"))
            assertFalse("$key must not carry a jwt", value.startsWith("eyJ"))
        }
    }

    /**
     * A signed-in listener and a guest post the same envelope.
     *
     * Structural rather than incidental: [ReportPayload] takes a category, a string
     * and a diagnostics snapshot, and there is no parameter an identity could
     * arrive through. This states it as a test so that adding one is a visible
     * change to this file rather than a quiet one to a data class.
     */
    @Test
    fun `the payload has no way to carry an identity`() {
        val constructorParams = ReportPayload::class.constructors.first().parameters.map { it.name }
        assertEquals(listOf("category", "message", "diagnostics"), constructorParams)
    }
}
