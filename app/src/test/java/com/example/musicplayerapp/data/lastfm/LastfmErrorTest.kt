package com.example.musicplayerapp.data.lastfm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every error code this feature can meet, and the one correct response to it.
 *
 * The table matters more than any single entry. Each of these has several
 * plausible wrong answers, and the wrong ones are not loud: a permanent failure
 * treated as transient becomes a queue that never drains, and a transient one
 * treated as permanent is a listener's scrobbles deleted. Both are invisible
 * without this.
 */
class LastfmErrorTest {

    @Test
    fun `transient service failures are retried`() {
        assertEquals(LastfmAction.Retry, actionForCode(8))   // operation failed
        assertEquals(LastfmAction.Retry, actionForCode(11))  // service offline
        assertEquals(LastfmAction.Retry, actionForCode(16))  // temporary error
    }

    @Test
    fun `a rate limit backs off instead of retrying`() {
        // Retrying a rate limit promptly is how an api key gets suspended, which is
        // the failure with the largest blast radius in this whole feature.
        val action = actionForCode(29)

        assertTrue("$action", action is LastfmAction.Backoff)
        assertEquals(RATE_LIMIT_FLOOR_MS, (action as LastfmAction.Backoff).minDelayMs)
        assertTrue("a floor worth having", action.minDelayMs >= 60_000L)
    }

    @Test
    fun `an invalid session key asks the listener to authorise again`() {
        // 9 means the listener revoked this application, or Last.fm invalidated the
        // key. Their queued scrobbles are still good once they re-authorise, so this
        // must not touch the rows.
        assertEquals(LastfmAction.Reauthenticate, actionForCode(9))
    }

    @Test
    fun `the three token states are told apart`() {
        // The whole reason auth needs its own handling. 14 is the expected answer
        // while the listener has not granted access yet and must not look like a
        // failure to anyone; 15 and 4 mean the token is unusable and a new one is
        // needed.
        assertEquals(LastfmAction.AwaitAuthorization, actionForCode(14))
        assertEquals(LastfmAction.RestartAuthorization, actionForCode(15))
        assertEquals(LastfmAction.RestartAuthorization, actionForCode(4))
    }

    @Test
    fun `a bad or suspended api key disables the feature`() {
        // Nothing the listener can do, and nothing a retry can fix.
        assertEquals(LastfmAction.DisableFeature, actionForCode(10))
        assertEquals(LastfmAction.DisableFeature, actionForCode(26))
    }

    @Test
    fun `our own bugs park the row rather than dropping it`() {
        // A wrong signature or a deprecated call is permanent until a new build - and
        // it is not the row's fault, so the row survives to be delivered by the build
        // that fixes it.
        assertEquals(LastfmAction.Park, actionForCode(13)) // invalid signature
        assertEquals(LastfmAction.Park, actionForCode(2))  // invalid service
        assertEquals(LastfmAction.Park, actionForCode(3))  // invalid method
        assertEquals(LastfmAction.Park, actionForCode(5))  // invalid format
        assertEquals(LastfmAction.Park, actionForCode(27)) // deprecated request
    }

    @Test
    fun `permanently malformed rows are dropped`() {
        // No build and no retry makes a malformed parameter acceptable, so carrying
        // the row forever would only mean retrying it forever.
        assertEquals(LastfmAction.Drop, actionForCode(6)) // invalid parameters
        assertEquals(LastfmAction.Drop, actionForCode(7)) // invalid resource
    }

    @Test
    fun `an unknown code parks, which is safe in both directions`() {
        // Nothing is deleted on the strength of an error nobody has read, and nothing
        // loops on one that turns out to be permanent.
        assertNull(LastfmError.from(9999))
        assertEquals(LastfmAction.Park, actionForCode(9999))
        assertEquals(LastfmAction.Park, actionForCode(0))
        assertEquals(LastfmAction.Park, actionForCode(-1))
    }

