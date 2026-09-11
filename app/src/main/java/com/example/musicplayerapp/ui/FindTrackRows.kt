package com.example.musicplayerapp.ui

import android.content.Context
import android.view.View
import android.widget.TextView
import com.example.musicplayerapp.R
import com.example.musicplayerapp.utils.MusicSearchHelper

/**
 * The header and the four service rows of the find-track sheet, wherever it is
 * drawn (G4b).
 *
 * The frozen file has one find-track pattern - `find-track-sheet` 2517:2524's own
 * note is "One find-track pattern shared by Broadcast History, the player menu and
 * Collection. Collection adds a divider and a destructive row below these four;
 * nothing else differs." So there is one layout
 * (`include_find_track_rows.xml`) and one binder, and the two sheets are
 * presentation variants of it:
 *
 *  - [com.example.musicplayerapp.fragments.FindTrackSheet] - PLAYER and History:
 *    these four rows and nothing below them.
 *  - [com.example.musicplayerapp.fragments.CollectionTrackSheet] - the same four,
 *    then its own divider and `Удалить из коллекции`. The removal stays in that
 *    sheet and in FavoritesFragment; nothing here knows it exists.
 *
 * Every destination and every search string is [MusicSearchHelper]'s, unchanged.
 * This decides only which row calls which of its four functions. Whether an app
 * or a browser answers is the helper's `ACTION_VIEW` on an https link, as it has
 * always been.
 */
object FindTrackRows {

    /** The four rows in the frozen order, and the search each one runs. */
    private val SERVICES: List<Pair<Int, (Context, String, String) -> Unit>> = listOf(
        R.id.row_spotify to MusicSearchHelper::openSpotify,
        R.id.row_apple_music to MusicSearchHelper::openAppleMusic,
        R.id.row_youtube to MusicSearchHelper::openYouTube,
        R.id.row_yandex to MusicSearchHelper::openYandexMusic,
    )

    /**
     * Writes the header and wires the rows under [root].
     *
     * `Bottom Sheet / title` is the TRACK and `Bottom Sheet / subtitle` the
     * ARTIST, in every variant - the frozen sheet reads "CRYOGEN" over "MUSE".
     *
     * @param onChosen runs after a row has handed its search off, so the sheet can
     *   close. The search itself has already been started when it runs.
     */
    fun bind(root: View, artist: String, title: String, onChosen: () -> Unit) {
        root.findViewById<TextView>(R.id.sheet_title).text = title
        root.findViewById<TextView>(R.id.sheet_subtitle).text = artist

        for ((rowId, open) in SERVICES) {
            root.findViewById<View>(rowId).setOnClickListener { row ->
                open(row.context, artist, title)
                onChosen()
            }
        }
    }
}
