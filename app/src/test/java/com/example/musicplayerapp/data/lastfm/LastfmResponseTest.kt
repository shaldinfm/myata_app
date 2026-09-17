package com.example.musicplayerapp.data.lastfm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing Last.fm's JSON, including the two habits it inherited from its XML.
 *
 * The bodies here are shaped like the real ones - `#text` wrappers, string-typed
 * numbers, `@attr` - because a parser written against a tidied-up idea of the
 * format works all through development and fails on the first real response.
 *
 * No network, no credentials: every case is a literal string.
 */
class LastfmResponseTest {

    // ---- errors, which any method can answer with -------------------------------

    @Test
    fun `an error envelope is a failure carrying its action`() {
        val result = LastfmResponses.session(
            """{"error":9,"message":"Invalid session key - Please re-authenticate","links":[]}"""
        )

        assertTrue("$result", result is LastfmResult.Failure)
        val failure = result as LastfmResult.Failure
        assertEquals(9, failure.code)
        assertEquals(LastfmError.INVALID_SESSION_KEY, failure.error)
        assertEquals(LastfmAction.Reauthenticate, failure.action)
    }

    @Test
    fun `an error is recognised whatever method was called`() {
        // Last.fm returns the error envelope in place of the expected shape for every
        // method, and with a 200, which is why this feature does not branch on status
        // codes.
        listOf(
            LastfmResponses.token("""{"error":15,"message":"This token has expired"}"""),
            LastfmResponses.nowPlaying("""{"error":15,"message":"This token has expired"}"""),
            LastfmResponses.scrobbles("""{"error":15,"message":"This token has expired"}"""),
        ).forEach {
            assertTrue("$it", it is LastfmResult.Failure)
            assertEquals(LastfmAction.RestartAuthorization, (it as LastfmResult.NotOk).action)
        }
    }

    @Test
    fun `an error code sent as a string is still an error`() {
        val result = LastfmResponses.token("""{"error":"29","message":"Rate Limit Exceeded"}""")

        assertTrue("$result", result is LastfmResult.Failure)
        assertEquals(29, (result as LastfmResult.Failure).code)
    }

    @Test
    fun `an unreadable body is malformed and retried, not dropped`() {
        // In the field this is a captive portal or a proxy page far more often than a
        // protocol change, and both are transient.
        listOf(
            LastfmResponses.token("<html>Hotel WiFi sign-in</html>"),
            LastfmResponses.token(""),
            LastfmResponses.token("[1,2,3]"),
        ).forEach {
            assertTrue("$it", it is LastfmResult.Malformed)
            assertEquals(LastfmAction.Retry, (it as LastfmResult.NotOk).action)
        }
    }

    @Test
    fun `a malformed reason never quotes the body`() {
        // The auth path's bodies hold a session key, so a reason that echoed the
        // input would put one in the log.
        val result = LastfmResponses.session("""{"session":{"name":"f0ul482"}}""")

        assertTrue("$result", result is LastfmResult.Malformed)
        assertFalse((result as LastfmResult.Malformed).reason.contains("f0ul482"))
    }

    // ---- auth --------------------------------------------------------------------

    @Test
    fun `auth getToken yields the token`() {
        val result = LastfmResponses.token("""{"token":"8c2b1f4e9a0d3c7b5e6f1a2d4c8b0e3f"}""")

        assertEquals("8c2b1f4e9a0d3c7b5e6f1a2d4c8b0e3f", (result as LastfmResult.Ok).value)
    }

    @Test
    fun `auth getSession yields the username and key`() {
        val result = LastfmResponses.session(
            """{"session":{"name":"f0ul482","key":"1a2b3c4d5e6f70819a2b3c4d5e6f7081","subscriber":0}}"""
        )

        val session = (result as LastfmResult.Ok).value
        assertEquals("f0ul482", session.username)
        assertEquals("1a2b3c4d5e6f70819a2b3c4d5e6f7081", session.sessionKey)
        assertFalse(session.subscriber)
    }

