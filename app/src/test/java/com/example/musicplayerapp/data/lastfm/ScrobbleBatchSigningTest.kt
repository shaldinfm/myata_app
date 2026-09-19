package com.example.musicplayerapp.data.lastfm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The batch-scrobble signature, pinned against a string written out by hand
 * (G6b P6a).
 *
 * Last.fm: parameter names "MUST be sorted according to the ASCII table (i.e.,
 * artist[10] comes before artist[1])". A numeric sort of the index would put
 * `artist[1]` first and every batch of 11 or more would be refused with
 * "invalid method signature". The expected string below is the ASCII order, typed
 * rather than computed, so the test cannot agree with a wrong implementation.
 *
 * The key and secret are fabricated. The signature string contains the secret,
 * which is why production never logs it; a test may assert it.
 */
class ScrobbleBatchSigningTest {

    private val key = "0123456789abcdef0123456789abcdef"
    private val secret = "fedcba9876543210fedcba9876543210"
    private val factory = LastfmRequestFactory(key, SharedSecretSigner(secret))

    private val batch = (0 until 12).map { i ->
        LastfmScrobble(artist = "A$i", track = "T$i", startedAtUtcSeconds = 1_789_000_000L + i, durationSec = 200 + i)
    }

    private val request = factory.scrobble(batch, "sk-fake")!!

    /**
     * One `<name><value>` pair per item field, in the ASCII order of the **whole
     * name**: `artist[1]` against `artist[10]` compares `]` (0x5D) with `0` (0x30),
     * so `[10]` and `[11]` come before `[1]`. Typed out, not sorted by code.
     */
    private fun pairs(field: String, value: (Int) -> String): String =
        listOf(0, 10, 11, 1, 2, 3, 4, 5, 6, 7, 8, 9).joinToString("") { "$field[$it]${value(it)}" }

    private val expectedBase =
        "api_key$key" +
            pairs("artist") { "A$it" } +
            pairs("chosenByUser") { "0" } +
            pairs("duration") { "${200 + it}" } +
            "methodtrack.scrobble" +
            "sksk-fake" +
            pairs("timestamp") { "${1_789_000_000L + it}" } +
            pairs("track") { "T$it" } +
            secret

    @Test
    fun `the index sorts as ASCII - artist 10 and 11 before artist 1`() {
        assertTrue("Last.fm's own example", "artist[10]" < "artist[1]")
        val base = expectedBase
        assertTrue(base.indexOf("artist[10]") < base.indexOf("artist[1]A1"))
        assertTrue(base.indexOf("artist[11]") < base.indexOf("artist[1]A1"))
        assertTrue(base.indexOf("artist[1]A1") < base.indexOf("artist[2]"))
    }

    @Test
    fun `the batch is signed over exactly the hand-written ASCII-ordered string`() {
        val sent = request.params
        assertEquals(expectedBase, LastfmSignature.signatureBase(sent - "api_sig", secret))
        assertEquals(LastfmSignature.md5Hex(expectedBase), sent["api_sig"])
    }

    @Test
    fun `format is sent but never signed, and api_sig is not signed over itself`() {
        assertEquals("json", request.params["format"])
        assertFalse(expectedBase.contains("format"))
        assertFalse(expectedBase.contains("api_sig"))
    }

    @Test
    fun `every item claims chosenByUser 0, and nothing unsent is invented`() {
        val names = request.params.keys
        (0 until 12).forEach { assertEquals("0", request.params["chosenByUser[$it]"]) }
        for (forbidden in listOf("streamId", "album", "mbid", "trackNumber", "albumArtist", "context")) {
            assertTrue("$forbidden must not be sent", names.none { it.startsWith(forbidden) })
        }
        assertEquals(LastfmHttpMethod.POST, request.httpMethod)
        assertEquals("track.scrobble", request.params["method"])
    }
}
