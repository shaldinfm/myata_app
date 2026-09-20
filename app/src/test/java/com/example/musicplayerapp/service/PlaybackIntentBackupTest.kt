package com.example.musicplayerapp.service

import com.example.musicplayerapp.data.PlaybackIntentStore
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The playback-intent record never leaves this device.
 *
 * It is runtime state about one speaker: which station was last selected, and
 * whether the listener wanted it playing. Carried through a cloud backup or a
 * device transfer it would hand a phone that has never played anything the belief
 * that it was mid-broadcast - and since the record is what a `START_STICKY`
 * restart acts on, the first thing that phone's system restarted could be a radio
 * its owner never started there. Nothing is lost by dropping it, because a fresh
 * device has nothing to recover.
 *
 * Both places matter and they are different files: `fullBackupContent` is what API
 * 24-30 reads, `dataExtractionRules` is what API 31+ reads, and the newer one has
 * two independent sections - cloud backup and device transfer - each of which has
 * to say so separately.
 *
 * `LastfmLinkTest` owns the complementary claim, that these files exclude *only*
 * these files and carry no `<include>`.
 */
class PlaybackIntentBackupTest {

    private val excluded = "sharedpref:${PlaybackIntentStore.FILE}.xml"

    @Test
    fun `the record is excluded from Auto Backup on API 24-30`() {
        assertTrue(
            "$excluded must be excluded from fullBackupContent",
            excluded in excludes("src/main/res/xml/lastfm_backup_rules.xml"),
        )
    }

    @Test
    fun `the record is excluded from cloud backup and device transfer on API 31+`() {
        val doc = parse("src/main/res/xml/lastfm_data_extraction_rules.xml")
        for (section in listOf("cloud-backup", "device-transfer")) {
            val nodes = doc.getElementsByTagName(section)
            assertEquals("one <$section>", 1, nodes.length)
            val ex = (nodes.item(0) as Element).getElementsByTagName("exclude")
            val found = (0 until ex.length).map {
                val e = ex.item(it) as Element
                "${e.getAttribute("domain")}:${e.getAttribute("path")}"
            }
            assertTrue("$excluded must be excluded from <$section>, found $found", excluded in found)
        }
    }

    @Test
    fun `the excluded path is the file the store actually writes`() {
        // The rule files name a path; the store names a prefs file. A rename on
        // one side and not the other would silently start backing the record up
        // again, with nothing to show for it until somebody restored a phone.
        assertEquals("myata_playback_intent", PlaybackIntentStore.FILE)
    }

    private fun excludes(path: String): List<String> {
        val nodes = parse(path).getElementsByTagName("exclude")
        return (0 until nodes.length).map {
            val e = nodes.item(it) as Element
            "${e.getAttribute("domain")}:${e.getAttribute("path")}"
        }
    }

    private fun parse(path: String) =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file(path))

    /** Unit tests run from the module directory; the positive control is that the file exists. */
    private fun file(path: String): File = File(path).also {
        assertTrue("expected ${it.absolutePath} to exist", it.isFile)
    }
}
