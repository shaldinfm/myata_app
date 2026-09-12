package com.example.musicplayerapp.ui

import androidx.core.net.toUri
import android.widget.ImageView
import com.example.musicplayerapp.R
import com.example.musicplayerapp.data.NowPlayingArtwork
import com.squareup.picasso.Callback
import com.squareup.picasso.Picasso

/**
 * Draws the current track's cover into one view - the PLAYER's and the Mini
 * Player's single rule for it.
 *
 * The rule the two surfaces have to share is *when the previous cover comes down*,
 * not how it is loaded. Both used to leave it up: `noPlaceholder` keeps whatever
 * the view already holds until the next image decodes, which is right for a
 * reload of the same cover and wrong for a new track, because the previous
 * track's artwork then stands under the new title for as long as the load takes -
 * and for the whole track when no cover ever arrives (G5 recon, issue B).
 *
 * So the cover comes down as soon as the URL changes, whatever it changes to, and
 * the fallback plate stands until the new one has actually decoded. There is no
 * state here: [render] is told what is currently showing and returns what is
 * showing after it, so each surface keeps its own answer and neither can be
 * confused by the other's.
 */
object CoverArt {

    /**
     * Puts the cover for [img] into [view], or the fallback plate when there is
     * none to put there yet.
     *
     * @param img the current track's [com.example.musicplayerapp.data.PlayerState.img].
     * @param loaded what this view is showing now - the previous return value.
     * @param onLoadFailed run when the load fails, so the caller can forget the
     *   URL and let a later state try it again.
     * @return the URL now on screen, or null when the plate is up.
     */
    fun render(
        view: ImageView,
        img: String?,
        loaded: String?,
        onLoadFailed: () -> Unit = {},
    ): String? {
        val url = NowPlayingArtwork.coverUrl(img)

        if (url == null) {
            // Either no lookup has finished for this track or one found nothing.
            // Both are "this track has no cover", and neither is a reason to keep
            // showing the last track's.
            plate(view)
            return null
        }

        // The same cover as the one already up: leave it alone. A metadata tick
        // that repeats the current track must not restart its load, which is what
        // would make the artwork blink on every poll.
        if (url == loaded) {
            view.alpha = 1f
            return loaded
        }

        // A different cover: down with the old one first. Only after this does
        // `noPlaceholder` mean what it is here for - keeping the plate steady
        // while the new cover decodes, instead of the previous track's artwork.
        plate(view)

        Picasso.get()
            .load(url.toUri())
            .noPlaceholder()
            .error(R.drawable.zaglushka_logo)
            .fit()
            .centerCrop()
            .into(view, object : Callback {
                override fun onSuccess() = Unit

                override fun onError(e: Exception?) {
                    // The plate is already up; this only lets the caller drop the
                    // URL so the same one can be tried again by a later state.
                    view.setImageResource(R.drawable.zaglushka_logo)
                    onLoadFailed()
                }
            })

        return url
    }

    /** The designed fallback: the station logo, with nothing in flight over it. */
    private fun plate(view: ImageView) {
        Picasso.get().cancelRequest(view)
        view.setImageResource(R.drawable.zaglushka_logo)
        view.alpha = 1f
    }
}
