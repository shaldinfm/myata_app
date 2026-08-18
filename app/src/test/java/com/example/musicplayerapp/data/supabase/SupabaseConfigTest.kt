package com.example.musicplayerapp.data.supabase

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that decides whether this build has a backend, and the one that decides
 * whether a key should ever have been in it.
 *
 * `isConfigured` is read off `BuildConfig` and so depends on what the machine
 * running the tests has in `supabase.properties`; what is asserted here is the
 * decision itself, applied to values, which is the part that must not drift.
 */
class SupabaseConfigTest {

    private fun configured(url: String, key: String): Boolean =
        url.startsWith("https://") && key.isNotBlank()

    @Test
    fun `a project needs an https url and a key`() {
        assertTrue(configured("https://abc.supabase.co", "sb_publishable_x"))

        assertFalse("no key", configured("https://abc.supabase.co", ""))
        assertFalse("blank key", configured("https://abc.supabase.co", "   "))
        assertFalse("no url", configured("", "sb_publishable_x"))
    }

    @Test
    fun `the real object agrees with the rule, whatever this machine has configured`() {
        // Asserted as a relationship rather than a value: a developer with a real
        // supabase.properties and CI with none must both pass, and what must hold in
        // either case is that isConfigured says exactly what the rule says.
        assertTrue(
            "isConfigured=${SupabaseConfig.isConfigured} for url='${SupabaseConfig.url}'",
            SupabaseConfig.isConfigured ==
                configured(SupabaseConfig.url, SupabaseConfig.publishableKey),
        )
    }

    @Test
    fun `a shipped key is never a secret one`() {
        // The build refuses this too. If it ever gets past both, the fix is to
        // rotate the key, not to relax the check.
        assertFalse(
            "a SECRET key is compiled into this build",
            SupabaseConfig.isSecretKey(SupabaseConfig.publishableKey),
        )
    }

    @Test
    fun `a url without https is not a project`() {
        // network_security_config permits cleartext for the audio stream and nothing
        // else, so a scheme-less or http URL cannot work and must not look like it
        // might.
        assertFalse(configured("http://abc.supabase.co", "sb_publishable_x"))
        assertFalse(configured("abc.supabase.co", "sb_publishable_x"))
    }

    @Test
    fun `an unconfigured build is a supported state, not a failure`() {
        // The whole point: a fresh clone and CI have no supabase.properties, and the
        // app is expected to build and run exactly as before.
        assertFalse(configured("", ""))
    }

    @Test
    fun `secret keys are recognised so they can be refused`() {
        assertTrue(SupabaseConfig.isSecretKey("sb_secret_abc123"))
        assertTrue(SupabaseConfig.isSecretKey("service_role.jwt.here"))
    }

    @Test
    fun `publishable keys are the ones allowed to ship`() {
        assertFalse(SupabaseConfig.isSecretKey("sb_publishable_abc123"))
        // The legacy anon key is not a secret either - it is the old name for the
        // same idea, and still works until the legacy keys are switched off.
        assertFalse(SupabaseConfig.isSecretKey("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.anon"))
    }
}
