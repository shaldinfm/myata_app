package com.example.musicplayerapp.data.lastfm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that decides whether this build has Last.fm in it.
 *
 * `isConfigured` is read off `BuildConfig`, so it depends on what the machine
 * running the tests has in `lastfm.properties`. What is asserted here is the
 * decision itself, applied to values - the part that must not drift - plus the
 * relationship between the real object and that decision, which holds both on a
 * developer machine with credentials and in CI with none.
 *
 * Every value here is fabricated.
 */
class LastfmConfigTest {

    private fun configured(key: String, secret: String): Boolean =
        LastfmConfig.looksLikeCredential(key) && LastfmConfig.looksLikeCredential(secret)

    @Test
    fun `a configured build needs both a key and a secret`() {
        val key = "0123456789abcdef0123456789abcdef"
        val secret = "fedcba9876543210fedcba9876543210"

        assertTrue(configured(key, secret))
        assertFalse("no secret", configured(key, ""))
        assertFalse("no key", configured("", secret))
        assertFalse("neither", configured("", ""))
    }

    @Test
    fun `the placeholders in the example template are not credentials`() {
        // The reason the shape is checked at all. A lastfm.properties copied from the
        // template but never filled in must leave the feature absent, not produce
        // error 10 on the listener's first tap at the one moment they are paying
        // attention to it.
        assertFalse(LastfmConfig.looksLikeCredential("REPLACE_WITH_YOUR_LASTFM_API_KEY"))
        assertFalse(LastfmConfig.looksLikeCredential("REPLACE_WITH_YOUR_LASTFM_SHARED_SECRET"))
    }

    @Test
    fun `a credential is 32 hex characters in either case`() {
        assertTrue(LastfmConfig.looksLikeCredential("0123456789abcdef0123456789abcdef"))
        assertTrue("uppercase is still hex", LastfmConfig.looksLikeCredential("0123456789ABCDEF0123456789ABCDEF"))
        assertTrue("mixed case", LastfmConfig.looksLikeCredential("0123456789aBcDeF0123456789AbCdEf"))

        assertFalse("31 characters", LastfmConfig.looksLikeCredential("0123456789abcdef0123456789abcde"))
        assertFalse("33 characters", LastfmConfig.looksLikeCredential("0123456789abcdef0123456789abcdef0"))
        assertFalse("not hex", LastfmConfig.looksLikeCredential("0123456789abcdefg123456789abcdef"))
        assertFalse("whitespace", LastfmConfig.looksLikeCredential("0123456789abcdef 123456789abcdef"))
        assertFalse("blank", LastfmConfig.looksLikeCredential("                                "))
    }

    @Test
    fun `the real object agrees with the rule, whatever this machine has configured`() {
        // Asserted as a relationship rather than a value, like SupabaseConfigTest: a
        // developer with real credentials and CI with none must both pass, and what
        // holds either way is that isConfigured says exactly what the rule says.
        assertEquals(
            "isConfigured=${LastfmConfig.isConfigured}",
            configured(LastfmConfig.apiKey, LastfmConfig.apiSecret),
            LastfmConfig.isConfigured,
        )
    }

    @Test
    fun `an unconfigured build is a supported state, not a failure`() {
        // The whole point. A fresh clone and CI have no lastfm.properties, and the
        // app is expected to build and run exactly as it did before this existed.
        assertFalse(configured("", ""))
    }

    @Test
    fun `toString never carries the credentials`() {
        // A config object ends up interpolated into a log line sooner or later, and
        // one of these two values is the shared secret.
        val rendered = LastfmConfig.toString()

        assertFalse(rendered.contains(LastfmConfig.apiSecret) && LastfmConfig.apiSecret.isNotEmpty())
        assertFalse(rendered.contains(LastfmConfig.apiKey) && LastfmConfig.apiKey.isNotEmpty())
        assertTrue(rendered.startsWith("LastfmConfig(isConfigured="))
    }

    @Test
    fun `a signer exists exactly when the build is configured`() {
        assertEquals(LastfmConfig.isConfigured, LastfmSigners.forThisBuild() != null)
        assertEquals(LastfmConfig.isConfigured, LastfmRequestFactory.forThisBuild() != null)
    }

    @Test
    fun `the signer never renders its secret`() {
        val rendered = SharedSecretSigner("fedcba9876543210fedcba9876543210").toString()

        assertFalse(rendered.contains("fedcba"))
        assertEquals("SharedSecretSigner(configured=true)", rendered)
        assertEquals("SharedSecretSigner(configured=false)", SharedSecretSigner("").toString())
    }
}