    @Test
    fun `a subscriber is recognised whether the flag is a number or a string`() {
        fun subscriber(raw: String): Boolean =
            (LastfmResponses.session(
                """{"session":{"name":"n","key":"k","subscriber":$raw}}"""
            ) as LastfmResult.Ok).value.subscriber

        assertTrue(subscriber("1"))
        assertTrue(subscriber("\"1\""))
        assertFalse(subscriber("0"))
        assertFalse(subscriber("\"0\""))
    }

    @Test
    fun `a session without a key is malformed rather than a session with an empty key`() {
        // An empty session key would be stored, used, and fail as error 9 forever.
        val result = LastfmResponses.session("""{"session":{"name":"f0ul482","subscriber":0}}""")

        assertTrue("$result", result is LastfmResult.Malformed)
    }

    @Test
    fun `a session never renders its key`() {
        val session = LastfmSession("f0ul482", "1a2b3c4d5e6f70819a2b3c4d5e6f7081", false)
        val rendered = session.toString()

        assertFalse(rendered.contains("1a2b3c4d"))
        assertTrue(rendered.contains("f0ul482"))
        assertTrue(rendered.contains("redacted"))
    }

    // ---- now playing --------------------------------------------------------------

    @Test
    fun `now playing succeeds and yields nothing worth keeping`() {
        val result = LastfmResponses.nowPlaying(
            """{"nowplaying":{"artist":{"corrected":"0","#text":"BAD SUNS"},
               "track":{"corrected":"0","#text":"BACK TO ZERO"},
               "album":{"corrected":"0","#text":""},
               "albumArtist":{"corrected":"0","#text":""},
               "ignoredMessage":{"code":"0","#text":""}}}"""
        )

        assertTrue("$result", result is LastfmResult.Ok)
    }

    // ---- scrobbles ----------------------------------------------------------------

    @Test
    fun `a batch of one arrives as an object, not an array`() {
        // Last.fm's XML-to-JSON translation collapses a one-element list into an
        // object. A radio app scrobbles one track at a time almost always, so a
        // parser that assumed an array would fail on nearly every real drain while
        // passing every multi-entry test.
        val result = LastfmResponses.scrobbles(
            """{"scrobbles":{"@attr":{"accepted":1,"ignored":0},
               "scrobble":{"artist":{"corrected":"0","#text":"BAD SUNS"},
                 "track":{"corrected":"0","#text":"BACK TO ZERO"},
                 "album":{"corrected":"0","#text":"Accelerator"},
                 "timestamp":"1789672459",
                 "ignoredMessage":{"code":"0","#text":""}}}}"""
        )

        val outcome = (result as LastfmResult.Ok).value
        assertEquals(1, outcome.accepted)
        assertEquals(0, outcome.ignored)
        assertEquals(1, outcome.verdicts.size)

        val verdict = outcome.verdicts.single()
        assertEquals("BAD SUNS", verdict.artist)
        assertEquals("BACK TO ZERO", verdict.track)
        assertEquals(1789672459L, verdict.timestamp)
        assertTrue(verdict.ignored.accepted)
    }

    @Test
    fun `a batch of several arrives as an array`() {
        val result = LastfmResponses.scrobbles(
            """{"scrobbles":{"@attr":{"accepted":1,"ignored":1},
               "scrobble":[
                 {"artist":{"#text":"TEMPLES"},"track":{"#text":"GLIMMER"},
                  "timestamp":1789671430,"ignoredMessage":{"code":"0","#text":""}},
                 {"artist":{"#text":"CREAM SODA"},"track":{"#text":"ЕДУ В ЕГИПЕТ"},
                  "timestamp":1789593601,"ignoredMessage":{"code":"3","#text":"Timestamp was too old"}}
               ]}}"""
        )

        val outcome = (result as LastfmResult.Ok).value
        assertEquals(1, outcome.accepted)
        assertEquals(1, outcome.ignored)
        assertEquals(2, outcome.verdicts.size)

        assertTrue(outcome.verdicts[0].ignored.accepted)
        assertEquals("ЕДУ В ЕГИПЕТ", outcome.verdicts[1].track)
        assertEquals(IgnoredScrobble.TIMESTAMP_TOO_OLD, outcome.verdicts[1].ignored)
        assertFalse(outcome.verdicts[1].ignored.accepted)
    }

