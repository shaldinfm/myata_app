package com.example.musicplayerapp.ui

import android.content.Intent
import android.net.Uri

/**
 * What a tap on a HOME playlist card opens: the playlist's link, in a browser.
 *
 * The link is the one `playlists.txt` gave, verbatim - `Uri.parse` keeps the string it is
 * given, so `?slug=dance-mix` reaches the browser as `?slug=dance-mix`. Split out of
 * `MainFragment` only so a device test can assert on the exact Intent a tap sends.
 */
object PlaylistLink {

    fun viewIntent(link: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(link)).addCategory(Intent.CATEGORY_BROWSABLE)
}
