package com.example.musicplayerapp.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The PLAYER control's three faces, one case per rule.
 *
 * The projection is small on purpose: the two inputs are the service's own, and
 * the only judgement in here is which wins when both are true. `PlayerControl`
 * turns the answer into pixels and never asks a second question.
 */
class PlayerControlStateTest {

    @Test
    fun `nothing loaded shows Play`() {
        assertEquals(
            PlayerControlState.PLAY,
            PlayerControlState.of(isPlaying = false, isBuffering = false),
        )
    }

    @Test
    fun `a paused stream shows Play - the same face as idle`() {
        // The control carries no notion of "loaded but paused"; the frozen design
        // gives it one Play glyph and this is it.
        assertEquals(
            PlayerControlState.PLAY,
            PlayerControlState.of(isPlaying = false, isBuffering = false),
        )
    }

    @Test
    fun `playing shows Pause`() {
        assertEquals(
            PlayerControlState.PAUSE,
            PlayerControlState.of(isPlaying = true, isBuffering = false),
        )
    }

    @Test
    fun `connecting shows the progress indicator`() {
        assertEquals(
            PlayerControlState.CONNECTING,
            PlayerControlState.of(isPlaying = false, isBuffering = true),
        )
    }

    /**
     * A stream switch: the old stream is still playing while the new one
     * connects. The control has to say "connecting" for the swap to read, so
     * buffering wins.
     */
    @Test
    fun `buffering while still playing the old stream shows the progress indicator`() {
        assertEquals(
            PlayerControlState.CONNECTING,
            PlayerControlState.of(isPlaying = true, isBuffering = true),
        )
    }
}
