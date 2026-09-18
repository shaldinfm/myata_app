package com.example.musicplayerapp.data.lastfm

import com.example.musicplayerapp.data.lastfm.queue.LastfmQueueDatabase
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The link derived from four stored fields, the redaction of everything that holds
 * a secret, and the backup exclusion that keeps the session on this device.
 */
class LastfmLinkTest {

    private val now = 1_789_700_000_000L
    private val hour = LastfmLink.PENDING_TOKEN_LIFETIME_MS

    @Test
    fun `nothing stored is not linked`() {
        assertEquals(LastfmLink.NotLinked, LastfmLink.of(LastfmStoredSession.EMPTY, now))
    }

    @Test
    fun `a session key and a username is linked`() {
        val link = LastfmLink.of(LastfmStoredSession(sessionKey = "sk", username = "u"), now)
        assertEquals(LastfmLink.Linked("sk", "u"), link)
    }

    @Test
    fun `a live pending token is pending`() {
        val link = LastfmLink.of(
            LastfmStoredSession(pendingToken = "t", pendingTokenIssuedAt = now - 1), now,
        )
        assertEquals(LastfmLink.Pending("t", now - 1), link)
    }

    @Test
    fun `an expired pending token derives to not linked`() {
        val link = LastfmLink.of(
            LastfmStoredSession(pendingToken = "t", pendingTokenIssuedAt = now - hour), now,
        )
        assertEquals(LastfmLink.NotLinked, link)
    }

    @Test
    fun `a username with no session and no live token is the re-auth state`() {
        // Nothing in P3b writes this - success writes key and name together,
        // Disconnect clears all four - so it is the dormant hook for the error-9 path
        // that authenticated methods will bring. It must still derive correctly.
        assertEquals(
            LastfmLink.ReauthRequired("f0ul482"),
            LastfmLink.of(LastfmStoredSession(username = "f0ul482"), now),
        )
    }

    @Test
    fun `a live token outranks the re-auth state, and cancelling it returns there`() {
        val reconnecting = LastfmStoredSession(
            username = "f0ul482", pendingToken = "t", pendingTokenIssuedAt = now - 1,
        )
        assertTrue(LastfmLink.of(reconnecting, now) is LastfmLink.Pending)
        assertEquals(
            LastfmLink.ReauthRequired("f0ul482"),
            LastfmLink.of(reconnecting.copy(pendingToken = null, pendingTokenIssuedAt = null), now),
        )
    }

    @Test
    fun `nothing that holds a secret renders it`() {
        val token = "tok-SECRET-1"
        val key = "sk-SECRET-2"
        val rendered = listOf(
            LastfmStoredSession(sessionKey = key, username = "u", pendingToken = token, pendingTokenIssuedAt = now).toString(),
            LastfmLink.Pending(token, now).toString(),
            LastfmLink.Linked(key, "u").toString(),
            LastfmAuth.Begin.OpenBrowser("https://www.last.fm/api/auth/?api_key=k&token=$token").toString(),
            LastfmTransportResult.Body("""{"session":{"key":"$key"}}""").toString(),
        )
        rendered.forEach {
            assertFalse("token leaked: $it".replace(token, "<T>"), it.contains(token))
            assertFalse("key leaked: $it".replace(key, "<K>"), it.contains(key))
        }
    }

    @Test
    fun `an unreachable result carries a class name and never a message`() {
        // The transport keeps only the exception's simple name - a message can quote
        // the GET URL, whose query holds api_sig and the pending token.
        val result = LastfmTransportResult.Unreachable("SocketTimeoutException")
        assertEquals("Unreachable(kind=SocketTimeoutException)", result.toString())
    }

    // ---- backup ---------------------------------------------------------------

    @Test
    fun `the manifest points at both backup rule files`() {
        val manifest = file("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:allowBackup=\"true\""))
        assertTrue(manifest.contains("android:fullBackupContent=\"@xml/lastfm_backup_rules\""))
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/lastfm_data_extraction_rules\""))
    }

    /**
     * Exactly the Last.fm files, and nothing else: the session (P3b), and the P5
     * scrobble queue database with its write-ahead log and shared-memory files.
     */
    private val lastfmExcludes = listOf(
        "sharedpref:${PrefsLastfmSessionStore.FILE}.xml",
        "database:${LastfmQueueDatabase.FILE}",
        "database:${LastfmQueueDatabase.FILE}-wal",
        "database:${LastfmQueueDatabase.FILE}-shm",
    )

    @Test
    fun `the API 24-30 rules exclude the Last fm session and queue, and only them`() {
        assertEquals(lastfmExcludes, excludes("src/main/res/xml/lastfm_backup_rules.xml"))
        assertIncludesNothing("src/main/res/xml/lastfm_backup_rules.xml")
    }

    @Test
    fun `the API 31+ rules exclude them from cloud backup and device transfer, and only them`() {
        val doc = parse("src/main/res/xml/lastfm_data_extraction_rules.xml")
        for (section in listOf("cloud-backup", "device-transfer")) {
            val nodes = doc.getElementsByTagName(section)
            assertEquals("one <$section>", 1, nodes.length)
            val ex = (nodes.item(0) as Element).getElementsByTagName("exclude")
            val found = (0 until ex.length).map {
                val e = ex.item(it) as Element
                "${e.getAttribute("domain")}:${e.getAttribute("path")}"
            }
            assertEquals("$section excludes exactly the Last.fm files", lastfmExcludes, found)
        }
        assertIncludesNothing("src/main/res/xml/lastfm_data_extraction_rules.xml")
    }

    @Test
    fun `the excluded paths are the files the app actually writes`() {
        assertEquals("lastfm_session", PrefsLastfmSessionStore.FILE)
        assertEquals("lastfm_queue", LastfmQueueDatabase.FILE)
        assertEquals(lastfmExcludes, excludes("src/main/res/xml/lastfm_backup_rules.xml"))
    }

    @Test
    fun `no other file is excluded - the rest of the app, myata_database included, is backed up as before`() {
        for (path in listOf(
            "src/main/res/xml/lastfm_backup_rules.xml",
            "src/main/res/xml/lastfm_data_extraction_rules.xml",
        )) {
            val all = excludes(path)
            assertTrue("$path excludes only Last.fm files: $all", all.all { it in lastfmExcludes })
            assertTrue("$path must not exclude the Collections database", all.none { it.contains("myata_database") })
        }
    }

    private fun excludes(path: String): List<String> {
        val nodes = parse(path).getElementsByTagName("exclude")
        return (0 until nodes.length).map {
            val e = nodes.item(it) as Element
            "${e.getAttribute("domain")}:${e.getAttribute("path")}"
        }
    }

    /**
     * No `<include>` anywhere. An include switches Auto Backup from "everything but
     * the excludes" to "only the includes", which would silently stop backing up the
     * rest of the app - including the Supabase session this slice must not touch.
     */
    private fun assertIncludesNothing(path: String) {
        assertEquals("$path must not include", 0, parse(path).getElementsByTagName("include").length)
    }

    private fun parse(path: String) =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file(path))

    /** Unit tests run from the module directory; the positive control is that the file exists. */
    private fun file(path: String): File = File(path).also {
        assertTrue("expected ${it.absolutePath} to exist", it.isFile)
    }
}
