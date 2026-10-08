package com.example.musicplayerapp.ui

import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.widget.ImageView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.view.animation.PathInterpolatorCompat
import androidx.core.widget.ImageViewCompat
import com.example.musicplayerapp.R
import com.example.musicplayerapp.data.Reaction

/**
 * Paints the PLAYER's `like` and `dislike` from the track's stored [Reaction].
 *
 * ## Two independent things
 *
 * - **The persistent state** is this: `isSelected`, the glyph and the tint, all three
 *   from [PlayerReactionLook]. At rest a control is its outlined glyph in the frozen
 *   row's own colour; active it is the **filled** glyph - Like in `primary`, the same
 *   resource the play/pause surface is filled with, Dislike in
 *   `player_dislike_active`. The shape is what says "on": `primary` sits at the rest
 *   glyph's luminance in light, so the colour alone never could.
 *   Nothing in a click listener sets any of it; a tap writes the reaction, Room
 *   reports it, and the observer calls this.
 * - **The press** is `animator/player_reaction_press`, a stateListAnimator on the
 *   slot style: scale to 0.92 while pressed and back on release. It only ever reads
 *   `state_pressed`, so when it finishes nothing about the glyph or colour changes.
 *
 * A change of state on screen swaps the glyph at once and fades the tint over
 * `player_reaction_tint_ms`; a first paint (a view that is not attached yet - a new
 * page, a return to the screen, a recreated activity) is immediate, so a rebind never
 * animates from the wrong colour.
 */
object PlayerReactionControls {

    fun render(like: ImageView, dislike: ImageView, reaction: Reaction, animate: Boolean = true) {
        val look = PlayerReactionLook.of(reaction)

        paint(
            like, look.likeActive,
            R.drawable.ic_player_like, R.drawable.ic_player_like_filled,
            R.color.player_like, R.color.primary,
            animate,
        )
        like.contentDescription = like.context.getString(
            if (look.likeActive) R.string.player_favorite_remove else R.string.player_favorite_add
        )

        paint(
            dislike, look.dislikeActive,
            R.drawable.ic_player_dislike, R.drawable.ic_player_dislike_filled,
            R.color.player_control_action, R.color.player_dislike_active,
            animate,
        )
        dislike.contentDescription = dislike.context.getString(
            if (look.dislikeActive) R.string.player_dislike_remove else R.string.player_dislike_add
        )
    }

    /** The colour [view] is meant to end on for [active] - rest or active, in its own theme. */
    fun targetColor(view: ImageView, active: Boolean, @ColorRes rest: Int, @ColorRes on: Int): Int =
        ContextCompat.getColor(view.context, if (active) on else rest)

    /** The glyph [view] was last given - outlined or filled - or null before a first paint. */
    @DrawableRes
    fun shownIcon(view: ImageView): Int? = view.getTag(R.id.player_reaction_icon) as? Int

    private fun paint(
        view: ImageView,
        active: Boolean,
        @DrawableRes outlined: Int,
        @DrawableRes filled: Int,
        @ColorRes rest: Int,
        @ColorRes on: Int,
        animate: Boolean,
    ) {
        view.isSelected = active

        // The glyph is not faded: outline and filled share one box, so the swap is a
        // change of fill and nothing moves. Only on a change, so a re-render of the
        // same state does not rebuild the drawable.
        val icon = if (active) filled else outlined
        if (shownIcon(view) != icon) {
            view.setImageResource(icon)
            view.setTag(R.id.player_reaction_icon, icon)
        }

        val target = targetColor(view, active, rest, on)

        // A running fade belongs to an older state; the new one starts from wherever
        // that fade had got to, so there is never a jump.
        (view.getTag(R.id.player_reaction_tint_animator) as? ValueAnimator)?.cancel()
        view.setTag(R.id.player_reaction_tint_animator, null)

        val from = ImageViewCompat.getImageTintList(view)?.defaultColor
        if (!animate || !view.isAttachedToWindow || from == null || from == target) {
            setTint(view, target)
            return
        }

        val animator = ValueAnimator.ofArgb(from, target).apply {
            duration = view.resources.getInteger(R.integer.player_reaction_tint_ms).toLong()
            interpolator = PathInterpolatorCompat.create(0.4f, 0f, 0.2f, 1f)
            addUpdateListener { setTint(view, it.animatedValue as Int) }
        }
        view.setTag(R.id.player_reaction_tint_animator, animator)
        animator.start()
    }

    private fun setTint(view: ImageView, color: Int) {
        ImageViewCompat.setImageTintList(view, ColorStateList.valueOf(color))
    }
}
