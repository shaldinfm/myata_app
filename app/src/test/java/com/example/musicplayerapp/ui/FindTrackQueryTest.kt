package com.example.musicplayerapp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the find-track sheet will and will not open for (G4b).
 *
 * The sheet's heading and its four searches are the same two strings, so a query
 * that is half blank would open a sheet titled with nothing that searches for
 * " - " - and "metadata unavailable" is exactly that case on the PLAYER.
 */
class FindTrackQueryTest {

    @Test
    fun `a track with both halves is a query`() {
        assertEquals(FindTrackQuery("MUSE", "CRYOGEN"), FindTrackQuery.of("MUSE", "CRYOGEN"))
    }

    @Test
    fun `missing metadata is no query`() {
        assertNull(FindTrackQuery.of(null, null))
        assertNull(FindTrackQuery.of("MUSE", null))
        assertNull(FindTrackQuery.of(null, "CRYOGEN"))
    }

    @Test
    fun `a blank half is no query`() {
        assertNull(FindTrackQuery.of("", "CRYOGEN"))
        assertNull(FindTrackQuery.of("MUSE", "   "))
        assertNull(FindTrackQuery.of("\t", "\n"))
    }

    /**
     * The metadata endpoints disagree about whitespace (see BroadcastHistoryFeed),
     * and a trailing space would otherwise ride into the heading and every search.
     * Only the ends: the words themselves are the station's.
     */
    @Test
    fun `the ends are trimmed and the words are kept`() {
        assertEquals(
            FindTrackQuery("JORDAN RAKEI FT. TOM MCFARLAND", "Краснознамённая дивизия"),
            FindTrackQuery.of("  JORDAN RAKEI FT. TOM MCFARLAND ", "Краснознамённая дивизия\n"),
        )
    }
}
