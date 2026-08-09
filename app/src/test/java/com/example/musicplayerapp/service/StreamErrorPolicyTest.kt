package com.example.musicplayerapp.service

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import javax.net.ssl.SSLHandshakeException

/**
 * The HTTP status rules cannot be exercised against the live radio server, and
 * getting them wrong is expensive in both directions - retrying a 404 forever, or
 * abandoning a 503 that would have cleared on its own. So they are pinned here.
 */
class StreamErrorPolicyTest {

    private fun error(errorCode: Int, cause: Throwable? = null) =
        PlaybackException("test", cause, errorCode)

    // InvalidResponseCodeException cannot be built here: it needs a DataSpec, whose
    // builder needs a real android.net.Uri, which is a stub in local unit tests. The
    // status rule is therefore pinned directly - it is the part that decides - and
    // the extraction of that status from the cause chain is covered on-device
    // instead (the PLAYER_ERROR log line carries the cause class).

    // ---- transient transport failures: reconnect ----

    @Test
    fun `network failures are recoverable`() {
        assertTrue(isRecoverable(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertTrue(isRecoverable(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT))
        assertTrue(isRecoverable(PlaybackException.ERROR_CODE_TIMEOUT))
    }

    @Test
    fun `mid-stream disconnect shapes are recoverable`() {
        // How a live connection dropped by the server usually surfaces.
        assertTrue(isRecoverable(PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
        assertTrue(isRecoverable(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE))
        assertTrue(isRecoverable(PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW))
    }

    // ---- permanent failures: do not reconnect ----

    @Test
    fun `configuration and content failures are not recoverable`() {
        assertFalse(isRecoverable(PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED))
        assertFalse(isRecoverable(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND))
        assertFalse(isRecoverable(PlaybackException.ERROR_CODE_IO_NO_PERMISSION))
        assertFalse(isRecoverable(PlaybackException.ERROR_CODE_DECODING_FAILED))
        assertFalse(isRecoverable(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
        assertFalse(isRecoverable(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED))
        assertFalse(isRecoverable(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED))
    }

    // ---- HTTP status split ----

    @Test
    fun `server side statuses are recoverable`() {
        listOf(500, 502, 503, 504).forEach {
            assertTrue("$it should be retried", StreamErrorPolicy.isRecoverableHttpStatus(it))
        }
    }

    @Test
    fun `client side statuses are not recoverable`() {
        listOf(400, 401, 403, 404, 410).forEach {
            assertFalse("$it must not be retried", StreamErrorPolicy.isRecoverableHttpStatus(it))
        }
    }

    @Test
    fun `timeout and rate limit statuses are recoverable`() {
        assertTrue(StreamErrorPolicy.isRecoverableHttpStatus(408))
        assertTrue(StreamErrorPolicy.isRecoverableHttpStatus(429))
    }

    @Test
    fun `a bad http status alone is not enough to retry`() {
        // Without a response code on the cause we cannot tell 503 from 404, so the
        // generic code must not be treated as recoverable by itself.
        assertFalse(isRecoverable(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
    }

    // ---- TLS detection gates the legacy cleartext fallback (issue #16) ----

    @Test
    fun `tls failure is detected through the cause chain`() {
        val wrapped = error(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            IOException("outer", SSLHandshakeException("handshake failed"))
        )
        assertTrue(StreamErrorPolicy.isTlsFailure(wrapped))
    }

    @Test
    fun `an ordinary network drop is not a tls failure`() {
        val plain = error(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            IOException("connection reset")
        )
        assertFalse(StreamErrorPolicy.isTlsFailure(plain))
    }

    // ---- backoff ----

    @Test
    fun `backoff doubles and then caps`() {
        val base = 1_000L
        val max = 30_000L
        assertEquals(1_000L, StreamErrorPolicy.backoffDelayMs(0, base, max))
        assertEquals(2_000L, StreamErrorPolicy.backoffDelayMs(1, base, max))
        assertEquals(4_000L, StreamErrorPolicy.backoffDelayMs(2, base, max))
        assertEquals(8_000L, StreamErrorPolicy.backoffDelayMs(3, base, max))
        assertEquals(16_000L, StreamErrorPolicy.backoffDelayMs(4, base, max))
        assertEquals(30_000L, StreamErrorPolicy.backoffDelayMs(5, base, max))
        // Never grows past the cap however many attempts are made.
        assertEquals(30_000L, StreamErrorPolicy.backoffDelayMs(9, base, max))
    }

    private fun isRecoverable(errorCode: Int) = StreamErrorPolicy.isRecoverable(error(errorCode))
}
