package com.example.musicplayerapp.ui

/**
 * What the PLAYER's inline Broadcast History section is showing.
 *
 * A projection over the history `StreamsViewModel` already publishes - the same
 * `historyTracks` and `historyLoading` the History bottom sheet reads - plus one
 * piece of view state: how many rows the reader has asked to see. It owns no
 * data, fetches nothing and cannot disagree with the ViewModel. Phase C adds an
 * inline surface for the existing history, not a second copy of it.
 *
 * The cap is not here. `StreamsViewModel.loadHistory` requests and keeps at most
 * `HISTORY_LIMIT` entries, so `total` arrives already bounded and this decides
 * only how much of it is revealed.
 *
 * Error is not a state. [com.example.musicplayerapp.data.HistoryRepository]
 * returns an empty list on a failed request rather than raising, so a reachable
 * stream with no history and an unreachable API are the same thing here. That is
 * pre-existing behaviour and this does not change it - it is why [EMPTY]'s string
 * has to be true of both.
 *
 * Kept free of Android types so the whole projection is a unit test.
 */
data class BroadcastHistoryState(
    val mode: Mode,
    /** How many rows to draw. Zero unless [mode] is [Mode.POPULATED]. */
    val visibleCount: Int,
    /** Whether "Показать ещё" has anything left to reveal. */
    val isShowMoreVisible: Boolean,
) {
    enum class Mode {
        /** A request is in flight and there is nothing to show yet. */
        LOADING,

        /** The request finished and produced nothing - no history, or no API. */
        EMPTY,

        /** There are rows. */
        POPULATED,
    }

    companion object {
        /**
         * Rows shown before the reader asks for more.
         *
         * Three, because that is what the frozen section draws: `List` holds one
         * `History Item` and two more sit beside it at y=139 and y=213, one row
         * pitch apart, with `Button:margin` starting immediately below at 287.
         */
        const val INITIAL_ROWS = 3

        /**
         * How many more rows each tap of "Показать ещё" reveals.
         *
         * The frozen frame says nothing about this - it draws one populated
         * state and one button. Ten reaches the 30 the brief asks for in three
         * taps, and it is also what bounds the artwork fan-out: a cover is
         * resolved per visible row, so the reveal step is the number of
         * ArtworkRepository lookups a single tap can start.
         */
        const val REVEAL_STEP = 10

        /**
         * @param total entries the ViewModel is holding, already capped by it.
         * @param isLoading whether a history request is in flight.
         * @param revealed rows the reader has asked for; starts at [INITIAL_ROWS].
         */
        fun of(total: Int, isLoading: Boolean, revealed: Int = INITIAL_ROWS): BroadcastHistoryState {
            if (total <= 0) {
                return BroadcastHistoryState(
                    mode = if (isLoading) Mode.LOADING else Mode.EMPTY,
                    visibleCount = 0,
                    isShowMoreVisible = false,
                )
            }

            // A refresh over rows that are already up keeps them up: the section
            // is inline and swapping it for a spinner on every poll would make
            // the page jump under the reader.
            val visible = revealed.coerceIn(1, total)
            return BroadcastHistoryState(
                mode = Mode.POPULATED,
                visibleCount = visible,
                isShowMoreVisible = visible < total,
            )
        }

        /** The next reveal target after a tap of "Показать ещё". */
        fun reveal(revealed: Int): Int = revealed + REVEAL_STEP
    }
}
