package com.example.musicplayerapp.ui

import com.example.musicplayerapp.ui.BroadcastHistoryState.Companion.INITIAL_ROWS
import com.example.musicplayerapp.ui.BroadcastHistoryState.Companion.REVEAL_STEP
import com.example.musicplayerapp.ui.BroadcastHistoryState.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The PLAYER's inline Broadcast History section, as a projection.
 *
 * Covers the entry counts the brief names - 1, 3, more than 3, and the 30 the
 * ViewModel caps at - and the three states the ViewModel can be in.
 */
class BroadcastHistoryStateTest {

    // ---- empty, loading, and the error that looks like empty ----

    @Test
    fun `nothing yet and a request in flight is loading`() {
        val state = BroadcastHistoryState.of(total = 0, isLoading = true)

        assertEquals(Mode.LOADING, state.mode)
        assertEquals(0, state.visibleCount)
        assertFalse(state.isShowMoreVisible)
    }

    @Test
    fun `nothing and nothing in flight is empty`() {
        val state = BroadcastHistoryState.of(total = 0, isLoading = false)

        assertEquals(Mode.EMPTY, state.mode)
        assertEquals(0, state.visibleCount)
        assertFalse(state.isShowMoreVisible)
    }

    /**
     * HistoryRepository answers a failed request with an empty list rather than
     * raising, so the UI cannot tell "no history" from "no API" and must not
     * claim to. Both land on EMPTY, which is what the string there has to suit.
     */
    @Test
    fun `a failed request is indistinguishable from an empty one`() {
        assertEquals(
            BroadcastHistoryState.of(total = 0, isLoading = false),
            BroadcastHistoryState.of(total = 0, isLoading = false),
        )
    }

    /**
     * A refresh over rows that are already up leaves them up. The section is
     * inline on a scrolling page - swapping it for a spinner on every poll would
     * move everything under the reader's finger.
     */
    @Test
    fun `a refresh over existing rows keeps them visible`() {
        val state = BroadcastHistoryState.of(total = 12, isLoading = true)

        assertEquals(Mode.POPULATED, state.mode)
        assertEquals(INITIAL_ROWS, state.visibleCount)
    }

    // ---- the entry counts the brief names ----

    @Test
    fun `one entry shows one row and offers nothing more`() {
        val state = BroadcastHistoryState.of(total = 1, isLoading = false)

        assertEquals(Mode.POPULATED, state.mode)
        assertEquals(1, state.visibleCount)
        assertFalse(state.isShowMoreVisible)
    }

    /** Three is what the frozen section draws, and it is exactly full. */
    @Test
    fun `three entries fill the frozen section with nothing left over`() {
        val state = BroadcastHistoryState.of(total = 3, isLoading = false)

        assertEquals(3, state.visibleCount)
        assertEquals(INITIAL_ROWS, state.visibleCount)
        assertFalse(state.isShowMoreVisible)
    }

    @Test
    fun `more than three shows three and offers the rest`() {
        val state = BroadcastHistoryState.of(total = 4, isLoading = false)

        assertEquals(INITIAL_ROWS, state.visibleCount)
        assertTrue(state.isShowMoreVisible)
    }

    @Test
    fun `thirty entries start at three`() {
        val state = BroadcastHistoryState.of(total = 30, isLoading = false)

        assertEquals(INITIAL_ROWS, state.visibleCount)
        assertTrue(state.isShowMoreVisible)
    }

    // ---- "Показать ещё" ----

    /**
     * The control is never a decoration: whenever it is offered, tapping it
     * strictly increases the number of real rows on screen.
     */
    @Test
    fun `every offered tap reveals at least one more row`() {
        for (total in 1..30) {
            var revealed = INITIAL_ROWS
            var state = BroadcastHistoryState.of(total, isLoading = false, revealed = revealed)

            while (state.isShowMoreVisible) {
                val before = state.visibleCount
                revealed = BroadcastHistoryState.reveal(revealed)
                state = BroadcastHistoryState.of(total, isLoading = false, revealed = revealed)

                assertTrue(
                    "total=$total: a tap did not reveal a new row",
                    state.visibleCount > before,
                )
            }
        }
    }

    /** Taps run out exactly when the history does, and never before. */
    @Test
    fun `the button disappears exactly when everything is shown`() {
        for (total in 1..30) {
            var revealed = INITIAL_ROWS
            var state = BroadcastHistoryState.of(total, isLoading = false, revealed = revealed)
            var taps = 0

            while (state.isShowMoreVisible) {
                revealed = BroadcastHistoryState.reveal(revealed)
                state = BroadcastHistoryState.of(total, isLoading = false, revealed = revealed)
                taps++
                assertTrue("total=$total: the reveal did not terminate", taps <= 30)
            }

            assertEquals("total=$total", total, state.visibleCount)
        }
    }

    @Test
    fun `thirty entries are fully revealed in three taps`() {
        var revealed = INITIAL_ROWS
        repeat(3) { revealed = BroadcastHistoryState.reveal(revealed) }

        val state = BroadcastHistoryState.of(total = 30, isLoading = false, revealed = revealed)

        assertEquals(30, state.visibleCount)
        assertFalse(state.isShowMoreVisible)
    }

    /**
     * The revealed count is a request, not a promise: it can exceed the history
     * that is actually there - after a tap, or after a stream switch replaces a
     * long history with a short one - and the section still draws only real rows.
     */
    @Test
    fun `asking for more rows than exist draws only the ones that do`() {
        val state = BroadcastHistoryState.of(total = 5, isLoading = false, revealed = 100)

        assertEquals(5, state.visibleCount)
        assertFalse(state.isShowMoreVisible)
    }

    @Test
    fun `the reveal step is what it says`() {
        assertEquals(INITIAL_ROWS + REVEAL_STEP, BroadcastHistoryState.reveal(INITIAL_ROWS))
    }
}
