package com.example.musicplayerapp

import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The stream hosts are trusted on every Android version the app supports, and
 * trusting them opened no cleartext door.
 *
 * ## The failure this pins
 *
 * On 2026-09-15 the `*.dline-media.com` certificate was reissued under
 * GlobalSign Root R46. The app bundled only Root R6, and R46 reaches the system
 * store only on Android 14, so every station failed on Android 13 and older with
 * `Trust anchor for certification path not found` while the API 36 emulator
 * played normally. `radio.dline-media.com` also redirects every stream to a node
 * host (`drh-node-03.dline-media.com`), which the old config did not cover at
 * all - fixing the first host alone would have moved the failure one hop along.
 *
 * ## Why the XML, and how the host is resolved
 *
 * The platform's reading of the config is a device-only value; the instrumented
 * half is `StreamTrustPolicyTest`. This runs on the JVM, which is what CI gates on.
 * Hosts are resolved the way the platform documents it: the domain-config with the
 * most specific (longest) matching `<domain>` wins, a subdomain matches only when
 * `includeSubdomains` is true, and a host nothing matches gets `<base-config>`.
 */
class StreamTrustConfigTest {

    private companion object {
        const val RADIO_HOST = "radio.dline-media.com"
        const val NODE_HOST = "drh-node-03.dline-media.com"

        const val SYSTEM = "system"
        const val R46 = "@raw/globalsign_root_r46"
        const val R6 = "@raw/globalsign_root_r6"

        /** Published by GlobalSign; the served chain's root matched it on 2026-09-25. */
        const val R46_SHA256 =
            "4FA3126D8D3A11D1C4855A4F807CBAD6CF919D3A5A88B03BEA2C6372D93C40C9"
        const val R6_SHA256 =
            "2CABEAFE37D06CA22ABA7391C0033D25982952C453647349763A3AB5AD6CCF69"
    }

    // ---- 1. the anchors themselves ----------------------------------------------------

    @Test
    fun `Root R46 is the official GlobalSign root`() {
        val cert = certificate("globalsign_root_r46.pem")
        assertEquals(R46_SHA256, sha256(cert))
        assertEquals("CN=GlobalSign Root R46,O=GlobalSign nv-sa,C=BE", cert.subjectX500Principal.name)
        assertRoot(cert)
    }

    @Test
    fun `Root R6 is still bundled`() {
        val cert = certificate("globalsign_root_r6.pem")
        assertEquals(R6_SHA256, sha256(cert))
        assertEquals("CN=GlobalSign,O=GlobalSign,OU=GlobalSign Root CA - R6", cert.subjectX500Principal.name)
        assertRoot(cert)
    }

    // ---- 2. who gets them ---------------------------------------------------------------

    @Test
    fun `the stream host trusts the system store, R46 and R6`() {
        assertEquals(setOf(SYSTEM, R46, R6), anchors(configFor(RADIO_HOST)))
    }

    @Test
    fun `the redirect node trusts the system store, R46 and R6`() {
        assertEquals(setOf(SYSTEM, R46, R6), anchors(configFor(NODE_HOST)))
        // Any other node the provider redirects to lands in the same block.
        assertEquals(setOf(SYSTEM, R46, R6), anchors(configFor("drh-node-07.dline-media.com")))
    }

    // ---- 3. cleartext stays where it was --------------------------------------------------

    @Test
    fun `the redirect node gets no cleartext`() {
        assertFalse(cleartextPermitted(NODE_HOST))
        assertFalse(cleartextPermitted("dline-media.com"))
    }

    @Test
    fun `cleartext is still permitted for the stream host alone`() {
        // Unchanged by this fix: the legacy TV fallback depends on it.
        assertTrue(cleartextPermitted(RADIO_HOST))
        val cleartext = domainConfigs().filter { it.getAttribute("cleartextTrafficPermitted") == "true" }
        assertEquals(1, cleartext.size)
        assertEquals(listOf(RADIO_HOST to false), domains(cleartext.single()))
    }

    @Test
    fun `cleartext is denied by default`() {
        assertEquals("false", baseConfig().getAttribute("cleartextTrafficPermitted"))
        assertFalse(cleartextPermitted("example.com"))
        assertFalse(cleartextPermitted("radiomyata.ru"))
    }

    // ---- helpers --------------------------------------------------------------------------

    private fun resFile(path: String): File =
        listOf(File("src/main/res/$path"), File("app/src/main/res/$path"))
            .firstOrNull { it.isFile }
            ?: error("cannot locate src/main/res/$path from ${File("").absolutePath}")

    private fun certificate(name: String): X509Certificate =
        resFile("raw/$name").inputStream().use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }

    private fun sha256(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02X".format(it) }

    /** Self-signed CA, currently valid: a root, not an intermediate or a leaf. */
    private fun assertRoot(cert: X509Certificate) {
        assertEquals(cert.subjectX500Principal, cert.issuerX500Principal)
        cert.verify(cert.publicKey)
        assertTrue("not a CA certificate", cert.basicConstraints >= 0)
        cert.checkValidity(Date())
    }

    private val config: Element by lazy {
        DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(resFile("xml/network_security_config.xml")).documentElement
    }

    private fun children(parent: Element, tag: String): List<Element> {
        val nodes = parent.childNodes
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }.filter { it.tagName == tag }
    }

    private fun baseConfig(): Element = children(config, "base-config").single()

    private fun domainConfigs(): List<Element> = children(config, "domain-config")

    /** (domain, includeSubdomains) for each `<domain>` of [domainConfig]. */
    private fun domains(domainConfig: Element): List<Pair<String, Boolean>> =
        children(domainConfig, "domain").map { it.textContent.trim() to (it.getAttribute("includeSubdomains") == "true") }

    /** The domain-config the platform applies to [host], or null for base-config. */
    private fun configFor(host: String): Element? =
        domainConfigs()
            .flatMap { dc -> domains(dc).map { (domain, subs) -> Triple(dc, domain, subs) } }
            .filter { (_, domain, subs) -> host == domain || (subs && host.endsWith(".$domain")) }
            .maxByOrNull { (_, domain, _) -> domain.length }
            ?.first

    private fun cleartextPermitted(host: String): Boolean {
        val own = configFor(host)?.getAttribute("cleartextTrafficPermitted").orEmpty()
        return (own.ifEmpty { baseConfig().getAttribute("cleartextTrafficPermitted") }) == "true"
    }

    private fun anchors(domainConfig: Element?): Set<String> {
        assertNotNull("host falls through to base-config", domainConfig)
        return children(children(domainConfig!!, "trust-anchors").single(), "certificates")
            .map { it.getAttribute("src") }.toSet()
    }
}