    @Test
    fun `codes map to the errors Last fm documents`() {
        assertEquals(LastfmError.INVALID_SESSION_KEY, LastfmError.from(9))
        assertEquals(LastfmError.INVALID_API_KEY, LastfmError.from(10))
        assertEquals(LastfmError.SERVICE_OFFLINE, LastfmError.from(11))
        assertEquals(LastfmError.INVALID_METHOD_SIGNATURE, LastfmError.from(13))
        assertEquals(LastfmError.TOKEN_NOT_AUTHORIZED, LastfmError.from(14))
        assertEquals(LastfmError.TOKEN_EXPIRED, LastfmError.from(15))
        assertEquals(LastfmError.TEMPORARY_ERROR, LastfmError.from(16))
        assertEquals(LastfmError.SUSPENDED_API_KEY, LastfmError.from(26))
        assertEquals(LastfmError.RATE_LIMIT_EXCEEDED, LastfmError.from(29))
    }

    @Test
    fun `every named error has exactly one code and a decided action`() {
        val codes = LastfmError.entries.map { it.code }
        assertEquals("duplicate codes in the enum", codes.size, codes.toSet().size)

        LastfmError.entries.forEach { error ->
            assertEquals(error, LastfmError.from(error.code))
        }

        // The exhaustive table, written out rather than derived. A new entry added to
        // LastfmError without a line here fails this test, which is the point: the
        // wrong action for an error is silent in production, so adding one has to be
        // a decision somebody wrote down.
        val expected = mapOf(
            LastfmError.INVALID_SERVICE to LastfmAction.Park,
            LastfmError.INVALID_METHOD to LastfmAction.Park,
            LastfmError.AUTHENTICATION_FAILED to LastfmAction.RestartAuthorization,
            LastfmError.INVALID_FORMAT to LastfmAction.Park,
            LastfmError.INVALID_PARAMETERS to LastfmAction.Drop,
            LastfmError.INVALID_RESOURCE to LastfmAction.Drop,
            LastfmError.OPERATION_FAILED to LastfmAction.Retry,
            LastfmError.INVALID_SESSION_KEY to LastfmAction.Reauthenticate,
            LastfmError.INVALID_API_KEY to LastfmAction.DisableFeature,
            LastfmError.SERVICE_OFFLINE to LastfmAction.Retry,
            LastfmError.INVALID_METHOD_SIGNATURE to LastfmAction.Park,
            LastfmError.TOKEN_NOT_AUTHORIZED to LastfmAction.AwaitAuthorization,
            LastfmError.TOKEN_EXPIRED to LastfmAction.RestartAuthorization,
            LastfmError.TEMPORARY_ERROR to LastfmAction.Retry,
            LastfmError.SUSPENDED_API_KEY to LastfmAction.DisableFeature,
            LastfmError.DEPRECATED_REQUEST to LastfmAction.Park,
            LastfmError.RATE_LIMIT_EXCEEDED to LastfmAction.Backoff(RATE_LIMIT_FLOOR_MS),
        )

        assertEquals(
            "every LastfmError needs a decided action",
            LastfmError.entries.toSet(),
            expected.keys,
        )
        expected.forEach { (error, action) ->
            assertEquals("action for $error", action, actionForCode(error.code))
        }
    }

    @Test
    fun `an ignored scrobble was accepted by the server and judged, not rejected`() {
        // These arrive inside a 2xx, per scrobble. Retrying would send them to be
        // judged the same way, so every one of them means the row is done.
        assertTrue(IgnoredScrobble.from(0).accepted)

        assertEquals(IgnoredScrobble.ARTIST_IGNORED, IgnoredScrobble.from(1))
        assertEquals(IgnoredScrobble.TRACK_IGNORED, IgnoredScrobble.from(2))
        assertEquals(IgnoredScrobble.TIMESTAMP_TOO_OLD, IgnoredScrobble.from(3))
        assertEquals(IgnoredScrobble.TIMESTAMP_TOO_NEW, IgnoredScrobble.from(4))
        assertEquals(IgnoredScrobble.DAILY_LIMIT_EXCEEDED, IgnoredScrobble.from(5))

        listOf(1, 2, 3, 4, 5).forEach {
            assertFalse("code $it is not accepted", IgnoredScrobble.from(it).accepted)
        }
    }

    @Test
    fun `an unnamed ignore code is ignored, never silently accepted`() {
        // The one outcome that must be impossible: reading "declined for a reason we
        // have no name for" as "recorded".
        assertEquals(IgnoredScrobble.UNKNOWN, IgnoredScrobble.from(99))
        assertFalse(IgnoredScrobble.from(99).accepted)
        assertFalse(IgnoredScrobble.from(-7).accepted)
    }
}
