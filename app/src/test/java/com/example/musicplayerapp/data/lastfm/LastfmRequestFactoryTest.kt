package com.example.musicplayerapp.data.lastfm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What each call actually sends.
 *
 * Constructed with fabricated credentials through the [LastfmSigner] seam, so
 * nothing here can involve a real key, and nothing here makes a request - a
 * [LastfmRequest] is inert data.
 */
class LastfmRequestFactoryTest {

    private val fakeApiKey = "0123456789abcdef0123456789abcdef"
    private val fakeSecret = "fedcba9876543210fedcba9876543210"
    private val sessionKey = "sessionkey"

    private val factory = LastfmRequestFactory(fakeApiKey, SharedSecretSigner(fakeSecret))

    /** A signer that refuses, which is what an unconfigured build has. */
    private val unconfigured = LastfmRequestFactory(fakeApiKey, SharedSecretSigner(""))

    @Test
    fun `every call carries method, api key, signature and format`() {
        val request = factory.authGetToken()!!

        assertEquals("auth.getToken", request.params["method"])
        assertEquals(fakeApiKey, request.params["api_key"])
        assertEquals("json", request.params["format"])
        assertEquals(32, request.params.getValue("api_sig").length)
    }

    @Test
    fun `the signature is over the parameters minus format, and never over itself`() {
        // The ordering contract in LastfmRequestFactory, asserted rather than trusted:
        // api_sig cannot be part of its own input, and format must not be signed. Both
        // produce error 13 and nothing more informative, on every call.
        val request = factory.authGetSession("a-token")!!

        val signable = request.params - "api_sig" - "format"
        assertEquals(
            LastfmSignature.sign(signable, fakeSecret),
            request.params["api_sig"],
        )
        assertFalse("api_sig must not be in its own input", "api_sig" in signable)
    }

    @Test
    fun `auth getToken is a signed GET with no other parameters`() {
        val request = factory.authGetToken()!!

        assertEquals(LastfmHttpMethod.GET, request.httpMethod)
        assertEquals(
            setOf("method", "api_key", "api_sig", "format"),
            request.params.keys,
        )
        assertEquals("122db8efff0100fb11599a7629ab9106", request.params["api_sig"])
    }

    @Test
    fun `auth getSession carries the token`() {
        val request = factory.authGetSession("the-token")!!

        assertEquals("auth.getSession", request.params["method"])
        assertEquals(LastfmHttpMethod.GET, request.httpMethod)
        assertEquals("the-token", request.params["token"])
    }

    @Test
    fun `the authorization url is the callback-free desktop flow`() {
        // No callback and no deep link: the listener grants access on Last.fm's own
        // site and simply comes back, and auth.getSession is retried on resume. If
        // this ever grows a `cb` parameter it has become the web flow and needs an
        // intent filter, which is a decision and not a tweak.
        val url = LastfmRequestFactory.authorizationUrl(fakeApiKey, "the-token")

        assertEquals(
            "https://www.last.fm/api/auth/?api_key=$fakeApiKey&token=the-token",
            url,
        )
        assertFalse("no callback in the desktop flow", url.contains("cb="))
        assertTrue(url.startsWith("https://"))
    }

    @Test
    fun `now playing is a signed POST and omits what it cannot prove`() {
        // duration is shown to the listener by Last.fm, so a guess would be worse
        // than nothing. Absent unless the station's metadata actually proves it.
        val request = factory.updateNowPlaying(
            artist = "BAD SUNS",
            track = "BACK TO ZERO",
            sessionKey = sessionKey,
        )!!

        assertEquals("track.updateNowPlaying", request.params["method"])
        assertEquals(LastfmHttpMethod.POST, request.httpMethod)
        assertEquals("BAD SUNS", request.params["artist"])
        assertEquals("BACK TO ZERO", request.params["track"])
        assertEquals(sessionKey, request.params["sk"])
        assertFalse("album", "album" in request.params)
        assertFalse("duration", "duration" in request.params)
        // The parameter does not exist on this method.
        assertFalse("chosenByUser", "chosenByUser" in request.params)
        assertEquals("fc618bb5ab459d3f2b443142f5a1d6af", request.params["api_sig"])
    }

    @Test
    fun `now playing includes album and duration when they are known`() {
        val request = factory.updateNowPlaying(
            artist = "BAD SUNS",
            track = "BACK TO ZERO",
            sessionKey = sessionKey,
            album = "Accelerator",
            durationSec = 204,
        )!!

        assertEquals("Accelerator", request.params["album"])
        assertEquals("204", request.params["duration"])
    }

