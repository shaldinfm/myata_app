package com.example.musicplayerapp.ui

import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.TransitionDrawable
import android.widget.ImageView
import com.example.musicplayerapp.R
import com.example.musicplayerapp.data.NowPlayingArtwork
import com.squareup.picasso.Callback
import com.squareup.picasso.NetworkPolicy
import com.squareup.picasso.Picasso

/**
 * Track artwork, loaded one way everywhere: the PLAYER, the Mini Player and the TV
 * player through [NowPlaying]; the History and COLLECTION rows through [loadRow].
 *
 * The placeholder is [R.drawable.artwork_placeholder] - the station mark on the
 * theme's surface - wherever a track has no cover to show. It is always inflated
 * through the view's own context, so it is the activity's theme, including a
 * night mode the activity set locally (Picasso's `placeholder(res)` / `error(res)`
 * inflate through the application context and would not be).
 */
object CoverArt {

    /**
     * The current track as one unit: its title, artist and cover change together.
     *
     * A new track is never shown under the previous track's cover, and the
     * placeholder is never a split-second frame between two covers. What the
     * listener sees depends only on where the new track's cover is:
     *
     *  - **decoded in memory** - text and cover switch in the same frame, the
     *    cover crossfading over the old one;
     *  - **on disk** (seen before, or warmed by the ViewModel before it published
     *    the URL) - the previous track stays up, text and cover together, for the
     *    local decode only, then both switch with the crossfade. The wait is bounded
     *    by disk I/O: this load is offline-only and never touches the network;
     *  - **not local** - text switches at once over the placeholder, and the cover
     *    fades in when it arrives;
     *  - **no cover** (lookup still running, [NowPlayingArtwork.NO_IMAGE], or a
     *    failed load) - text switches over the placeholder.
     *
     * The ViewModel keeps the "lookup still running" case to tracks it has never
     * resolved: a track whose answer is already known is published with its cover
     * in the same state (StreamsViewModel.publishTrack), so an ordinary track
     * change never passes through it.
     *
     * No timers: every step waits on an event - the state, a decode, a failure.
     *
     * @param placeholder this surface's plate; TV keeps its own.
     * @param onLoaded the decoded cover, for a caller that needs the pixels - TV's
     *   ambient background. The image at the view's own size (`fit()`).
     * @param onLoadFailed a cover that could not load; the placeholder is up.
     */
    class NowPlaying(
        private val view: ImageView,
        @DrawableRes private val placeholder: Int = R.drawable.artwork_placeholder,
        private val onLoaded: ((Bitmap) -> Unit)? = null,
        private val onLoadFailed: () -> Unit = {},
    ) {
        /** The cover on screen, or null while the placeholder (or nothing yet) is. */
        private var shown: String? = null

        /**
         * Whether this view has shown a track yet. Its first one is drawn as it is -
         * a freshly inflated view is already fading in with its screen - and only
         * later changes crossfade.
         */
        private var presented = false

        /** A cover being loaded, and the text waiting to go up with it. */
        private var loading: String? = null
        private var waiting: (() -> Unit)? = null

        /**
         * Shows the track whose cover is [img], binding its text with [apply] at
         * the moment its cover goes up (or its placeholder does).
         */
        fun present(img: String?, apply: () -> Unit) {
            val url = NowPlayingArtwork.coverUrl(img)

            if (url == null) {
                cancel()
                apply()
                // A cover (or a track held for one) gives way to the placeholder; a
                // placeholder already up stays as it is.
                if (shown != null) crossfade(view, placeholderDrawable(), animate = presented)
                shown = null
                presented = true
                return
            }

            // The same cover: a metadata tick, or a new track from the same release.
            if (url == shown && loading == null) {
                apply()
                return
            }
            // The same cover is already on its way: this text goes up with it,
            // or now if the text has already been released.
            if (url == loading) {
                if (waiting != null) waiting = apply else apply()
                return
            }

            cancel()
            loading = url
            waiting = apply
            val before = view.drawable

            // Memory or disk only. A hit is the cover arriving with its text; a miss
            // is "not local", which must not hold the previous track up.
            request(url).networkPolicy(NetworkPolicy.OFFLINE).noFade()
                .into(view, object : Callback {
                    override fun onSuccess() {
                        if (loading != url) return
                        release()
                        shown = url
                        loading = null
                        val cover = view.drawable
                        if (before != null && cover != null) crossfade(view, cover, from = before, animate = presented)
                        presented = true
                        deliver(cover)
                    }

                    override fun onError(e: Exception?) {
                        if (loading != url) return
                        // Not local: the text goes up now, over the placeholder, and
                        // the cover follows from the network with Picasso's fade.
                        release()
                        crossfade(view, placeholderDrawable(), animate = presented)
                        shown = null
                        presented = true
                        fromNetwork(url)
                    }
                })
        }

        /** The view is gone; the next one starts from nothing. */
        fun reset() {
            cancel()
            shown = null
        }

        private fun fromNetwork(url: String) {
            val request = request(url)
            if (!Motion.enabled(view.context)) request.noFade()
            request.into(view, object : Callback {
                override fun onSuccess() {
                    if (loading != url) return
                    shown = url
                    loading = null
                    deliver(view.drawable)
                }

                override fun onError(e: Exception?) {
                    if (loading != url) return
                    loading = null
                    view.setImageDrawable(placeholderDrawable())
                    onLoadFailed()
                }
            })
        }

        private fun request(url: String) =
            Picasso.get().load(url.toUri()).noPlaceholder().fit().centerCrop()

        private fun release() {
            val text = waiting
            waiting = null
            text?.invoke()
        }

        private fun cancel() {
            Picasso.get().cancelRequest(view)
            loading = null
            // A track that was waiting on its cover and has been superseded never
            // goes up at all; the next one brings its own text.
            waiting = null
        }

        private fun deliver(drawable: Drawable?) {
            val bitmap = ((drawable as? TransitionDrawable)?.let { it.getDrawable(it.numberOfLayers - 1) } ?: drawable)
                .let { it as? BitmapDrawable }?.bitmap
            if (bitmap != null) onLoaded?.invoke(bitmap)
        }

        private fun placeholderDrawable(): Drawable? = ContextCompat.getDrawable(view.context, placeholder)
    }

