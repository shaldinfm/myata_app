package com.example.musicplayerapp.ui

/**
 * Which of the four frozen frames the full-screen История эфира is showing (G4b):
 * `history-loading` 2517:2420, `history-content` 2523:23, `history-empty`
 * 2517:2509 and `history-error` 2517:2547.
 *
 * A projection over the ViewModel's one history - the same `historyTracks` and
 * `historyLoading` the PLAYER's inline section reads, plus `historyFailed` - and
 * nothing else. It owns no data and fetches nothing.
 *
 * ## The order of the checks is the design
 *
 *  1. **Rows win.** `history-error`'s own note: "Retry re-requests; it does not
 *     clear a cached list." A refresh that fails over rows already on screen
 *     leaves them there, and a refresh in flight over them does not swap them for
 *     a skeleton.
 *  2. **Then loading.** Nothing to show and a request in flight is the skeleton -
 *     including a retry from the error frame, so the tap visibly does something.
 *  3. **Then error.** The last request failed and there is nothing cached.
 *  4. **Otherwise empty.** The request succeeded and the endpoint answered with
 *     nothing - which `history-empty`'s note keeps distinct from the error.
 *
 * The inline PLAYER section does not use this. It has no error frame, and
 * [BroadcastHistoryState] keeps its own three states unchanged.
 *
 * Kept free of Android types so the whole projection is a unit test.
 */
enum class HistoryScreenState {
    LOADING,
    CONTENT,
    EMPTY,
    ERROR;

    companion object {
        /**
         * @param total entries the ViewModel is publishing.
         * @param isLoading whether a history request is in flight.
         * @param failed whether the last finished request failed.
         */
        fun of(total: Int, isLoading: Boolean, failed: Boolean): HistoryScreenState = when {
            total > 0 -> CONTENT
            isLoading -> LOADING
            failed -> ERROR
            else -> EMPTY
        }
    }
}
