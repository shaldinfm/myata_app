package com.example.musicplayerapp.data.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The five categories, and the keys they post under.
 *
 * Pinned the way `AboutLinksTest` pins the destinations "О нас" opens, and for the
 * same reason: these are values somebody else depends on. Whoever triages reports
 * groups by [ReportCategory.wire], so a rename here silently splits one bucket
 * into two and makes a month of reports look like a new problem appearing.
 *
 * The labels are deliberately **not** asserted. They are copy, they live in
 * strings.xml, and they are allowed to be rewritten - which is the whole reason
 * the wire key is a separate thing from the label.
 */
class ReportCategoryTest {

    @Test
    fun `the five frozen categories are all of them, in the frozen order`() {
        assertEquals(
            listOf(
                ReportCategory.PLAYBACK_WONT_START,
                ReportCategory.STOPPED_BY_ITSELF,
                ReportCategory.HEADPHONES,
                ReportCategory.INTERFACE,
                ReportCategory.OTHER,
            ),
            ReportCategory.entries.toList(),
        )
    }

    /**
     * The keys themselves, one assertion per key rather than a list, so a failure
     * names the one that moved.
     */
    @Test
    fun `the wire keys are stable`() {
        assertEquals("playback_wont_start", ReportCategory.PLAYBACK_WONT_START.wire)
        assertEquals("stopped_by_itself", ReportCategory.STOPPED_BY_ITSELF.wire)
        assertEquals("headphones", ReportCategory.HEADPHONES.wire)
        assertEquals("ui", ReportCategory.INTERFACE.wire)
        assertEquals("other", ReportCategory.OTHER.wire)
    }

    @Test
    fun `no two categories share a key`() {
        val keys = ReportCategory.entries.map { it.wire }
        assertEquals("every category must be distinguishable on the wire", keys.size, keys.toSet().size)
    }

    /**
     * The keys are ASCII and carry no copy.
     *
     * A Russian label used as a key would be re-encoded by every hop between here
     * and a spreadsheet cell, and would tie the grouping to the wording.
     */
    @Test
    fun `the wire keys are lower-case ascii`() {
        for (category in ReportCategory.entries) {
            assertEquals(
                "${category.name} must post an ascii key",
                category.wire,
                category.wire.filter { it in 'a'..'z' || it == '_' },
            )
        }
    }

    @Test
    fun `an unknown or missing key restores nothing`() {
        assertNull(ReportCategory.fromWire(null))
        assertNull(ReportCategory.fromWire(""))
        assertNull(ReportCategory.fromWire("Музыка не запускается"))
        assertEquals(ReportCategory.HEADPHONES, ReportCategory.fromWire("headphones"))
    }
}
