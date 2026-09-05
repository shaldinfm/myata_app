package com.example.musicplayerapp.ui.settings

import androidx.appcompat.app.AppCompatDelegate
import com.example.musicplayerapp.R

/**
 * Which of the three appearances the listener has asked for.
 *
 * `settings-appearance` 2517:2817 / 2517:3784 offers exactly three rows and no
 * fourth. A true-black / AMOLED variant was considered and dropped - see
 * `tools/figma-export/screens-3.6.6/PR23-CONCEPT-REVIEW.md` - so this enum is
 * closed rather than open, and a stored value outside it is corruption rather
 * than a newer build's choice.
 *
 * Everything here is a claim about a string or a constant, so all of it is
 * provable in a unit test with no Context, no View and no device. That is the
 * same split `ProfileAccount` and `MiniPlayerUiState` already use.
 */
enum class ThemeMode {
    /** Follow the device. The default, and what every install did before G1. */
    SYSTEM,
    LIGHT,
    DARK,
    ;

    /**
     * The stored form.
     *
     * Lower-case words rather than the ordinal: an ordinal survives a reorder of
     * this enum silently and wrongly, and these three strings are what a
     * `adb shell run-as ... cat shared_prefs` has to be readable as.
     */
    fun stored(): String = when (this) {
        SYSTEM -> STORED_SYSTEM
        LIGHT -> STORED_LIGHT
        DARK -> STORED_DARK
    }

    /**
     * The value this mode assigns to an activity delegate's **local** night mode.
     *
     * [SYSTEM] is `MODE_NIGHT_UNSPECIFIED`, which is AppCompat's documented way of
     * saying *there is no local override*: the delegate falls back to the process
     * default, which nothing in this app ever sets, and that follows the system.
     * It is therefore not merely equivalent to the pre-G1 behaviour - it is the
     * identical state, with no override installed at all.
     *
     * ## Why not MODE_NIGHT_FOLLOW_SYSTEM, which says the same thing in words
     *
     * Because it measurably costs a recreation. `MainActivity` assigns this in
     * `attachBaseContext`, and an install that has chosen nothing assigns it on
     * every cold start; AppCompat's own unset value is `MODE_NIGHT_UNSPECIFIED`, so
     * assigning the explicit `FOLLOW_SYSTEM` is a *change* to the delegate, and a
     * change is what `applyDayNight()` turns into a recreation.
     *
     * That was caught by
     * `AppearanceSelectionTest.the_default_appearance_does_not_recreate_the_activity_on_launch`,
     * which counted **two** MainActivity creations on a plain launch. Every listener
     * who never opens the appearance screen would have paid a second full activity
     * creation on every launch, for a night mode identical to the one they already
     * had. The two constants follow the system identically once applied; only one of
     * them is free to assign.
     *
     * ## Known and accepted for G1
     *
     * Whichever constant is used, "follow the system" reads a platform-wide dark
     * setting that only arrived at API 29, while `minSdk` is 24. On 24-28 there is
     * nothing for it to follow on an ordinary phone, so SYSTEM resolves to Light
     * there. Светлая and Тёмная are unaffected, and Тёмная is how a listener on those
     * releases gets a dark app at all. See `docs/SETTINGS-APPEARANCE-3.6.6.md`.
     */
    fun localNightMode(): Int = when (this) {
        SYSTEM -> AppCompatDelegate.MODE_NIGHT_UNSPECIFIED
        LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        DARK -> AppCompatDelegate.MODE_NIGHT_YES
    }

    /** The row label, and the value the `Тема` row shows on the settings shell. */
    fun labelRes(): Int = when (this) {
        SYSTEM -> R.string.settings_theme_system
        LIGHT -> R.string.settings_theme_light
        DARK -> R.string.settings_theme_dark
    }

    companion object {
        const val STORED_SYSTEM = "system"
        const val STORED_LIGHT = "light"
        const val STORED_DARK = "dark"

        /** What an install with no choice on disk gets. Not a value that is ever written. */
        val DEFAULT = SYSTEM

        /**
         * Reads the stored form back, and refuses to fail.
         *
         * Null - the absent key, which is every install upgrading into G1 - is
         * [DEFAULT], and so is anything unrecognised: a downgrade that wrote
         * something else, a hand-edited prefs file, a truncated write. There is no
         * third outcome, because the alternative to a default here is a screen with
         * no appearance at all.
         */
        fun fromStored(raw: String?): ThemeMode = when (raw) {
            STORED_LIGHT -> LIGHT
            STORED_DARK -> DARK
            STORED_SYSTEM -> SYSTEM
            else -> DEFAULT
        }
    }
}
