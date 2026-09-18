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
        LastfmConfig.isSupplied(key) && LastfmConfig.isSupplied(secret)

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
        // The one job this check exists for: a lastfm.properties copied from the
        // template but never filled in must leave the feature absent, not produce
        // error 10 on the listener's first tap at the one moment they are paying
        // attention to it.
        assertFalse(LastfmConfig.isSupplied("REPLACE_WITH_YOUR_LASTFM_API_KEY"))
        assertFalse(LastfmConfig.isSupplied("REPLACE_WITH_YOUR_LASTFM_SHARED_SECRET"))
        // Matched on the marker, so rewording the template cannot turn a placeholder
        // into something accepted as a credential.
        assertFalse(LastfmConfig.isSupplied("replace_with_anything_at_all"))
        assertFalse(LastfmConfig.isSupplied("  REPLACE ME  "))
    }

    @Test
    fun `blank and whitespace-only values are not credentials`() {
        assertFalse("empty", LastfmConfig.isSupplied(""))
        assertFalse("spaces", LastfmConfig.isSupplied("     "))
        assertFalse("tab and newline", LastfmConfig.isSupplied("\t\n "))
    }

    @Test
    fun `no shape is imposed, because Last fm documents none`() {
        // Checked against the official specification rather than against examples or
        // existing keys:
        //
        //  - /api/authspec documents the api key's LENGTH ("Your 32-character API
        //    Key", §3.3, §4.1, §7) but never its character set;
        //  - it documents NEITHER for the shared secret. §2 says only "Your account
        //    page contains your secret", and §8's own worked example uses
        //    'ilovecher'. The desktop, web and mobile how-tos use 'mysecret'.
        //  - the "32-character hexadecimal" wording in the spec is about api_sig,
        //    which is an md5 output and so 32 hex by construction. It says nothing
        //    about the credentials fed into it.
        //
        // An earlier version of this file required 32 hex for both. It would have
        // rejected Last.fm's own documented example secret, and the way it failed
        // would have been silent - a build with genuine credentials in which the
        // feature never appears and nothing explains why.
        assertTrue(
            "Last.fm's own example secret must be accepted",
            LastfmConfig.isSupplied("ilovecher"),
        )
        assertTrue("the how-tos' example secret", LastfmConfig.isSupplied("mysecret"))
        assertTrue("not hexadecimal", LastfmConfig.isSupplied("zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz"))
        assertTrue("shorter than 32", LastfmConfig.isSupplied("short"))
        assertTrue("longer than 32", LastfmConfig.isSupplied("x".repeat(64)))
        assertTrue("punctuation", LastfmConfig.isSupplied("a-b_c.d~e"))

        // Whether a credential is *valid* is Last.fm's answer to give, not ours: an
        // accepted-but-wrong value produces error 10 or 13, which the error mapping
        // already handles, and that is the honest failure mode.
        assertTrue(LastfmConfig.isSupplied("0123456789abcdef0123456789abcdef"))
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
    fun `the test override decides without touching the credentials`() {
        // The seam P3a's instrumentation uses to reach the Settings row in a build
        // with no credentials - which is every build today. It overrides the
        // decision only, so no test can ever be handed something that signs.
        try {
            LastfmConfig.configuredOverrideForTest = true
            assertTrue(LastfmConfig.isConfigured)
            LastfmConfig.configuredOverrideForTest = false
            assertFalse(LastfmConfig.isConfigured)
        } finally {
            LastfmConfig.configuredOverrideForTest = null
        }
        // Cleared, it falls back to the real rule again.
        assertEquals(
            configured(LastfmConfig.apiKey, LastfmConfig.apiSecret),
            LastfmConfig.isConfigured,
        )
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
