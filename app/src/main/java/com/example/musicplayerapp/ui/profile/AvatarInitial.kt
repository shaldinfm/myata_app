package com.example.musicplayerapp.ui.profile

import android.graphics.Rect
import android.widget.TextView
import androidx.core.view.doOnLayout

/**
 * Puts the initial in the middle of the avatar circle - properly, and for any letter.
 *
 * ## Why anything is needed at all
 *
 * `gravity="center"` centres the **line box**, and a line box is not the glyph. Two
 * things push the ink off centre inside it:
 *
 *  - the typography token sets a 32dp line height on a 24sp face, and below API 34
 *    that arrives as extra leading rather than as symmetrical padding;
 *  - a capital with no descender - `Д`, `П`, every initial this screen will ever
 *    draw - occupies the upper part of its own line and leaves the descent space
 *    empty underneath.
 *
 * Together they left the ink about 1.9dp low: the same size of error as the one in
 * the frame that this correction exists to fix, in the other direction. Centring by
 * line box would have looked fixed and measured wrong.
 *
 * ## What this does instead
 *
 * Measures the glyph's ink with [android.graphics.Paint.getTextBounds], works out
 * where that ink will actually land, and shifts the text by the difference. Every
 * number comes from the font at its current size, so it is right for `Д`, right for
 * the `П` fallback, and right for a letter nobody has thought of yet - which a
 * constant tuned against one glyph would not be.
 *
 * **The circle never moves.** The translation is applied to the text view alone, and
 * the circle is its parent - which is exactly why the layout separates them. The
 * frame's 64dp circle at (16, 20) stays where the frame puts it.
 */
object AvatarInitial {

    private val ink = Rect()

    /**
     * Centres [view]'s text within [view]'s own bounds, after layout.
     *
     * Idempotent and cheap: it recomputes from scratch each time rather than
     * accumulating, so calling it again when the initial changes is correct.
     */
    fun centre(view: TextView) {
        view.doOnLayout {
            val text = view.text?.toString().orEmpty()
            if (text.isEmpty()) {
                view.translationY = 0f
                return@doOnLayout
            }

            val layout = view.layout ?: return@doOnLayout
            view.paint.getTextBounds(text, 0, text.length, ink)

            // Where the line will be drawn: the layout is centred in the view's inner
            // box, which is what the view's own gravity already arranged.
            val inner = view.height - view.paddingTop - view.paddingBottom
            val layoutTop = view.paddingTop + (inner - layout.height) / 2f
            val baseline = layoutTop + layout.getLineBaseline(0)

            // `ink.top` is negative above the baseline, so this is the ink's own
            // middle in the view's coordinates.
            val inkCentre = baseline + (ink.top + ink.bottom) / 2f
            view.translationY = view.height / 2f - inkCentre

            // The same correction horizontally. It is usually near zero - the glyph
            // is already centred in its advance width - but a letter whose side
            // bearings differ, which plenty do, is otherwise a pixel or two off.
            val lineLeft = layout.getLineLeft(0)
            val inkMiddleX = lineLeft + (ink.left + ink.right) / 2f
            view.translationX = view.width / 2f - inkMiddleX
        }
    }
}
