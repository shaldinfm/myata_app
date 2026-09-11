package com.example.musicplayerapp.ui

import com.example.musicplayerapp.ui.HistoryScreenState.CONTENT
import com.example.musicplayerapp.ui.HistoryScreenState.EMPTY
import com.example.musicplayerapp.ui.HistoryScreenState.ERROR
import com.example.musicplayerapp.ui.HistoryScreenState.LOADING
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The full-screen История эфира's four frames, as a projection (G4b).
 *
 * Every combination of the three inputs, because the order the checks run in is
 * the whole design and a reordering would still compile.
 */
class HistoryScreenStateTest {

    @Test
    fun `nothing yet and a request in flight is the skeleton`() {
        assertEquals(LOADING, HistoryScreenState.of(total = 0, isLoading = true, failed = false))
    }

    @Test
    fun `a successful answer with nothing in it is history-empty`() {
        assertEquals(EMPTY, HistoryScreenState.of(total = 0, isLoading = false, failed = false))
    }

    @Test
    fun `a failed request with nothing cached is history-error`() {
        assertEquals(ERROR, HistoryScreenState.of(total = 0, isLoading = false, failed = true))
    }

    /**
     * Empty and error are two frozen frames with two different messages, and they
     * are distinct here because the repository now says which one happened. This
     * is the inverse of what BroadcastHistoryStateTest pins for the inline section,
     * which keeps treating them as one.
     */
    @Test
    fun `empty and error are different frames`() {
        val empty = HistoryScreenState.of(total = 0, isLoading = false, failed = false)
        val error = HistoryScreenState.of(total = 0, isLoading = false, failed = true)
        assert(empty != error)
    }

    /** A retry from the error frame shows the skeleton while it runs. */
    @Test
    fun `a retry in flight after a failure is the skeleton, not the error`() {
        assertEquals(LOADING, HistoryScreenState.of(total = 0, isLoading = true, failed = true))
    }

    /** history-error's note: "Retry re-requests; it does not clear a cached list." */
    @Test
    fun `a failed refresh over cached rows keeps the rows`() {
        assertEquals(CONTENT, HistoryScreenState.of(total = 12, isLoading = false, failed = true))
    }

    @Test
    fun `a refresh in flight over rows keeps the rows`() {
        assertEquals(CONTENT, HistoryScreenState.of(total = 12, isLoading = true, failed = false))
        assertEquals(CONTENT, HistoryScreenState.of(total = 12, isLoading = true, failed = true))
    }

    @Test
    fun `rows are content from one to the ceiling`() {
        for (total in 1..30) {
            assertEquals("total=$total", CONTENT, HistoryScreenState.of(total, isLoading = false, failed = false))
        }
    }
}
