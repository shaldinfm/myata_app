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
 * The HOME playlist card's tap lands on the view that is listening for it.
 *
 * ## The regression this pins
 *
 * The 3.6.6 HOME migration made the card a clickable `MaterialCardView` filling the whole
 * item, while `PlaylistAdapter` kept its click listener on the item's root `FrameLayout`.
 * A clickable child consumes the entire touch, so the root's listener never fired: every
 * card rippled and none of them opened. The device test that existed called
 * `performClick()` on the root directly, which skips touch dispatch altogether - so it
 * passed the whole time.
 *
 * A source test, because it runs in CI on every push and the instrumentation suite does
 * not. `PlaylistCardClickTest` is the same claim made with a real touch on a device.
 */
class PlaylistCardLayoutTest {

    private val layoutPath = "app/src/main/res/layout/rw_playlist_item.xml"
    private val adapterPath =
        "app/src/main/java/com/example/musicplayerapp/adapters/PlaylistAdapter.kt"

    @Test
    fun `the only clickable view in the card layout is the card the adapter listens on`() {
        val clickable = elements(repoFile(layoutPath))
            .filter { it.getAttribute("android:clickable") == "true" }

        assertEquals(
            "rw_playlist_item.xml must have exactly one clickable view - a second one inside " +
                "or around it swallows the tap before the listener sees it",
            1,
            clickable.size,
        )
        assertEquals("@+id/playlist_card", clickable.single().getAttribute("android:id"))
    }

    @Test
    fun `the adapter listens on the card, not on the item root`() {
        val source = repoFile(adapterPath).readText()

        assertTrue(
            "PlaylistAdapter must look the card up by R.id.playlist_card",
            source.contains("R.id.playlist_card"),
        )
        assertTrue(
            "PlaylistAdapter must attach its click listener to the card",
            source.contains("card.setOnClickListener"),
        )
        assertFalse(
            "a listener on itemView never fires while the card inside it is clickable",
            source.contains("itemView.setOnClickListener"),
        )
    }

    private fun elements(file: File): List<Element> {
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(file).getElementsByTagName("*")
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    /** Finds a repository-relative file from wherever the test runner starts. */
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
