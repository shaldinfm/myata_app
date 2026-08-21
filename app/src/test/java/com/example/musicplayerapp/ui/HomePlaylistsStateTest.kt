package com.example.musicplayerapp.ui

import com.example.musicplayerapp.data.PlaylistsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The HOME playlist section, as a decision table.
 *
 * These are the states HOME could never be in before: the splash guaranteed the
 * data existed by the time HOME did, so "not loaded yet" and "the load failed"
 * had no rendering at all. Each case here is one of those, pinned so the section
 * keeps its answer when the splash stops providing the guarantee.
 */
class HomePlaylistsStateTest {

    // ==================== nothing yet ====================

    @Test
    fun `before the first value there is nothing to say but a spinner`() {
        // The LiveData has not emitted: HOME was created before the loader spoke.
        assertEquals(
            HomePlaylistsState.LOADING,
            HomePlaylistsState.of(state = null, itemCount = 0, isOnline = true),
        )
    }

    @Test
    fun `loading with nothing held is loading`() {
        assertEquals(
            HomePlaylistsState.LOADING,
            HomePlaylistsState.of(PlaylistsState.LOADING, itemCount = 0, isOnline = true),
        )
    }

    // ==================== content wins ====================

    @Test
    fun `data that has arrived is shown whatever the loader is doing`() {
        // The case that matters most: a refresh, a poll, or a failure must never
        // replace cards the reader can already see.
        for (state in listOf(null, PlaylistsState.LOADING, PlaylistsState.READY, PlaylistsState.ERROR)) {
            for (online in listOf(true, false)) {
                assertEquals(
                    "state=$state online=$online",
                    HomePlaylistsState.POPULATED,
                    HomePlaylistsState.of(state, itemCount = 3, isOnline = online),
                )
            }
        }
    }

    @Test
    fun `a single playlist is still content`() {
        assertEquals(
            HomePlaylistsState.POPULATED,
            HomePlaylistsState.of(PlaylistsState.READY, itemCount = 1, isOnline = true),
        )
    }

    // ==================== failure ====================

    @Test
    fun `a failure with a network offers a retry`() {
        val state = HomePlaylistsState.of(PlaylistsState.ERROR, itemCount = 0, isOnline = true)
        assertEquals(HomePlaylistsState.ERROR_FAILED, state)
        assertTrue(state.isRetryable)
        assertTrue(state.isStatus)
    }

    @Test
    fun `a failure with no network says so instead`() {
        // Same state to the loader, a different sentence to read - which is the
        // only reason connectivity is consulted at all.
        val state = HomePlaylistsState.of(PlaylistsState.ERROR, itemCount = 0, isOnline = false)
        assertEquals(HomePlaylistsState.ERROR_OFFLINE, state)
        assertTrue(state.isRetryable)
    }

    // ==================== the empty answer ====================

    @Test
    fun `ready with nothing in it is empty rather than an error`() {
        // Unreachable through refreshPlaylists today, which retries while the
        // response is empty - but mapped, so a future loader change lands in a
        // defined state instead of an undefined one.
        val state = HomePlaylistsState.of(PlaylistsState.READY, itemCount = 0, isOnline = true)
        assertEquals(HomePlaylistsState.EMPTY, state)
        assertFalse(state.isStatus)
        assertFalse(state.isRetryable)
    }

    // ==================== the shape of the answers ====================

    @Test
    fun `only the row draws cards`() {
        assertFalse(HomePlaylistsState.POPULATED.isStatus)
        assertFalse(HomePlaylistsState.POPULATED.isRetryable)
    }

    @Test
    fun `loading is a status without a retry`() {
        assertTrue(HomePlaylistsState.LOADING.isStatus)
        assertFalse(HomePlaylistsState.LOADING.isRetryable)
    }

    @Test
    fun `every combination maps to exactly one state`() {
        // Totality: the fragment reads `vm.playlistsState.value`, which is
        // nullable, and a size it did not check. Nothing may fall through.
        val states = listOf(null, PlaylistsState.LOADING, PlaylistsState.READY, PlaylistsState.ERROR)
        var mapped = 0
        for (s in states) for (n in listOf(0, 1, 7)) for (online in listOf(true, false)) {
            HomePlaylistsState.of(s, n, online)
            mapped++
        }
        assertEquals(states.size * 3 * 2, mapped)
    }
}
