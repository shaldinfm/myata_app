package com.example.musicplayerapp.data.supabase

import com.example.musicplayerapp.BuildConfig
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The account's sign-in state never leaves the device in a backup or a device
 * transfer, and the reaction database deliberately still does.
 *
 * Read from the XML the manifest points at, in all three places a file can leave:
 * Auto Backup on API 24-30 (`fullBackupContent`), and cloud backup and device
 * transfer on API 31+ (`dataExtractionRules`). A rule missing from any one of them is
 * a restore path that brings a spent refresh token back.
 */
class AccountBackupRulesTest {

    /**
     * supabase-kt 3.2.6's default session manager stores through multiplatform-settings'
     * no-arg `Settings()`, which is the default SharedPreferences file:
     * `<packageName>_preferences`. The package name is the applicationId.
     */
    private val sessionFile = "${BuildConfig.APPLICATION_ID}_preferences.xml"

    private val accountFiles = listOf(
        "${IdentityStore.FILE}.xml",
        sessionFile,
        "${LastSyncStore.FILE}.xml",
    )

    @Test
    fun sign_in_state_is_excluded_from_every_backup_and_transfer_section() {
        for ((section, excluded) in sections()) {
            for (file in accountFiles) {
                assertTrue("$file must be excluded in $section", "sharedpref" to file in excluded)
            }
        }
    }

    @Test
    fun the_reaction_database_is_retained_on_purpose() {
        // RETAINED, not forgotten: for a guest it is the only copy of their Collection,
        // and a restored copy carries its owner row. Excluding it is a product decision
        // this test exists to make visible if it is ever taken.
        for ((section, excluded) in sections()) {
            for (path in listOf("myata_database", "myata_database-wal", "myata_database-shm")) {
                assertFalse("$path is retained in $section", "database" to path in excluded)
            }
        }
    }

    @Test
    fun every_section_is_present() {
        assertEquals(
            setOf("full-backup-content", "cloud-backup", "device-transfer"),
            sections().keys,
        )
    }

    private fun sections(): Map<String, Set<Pair<String, String>>> {
        val result = linkedMapOf<String, Set<Pair<String, String>>>()

        val full = parse("xml/lastfm_backup_rules.xml")
        result["full-backup-content"] = excludes(full)

        val extraction = parse("xml/lastfm_data_extraction_rules.xml")
        for (name in listOf("cloud-backup", "device-transfer")) {
            val node = extraction.getElementsByTagName(name).item(0) as Element
            result[name] = excludes(node)
        }
        return result
    }

    private fun excludes(root: Element): Set<Pair<String, String>> {
        val nodes = root.getElementsByTagName("exclude")
        return (0 until nodes.length).map { nodes.item(it) as Element }
            .map { it.getAttribute("domain") to it.getAttribute("path") }
            .toSet()
    }

    private fun parse(path: String): Element {
        val file = listOf(File("src/main/res/$path"), File("app/src/main/res/$path"))
            .firstOrNull { it.isFile }
            ?: error("cannot locate src/main/res/$path from ${File("").absolutePath}")
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
    }
}