    @Test
    fun `an ignored scrobble is a verdict, not an error`() {
        // The distinction the queue depends on: the request succeeded, so there is
        // nothing to retry and the row is deleted.
        val result = LastfmResponses.scrobbles(
            """{"scrobbles":{"@attr":{"accepted":0,"ignored":1},
               "scrobble":{"artist":{"#text":"A"},"track":{"#text":"T"},"timestamp":1,
                 "ignoredMessage":{"code":"1","#text":"Artist was ignored"}}}}"""
        )

        assertTrue("$result", result is LastfmResult.Ok)
        assertEquals(
            IgnoredScrobble.ARTIST_IGNORED,
            (result as LastfmResult.Ok).value.verdicts.single().ignored,
        )
    }

    @Test
    fun `totals sent as strings are read as numbers`() {
        val result = LastfmResponses.scrobbles(
            """{"scrobbles":{"@attr":{"accepted":"2","ignored":"0"},
               "scrobble":[{"artist":{"#text":"A"},"track":{"#text":"T"},"timestamp":1,
                            "ignoredMessage":{"code":"0","#text":""}},
                           {"artist":{"#text":"B"},"track":{"#text":"U"},"timestamp":2,
                            "ignoredMessage":{"code":"0","#text":""}}]}}"""
        )

        assertEquals(2, (result as LastfmResult.Ok).value.accepted)
    }

    @Test
    fun `missing totals are counted from the verdicts rather than failing the batch`() {
        val result = LastfmResponses.scrobbles(
            """{"scrobbles":{"scrobble":[
               {"artist":{"#text":"A"},"track":{"#text":"T"},"timestamp":1,
                "ignoredMessage":{"code":"0","#text":""}},
               {"artist":{"#text":"B"},"track":{"#text":"U"},"timestamp":2,
                "ignoredMessage":{"code":"2","#text":"Track was ignored"}}]}}"""
        )

        val outcome = (result as LastfmResult.Ok).value
        assertEquals(1, outcome.accepted)
        assertEquals(1, outcome.ignored)
    }

    @Test
    fun `a plain string artist is read as well as a text wrapper`() {
        // Defensive: a simplification on Last.fm's side must not break parsing.
        val result = LastfmResponses.scrobbles(
            """{"scrobbles":{"@attr":{"accepted":1,"ignored":0},
               "scrobble":{"artist":"BAD SUNS","track":"BACK TO ZERO","timestamp":1789672459,
                 "ignoredMessage":{"code":"0","#text":""}}}}"""
        )

        assertEquals("BAD SUNS", (result as LastfmResult.Ok).value.verdicts.single().artist)
    }

    @Test
    fun `a scrobble without an ignoredMessage counts as accepted`() {
        val result = LastfmResponses.scrobbles(
            """{"scrobbles":{"@attr":{"accepted":1,"ignored":0},
               "scrobble":{"artist":{"#text":"A"},"track":{"#text":"T"},"timestamp":1}}}"""
        )

        assertTrue((result as LastfmResult.Ok).value.verdicts.single().ignored.accepted)
    }

    @Test
    fun `a timestamp past 2038 survives`() {
        // Int seconds stop being able to hold a UTC timestamp in 2038, and a
        // truncated one is the kind of thing only ever found in production.
        val result = LastfmResponses.scrobbles(
            """{"scrobbles":{"@attr":{"accepted":1,"ignored":0},
               "scrobble":{"artist":{"#text":"A"},"track":{"#text":"T"},
                 "timestamp":2200000000,"ignoredMessage":{"code":"0","#text":""}}}}"""
        )

        assertEquals(2_200_000_000L, (result as LastfmResult.Ok).value.verdicts.single().timestamp)
    }

    @Test
    fun `a response with no scrobbles element is malformed`() {
        assertTrue(LastfmResponses.scrobbles("""{"ok":true}""") is LastfmResult.Malformed)
    }
}
