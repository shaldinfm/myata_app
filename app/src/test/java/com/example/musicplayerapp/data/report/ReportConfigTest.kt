package com.example.musicplayerapp.data.report

import com.example.musicplayerapp.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The endpoint gate.
 *
 * The equivalent of `SupabaseConfigTest`, and it holds the same shape of rule: an
 * unconfigured build is an ordinary state, and the app it produces has no report
 * form and no rows offering one.
 *
 * This suite runs in whatever configuration the build has, so it asserts the
 * *rule* rather than a particular answer - except for the one thing that must be
 * true of every build ever made, which is that no bot token is in the APK.
 */
class ReportConfigTest {

    /**
     * The override is instrumentation's door into the form and must never be left
     * open by a suite that used it. Cleared on both sides here so this file's own
     * assertions are about the build rather than about a leftover.
     */
    @Before
    @After
    fun noOverride() {
        ReportConfig.endpointOverrideForTest = null
    }

    @Test
    fun `an unshipped build has no override set`() {
        assertEquals(BuildConfig.REPORT_ENDPOINT, ReportConfig.endpoint)
    }

    @Test
    fun `the override decides, and clearing it hands the build back`() {
        ReportConfig.endpointOverrideForTest = "https://example.invalid/exec"
        assertTrue(ReportConfig.isConfigured)
        ReportConfig.endpointOverrideForTest = ""
        assertFalse("an empty override is not a configured build", ReportConfig.isConfigured)
        ReportConfig.endpointOverrideForTest = null
        assertEquals(BuildConfig.REPORT_ENDPOINT, ReportConfig.endpoint)
    }

    @Test
    fun `configured means an https endpoint and nothing less`() {
        assertEquals(
            BuildConfig.REPORT_ENDPOINT.startsWith("https://"),
            ReportConfig.isConfigured,
        )
    }

    /**
     * Cleartext is refused rather than attempted.
     *
     * `network_security_config` permits cleartext for exactly one host, the audio
     * stream. An `http://` endpoint would therefore fail inside the TLS layer in a
     * way that reads like an outage instead of like a mistake in a properties file
     * - and a report posted in the clear would put the listener's own words on the
     * wire for anybody on the network to read.
     */
    @Test
    fun `an unconfigured or cleartext endpoint is not configured`() {
        assertFalse(BuildConfig.REPORT_ENDPOINT.startsWith("http://"))
        if (BuildConfig.REPORT_ENDPOINT.isBlank()) {
            assertFalse("a blank endpoint must not read as configured", ReportConfig.isConfigured)
        }
    }

    /**
     * The one assertion that must hold for every build, configured or not.
     *
     * A Telegram bot token in an APK is extractable by anyone who downloads it, and
     * lets them post as the bot and read its updates. It belongs in the endpoint's
     * own server-side storage. The Gradle script refuses one in report.properties;
     * this is the runtime half of the same check, for the same reason
     * `SupabaseConfig` carries a runtime guard against a secret key.
     */
    @Test
    fun `no bot token is anywhere in this build's report configuration`() {
        val endpoint = BuildConfig.REPORT_ENDPOINT
        assertFalse("a bot token must never ship in the app", endpoint.contains("bot", true))
        assertTrue(
            "a Telegram token pattern must never be in the endpoint",
            Regex("""\d{6,}:[A-Za-z0-9_-]{30,}""").find(endpoint) == null,
        )
    }
}
