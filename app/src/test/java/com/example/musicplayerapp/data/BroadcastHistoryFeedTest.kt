package com.example.musicplayerapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The owner's Broadcast History contract, as arithmetic.
 *
 * The payload these run against is the shape the live API really returns - the
 * current track as entry 0, newest first - which was checked against
 * `api_track_history.php` and `api_all_tracks.php` rather than assumed. See
 * [BroadcastHistoryFeed] for that reading.
 */
class BroadcastHistoryFeedTest {

    private var clock = 1_000L

    private fun track(artist: String, title: String): HistoryTrack {
        clock -= 200
        return HistoryTrack(artist = artist, track = title, playedAt = clock, playedAtFormatted = "00:00")
    }

    private fun idOf(artist: String, title: String) = BroadcastHistoryFeed.identityOf(artist, title)

    private fun titles(tracks: List<HistoryTrack>) = tracks.map { it.track }

    /* ------------------------------------------------------------ identity -- */

    @Test
    fun `identity ignores case and surrounding space`() {
        assertEquals(
            BroadcastHistoryFeed.identityOf("THE MAINE", "DIE TO FALL"),
            BroadcastHistoryFeed.identityOf("  the maine ", "Die To Fall"),
        )
    }

    @Test
    fun `identity ignores repeated inner space`() {
        assertEquals(
            BroadcastHistoryFeed.identityOf("JORDAN RAKEI FT. TOM MCFARLAND", "EASY TO LOVE"),
            BroadcastHistoryFeed.identityOf("JORDAN  RAKEI FT.  TOM MCFARLAND", "EASY TO LOVE"),
        )
    }

    @Test
    fun `a track is not the same as another by the same artist`() {
        val one = BroadcastHistoryFeed.identityOf("THE XX", "DANGEROUS")
        val two = BroadcastHistoryFeed.identityOf("THE XX", "ON HOLD")

        assertEquals(false, one == two)
    }

    @Test
    fun `an incomplete pair has no identity`() {
        assertNull(BroadcastHistoryFeed.identityOf("THE XX", ""))
        assertNull(BroadcastHistoryFeed.identityOf(null, "DANGEROUS"))
        assertNull(BroadcastHistoryFeed.identityOf("   ", "DANGEROUS"))
    }

    /* ------------------------------------------- the current track is gone -- */

    @Test
    fun `the currently playing track is not row 1`() {
        val raw = listOf(
            track("THE MAINE", "DIE TO FALL"),
            track("THE XX", "DANGEROUS"),
            track("THE SNUTS", "DEFIBRILLATOR"),
        )

        val shown = BroadcastHistoryFeed.project(raw, idOf("THE MAINE", "DIE TO FALL"), limit = 30)

        assertEquals(listOf("DANGEROUS", "DEFIBRILLATOR"), titles(shown))
    }

    @Test
    fun `the current track is recognised through case and spacing`() {
        val raw = listOf(track("THE MAINE", "DIE TO FALL"), track("THE XX", "DANGEROUS"))

        val shown = BroadcastHistoryFeed.project(raw, idOf("the  maine", "die to fall "), limit = 30)

        assertEquals(listOf("DANGEROUS"), titles(shown))
    }

    /**
     * The transition the brief spells out: B becomes current, A becomes row 1,
     * and B is nowhere in the list.
     */
    @Test
    fun `on A to B, A is position 0 and B is absent`() {
        val b = track("THE MAINE", "DIE TO FALL")
        val a = track("THE XX", "DANGEROUS")
        val older = track("THE SNUTS", "DEFIBRILLATOR")

        // Before the API has caught up: its head is still A.
        val duringHandover = BroadcastHistoryFeed.project(
            listOf(a, older), idOf("THE MAINE", "DIE TO FALL"), limit = 30,
        )
        // After it has: its head is B.
        val afterRefresh = BroadcastHistoryFeed.project(
            listOf(b, a, older), idOf("THE MAINE", "DIE TO FALL"), limit = 30,
        )

        assertEquals(listOf("DANGEROUS", "DEFIBRILLATOR"), titles(duringHandover))
        assertEquals(
            "the refresh must land on the identical list, or it flashes",
            duringHandover, afterRefresh,
        )
    }

    @Test
    fun `a duplicated head is dropped with the current track`() {
        val raw = listOf(
            track("THE MAINE", "DIE TO FALL"),
            track("THE MAINE", "DIE TO FALL"),
            track("THE XX", "DANGEROUS"),
        )

        val shown = BroadcastHistoryFeed.project(raw, idOf("THE MAINE", "DIE TO FALL"), limit = 30)

        assertEquals(listOf("DANGEROUS"), titles(shown))
    }

    /**
     * A station repeats. An earlier play of what is on now is history that really
     * happened, and purging it would make the log lie - see [BroadcastHistoryFeed].
     */
    @Test
    fun `an earlier play of the current track survives further down`() {
        val raw = listOf(
            track("THE MAINE", "DIE TO FALL"),
            track("THE XX", "DANGEROUS"),
            track("THE MAINE", "DIE TO FALL"),
            track("THE SNUTS", "DEFIBRILLATOR"),
        )

        val shown = BroadcastHistoryFeed.project(raw, idOf("THE MAINE", "DIE TO FALL"), limit = 30)

        assertEquals(listOf("DANGEROUS", "DIE TO FALL", "DEFIBRILLATOR"), titles(shown))
    }

    @Test
    fun `nothing is filtered while the current track is unknown`() {
        val raw = listOf(track("THE MAINE", "DIE TO FALL"), track("THE XX", "DANGEROUS"))

        val shown = BroadcastHistoryFeed.project(raw, currentIdentity = null, limit = 30)

        assertEquals(listOf("DIE TO FALL", "DANGEROUS"), titles(shown))
    }

    /* --------------------------------------------------- dedupe and bounds -- */

    @Test
    fun `adjacent duplicates collapse but a later repeat does not`() {
        val raw = listOf(
            track("THE XX", "DANGEROUS"),
            track("THE XX", "DANGEROUS"),
            track("THE SNUTS", "DEFIBRILLATOR"),
            track("THE XX", "DANGEROUS"),
        )

        val shown = BroadcastHistoryFeed.project(raw, currentIdentity = null, limit = 30)

        assertEquals(listOf("DANGEROUS", "DEFIBRILLATOR", "DANGEROUS"), titles(shown))
    }

    /**
     * The cap is applied after the filtering, so dropping the current track pulls
     * a real entry up into the list rather than leaving it 29 long.
     */
    @Test
    fun `the limit is filled from what survives, not from the payload`() {
        val raw = List(31) { track("ARTIST $it", "TRACK $it") }

        val shown = BroadcastHistoryFeed.project(raw, idOf("ARTIST 0", "TRACK 0"), limit = 30)

        assertEquals(30, shown.size)
        assertEquals("TRACK 1", shown.first().track)
        assertEquals("TRACK 30", shown.last().track)
    }

    @Test
    fun `an empty payload projects to nothing`() {
        assertEquals(emptyList<HistoryTrack>(), BroadcastHistoryFeed.project(emptyList(), null, 30))
    }

    @Test
    fun `a history of nothing but the current track is empty, not a repeat of it`() {
        val raw = listOf(track("THE MAINE", "DIE TO FALL"))

        val shown = BroadcastHistoryFeed.project(raw, idOf("THE MAINE", "DIE TO FALL"), limit = 30)

        assertEquals(emptyList<HistoryTrack>(), shown)
    }
}
