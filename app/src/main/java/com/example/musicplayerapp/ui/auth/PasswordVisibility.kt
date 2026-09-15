package com.example.musicplayerapp.ui.auth

import android.text.method.PasswordTransformationMethod
import android.widget.EditText
import android.widget.ImageView
import com.example.musicplayerapp.R

/**
 * Makes [toggle] show and hide the password typed into [field], starting hidden.
 *
 * One function for every password input - sign-in, create-account and the recovery
 * new-password field - because what the control does to a half-typed password must
 * not differ between them.
 *
 * It swaps the *transformation*, never `inputType`. The field stays `textPassword`
 * throughout, so an IME keeps treating it as a password - no suggestions, nothing
 * learned - even while it is readable, and the typeface is left alone. The text is
 * never touched, so nothing a render or a validator sees changes either.
 *
 * Swapping the transformation re-lays the text and can drop the caret to the start
 * on some platform versions, so the selection is carried across by hand.
 *
 * A tap does nothing while [toggle] is disabled. The screens disable it together with
 * the field while a request runs, and `isEnabled = false` only stops a touch:
 * `performClick` still runs the listener, which is the path an accessibility action
 * takes - so the flag is checked here too.
 */
fun bindPasswordVisibilityToggle(field: EditText, toggle: ImageView) {
    showPassword(field, toggle, visible = false)
    toggle.setOnClickListener {
        if (!toggle.isEnabled) return@setOnClickListener
        showPassword(field, toggle, visible = isPasswordHidden(field))
    }
}

/** Whether [field] is currently masking what is typed in it. */
fun isPasswordHidden(field: EditText): Boolean =
    field.transformationMethod is PasswordTransformationMethod

private fun showPassword(field: EditText, toggle: ImageView, visible: Boolean) {
    val start = field.selectionStart
    val end = field.selectionEnd

    field.transformationMethod = if (visible) null else PasswordTransformationMethod.getInstance()

    if (start >= 0 && end >= 0) {
        val length = field.length()
        field.setSelection(start.coerceAtMost(length), end.coerceAtMost(length))
    }

    toggle.setImageResource(
        if (visible) R.drawable.ic_auth_password_visibility else R.drawable.ic_auth_password_visibility_off
    )
    toggle.contentDescription = toggle.context.getString(
        if (visible) R.string.auth_password_hide_description else R.string.auth_password_show_description
    )
}