    /**
     * A list row's cover. The row shows the placeholder until the cover has
     * decoded, then it fades in. A recycled row belongs to a different track, so it
     * is reset with [clearRow] on bind; a row rebound to the *same* track should not
     * be reset at all, which the adapters check before calling either.
     *
     * @param onFailed run when the load fails, after the placeholder is back.
     */
    fun loadRow(view: ImageView, url: String, onFailed: () -> Unit = {}) {
        val request = Picasso.get().load(url).noPlaceholder().fit().centerCrop()
        if (!Motion.enabled(view.context)) request.noFade()
        request.into(view, object : Callback {
            override fun onSuccess() = Unit

            override fun onError(e: Exception?) {
                view.setImageResource(R.drawable.artwork_placeholder)
                onFailed()
            }
        })
    }

    /** Back to the placeholder, with nothing in flight over it. */
    fun clearRow(view: ImageView) {
        Picasso.get().cancelRequest(view)
        view.setImageResource(R.drawable.artwork_placeholder)
    }

    /**
     * Replaces what [view] shows with [to], crossfading from [from] (by default
     * what it shows now) over the app's motion duration. Immediate when the system
     * has animations off or there is nothing to fade from.
     */
    private fun crossfade(
        view: ImageView,
        to: Drawable?,
        from: Drawable? = view.drawable,
        animate: Boolean = true,
    ) {
        val old = (from as? TransitionDrawable)?.let { it.getDrawable(it.numberOfLayers - 1) } ?: from
        if (!animate || to == null || old == null || old === to || !Motion.enabled(view.context)) {
            view.setImageDrawable(to)
            return
        }
        val transition = TransitionDrawable(arrayOf(old, to)).apply { isCrossFadeEnabled = true }
        view.setImageDrawable(transition)
        val duration = Motion.duration(view.context).toInt()
        transition.startTransition(duration)
        // Once the fade has finished, the view holds the cover itself rather than a
        // two-layer drawable still referencing the previous one.
        view.postDelayed({ if (view.drawable === transition) view.setImageDrawable(to) }, duration.toLong())
    }
}