    @Test
    fun `a blank album or a non-positive duration is omitted rather than sent empty`() {
        val request = factory.updateNowPlaying(
            artist = "BAD SUNS",
            track = "BACK TO ZERO",
            sessionKey = sessionKey,
            album = "   ",
            durationSec = 0,
        )!!

        assertFalse("album", "album" in request.params)
        assertFalse("duration", "duration" in request.params)
    }

    @Test
    fun `scrobbles use array notation and always claim chosenByUser zero`() {
        // Radio is never user-selected, and Last.fm assumes 1 when the parameter is
        // absent - so omitting it would quietly claim the opposite of what is true.
        val request = factory.scrobble(
            listOf(
                LastfmScrobble("BAD SUNS", "BACK TO ZERO", 1789672459L),
                LastfmScrobble("TEMPLES", "GLIMMER", 1789671430L, durationSec = 185),
            ),
            sessionKey,
        )!!

        assertEquals("track.scrobble", request.params["method"])
        assertEquals(LastfmHttpMethod.POST, request.httpMethod)

        assertEquals("BAD SUNS", request.params["artist[0]"])
        assertEquals("BACK TO ZERO", request.params["track[0]"])
        assertEquals("1789672459", request.params["timestamp[0]"])
        assertEquals("0", request.params["chosenByUser[0]"])
        assertFalse("duration[0]", "duration[0]" in request.params)

        assertEquals("TEMPLES", request.params["artist[1]"])
        assertEquals("GLIMMER", request.params["track[1]"])
        assertEquals("1789671430", request.params["timestamp[1]"])
        assertEquals("0", request.params["chosenByUser[1]"])
        assertEquals("185", request.params["duration[1]"])
    }

    @Test
    fun `a full batch signs to the pinned value`() {
        // Twelve entries, so the artist[10]-before-artist[1] ordering is exercised
        // through the builder and not only through the signer.
        val request = factory.scrobble(
            (0 until 12).map { LastfmScrobble("A$it", "T$it", 1789672459L + it) },
            sessionKey,
        )!!

        assertEquals("cd3e31cc6546c12127657eb8e7829b8c", request.params["api_sig"])
    }

    @Test
    fun `a batch may hold fifty scrobbles and not fifty-one`() {
        val fifty = (0 until 50).map { LastfmScrobble("A$it", "T$it", 1789672459L + it) }
        assertEquals(50, LastfmRequestFactory.MAX_BATCH)
        assertTrue(factory.scrobble(fifty, sessionKey) != null)

        // Truncating would drop scrobbles silently, which is the one thing a queue
        // must never do. A caller that over-fills a batch has a bug.
        val tooMany = fifty + LastfmScrobble("A50", "T50", 1789672509L)
        val thrown = runCatching { factory.scrobble(tooMany, sessionKey) }.exceptionOrNull()
        assertTrue("expected IllegalArgumentException, got $thrown", thrown is IllegalArgumentException)
    }

    @Test
    fun `an empty batch is no request at all`() {
        assertNull(factory.scrobble(emptyList(), sessionKey))
    }

    @Test
    fun `an unconfigured build produces no requests`() {
        // Null rather than an exception: "this build has no Last.fm" is an ordinary
        // state, and every caller has to handle it anyway.
        assertNull(unconfigured.authGetToken())
        assertNull(unconfigured.authGetSession("t"))
        assertNull(unconfigured.updateNowPlaying("a", "t", sessionKey))
        assertNull(unconfigured.scrobble(listOf(LastfmScrobble("a", "t", 1L)), sessionKey))
    }

    @Test
    fun `the endpoint is the versioned https api root`() {
        assertEquals("https://ws.audioscrobbler.com/2.0/", LastfmRequestFactory.ENDPOINT)
    }

    @Test
    fun `toString never carries the session key or the signature`() {
        // `sk` belongs to a listener and api_sig is derived from the shared secret.
        // A request interpolated into a log line must carry neither.
        val request = factory.scrobble(
            listOf(LastfmScrobble("BAD SUNS", "BACK TO ZERO", 1789672459L)),
            sessionKey,
        )!!
        val rendered = request.toString()

        assertFalse(rendered.contains(sessionKey))
        assertFalse(rendered.contains(request.params.getValue("api_sig")))
        assertTrue(rendered.contains("track.scrobble"))
    }
}
