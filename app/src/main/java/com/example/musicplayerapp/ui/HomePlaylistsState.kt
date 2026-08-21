package com.example.musicplayerapp.ui

import com.example.musicplayerapp.data.PlaylistsState

/**
 * What HOME's "Мятные плейлисты" section is showing.
 *
 * A projection over the two things `StreamsViewModel` already publishes -
 * `playlistsState` and `playlistList` - plus whether the device currently has a
 * network. It owns no data, fetches nothing, and cannot disagree with the
 * ViewModel.
 *
 * It exists because HOME used to be unable to answer this question at all. The
 * section read `playlistList.value` **once**, in `onCreateView`, with no observer:
 * that was only ever safe because the splash screen held HOME back until the load
 * had finished, so the value was guaranteed non-null by the time HOME existed.
 * The moment HOME can be created earlier - which is the point of this work - the
 * section needs a real answer for "not loaded yet", "loaded nothing", and "the
 * load failed", and that answer is here rather than spread through the fragment.
 *
 * ## Content wins over status
 *
 * The first rule is that anything already on screen stays on screen. A refresh
 * that fails, or a poll that starts again, must not replace cards the reader can
 * already see with a spinner or an error - so a non-empty list is [Mode.POPULATED]
 * whatever the load state says. It is the same principle
 * [BroadcastHistoryState] applies to the PLAYER's history rows, and for the same
 * reason: the section is inline, and swapping it out under the reader is worse
 * than showing slightly stale content.
 *
 * ## READY with nothing in it
 *
 * Not reachable today: `refreshPlaylists` retries while the response is empty and
 * only reports READY when it has a non-empty list, so READY implies content. The
 * case is still mapped, because "the mapper is total" is cheaper than "the caller
 * must know which combinations cannot happen", and a future change to the loader
 * would otherwise land HOME in an undefined state rather than a defined empty one.
 *
 * Kept free of Android types - no `Context`, no resource ids - so the whole
 * projection is a unit test. The fragment turns [Mode] into views and strings.
 */
enum class HomePlaylistsState {

    /** A load is in flight and there is nothing to show yet. */
    LOADING,

    /** There are cards. The only state in which the row itself is drawn. */
    POPULATED,

    /**
     * The load finished and produced nothing. The section has nothing to say, so
     * the heading goes with the row rather than captioning an empty band.
     */
    EMPTY,

    /** The load failed while the device has a network: offer a retry. */
    ERROR_FAILED,

    /** The load failed and there is no network to retry over yet. */
    ERROR_OFFLINE;

    /** Whether the section shows its inline status instead of the card row. */
    val isStatus: Boolean get() = this == LOADING || this == ERROR_FAILED || this == ERROR_OFFLINE

    /** Whether the reader is being offered a Retry. */
    val isRetryable: Boolean get() = this == ERROR_FAILED || this == ERROR_OFFLINE

    companion object {

        /**
         * @param state the loader's own state, as published by the ViewModel.
         * @param itemCount how many playlists are currently held. Content wins:
         *   any non-empty list is [POPULATED] regardless of [state].
         * @param isOnline whether the device has a network right now. Only
         *   consulted to choose which failure the reader is told about.
         */
        fun of(state: PlaylistsState?, itemCount: Int, isOnline: Boolean): HomePlaylistsState {
            if (itemCount > 0) return POPULATED

            return when (state) {
                PlaylistsState.READY -> EMPTY
                PlaylistsState.ERROR -> if (isOnline) ERROR_FAILED else ERROR_OFFLINE
                // LOADING, and null - the LiveData before its first value. Both mean
                // "no answer yet", which is what the spinner says.
                else -> LOADING
            }
        }
    }
}
