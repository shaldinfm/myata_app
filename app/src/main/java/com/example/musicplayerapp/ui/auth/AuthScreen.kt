package com.example.musicplayerapp.ui.auth

import android.view.View
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.musicplayerapp.R

/**
 * The handful of things auth-sign-in and auth-create-account do identically.
 *
 * Extracted rather than duplicated because both are claims about *behaviour* that the
 * two screens must not be allowed to disagree on - what an inline error does to the
 * layout, and where the system bars go. Everything that differs between the screens
 * stays in the screen.
 */

/**
 * Shows [message] under a field, or takes the row out of the layout entirely.
 *
 * `GONE` rather than `INVISIBLE`, and that is the whole reason this is one function.
 * No approved Figma frame draws an auth error, so the resting geometry of both
 * screens is measured with no error rows present; an `INVISIBLE` row would reserve
 * its height and every measurement below it would be wrong by a line before anything
 * had even failed.
 */
fun TextView.setInlineError(@StringRes message: Int?) {
    if (message == null) {
        text = null
        visibility = View.GONE
    } else {
        setText(message)
        visibility = View.VISIBLE
    }
}

/**
 * Puts the 64dp band below the status bar and keeps the scroll clear of the system
 * navigation.
 *
 * The same treatment `ProfileGuestFragment` applies, for the same reasons and with
 * the same union: `systemBars()` **or** `displayCutout()`, because the status bar
 * normally covers a top cutout but that is the platform being helpful rather than a
 * guarantee. Without it the heading is drawn over the system clock.
 *
 * These screens hide the bottom bar, so nothing else is reserving the navigation
 * inset for them and the scroll has to clear it itself.
 */
fun applyAuthInsets(root: View, scroll: View) {
    ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        view.setPadding(view.paddingLeft, bars.top, view.paddingRight, view.paddingBottom)

        scroll.setPadding(
            scroll.paddingLeft,
            scroll.paddingTop,
            scroll.paddingRight,
            view.resources.getDimensionPixelSize(R.dimen.content_bottom_clearance) + bars.bottom,
        )
        insets
    }
}
