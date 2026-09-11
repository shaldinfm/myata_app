package com.example.musicplayerapp.ui

import android.widget.TextView
import androidx.core.widget.TextViewCompat
import com.example.musicplayerapp.R

/**
 * The line metrics a Broadcast History row's title and artist are set on.
 *
 * ## Why the token's own line height is wrong here
 *
 * The frozen rows are typed 17/28 over 14/20 - `History Item N` in the PLAYER's
 * `Broadcast History Section`, and the full-screen `history-content` 2523:23 the
 * same. But **28 there is the height of a one-line slot, not a leading to
 * repeat**: the title box is 28 tall over a 20 artist box, which is how the block
 * comes to 48 in the mock. The frozen file never shows a wrapped title - where it
 * shows overflow, at `History Item / Краснознамённая...`, it is the *artist* that
 * wraps, on its own 20.
 *
 * Applied as a leading, that 28 is 6 more than Onest Regular needs at 17sp, on
 * every line after the first. One line looks right and three look airy, which is
 * exactly the complaint that produced this file.
 *
 * So the rows are set on the natural line instead - 22 for the 17sp title, 18 for
 * the 14sp artist - and the frozen one-line slot is preserved by `minHeight` on
 * the views rather than by inflating their leading. A single line still measures
 * the frozen 22 + 18 = 40; a wrapped one grows by a real line rather than by an
 * exaggerated one.
 *
 * ## includeFontPadding
 *
 * Off, and it is the half that actually moves the number. With it on, the font's
 * own overshoot adds ~2px to the title and ~3px to the artist before any line
 * height is applied, so the block cannot be made to measure 40 by setting line
 * heights alone.
 *
 * ## Why this is a function and not a style
 *
 * A `TextAppearance` cannot express it. The token that carries 17sp also carries
 * `android:lineHeight` 28 - correctly, for every other surface that uses it - and
 * the rows need the size without the leading. Overriding `android:lineHeight` in
 * the layout would work on API 28+ and silently do nothing on 24, where the
 * attribute does not exist; `TextViewCompat.setLineHeight` is the one call that
 * behaves the same on both.
 *
 * G4a moved this out of `PlayerHistoryAdapter`, where it had been correct but
 * private, so the second list could stop being wrong in the same way.
 */
object HistoryRowTypography {

    /** Applies the history line metrics to one row's two text views. */
    fun apply(title: TextView, artist: TextView) {
        val res = title.resources
        set(title, res.getDimensionPixelSize(R.dimen.history_row_title_line_height))
        set(artist, res.getDimensionPixelSize(R.dimen.history_row_artist_line_height))
    }

    /**
     * Sets one view's line to [targetPx], **undoing** whatever the token left.
     *
     * `setLineSpacing(0f, 1f)` first, and it is the whole fix. `TextView.setLineHeight`
     * is, in substance:
     *
     * ```
     * val fontHeight = paint.getFontMetricsInt(null)
     * if (lineHeight != fontHeight) setLineSpacing(lineHeight - fontHeight, 1f)
     * ```
     *
     * - a **no-op when the target equals the natural font height**. Onest Regular
     * at 17sp measures almost exactly the 22 wanted here, so the call did nothing,
     * and what it was meant to replace stayed: the 17/28 token's own
     * `android:lineHeight` is applied at inflation and had already put 6sp of
     * extra spacing on the view. The correction looked applied, measured
     * unapplied, and every wrapped title was 28 apart.
     *
     * `PlayerHistoryAdapter` has carried these two calls since Phase C and they
     * have never had an effect - its own comment noticed they were "usually
     * no-ops" and read that as harmless, when it meant the opposite.
     * `HistoryRowTypographyTest` measures the gap between two real lines rather
     * than trusting the setter, which is how it surfaced.
     *
     * Clearing the extra first makes the no-op branch correct instead of
     * dangerous: with nothing left over, "target equals natural" really is
     * nothing to do.
     */
    private fun set(view: TextView, targetPx: Int) {
        view.includeFontPadding = false
        view.setLineSpacing(0f, 1f)
        TextViewCompat.setLineHeight(view, targetPx)
    }
}
