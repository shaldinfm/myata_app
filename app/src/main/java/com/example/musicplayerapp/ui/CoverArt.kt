package com.example.musicplayerapp.ui

import androidx.annotation.DrawableRes
import androidx.core.net.toUri
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.widget.ImageView
import com.example.musicplayerapp.R
import com.example.musicplayerapp.data.NowPlayingArtwork
import com.squareup.picasso.Callback
import com.squareup.picasso.Picasso

/**
 * Draws the current track's cover into one view - the single rule the PLAYER, the
 * Mini Player and the TV player share.
 *
 * ## What a track change looks like
 *
 * Previous cover -> the next cover has decoded -> a short crossfade -> next cover.
 * The previous valid cover stays up until the next one is actually ready, and the
 * placeholder is never an in-between frame. It is shown only when there genuinely
 * is no cover to show:
 *
 *  - the view has never had one (first load of a session, a freshly inflated view);
 *  - the resolver looked and found nothing ([NowPlayingArtwork.NO_IMAGE]);
 *  - the load failed.
 *
 * This reverses the G5a rule, which took the cover down the instant the URL
 * changed and stood the plate up for the length of the lookup and the decode - the
 * "old cover, plate, new cover" flash on every track change. What G5a guarded
 * against was a finished track's cover standing under the next track for the
 * *whole* track, and that cannot come back: the ViewModel's lookup always ends in
 * either a URL or NO_IMAGE (a failure is published as NO_IMAGE), so "pending" is
 * bounded by one lookup and NO_IMAGE still brings the placeholder up.
 *
 * ## The crossfade
 *
 * Picasso's own: with `noPlaceholder` the view keeps what it is showing, and when
 * the new bitmap arrives from disk or network Picasso fades it in over that
 * drawable. A cover already in the memory cache is swapped in the same frame,
 * which is what a view being re-inflated with the cover it had wants. With system
 * animations removed the fade is skipped ([Motion.enabled]).
 *
 * There is no state here: [render] is told what is currently showing and returns
 * what is showing after it, so each surface keeps its own answer.
 */
object CoverArt {

    /**
     * Puts the cover for [img] into [view], keeping the previous one up while
     * the next is on its way.
     *
     * @param img the current track's [com.example.musicplayerapp.data.PlayerState.img]:
     *   a URL, [NowPlayingArtwork.NO_IMAGE], or null while its lookup runs.
     * @param loaded what this view is showing, or loading, now - the previous
     *   return value.
     * @param onLoaded the decoded cover, for a caller that needs the pixels as
     *   well as the picture - the TV player takes its ambient background colour
     *   from exactly this bitmap. It is the image at the view's own size (`fit()`).
     *   Null for every caller that only wanted the picture.
     * @param placeholder the plate for this surface. The phone uses the
     *   theme-aware [R.drawable.artwork_placeholder]; TV keeps its own.
     * @param onLoadFailed run when the load fails, so the caller can forget the
     *   URL and let a later state try it again.
     * @return the URL now on screen (or on its way), or null when the placeholder is up.
     */
    fun render(
        view: ImageView,
        img: String?,
        loaded: String?,
        onLoaded: ((Bitmap) -> Unit)? = null,
        @DrawableRes placeholder: Int = R.drawable.artwork_placeholder,
        onLoadFailed: () -> Unit = {},
    ): String? {
        val url = NowPlayingArtwork.coverUrl(img)

        if (url == null) {
            if (img == null && loaded != null) {
                // A new track whose lookup has not answered yet. The previous cover
                // stays until the answer is here - a URL to fade to, or NO_IMAGE.
                return loaded
            }
            // Nothing has ever been shown, or the resolver found nothing.
            showPlaceholder(view, placeholder)
            return null
        }

        // The same cover as the one already up: leave it alone. A metadata tick
        // that repeats the current track must not restart its load, which is what
        // would make the artwork blink on every poll.
        if (url == loaded) {
            view.alpha = 1f
            return loaded
        }

        // A different cover. The view keeps showing what it has - the previous
        // cover, or the placeholder - until this one has decoded.
        val request = Picasso.get()
            .load(url.toUri())
            .noPlaceholder()
            .fit()
            .centerCrop()
        if (!Motion.enabled(view.context)) request.noFade()

        view.alpha = 1f
        request.into(view, object : Callback {
            override fun onSuccess() {
                // Picasso's success drawable carries the decoded, `fit()`-sized
                // image - the same pixels now on screen.
                if (onLoaded != null) {
                    (view.drawable as? BitmapDrawable)?.bitmap?.let(onLoaded)
                }
            }

            override fun onError(e: Exception?) {
                // Not Picasso's `error(res)`: that inflates through the
                // application context, which does not carry the activity's own
                // night mode, so a Dark app on a Light system got a Light plate.
                view.setImageResource(placeholder)
                onLoadFailed()
            }
        })

        return url
    }

    /**
     * A list row's cover, loaded the same way as the current track's: whatever
     * the row shows stays until the cover has decoded, then it fades in.
     *
     * Rows differ from [render] in one respect only - a recycled row belongs to a
     * different track, so it is reset with [clearRow] on bind instead of keeping
     * the previous cover. A row rebound to the *same* track should not be reset at
     * all; the adapters check that before calling either.
     *
     * @param placeholder the row's plate, or null for a row whose plate is its own
     *   background (COLLECTION's `surface_container` tile).
     * @param onFailed run when the load fails, after the plate is back.
     */
    fun loadRow(
        view: ImageView,
        url: String,
        @DrawableRes placeholder: Int?,
        onFailed: () -> Unit = {},
    ) {
        val request = Picasso.get().load(url).noPlaceholder().fit().centerCrop()
        if (!Motion.enabled(view.context)) request.noFade()
        request.into(view, object : Callback {
            override fun onSuccess() = Unit

            override fun onError(e: Exception?) {
                // Through the view's context for the same reason as render's.
                if (placeholder != null) view.setImageResource(placeholder) else view.setImageDrawable(null)
                onFailed()
            }
        })
    }

    /** Back to the row's plate, with nothing in flight over it. */
    fun clearRow(view: ImageView, @DrawableRes placeholder: Int?) {
        Picasso.get().cancelRequest(view)
        if (placeholder != null) view.setImageResource(placeholder) else view.setImageDrawable(null)
    }

    /**
     * The placeholder, with nothing in flight over it. Resolved through the view's
     * own context, so it is the current theme's plate.
     */
    fun showPlaceholder(view: ImageView, @DrawableRes placeholder: Int = R.drawable.artwork_placeholder) {
        Picasso.get().cancelRequest(view)
        view.setImageResource(placeholder)
        view.alpha = 1f
    }
}
