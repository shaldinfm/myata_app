package com.example.musicplayerapp.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.annotation.StyleRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.widget.TextViewCompat

/**
 * Applies the frozen typography tokens the way they actually have to be applied.
 *
 * A token names a family, a weight, a size, a line height and no font padding.
 * Only the first three of those survive being written into a TextAppearance:
 *
 *  - `android:lineHeight` in a text appearance is honoured by the framework from
 *    API 28. Below that it is read by nobody, and minSdk here is 24. Measured on
 *    API 24, five of the six representative surfaces silently kept the font's own
 *    natural line height instead - see TypographyProbeTest.
 *  - `android:includeFontPadding` is not a text-appearance attribute at all. It is
 *    a TextView attribute, so a token declaring it is a no-op on every API level,
 *    36 included. It matters because the frozen line heights come from Figma,
 *    which has no font padding: leaving it on adds the font's top and bottom
 *    overshoot to the first and last line of every block.
 *
 * So the token is applied in three steps, in this order, because each depends on
 * the one before it: the appearance first, which settles the typeface and the
 * text size; then font padding; then the line height, which
 * TextViewCompat.setLineHeight computes as line spacing against the metrics those
 * two just settled. Applying the line height first, or applying the appearance
 * afterwards, silently loses it.
 *
 * Views inflated from XML get all of this without anyone calling anything, via
 * [Factory] - see its comment. [apply] is for the views that are not inflated
 * from a layout, and for text whose token changes at runtime.
 */
object MyataTypography {

    /**
     * Tokens this layer owns. The prefix is the whole test: Material's own widgets
     * set text appearances too, and forcing font padding off on those would change
     * components this migration is not touching.
     */
    private const val TOKEN_PREFIX = "TextAppearance.Myata"

    /** Applies [token] to [view] in full: appearance, font padding, line height. */
    fun apply(view: TextView, @StyleRes token: Int) {
        view.setTextAppearance(token)
        applyMetrics(view, token)
    }

    /**
     * Applies the two halves of a token that a text appearance cannot carry, for a
     * view whose appearance has already been set - which is the case for anything
     * the inflater has just built from `android:textAppearance`.
     */
    private fun applyMetrics(view: TextView, @StyleRes token: Int) {
        view.includeFontPadding = false
        val lineHeight = lineHeightOf(view.context, token)
        if (lineHeight > 0) {
            // Compat by construction: below API 34 this becomes line spacing
            // measured against the current font metrics, which is why it has to
            // run last. Above it, the framework does the same thing itself.
            TextViewCompat.setLineHeight(view, lineHeight)
        }
    }

    /**
     * The token's line height in pixels, or -1 if it declares none.
     *
     * Reads the app's own attribute rather than android:lineHeight, and that is
     * the whole reason the app has one. android:lineHeight's id belongs to the
     * framework's resource table from API 28, so on 24 this lookup does not return
     * the wrong number - it returns nothing, the layer concludes the token has no
     * line height, and every view quietly keeps the font's natural leading. It
     * cost a round of measurement on the API 24 image to see that, because it
     * fails silently and only below 28.
     */
    private fun lineHeightOf(context: Context, @StyleRes token: Int): Int {
        val a = context.obtainStyledAttributes(
            token, intArrayOf(com.example.musicplayerapp.R.attr.myataLineHeight),
        )
        try {
            return a.getDimensionPixelSize(0, -1)
        } finally {
            a.recycle()
        }
    }

    private fun isMyataToken(context: Context, @StyleRes token: Int): Boolean = try {
        context.resources.getResourceEntryName(token).startsWith(TOKEN_PREFIX)
    } catch (e: android.content.res.Resources.NotFoundException) {
        false
    }

    /**
     * Finishes every `<TextView>` the inflater builds, if it names a Myata token.
     *
     * This is the layer, and the reason there are no per-view calls anywhere: a
     * migrated layout says `android:textAppearance="@style/TextAppearance.Myata…"`
     * and nothing else, and gets the full token.
     *
     * It wraps AppCompat rather than replacing it. AppCompatDelegate.createView is
     * what turns a `<TextView>` tag into the MaterialTextView the theme's
     * viewInflater asks for, and that has to keep happening - it is also what
     * back-ports `android:fontFamily="@font/…"`, which the framework itself only
     * understands from API 26. So the delegate builds the view, and this only adds
     * what a text appearance could not carry.
     *
     * Install it before super.onCreate: AppCompat declines to install its own
     * factory if one is already set, which is exactly what makes wrapping work,
     * and it makes that decision during onCreate.
     */
    class Factory(private val delegate: AppCompatDelegate) : LayoutInflater.Factory2 {

        override fun onCreateView(
            parent: View?,
            name: String,
            context: Context,
            attrs: AttributeSet,
        ): View? {
            val view = delegate.createView(parent, name, context, attrs)
            if (view is TextView) {
                val token = textAppearanceOf(context, attrs)
                if (token != 0 && isMyataToken(context, token)) applyMetrics(view, token)
            }
            return view
        }

        override fun onCreateView(name: String, context: Context, attrs: AttributeSet): View? =
            onCreateView(null, name, context, attrs)

        /**
         * The text appearance this tag ends up with, whether it was written on the
         * view or inherited from its `style`. Reading it from the AttributeSet is
         * the only way: a TextView does not remember which appearance was applied
         * to it.
         */
        private fun textAppearanceOf(context: Context, attrs: AttributeSet): Int {
            val a = context.obtainStyledAttributes(
                attrs, intArrayOf(android.R.attr.textAppearance), 0, 0,
            )
            try {
                return a.getResourceId(0, 0)
            } finally {
                a.recycle()
            }
        }
    }
}
