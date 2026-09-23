package com.example.musicplayerapp.data

import com.example.musicplayerapp.ui.sleeptimer.SleepTimerState
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * What `Вернуть` puts back, on the JVM: the two replay rules that keep it intact, and
 * the fact that it never leaves the device.
 *
 * The snapshot itself is durable state (`SleepTimerStore.UNDO_FILE`), because the
 * command that consumes it is durable - `sleep_timer_undo` can be delivered to a service
 * that had nothing to do with the cancel - and delivery is at-least-once, so both
 * commands that carry identity can arrive twice. The rules below are pure functions of
 * what is on disk and of the id of the command that is running, which is what lets the
 * JVM hold them while the device tests (`SleepTimerServiceTest`,
 * `PlaybackCommandHandoffTest`) hold the behaviour they serve.
 */
class SleepTimerUndoTest {

    private fun armed(deadline: Long = 5_000L, minutes: Int = 30) =
        SleepTimerState.Armed(
            deadlineElapsedMs = deadline,
            durationMinutes = minutes,
            isCustom = false,
            generation = 1L,
        )

    // ==================== a replayed cancel ====================

    /**
     * Item 6. The first run of a cancel stores the snapshot; a replay of the *same*
     * command finds no armed timer - the first run disarmed it - so without this rule it
     * would write "nothing to put back" over the thing it just put there, and `Вернуть`
     * would be gone.
     */
    @Test
    fun `the cancel that created the snapshot cannot clear it by running again`() {
        val existing = SleepTimerStore.Cancelled(armed(), cancelledBy = "7")

        assertTrue(SleepTimerStore.Replay.cancelAlreadyRecorded(existing, "7"))
        assertFalse("a different cancel is a different gesture", SleepTimerStore.Replay.cancelAlreadyRecorded(existing, "8"))
        assertFalse("and with nothing stored there is nothing to protect", SleepTimerStore.Replay.cancelAlreadyRecorded(null, "7"))
    }

    // ==================== a replayed undo ====================

    /**
     * The same rule on the other side, and a stronger one: an undo that ran consumed the
     * snapshot, so a replay of it must be a no-op even if a *later* cancel has written a
     * new one - otherwise a stale replay would put back a timer the listener cancelled
     * afterwards.
     */
    @Test
    fun `an undo that already ran does not consume a later cancel's snapshot`() {
        assertTrue(SleepTimerStore.Replay.undoAlreadyConsumed(consumedBy = "12", undoId = "12"))
        assertFalse(SleepTimerStore.Replay.undoAlreadyConsumed(consumedBy = "12", undoId = "13"))
        assertFalse(
            "an install that has never undone anything has nothing to compare",
            SleepTimerStore.Replay.undoAlreadyConsumed(consumedBy = null, undoId = "12"),
        )
    }

    // ==================== and it never leaves the device ====================

    /**
     * Item 5's last requirement. A cancel snapshot is one device's gesture about one
     * device's speaker, so it is excluded from cloud backup and from device transfer -
     * in both rule files, because `fullBackupContent` is API 24-30 and
     * `dataExtractionRules` is 31+, and the latter has a section per direction data can
     * leave in.
     *
     * It lives in its own file for exactly this: the armed-timer record keeps its own
     * backup story (see `SleepTimerStore`), and exclusions are per file.
     */
    @Test
    fun `the cancel snapshot is excluded from cloud backup and device transfer`() {
        val excluded = "sharedpref:${SleepTimerStore.UNDO_FILE}.xml"
        assertEquals("myata_sleep_timer_undo", SleepTimerStore.UNDO_FILE)

        assertTrue(
            "$excluded must be excluded on API 24-30",
            excluded in excludes("src/main/res/xml/lastfm_backup_rules.xml"),
        )

        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(file("src/main/res/xml/lastfm_data_extraction_rules.xml"))
        for (section in listOf("cloud-backup", "device-transfer")) {
            val nodes = doc.getElementsByTagName(section)
            assertEquals("one <$section>", 1, nodes.length)
            val found = excludes(nodes.item(0) as Element)
            assertTrue("$excluded must be excluded from <$section>, found $found", excluded in found)
        }
    }

    /**
     * The armed record is a different thing and keeps its own story: the snapshot's keys
     * are not in its file, so `SleepTimerStoreTest`'s schema assertion stays true and a
     * `Вернуть` affordance cannot be confused with a timer that will fire.
     */
    @Test
    fun `the snapshot is not written into the armed timer's keys`() {
        val store = source("data/SleepTimerStore.kt")

        assertTrue("the snapshot has its own file", store.contains("\"myata_sleep_timer_undo\""))
        assertTrue("written and read through the store's own API", store.contains("fun readCancelled("))
        assertTrue(store.contains("fun consumeCancelled("))
    }

    // ============================ plumbing ============================

    private fun excludes(path: String): List<String> =
        excludes(
            DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(file(path))
                .documentElement,
        )

    private fun excludes(root: Element): List<String> {
        val nodes = root.getElementsByTagName("exclude")
        return (0 until nodes.length).map {
            val e = nodes.item(it) as Element
            "${e.getAttribute("domain")}:${e.getAttribute("path")}"
        }
    }

    private fun source(path: String): String =
        file("src/main/java/com/example/musicplayerapp/$path").readText()

    /** Unit tests run from the module directory; the positive control is that the file exists. */
    private fun file(path: String): File = File(path).also {
        assertTrue("expected ${it.absolutePath} to exist", it.isFile)
    }
}
