package com.example.musicplayerapp.data

import android.content.Context
import androidx.core.content.edit
import com.example.musicplayerapp.ui.settings.ThemeMode

/**
 * The appearance this install has chosen, durably.
 *
 * One key in one file, and deliberately not in any of the identity stores: an
 * appearance is a property of the device rather than of an account, so signing
 * out must not change it and signing in as somebody else must not carry theirs
 * across. `AccountDeletionCleanup` does not clear it for the same reason.
 *
 * ## The absent key is the answer
 *
 * Nothing is written at install or at upgrade. An install arriving from 3.6.5 has
 * no `theme_mode` at all, [read] answers [ThemeMode.SYSTEM], and SYSTEM installs
 * `MODE_NIGHT_UNSPECIFIED` - AppCompat's own "no local override", which is the
 * exact state the activity was in before G1. So the migration for existing
 * installs is that there is nothing to migrate. That is a
 * property of the shape rather than a step anybody has to run, which is why there
 * is no version number here and no backfill.
 *
 * ## apply(), not commit()
 *
 * `IdentityStore` commits synchronously because losing an identity marker splits a
 * listener in two. Losing this costs one wrong appearance until the next time the
 * screen is opened, which is `LastSyncStore`'s standard, not `IdentityStore`'s.
 * The write is on the main thread from a tap; `apply()` is what keeps it there
 * honestly.
 */
object ThemeStore {

    private const val PREFS = "myata_appearance"
    private const val KEY_MODE = "theme_mode"

    /** What is on disk, or [ThemeMode.SYSTEM] when nothing is - see the class note. */
    fun read(context: Context): ThemeMode =
        ThemeMode.fromStored(prefs(context).getString(KEY_MODE, null))

    /** Records a choice the listener actually made. Nothing else writes this file. */
    fun write(context: Context, mode: ThemeMode) {
        prefs(context).edit { putString(KEY_MODE, mode.stored()) }
    }

    /** Test-only: return this install to the state a fresh one is in. */
    fun clearForTest(context: Context) {
        prefs(context).edit(commit = true) { remove(KEY_MODE) }
    }

    /**
     * Test-only: what is literally on disk, or null when the key is absent.
     *
     * [read] cannot answer this - it maps an absent key and an unparseable one to
     * the same [ThemeMode.SYSTEM], which is the right behaviour for the app and the
     * wrong one for the assertion that nothing was written at all.
     */
    fun rawForTest(context: Context): String? = prefs(context).getString(KEY_MODE, null)

    /** Test-only: put a raw value on disk, including one this build cannot parse. */
    fun writeRawForTest(context: Context, raw: String?) {
        prefs(context).edit(commit = true) {
            if (raw == null) remove(KEY_MODE) else putString(KEY_MODE, raw)
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
