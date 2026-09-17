package com.example.musicplayerapp.data.lastfm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden vectors for `api_sig`.
 *
 * A wrong signature is the one failure this feature cannot diagnose from the
 * outside: Last.fm answers [LastfmError.INVALID_METHOD_SIGNATURE] and nothing
 * else, for a signed parameter that should not have been, a sort order that
 * differs, a byte encoded the wrong way, or a mangled secret. So the rule is
 * pinned against values computed independently of this code rather than asserted
 * against itself.
 *
 * **Every credential here is fabricated.** `mysecret` is Last.fm's own
 * documentation example; the 32-hex pair is a keyboard pattern. No test in this
 * package needs, reads or could accidentally use a real one - that is what the
 * [LastfmSigner] seam buys.
 */
class LastfmSignatureTest {

    private val fakeApiKey = "0123456789abcdef0123456789abcdef"
    private val fakeSecret = "fedcba9876543210fedcba9876543210"

    @Test
    fun `the base string matches Last fm's own documented example`() {
        // Straight from https://www.last.fm/api/desktopauth, which spells out:
        //   md5("api_keyxxxxxxxxmethodauth.getSessiontokenxxxxxxxmysecret")
        // Pinning the base string and not only the hash is what makes a failure
        // here readable - the hash alone would say "wrong" without saying how.
        val base = LastfmSignature.signatureBase(
            params = mapOf(
                "api_key" to "xxxxxxxx",
                "method" to "auth.getSession",
                "token" to "xxxxxxx",
            ),
            secret = "mysecret",
        )

        assertEquals("api_keyxxxxxxxxmethodauth.getSessiontokenxxxxxxxmysecret", base)
        assertEquals("68afb32bee072407a63b6c41f3e1e2b4", LastfmSignature.md5Hex(base))
    }

    @Test
    fun `format and callback are never signed`() {
        // The specification's one explicit prohibition: "You must not include the
        // format and callback parameters." Request construction adds format=json to
        // every call, so getting this wrong would break every call rather than an
        // unlucky one - which is the good case. This is the assertion that catches it.
        val withoutFormat = mapOf("method" to "auth.getToken", "api_key" to fakeApiKey)
        val withFormat = withoutFormat + mapOf("format" to "json", "callback" to "cb")

        assertEquals(
            LastfmSignature.sign(withoutFormat, fakeSecret),
            LastfmSignature.sign(withFormat, fakeSecret),
        )
        assertEquals(
            "122db8efff0100fb11599a7629ab9106",
            LastfmSignature.sign(withFormat, fakeSecret),
        )
    }

    @Test
    fun `parameters are signed in alphabetical order regardless of insertion order`() {
        // The map handed to the signer is built in whatever order a builder finds
        // convenient, and must not affect the result.
        val inOneOrder = linkedMapOf(
            "method" to "track.updateNowPlaying",
            "api_key" to fakeApiKey,
            "artist" to "BAD SUNS",
            "track" to "BACK TO ZERO",
            "sk" to "sessionkey",
            "format" to "json",
        )
        val inAnother = linkedMapOf(
            "track" to "BACK TO ZERO",
            "format" to "json",
            "sk" to "sessionkey",
            "api_key" to fakeApiKey,
            "artist" to "BAD SUNS",
            "method" to "track.updateNowPlaying",
        )

        assertEquals(
            "api_key${fakeApiKey}artistBAD SUNSmethodtrack.updateNowPlayingsksessionkey" +
                "trackBACK TO ZERO$fakeSecret",
            LastfmSignature.signatureBase(inOneOrder, fakeSecret),
        )
        assertEquals(
            LastfmSignature.sign(inOneOrder, fakeSecret),
            LastfmSignature.sign(inAnother, fakeSecret),
        )
        assertEquals(
            "fc618bb5ab459d3f2b443142f5a1d6af",
            LastfmSignature.sign(inOneOrder, fakeSecret),
        )
    }

    @Test
    fun `a batch past ten entries sorts artist bracket ten before artist bracket one`() {
        // The case that is invisible until a batch grows: '0' (0x30) sorts before
        // ']' (0x5D), so "artist[10]" precedes "artist[1]". It looks wrong and is
        // what a plain alphabetical sort of the full parameter names produces, which
        // is what the specification asks for and what the server re-derives.
        //
        // A batch of eleven or more only happens on a listener who has been offline
        // for a while - exactly when losing the scrobbles would matter most - so it
        // is pinned here rather than discovered there.
        val params = buildMap {
            put("method", "track.scrobble")
            put("api_key", fakeApiKey)
            put("sk", "sessionkey")
            repeat(12) { i ->
                put("artist[$i]", "A$i")
                put("track[$i]", "T$i")
                put("timestamp[$i]", (1789672459L + i).toString())
                put("chosenByUser[$i]", "0")
            }
        }

        val base = LastfmSignature.signatureBase(params, fakeSecret)
        assertTrue(
            "artist[10] must be signed before artist[1]",
            base.indexOf("artist[10]") < base.indexOf("artist[1]A1"),
        )
        assertEquals("cd3e31cc6546c12127657eb8e7829b8c", LastfmSignature.sign(params, fakeSecret))
    }

    @Test
    fun `non-ASCII values are hashed as UTF-8`() {
        // Most of what this station plays on XTRA is Cyrillic, so this is the common
        // case rather than an edge one. Hashing the platform default encoding instead
        // would work on a developer machine and fail on some devices.
        val params = mapOf(
            "method" to "track.updateNowPlaying",
            "api_key" to fakeApiKey,
            "artist" to "CREAM SODA",
            "track" to "ЕДУ В ЕГИПЕТ",
            "sk" to "sessionkey",
        )

        assertEquals("748ec8b7328b62956b66057668492c7a", LastfmSignature.sign(params, fakeSecret))
    }

    @Test
    fun `the secret changes the signature`() {
        // Trivially true, and worth stating: it is the only thing proving the secret
        // reaches the hash at all. A signer that dropped it would still produce a
        // plausible 32-hex string and fail only against the live service.
        val params = mapOf("method" to "auth.getToken", "api_key" to fakeApiKey)

        assertNotEquals(
            LastfmSignature.sign(params, fakeSecret),
            LastfmSignature.sign(params, "a different fake secret"),
        )
    }

    @Test
    fun `a signature is 32 lowercase hex characters`() {
        val signature = LastfmSignature.sign(
            mapOf("method" to "auth.getToken", "api_key" to fakeApiKey),
            fakeSecret,
        )

        assertEquals(32, signature.length)
        assertEquals(signature.lowercase(), signature)
        assertTrue(signature.all { it in "0123456789abcdef" })
    }

    @Test
    fun `an empty parameter map still signs the secret`() {
        // Not a case any builder produces - every call carries at least method and
        // api_key - but the function is total and says so.
        assertEquals(
            LastfmSignature.md5Hex(fakeSecret),
            LastfmSignature.sign(emptyMap(), fakeSecret),
        )
    }
}
