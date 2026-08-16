package com.example.musicplayerapp.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.setFragmentResult
import com.example.musicplayerapp.R
import com.example.musicplayerapp.utils.MusicSearchHelper
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * The FINAL per-track sheet, `Bottom Sheet / Действия с треком` (F1).
 *
 * This is what the single circular action on a Collection row opens, and it is
 * where the four inline service buttons and the delete cross the row used to
 * carry have gone. The layout is the frozen one; what lives here is only which
 * row does what, and that is deliberately unchanged from the controls it
 * replaces:
 *
 *  - Spotify / Apple Music / YouTube / Яндекс Музыка call exactly the
 *    [MusicSearchHelper] functions the inline buttons called, with the same
 *    artist and track. The helper itself is untouched, so every destination and
 *    every search string is the one that shipped.
 *  - `Удалить из коллекции` does not delete anything here. It reports the
 *    request back to [FavoritesFragment] through the fragment result API and
 *    closes, so the removal and its undo stay in one place next to the list that
 *    has to show both.
 *
 * The sheet is stateless: it is handed the artist and track it is about, and it
 * holds no reference to the row, the adapter or the database.
 */
class CollectionTrackSheet : BottomSheetDialogFragment() {

    private val artist: String get() = requireArguments().getString(ARG_ARTIST).orEmpty()
    private val track: String get() = requireArguments().getString(ARG_TRACK).orEmpty()

    override fun getTheme(): Int = R.style.Theme_Myata_CollectionTrackSheet

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_collection_track, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Open expanded, and never collapse.
        //
        // A bottom sheet's default is STATE_COLLAPSED at a peek height of 9/16 of
        // the window, which on a 1080x1794 API 24 window left the last row - the
        // destructive one - below the fold, reachable only by dragging the sheet
        // up. That is the right default for a sheet whose content is a long list
        // and the wrong one for five fixed actions: the whole point of this
        // surface is that the user can see what the row's single control offers.
        //
        // The frozen 447 fits with room to spare on both QA devices - the API 24
        // window measured 683dp tall and the API 36 one more - and the
        // NestedScrollView underneath is what covers anything shorter, so
        // expanding cannot push content off the top.
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }

        // `Bottom Sheet / title` is the track and `Bottom Sheet / subtitle` the
        // artist - the same order the row above draws them in, not the reverse.
        view.findViewById<TextView>(R.id.sheet_title).text = track
        view.findViewById<TextView>(R.id.sheet_subtitle).text = artist

        val ctx = requireContext()
        view.findViewById<View>(R.id.row_spotify).setOnClickListener {
            MusicSearchHelper.openSpotify(ctx, artist, track)
            dismiss()
        }
        view.findViewById<View>(R.id.row_apple_music).setOnClickListener {
            MusicSearchHelper.openAppleMusic(ctx, artist, track)
            dismiss()
        }
        view.findViewById<View>(R.id.row_youtube).setOnClickListener {
            MusicSearchHelper.openYouTube(ctx, artist, track)
            dismiss()
        }
        view.findViewById<View>(R.id.row_yandex).setOnClickListener {
            MusicSearchHelper.openYandexMusic(ctx, artist, track)
            dismiss()
        }

        view.findViewById<View>(R.id.row_remove).setOnClickListener {
            setFragmentResult(RESULT_REMOVE, Bundle().apply {
                putString(ARG_ARTIST, artist)
                putString(ARG_TRACK, track)
            })
            dismiss()
        }
    }

    companion object {
        const val TAG = "CollectionTrackSheet"

        /** Result key for `Удалить из коллекции`; the payload identifies the row. */
        const val RESULT_REMOVE = "collection_track_remove"

        const val ARG_ARTIST = "artist"
        const val ARG_TRACK = "track"

        fun show(fm: FragmentManager, artist: String, track: String) {
            CollectionTrackSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_ARTIST, artist)
                    putString(ARG_TRACK, track)
                }
            }.show(fm, TAG)
        }
    }
}
