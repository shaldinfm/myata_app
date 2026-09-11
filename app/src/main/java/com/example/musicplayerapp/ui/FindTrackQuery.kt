package com.example.musicplayerapp.ui

/**
 * One track the find-track sheet can search the streaming services for (G4b).
 *
 * The PLAYER's current track and a Broadcast History row both reach the sheet as
 * one of these, so the sheet never has to know which surface opened it. The
 * COLLECTION sheet does not go through here: it hands the saved row's own words
 * to the same rows, exactly as it always has.
 *
 * ## Why [of] can refuse
 *
 * A search needs both halves. An artist with no title, or the reverse, is not a
 * track the listener can recognise in a result list - and "search Spotify for
 * nothing" is a dead control dressed as a live one. So a blank half is no query
 * at all, and the entry point that asked for one draws itself unavailable rather
 * than opening a sheet with an empty heading.
 *
 * The PLAYER's placeholder pair ("YOUR MUSIC! YOUR STATION!" / "RADIO MYATA") is
 * refused one level up, in `StreamsViewModel.nowPlayingQuery`, because only the
 * ViewModel knows those strings are the placeholder rather than a track.
 *
 * Kept free of Android types so it is a unit test.
 */
data class FindTrackQuery(val artist: String, val title: String) {

    companion object {
        /** A query for [artist] and [title], or null when either is blank. */
        fun of(artist: String?, title: String?): FindTrackQuery? {
            val a = artist?.trim().orEmpty()
            val t = title?.trim().orEmpty()
            if (a.isEmpty() || t.isEmpty()) return null
            return FindTrackQuery(artist = a, title = t)
        }
    }
}
