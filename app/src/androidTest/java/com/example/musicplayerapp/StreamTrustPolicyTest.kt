package com.example.musicplayerapp

import android.security.NetworkSecurityPolicy
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The platform's own reading of `network_security_config.xml` for the stream hosts.
 *
 * `StreamTrustConfigTest` checks the XML on the JVM with a re-implementation of
 * the domain-matching rule. This asks the device instead, so a misreading of that
 * rule - for example, a subdomain block overriding the exact-host block - shows up
 * here even where the JVM test agrees with itself. Offline: nothing is fetched.
 */
@RunWith(AndroidJUnit4::class)
class StreamTrustPolicyTest {

    private val policy = NetworkSecurityPolicy.getInstance()

    @Test
    fun redirectNodeIsHttpsOnly() {
        assertFalse(policy.isCleartextTrafficPermitted("drh-node-03.dline-media.com"))
        assertFalse(policy.isCleartextTrafficPermitted("dline-media.com"))
    }

    @Test
    fun streamHostKeepsItsCleartextPermission() {
        assertTrue(policy.isCleartextTrafficPermitted("radio.dline-media.com"))
    }

    @Test
    fun cleartextIsDeniedByDefault() {
        assertFalse(policy.isCleartextTrafficPermitted)
        assertFalse(policy.isCleartextTrafficPermitted("example.com"))
    }
}
