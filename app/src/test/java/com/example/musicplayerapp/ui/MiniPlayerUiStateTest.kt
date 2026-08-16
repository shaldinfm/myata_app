package com.example.musicplayerapp.ui

import com.example.musicplayerapp.data.PlayerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Mini Player's projection of the existing playback state.
 *
 * The point of these is that the pill and the player screen cannot disagree: the
 * title/artist split, the placeholder pair and the "no artwork" markers are all
 * the ones `MyataStreamFragment` already uses, and a change to either side that
 * breaks that agreement should fail here.
 */
class MiniPlayerUiStateTest {

    private val brand = "RADIO MYATA"
    private val slogan = "YOUR MUSIC! YOUR STATION!"

    private fun project(
        stream: String?,
        myata: PlayerState? = null,
        gold: PlayerState? = null,
        xtra: PlayerState? = null,
        isPlaying: Boolean = false,
    ) = MiniPlayerUiState.from(stream, myata, gold, xtra, isPlaying, brand, slogan)

    private fun track(artist: String, song: String, img: String? = null) =
        PlayerState(artist, song, img)

    @Test
    fun `title is the song and artist is the artist`() {
        val ui = project("myata", myata = track("TWO DOOR CINEMA CLUB", "WHAT YOU KNOW"))

        assertEquals("WHAT YOU KNOW", ui.title)
        assertEquals("TWO DOOR CINEMA CLUB", ui.artist)
    }

    @Test
    fun `each stream reads its own metadata`() {
        val myata = track("A", "a")
        val gold = track("B", "b")
        val xtra = track("C", "c")

        assertEquals("a", project("myata", myata, gold, xtra).title)
        assertEquals("b", project("gold", myata, gold, xtra).title)
        assertEquals("c", project("myata_hits", myata, gold, xtra).title)
    }

    @Test
    fun `xtra alias resolves to the myata_hits metadata`() {
        val ui = project("xtra", myata = track("A", "a"), xtra = track("C", "c"))

        assertEquals("c", ui.title)
    }

    @Test
    fun `an unknown or missing stream key falls back to the default stream`() {
        val myata = track("A", "a")

        assertEquals("a", project(null, myata = myata).title)
        assertEquals("a", project("android.intent.action.PLAY", myata = myata).title)
    }

    @Test
    fun `no metadata yet shows the brand pair, not empty text`() {
        val ui = project("myata", myata = null)

        assertEquals(brand, ui.title)
        assertEquals(slogan, ui.artist)
        assertNull(ui.artworkUrl)
    }

    @Test
    fun `blank metadata is treated as no metadata`() {
        val ui = project("myata", myata = track(artist = "  ", song = "WHAT YOU KNOW"))

        assertEquals(brand, ui.title)
        assertEquals(slogan, ui.artist)
    }

    @Test
    fun `artwork url is passed through when there is one`() {
        val ui = project("myata", myata = track("A", "a", img = "https://example.test/a.jpg"))

        assertEquals("https://example.test/a.jpg", ui.artworkUrl)
    }

    @Test
    fun `NO_IMAGE and blank both mean there is no artwork to load`() {
        assertNull(project("myata", myata = track("A", "a", img = "NO_IMAGE")).artworkUrl)
        assertNull(project("myata", myata = track("A", "a", img = "")).artworkUrl)
        assertNull(project("myata", myata = track("A", "a", img = null)).artworkUrl)
    }

    @Test
    fun `playing state is carried through untouched`() {
        val myata = track("A", "a")

        assertTrue(project("myata", myata = myata, isPlaying = true).playing)
        assertFalse(project("myata", myata = myata, isPlaying = false).playing)
    }

    @Test
    fun `the placeholder pair does not depend on the playing state`() {
        val ui = project("myata", myata = null, isPlaying = true)

        assertEquals(brand, ui.title)
        assertTrue(ui.playing)
    }

    /* ------------------------------------------------- the connecting face -- */

    /**
     * The pill's play/pause slot and the PLAYER's central control read the same
     * two inputs and must reach the same answer - they are one tap apart, and a
     * pill still showing Play while the player shows a spinner is the pair
     * contradicting itself mid-connect.
     */
    private fun control(isPlaying: Boolean, isBuffering: Boolean) =
        MiniPlayerUiState.from(
            "myata", track("A", "a"), null, null, isPlaying, brand, slogan, isBuffering,
        ).control

    @Test
    fun `the control face is the players own projection`() {
        assertEquals(PlayerControlState.PLAY, control(isPlaying = false, isBuffering = false))
        assertEquals(PlayerControlState.PAUSE, control(isPlaying = true, isBuffering = false))
        assertEquals(PlayerControlState.CONNECTING, control(isPlaying = false, isBuffering = true))
    }

    @Test
    fun `buffering wins over playing, as it does on the player screen`() {
        assertEquals(PlayerControlState.CONNECTING, control(isPlaying = true, isBuffering = true))
    }

    @Test
    fun `the connecting face survives the placeholder pair`() {
        val ui = MiniPlayerUiState.from(
            "myata", null, null, null, false, brand, slogan, isBuffering = true,
        )

        assertEquals(brand, ui.title)
        assertEquals(PlayerControlState.CONNECTING, ui.control)
    }

    @Test
    fun `a caller that says nothing about buffering gets the playing face`() {
        assertEquals(PlayerControlState.PAUSE, project("myata", myata = track("A", "a"), isPlaying = true).control)
        assertEquals(PlayerControlState.PLAY, project("myata", myata = track("A", "a")).control)
    }
}
