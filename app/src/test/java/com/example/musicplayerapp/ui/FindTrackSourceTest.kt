package com.example.musicplayerapp.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Element

/**
 * The G4b find-track and menu wiring, asserted on the sources (G4b).
 *
 * Structural claims that do not need a device, so they run on every CI push where
 * the instrumentation suites do not:
 *
 *  - there is ONE set of service rows, and both sheets include it;
 *  - those rows carry the four glyphs G4a decoded from the file, in the frozen order;
 *  - the PLAYER / History sheet has no divider and no delete row, and the
 *    Collection sheet still has both, after the shared rows;
 *  - the PLAYER menu is the frozen four in the frozen order, built from one row
 *    spec - so its height is a sum of shared dimens, not a number of its own.
 */
class FindTrackSourceTest {

    private val rowsPath = "app/src/main/res/layout/include_find_track_rows.xml"
    private val findSheetPath = "app/src/main/res/layout/sheet_find_track.xml"
    private val collectionSheetPath = "app/src/main/res/layout/sheet_collection_track.xml"
    private val menuPath = "app/src/main/res/layout/menu_player_overflow.xml"

    // ==================== the shared rows ====================

    @Test
    fun `the four services are in the frozen order with the decoded glyphs`() {
        val rows = elementsWithIds(root(repoFile(rowsPath)))
            .filter { it.getAttribute("android:id").startsWith("@+id/row_") }

        assertEquals(
            listOf("@+id/row_spotify", "@+id/row_apple_music", "@+id/row_youtube", "@+id/row_yandex"),
            rows.map { it.getAttribute("android:id") },
        )
        assertEquals(
            "each row's glyph must be the one G4a decoded from the file",
            listOf(
                "@drawable/ic_sheet_spotify",
                "@drawable/ic_sheet_apple_music",
                "@drawable/ic_sheet_youtube_music",
                "@drawable/ic_sheet_yandex_music",
            ),
            rows.map { row -> child(row, "ImageView").getAttribute("android:src") },
        )
    }

    @Test
    fun `the shared rows hold nothing destructive`() {
        val ids = elementsWithIds(root(repoFile(rowsPath))).map { it.getAttribute("android:id") }
        assertFalse("row_remove belongs to the Collection sheet only", "@+id/row_remove" in ids)
        assertFalse("the divider belongs to the Collection sheet only", "@+id/sheet_divider" in ids)
    }

    // ==================== the two variants ====================

    @Test
    fun `the player and history sheet is the shared rows and nothing below them`() {
        val sheet = root(repoFile(findSheetPath))
        assertEquals(
            listOf("@layout/include_find_track_rows"),
            includes(sheet),
        )
        val ids = elementsWithIds(sheet).map { it.getAttribute("android:id") }
        assertFalse("no delete row in the Player/History variant", "@+id/row_remove" in ids)
        assertFalse("no divider in the Player/History variant", "@+id/sheet_divider" in ids)
    }

    @Test
    fun `the collection sheet is the shared rows then its own divider and delete row`() {
        val sheet = root(repoFile(collectionSheetPath))
        assertEquals(listOf("@layout/include_find_track_rows"), includes(sheet))

        // Document order within the card's column: the include, then the divider,
        // then the destructive row - "last and separated".
        val column = sheet.getElementsByTagName("include").item(0).parentNode as Element
        val order = children(column).map {
            if (it.tagName == "include") "include" else it.getAttribute("android:id")
        }
        assertEquals(listOf("include", "@+id/sheet_divider", "@+id/row_remove"), order)
    }

    // ==================== the menu ====================

    @Test
    fun `the player menu is the frozen four in the frozen order`() {
        val rows = children(root(repoFile(menuPath)))
        assertEquals(
            listOf(
                "@+id/player_overflow_find_track",
                "@+id/player_overflow_sleep_timer",
                "@+id/player_overflow_report_problem",
                "@+id/player_overflow_history",
            ),
            rows.map { it.getAttribute("android:id") },
        )
        assertEquals(
            listOf(
                "@drawable/ic_player_overflow_find_track",
                "@drawable/ic_sleep_timer_clock",
                "@drawable/ic_player_overflow_alert",
                "@drawable/ic_menu_doc",
            ),
            rows.map { child(it, "ImageView").getAttribute("android:src") },
        )
    }

    /**
     * The G4a compact geometry, carried to four rows without a second geometry:
     * the menu wraps its content, every row is the one row height, every row after
     * the first is the one gap below the previous, and the card pads 10 / 10.
     */
    @Test
    fun `the menu height is derived from the shared row spec`() {
        val menu = root(repoFile(menuPath))
        assertEquals("wrap_content", menu.getAttribute("android:layout_height"))
        assertEquals("@dimen/player_overflow_menu_pad_top", menu.getAttribute("android:paddingTop"))
        assertEquals("@dimen/player_overflow_menu_pad_bottom", menu.getAttribute("android:paddingBottom"))

        children(menu).forEachIndexed { index, row ->
            val id = row.getAttribute("android:id")
            assertEquals("$id height", "@dimen/player_overflow_row_height", row.getAttribute("android:layout_height"))
            assertEquals("$id start inset", "@dimen/player_overflow_row_inset", row.getAttribute("android:layout_marginStart"))
            assertEquals("$id end inset", "@dimen/player_overflow_row_inset", row.getAttribute("android:layout_marginEnd"))
            val gap = row.getAttribute("android:layout_marginTop")
            if (index == 0) {
                assertTrue("the first row sits on the card's padding, not a gap", gap.isEmpty())
            } else {
                assertEquals("$id gap above", "@dimen/player_overflow_row_gap", gap)
            }
        }
    }

    // ==================== helpers ====================

    private fun root(file: File): Element =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement

    private fun children(parent: Element): List<Element> =
        (0 until parent.childNodes.length).mapNotNull { parent.childNodes.item(it) as? Element }

    private fun child(parent: Element, tag: String): Element =
        children(parent).firstOrNull { it.tagName == tag }
            ?: throw AssertionError("${parent.getAttribute("android:id")} has no <$tag>")

    private fun elementsWithIds(root: Element): List<Element> {
        val out = mutableListOf<Element>()
        fun walk(e: Element) {
            if (e.hasAttribute("android:id")) out += e
            children(e).forEach(::walk)
        }
        walk(root)
        return out
    }

    private fun includes(root: Element): List<String> {
        val nodes = root.getElementsByTagName("include")
        return (0 until nodes.length).map { (nodes.item(it) as Element).getAttribute("layout") }
    }

    /** See CollectionRowActionAssetTest.repoFile. */
    private fun repoFile(relative: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        fail("could not find $relative from ${File("").absolutePath}")
        error("unreachable")
    }
}
