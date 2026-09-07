package com.example.musicplayerapp.data.report

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one line that decides whether a listener is thanked or asked to retry.
 *
 * Every case below is an answer `tools/report-endpoint/Code.gs` can actually
 * produce, so this is the client half of the endpoint contract checked without a
 * live endpoint. The forced-failure validation gate depends entirely on the
 * negative half: if a `{"ok":false}` read as a success, that gate would pass while
 * proving the opposite of what it exists to prove.
 */
class ReportAckTest {

    @Test
    fun `a delivered report is accepted`() {
        assertTrue(ReportAck.accepted("""{"ok":true}"""))
    }

    @Test
    fun `whitespace around the value does not change the answer`() {
        assertTrue(ReportAck.accepted("""{"ok": true}"""))
        assertTrue(ReportAck.accepted("""{ "ok" : true }"""))
        assertTrue(ReportAck.accepted("{\n  \"ok\": true\n}"))
    }

    /**
     * The regression this class was written for.
     *
     * The first implementation looked for the substring `"ok"`, which is in every
     * one of these, so each of them was reported to the listener as a delivered
     * message.
     */
    @Test
    fun `every failure the endpoint can return is a failure`() {
        for (body in listOf(
            """{"ok":false}""",
            """{"ok":false,"error":"no_body"}""",
            """{"ok":false,"error":"rate_limited"}""",
            """{"ok":false,"error":"bad_category"}""",
            """{"ok":false,"error":"send_failed"}""",
            """{"ok": false, "error": "send_failed"}""",
        )) {
            assertFalse("`$body` must not read as a delivered report", ReportAck.accepted(body))
        }
    }

    /**
     * Anything that is not the endpoint answering is not a delivery either.
     *
     * A captive portal, a proxy error page and a truncated body all arrive with a
     * 200, which is why the body is read at all.
     */
    @Test
    fun `a non-answer is not a delivery`() {
        for (body in listOf(
            "",
            "   ",
            "<html><body>Sign in to the network</body></html>",
            "Moved Temporarily",
            """{"error":"boom"}""",
            """{"ok":"true"}""",
            """{"okay":true}""",
            """{"ok":truthy}""",
            // The client reads a bounded prefix, so a long page cut mid-way must
            // not be able to look like an answer either.
            """{"status":"pending","detail":"the send is queued and not yet ok""",
        )) {
            assertFalse("`$body` must not read as a delivered report", ReportAck.accepted(body))
        }
    }
}
