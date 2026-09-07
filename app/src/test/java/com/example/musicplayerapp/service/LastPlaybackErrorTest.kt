package com.example.musicplayerapp.service

import androidx.media3.common.PlaybackException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * What the report is allowed to learn about a playback failure.
 *
 * The holder exists so issue #15's evidence can leave a listener's phone. What it
 * must never do is carry anything else off it, and a `PlaybackException` is a rich
 * object: it has a message, a cause with its own message, and a stack. Those are
 * written by libraries this project does not control and routinely contain URLs,
 * hosts and file paths.
 *
 * So the test is not "does it remember the error" - it is "does it remember *only*
 * the code".
 */
class LastPlaybackErrorTest {

    @Before
    @After
    fun reset() = LastPlaybackError.clearForTest()

    private fun error(code: Int, message: String, cause: Throwable? = null) =
        PlaybackException(message, cause, code)

    @Test
    fun `a fresh process has no error to report`() {
        assertNull(LastPlaybackError.peek())
    }

    @Test
    fun `the media3 code name is what is kept`() {
        LastPlaybackError.record(
            error(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, "irrelevant")
        )
        assertEquals("ERROR_CODE_IO_BAD_HTTP_STATUS", LastPlaybackError.peek()?.codeName)
    }

    /**
     * The exception's own message, and its cause's, must not survive the call.
     *
     * This is the assertion the whole class is for. The message here is the shape
     * of the real thing - a stream URL inside a data-source failure - and none of
     * it may appear in what is kept.
     */
    @Test
    fun `neither message nor cause nor url is retained`() {
        val cause = IllegalStateException(
            "Response code: 403, url=https://radio.dline-media.com/myata?token=abc123"
        )
        LastPlaybackError.record(
            error(
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                "Source error: https://radio.dline-media.com/myata",
                cause,
            )
        )

        val kept = LastPlaybackError.peek()
        assertNotNull(kept)
        val whole = kept.toString()
        // Not the bare substring "http": the Media3 constant for this failure is
        // ERROR_CODE_IO_BAD_HTTP_*STATUS*, so the word is legitimately part of the
        // code itself. What must not appear is anything that came from the message
        // or the cause - a scheme, a host, a query value, the status number.
        for (forbidden in listOf("://", "radio.dline-media.com", "token", "abc123", "403")) {
            assert(!whole.contains(forbidden, ignoreCase = true)) {
                "`$forbidden` must not survive into the capture, but the capture is: $whole"
            }
        }
        assertEquals("ERROR_CODE_IO_BAD_HTTP_STATUS", kept!!.codeName)
    }

    /**
     * The capture is a code and a timestamp, and has room for nothing else.
     *
     * Stated as a test so that widening it is a visible edit here rather than a
     * quiet field added to a data class.
     */
    @Test
    fun `the capture has exactly two fields`() {
        assertEquals(
            listOf("codeName", "elapsedRealtimeMs"),
            LastPlaybackError.Capture::class.constructors.first().parameters.map { it.name },
        )
    }

    @Test
    fun `the most recent error is the one reported`() {
        LastPlaybackError.record(error(PlaybackException.ERROR_CODE_TIMEOUT, "first"))
        LastPlaybackError.record(
            error(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, "second")
        )
        assertEquals(
            "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED",
            LastPlaybackError.peek()?.codeName,
        )
    }

    /**
     * A code name that is not a code name does not become one.
     *
     * `errorCodeName` comes from a library, and the cost of it one day returning
     * something unexpected is that the unexpected thing is posted to a third party.
     * The shape is enforced rather than trusted.
     */
    @Test
    fun `an unrecognisable code collapses to unknown rather than passing through`() {
        // A PlaybackException whose codeName is not a constant name cannot be built
        // through the public API, so the sanitiser is exercised through the only
        // door it has: a code Media3 does not know, which it renders as something
        // other than a constant.
        LastPlaybackError.record(error(999_999, "custom"))

        val kept = LastPlaybackError.peek()!!.codeName
        assert(kept.all { it in 'A'..'Z' || it in '0'..'9' || it == '_' }) {
            "the kept code must be a code, but was: $kept"
        }
    }
}
