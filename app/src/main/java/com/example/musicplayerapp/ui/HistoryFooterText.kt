package com.example.musicplayerapp.ui

/**
 * Which Russian form history-content's `Footer` takes for a count (G4b).
 *
 * The frame reads "Показаны последние 30 треков". The count is the list's real
 * length, so the words have to agree with it - "1 трек", "3 трека", "30 треков" -
 * and that agreement is chosen **here, by the Russian rule, not by Android's
 * quantity strings**.
 *
 * ## Why not `<plurals>`
 *
 * `getQuantityString` picks the form by the *device's* locale, not by the language
 * of the string. On a device set to English there are only `one` and `other`, so a
 * Russian `<plurals>` resource read "Показаны последние 30 трека" - measured on the
 * English-locale QA emulator, where `BroadcastHistoryLayoutTest` caught it. The app
 * ships Russian copy to every locale, so the Russian rule has to be applied
 * regardless of which one the listener's phone is in.
 *
 * The rest of the app sidesteps agreement altogether with abbreviations
 * (`24 мин`, see SleepTimerText); a footer that must read exactly as the frame
 * cannot, so it is the one place that needs this.
 *
 * Kept free of Android types so the rule is a unit test.
 */
object HistoryFooterText {

    enum class Form { ONE, FEW, MANY }

    /**
     * The standard Russian cardinal rule for whole numbers: 1, 21, 101 take ONE;
     * 2-4, 22-24 take FEW; everything else - 0, 5-20, 25-30, and the teens 11-14
     * whatever their last digit - takes MANY.
     */
    fun formOf(count: Int): Form {
        val n = kotlin.math.abs(count)
        val lastTwo = n % 100
        val last = n % 10
        return when {
            last == 1 && lastTwo != 11 -> Form.ONE
            last in 2..4 && lastTwo !in 12..14 -> Form.FEW
            else -> Form.MANY
        }
    }
}
