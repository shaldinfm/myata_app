package com.example.musicplayerapp.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentManager
import com.example.musicplayerapp.R
import com.example.musicplayerapp.ui.FindTrackQuery
import com.example.musicplayerapp.ui.FindTrackRows
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * `Bottom Sheet / Найти трек` - find-track-sheet 2517:2524 (G4b).
 *
 * Two doors open it: PLAYER > `Найти трек` for the track playing now, and the
 * circular action on a full-screen История эфира row for that row's track. It is
 * the Collection sheet without its divider and destructive row - the rows are the
 * same include and the same [FindTrackRows] binder, so every destination is
 * [com.example.musicplayerapp.utils.MusicSearchHelper]'s, unchanged.
 *
 * ## It is about the track it was opened for
 *
 * The sheet is handed one [FindTrackQuery] and holds nothing else - no ViewModel,
 * no LiveData. On PLAYER the stream moves on under it; a sheet that followed the
 * now-playing state would retitle itself mid-read, and a tap landing in that
 * moment would search a track the listener never asked about. So the heading and
 * the search are one snapshot, taken when the menu row was tapped, and they cannot
 * disagree: every row searches exactly the words the sheet is showing.
 *
 * Across a configuration change the arguments come back with it, so the snapshot
 * survives a rotation too.
 */
class FindTrackSheet : BottomSheetDialogFragment() {

    private val artist: String get() = requireArguments().getString(ARG_ARTIST).orEmpty()
    private val title: String get() = requireArguments().getString(ARG_TITLE).orEmpty()

    // The same floating sheet theme as the Collection sheet: a transparent window
    // behind a card that draws its own r28, 1px outline and 16 margins.
    override fun getTheme(): Int = R.style.Theme_Myata_CollectionTrackSheet

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.sheet_find_track, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Expanded and never collapsed, for the reason CollectionTrackSheet gives:
        // a 9/16 peek would put the last service below the fold on a short window,
        // and this is four fixed actions rather than a scrolling feed.
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }

        FindTrackRows.bind(view, artist = artist, title = title) { dismiss() }
    }

    companion object {
        const val TAG = "FindTrackSheet"

        const val ARG_ARTIST = "artist"
        const val ARG_TITLE = "title"

        /**
         * Opens the sheet for [query], unless one is already showing.
         *
         * A second tap that lands while the first sheet is still animating in
         * would otherwise stack a second copy on top of it. And nothing opens over
         * saved state: a commit after `onSaveInstanceState` throws, and a tap that
         * races the screen going away has no screen left to open anything on.
         */
        fun show(fm: FragmentManager, query: FindTrackQuery) {
            if (fm.isStateSaved || fm.findFragmentByTag(TAG) != null) return
            FindTrackSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_ARTIST, query.artist)
                    putString(ARG_TITLE, query.title)
                }
            }.show(fm, TAG)
        }
    }
}
