package com.example.musicplayerapp.data.report

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

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
 * Two earlier versions of this check were wrong in the same direction, which is
 * why it is now structural:
 *
 *  1. **Substring `"ok"`.** Present in every answer the endpoint can give, so
 *     every `{"ok":false}` read as a delivered report.
 *  2. **Regex `"ok"\s*:\s*true`.** Better, but still text matching: it accepts the
 *     string anywhere in the body, including inside a nested object
 *     (`{"result":{"ok":true}}` - which is the shape of Telegram's *own* reply, so
 *     a proxy or a future change that echoed it would read as success) and inside
 *     an HTML error page that merely contains those characters.
 *
 * So the body is now **parsed**, and only a genuine top-level boolean `true`
 * counts. Everything else - a nested `ok`, a quoted `"true"`, a numeric `1`, a
 * missing field, a JSON array, malformed JSON, an empty body, an HTML captive
 * portal - is not a delivery, and the listener sees `report-error`.
 *
 * ## Fail closed
 *
 * Every uncertainty resolves to "not delivered". Being wrong that way costs the
 * listener one retry on a message that did arrive; being wrong the other way
 * loses the report silently and tells them it was received. Those are not
 * comparable, so the parse is wrapped and any failure is a failure.
 *
 * The caller reads a bounded prefix of the response, so a body too large to fit
 * arrives truncated and therefore malformed - which fails closed for the same
 * reason. The real answer is eleven bytes.
 *
 * ## No new dependency
 *
 * `kotlinx.serialization.json` is already on the classpath through the Supabase
 * client and already used in `src/main`. It is also a real JVM library rather
 * than an `android.jar` stub, which `org.json` is - under this project's
 * `returnDefaultValues = true` a stubbed `JSONObject` would quietly return
 * defaults and these tests would prove nothing.
 */
object ReportAck {

    /**
     * True only when the body is a JSON object whose top-level `ok` is the boolean
     * `true`.
     *
     * [JsonPrimitive.isString] is checked as well as the value, because
     * `booleanOrNull` does not distinguish the literal `true` from the string
     * `"true"` on its own, and a server that started quoting its booleans would be
     * a different contract rather than the same one.
     */
    fun accepted(body: String): Boolean = runCatching {
        val ok = (Json.parseToJsonElement(body) as? JsonObject)?.get("ok")
        ok is JsonPrimitive && !ok.isString && ok.booleanOrNull == true
    }.getOrDefault(false)
}
