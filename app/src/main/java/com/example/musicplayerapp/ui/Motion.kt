package com.example.musicplayerapp.ui

import android.animation.ValueAnimator
import android.content.Context
import android.provider.Settings
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.navigation.NavOptions
import com.example.musicplayerapp.R

/**
 * The app's motion, in one place, so every screen moves the same way.
 *
 * Two things only, both deliberately plain:
 *
 *  - **screen changes** are the [screenFade] crossfade, the one every action in
 *    the nav graph already declared - calls that navigate by destination id take
 *    it from here rather than arriving without one;
 *  - **elements that appear with a screen** - the Mini Player, the bottom bar, a
 *    section whose data has just arrived - fade in over the same
 *    `motion_duration_medium`, through [reveal]. Hiding is immediate: whatever
 *    hides an element is also taking its screen away, and a fade-out would leave
 *    it floating over the next one.
 *
 * Everything runs on Animator, which the platform scales by the system animator
 * duration scale. With animations removed (Accessibility > Remove animations, or
 * the developer option at 0x) the fades complete in the same frame, so nothing
 * here needs a code path of its own for reduced motion - [enabled] exists only for
 * Picasso, whose crossfade is not an Animator.
 */
object Motion {

    private val enter = DecelerateInterpolator()

    /** The crossfade every navigation uses, as options for `navigate(id)`. */
    fun screenFade(builder: NavOptions.Builder = NavOptions.Builder()): NavOptions =
        builder
            .setEnterAnim(R.animator.fade_in)
            .setExitAnim(R.animator.fade_out)
            .setPopEnterAnim(R.animator.fade_in)
            .setPopExitAnim(R.animator.fade_out)
            .build()

    fun duration(context: Context): Long =
        context.resources.getInteger(R.integer.motion_duration_medium).toLong()

    /**
     * Makes [view] visible, fading it in if it was not already on screen.
     *
     * `visibility` flips at once, so layout and anything that reads it see the
     * final state immediately; only the alpha is animated. Calling it on a view
     * that is already fully shown does nothing, so it is safe from observers that
     * fire on every tick.
     */
    fun reveal(view: View) {
        if (view.visibility == View.VISIBLE && view.alpha == 1f) return
        if (view.visibility != View.VISIBLE) {
            view.animate().cancel()
            view.alpha = 0f
            view.visibility = View.VISIBLE
        }
        view.animate()
            .alpha(1f)
            .setDuration(duration(view.context))
            .setInterpolator(enter)
            .start()
    }

    /** Takes [view] off screen at once, cancelling a reveal still in progress. */
    fun conceal(view: View, hidden: Int = View.GONE) {
        view.animate().cancel()
        view.alpha = 1f
        view.visibility = hidden
    }

    fun setShown(view: View, shown: Boolean) {
        if (shown) reveal(view) else conceal(view)
    }

    /**
     * Whether the system lets the app animate at all. False when the animator
     * duration scale is 0 - the switch "Remove animations" flips.
     */
    fun enabled(context: Context): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            ValueAnimator.areAnimatorsEnabled()
        } else {
            Settings.Global.getFloat(
                context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
            ) != 0f
        }
}
