package com.example.musicplayerapp.data.report

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one line that decides whether a listener is thanked or asked to retry.
 *
 * Most of this file is negative, deliberately. The forced-failure validation gate
 * depends entirely on the negative half: if any `{"ok":false}` read as a success,
 * that gate would pass while proving the opposite of what it exists to prove -
 * which is exactly what the first two implementations of this check did.
 *
 * Every case below is either an answer `tools/report-endpoint/Code.gs` can produce
 * or a shape something between the app and it can produce, so this is the client
 * half of the endpoint contract checked without a live endpoint.
 */
class ReportAckTest {

    // ==================== the one accepted answer ====================

    @Test
    fun `a delivered report is accepted`() {
        assertTrue(ReportAck.accepted("""{"ok":true}"""))
    }

    @Test
    fun `formatting of the same answer does not change it`() {
        assertTrue(ReportAck.accepted("""{"ok": true}"""))
        assertTrue(ReportAck.accepted("""{ "ok" : true }"""))
        assertTrue(ReportAck.accepted("{\n  \"ok\": true\n}"))
        assertTrue(ReportAck.accepted("""  {"ok":true}  """))
    }

    @Test
    fun `other top-level fields alongside ok are ignored`() {
        assertTrue(ReportAck.accepted("""{"ok":true,"service":"myata-report","version":1}"""))
        assertTrue(ReportAck.accepted("""{"service":"myata-report","ok":true}"""))
    }

    // ==================== every failure the endpoint returns ====================

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

    // ==================== the type of `ok` is part of the contract ====================

    /**
     * A quoted `"true"` is a different contract, not the same one loosely written.
     * `booleanOrNull` alone would accept it, which is why the primitive's own
     * string-ness is checked.
     */
    @Test
    fun `a stringified boolean is not a boolean`() {
        assertFalse(ReportAck.accepted("""{"ok":"true"}"""))
        assertFalse(ReportAck.accepted("""{"ok":"True"}"""))
        assertFalse(ReportAck.accepted("""{"ok":"1"}"""))
    }

    @Test
    fun `a number is not a boolean`() {
        assertFalse(ReportAck.accepted("""{"ok":1}"""))
        assertFalse(ReportAck.accepted("""{"ok":0}"""))
        assertFalse(ReportAck.accepted("""{"ok":1.0}"""))
    }

    @Test
    fun `null and a missing field are both failures`() {
        assertFalse(ReportAck.accepted("""{"ok":null}"""))
        assertFalse(ReportAck.accepted("""{}"""))
        assertFalse(ReportAck.accepted("""{"error":"send_failed"}"""))
        assertFalse(ReportAck.accepted("""{"okay":true}"""))
        assertFalse(ReportAck.accepted("""{"OK":true}"""))
    }

    @Test
    fun `a composite value is not a boolean`() {
        assertFalse(ReportAck.accepted("""{"ok":{"ok":true}}"""))
        assertFalse(ReportAck.accepted("""{"ok":[true]}"""))
    }

    // ==================== structural, not textual ====================

    /**
     * The regression the structural parse was made for.
     *
     * `{"result":{"ok":true}}` is the shape of Telegram's **own** reply, so any
     * text match would read a proxied or echoed upstream response as our
     * endpoint's acknowledgement. Only the top level counts.
     */
    @Test
    fun `a nested ok is not our endpoint's answer`() {
        assertFalse(ReportAck.accepted("""{"result":{"ok":true}}"""))
        assertFalse(ReportAck.accepted("""{"ok":false,"telegram":{"ok":true}}"""))
        assertFalse(ReportAck.accepted("""{"data":{"nested":{"ok":true}}}"""))
    }

    @Test
    fun `text that merely contains the answer is not the answer`() {
        for (body in listOf(
            """<html><body>{"ok":true}</body></html>""",
            """// {"ok":true}""",
            """the endpoint would have said {"ok":true} if it were reachable""",
            """<pre>{"ok":true}</pre>""",
        )) {
            assertFalse("`$body` is not a JSON answer", ReportAck.accepted(body))
        }
    }

    @Test
    fun `a top-level array is not an answer`() {
        assertFalse(ReportAck.accepted("""[{"ok":true}]"""))
        assertFalse(ReportAck.accepted("""[true]"""))
    }

    @Test
    fun `a bare literal is not an answer`() {
        assertFalse(ReportAck.accepted("true"))
        assertFalse(ReportAck.accepted("\"ok\""))
        assertFalse(ReportAck.accepted("1"))
        assertFalse(ReportAck.accepted("null"))
    }

    // ==================== fail closed ====================

    @Test
    fun `malformed json is a failure`() {
        for (body in listOf(
            """{"ok":true""",
            """{"ok":truthy}""",
            """{ok:true}""",
            """{"ok":true,}""",
            """}{""",
            """{"ok":tru""",
        )) {
            assertFalse("`$body` must fail closed", ReportAck.accepted(body))
        }
    }

    @Test
    fun `an empty or blank body is a failure`() {
        assertFalse(ReportAck.accepted(""))
        assertFalse(ReportAck.accepted("   "))
        assertFalse(ReportAck.accepted("\n\t "))
    }

    /**
     * The transport reads a bounded prefix, so an oversized body arrives cut. A cut
     * body is malformed, and malformed fails closed - the same answer by a
     * different route, and the right one either way.
     */
    @Test
    fun `a truncated body is a failure`() {
        val long = """{"padding":"${"x".repeat(600)}","ok":true}"""
        assertFalse(ReportAck.accepted(long.take(512)))
    }

    @Test
    fun `an html error page is a failure`() {
        for (body in listOf(
            "<html><head><title>Error</title></head><body>500</body></html>",
            "Moved Temporarily",
            "<!DOCTYPE html><h1>Sign in to the network</h1>",
        )) {
            assertFalse("`$body` must fail closed", ReportAck.accepted(body))
        }
    }
}
