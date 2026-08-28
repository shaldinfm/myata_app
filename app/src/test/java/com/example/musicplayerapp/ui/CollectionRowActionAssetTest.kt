package com.example.musicplayerapp.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Element

/**
 * The COLLECTION row action drawable **is** the owner's Figma export, character for
 * character.
 *
 * ## Why this is a source test and not a rendering one
 *
 * `CollectionRowActionGlyphTest` rasterises the drawable and compares it with the
 * export, which is the right check for "does this draw the right shape in the right
 * place" and a hopeless one for "is this the right path". The difference that has
 * already caught this project out is 0.025 of a 40 viewport at a single vertex - the
 * published Material Symbols `arrow_forward` rounds one coordinate to `10.6` where
 * the authoritative drawing is symmetric at `10.575`. That is 0.09 square units of
 * area. At the 400px raster the pixel test uses it is nine pixels, against the 131 it
 * has to allow for two rasterisers disagreeing about anti-aliased edges, and no
 * achievable raster closes that gap: the geometric difference grows with the square
 * of the resolution but so does nothing else, while the ink it hides in grows just as
 * fast.
 *
 * So the exactness is asserted where it is exact - on the two files. This is the
 * assertion that fails if somebody "tidies" the path, re-exports it through a tool
 * that rounds, or quietly puts a Material glyph back.
 *
 * Nothing here needs a device, which is the other reason it lives in `src/test`: it
 * runs on every CI push, where the instrumentation suites do not.
 */
class CollectionRowActionAssetTest {

    private val drawablePath = "app/src/main/res/drawable/ic_collection_row_action.xml"
    private val exportPath =
        "tools/figma-export/collection-icons/owner-final/collection_row_action_arrow.svg"

    // ==================== the geometry ====================

    @Test
    fun `the drawable's path is the exported path, verbatim`() {
        val exported = element(repoFile(exportPath), "path").getAttribute("d")
        val drawable = element(repoFile(drawablePath), "path").getAttribute("android:pathData")

        assertTrue("the export has no path data", exported.isNotEmpty())

        // Not normalised, not re-ordered, not re-spaced. "Copied verbatim" is the
        // whole claim the drawable makes about itself, and this is that claim.
        assertEquals(
            "ic_collection_row_action.xml no longer carries the exported Figma path. " +
                "It must be copied from $exportPath unchanged - see that directory's " +
                "README for why an equivalent-looking path is not good enough.",
            exported,
            drawable,
        )
    }

    @Test
    fun `the drawable keeps the export's coordinate system`() {
        // The path is only verbatim if the viewport it is read in is the one it was
        // written in. A 24 viewport holding these numbers would draw the glyph at
        // 1.67x, off its own control.
        val vector = root(repoFile(drawablePath))
        assertEquals("40", vector.getAttribute("android:viewportWidth"))
        assertEquals("40", vector.getAttribute("android:viewportHeight"))
        assertEquals("40dp", vector.getAttribute("android:width"))
        assertEquals("40dp", vector.getAttribute("android:height"))

        val svg = root(repoFile(exportPath))
        assertEquals("0 0 40 40", svg.getAttribute("viewBox"))
    }

    @Test
    fun `the glyph is the only thing taken from the export`() {
        // The drawable must not have grown a rotation group, a second path, or the
        // export's ring. The ring is bg_collection_row_action, which keeps its own
        // themed colour; a copy of it here would draw twice and tint once.
        val vector = root(repoFile(drawablePath))
        val children = (0 until vector.childNodes.length)
            .mapNotNull { vector.childNodes.item(it) as? Element }

        assertEquals(
            "the drawable should hold exactly one <path> and nothing else, found " +
                children.joinToString { it.tagName },
            listOf("path"),
            children.map { it.tagName },
        )
    }

    // ==================== the ring the export also carries ====================

    @Test
    fun `the export's ring is the ring the layout already draws`() {
        // Not a test of the drawable - a test that leaving the <rect> behind was
        // right. If the export ever disagrees with bg_collection_row_action, the
        // container is what needs revisiting, and this says so out loud rather than
        // letting the glyph land inside a ring nobody re-checked.
        val ring = element(repoFile(exportPath), "rect")

        assertEquals("1", ring.getAttribute("x"))
        assertEquals("1", ring.getAttribute("y"))
        assertEquals("38", ring.getAttribute("width"))
        assertEquals("38", ring.getAttribute("height"))
        // r 19 on a 38 square inset by 1 in a 40 box: a circle, with a 2-wide stroke
        // centred on it, which is the INSIDE stroke the shape drawable draws.
        assertEquals("19", ring.getAttribute("rx"))
        assertEquals("2", ring.getAttribute("stroke-width"))
        // Light `primary`. The drawable holds a placeholder and the view tints, which
        // is what gives the dark theme its own colour.
        assertEquals("#1C4771", ring.getAttribute("stroke").uppercase())
    }

    // ==================== helpers ====================

    private fun root(file: File): Element =
        DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(file)
            .documentElement

    private fun element(file: File, tag: String): Element {
        val nodes = root(file).getElementsByTagName(tag)
        assertTrue("${file.name} has no <$tag>", nodes.length > 0)
        assertEquals("${file.name} has more than one <$tag>", 1, nodes.length)
        return nodes.item(0) as Element
    }

    /**
     * Finds a repository-relative file from wherever the test runner starts.
     *
     * Gradle runs unit tests with the module directory as the working directory, so
     * every path here is one level up; walking rather than hard-coding `..` keeps it
     * working if that ever changes.
     */
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
