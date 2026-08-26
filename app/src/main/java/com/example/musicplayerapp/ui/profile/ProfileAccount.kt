package com.example.musicplayerapp.ui.profile

/**
 * What the account card says, decided without a Context, a View or a device.
 *
 * Every rule here is a claim about a string or a number, so all of it is provable in
 * a unit test in microseconds - which matters, because the interesting cases are the
 * ones a screenshot never shows: the listener with no display name, the one whose
 * session has not come back yet, the install that has never synced.
 */
object ProfileAccount {

    /**
     * The name on the card.
     *
     * `user_metadata.display_name`, trimmed. Missing or blank falls back to
     * `Пользователь` rather than to the uid: a uid is a database key, and putting one
     * where somebody expects their name tells them nothing and looks like a fault.
     *
     * @return null when there is no usable name, so the caller resolves the fallback
     *   string from resources rather than this holding Russian copy.
     */
    fun displayName(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * The address under it.
     *
     * Session-only: `markRegistered` deliberately keeps a uid and nothing else, so an
     * install that is offline, or whose session has not restored yet, genuinely has
     * no address to show. Null then, and the caller shows `Email недоступен`.
     *
     * The card never implies the address is verified, because with Confirm Email off
     * it is not: it is the string the account is keyed by, and a tick beside it would
     * be a claim nobody checked.
     */
    fun email(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * The letter on the avatar.
     *
     * The first character of the name that is actually a letter or a digit, upper
     * cased. Skipping punctuation is not fussiness - a name stored as `"Денис"` with
     * the quotes, or as `@denis`, would otherwise put a quote mark or an at-sign on
     * the circle.
     *
     * Falls back to the first character of [fallbackName], which is how `П` arrives
     * for `Пользователь`, so the circle is never empty and never shows a uid.
     */
    fun initial(displayName: String?, fallbackName: String): String {
        val source = displayName(displayName) ?: fallbackName
        val letter = source.firstOrNull { it.isLetterOrDigit() } ?: fallbackName.first()
        return letter.uppercaseChar().toString()
    }

    /**
     * How long ago, as the frame writes it.
     *
     * The units are abbreviations - `мин`, `ч`, `дн` - which is the style 2517:2693
     * uses and also, conveniently, the form that needs no Russian plural agreement:
     * `1 мин назад` and `5 мин назад` are both correct as they stand, where
     * `1 минуту` / `5 минут` would need a rule.
     *
     * Beyond a week the relative form stops helping - "43 дн назад" is not something
     * anybody parses - so [Relative.Older] hands the caller the timestamp to format
     * as a date in the device's own locale.
     *
     * @param at when the last successful delivery happened, or null if none ever has.
     * @param now injected so the boundaries can be tested without waiting for them.
     */
    fun relativeSync(at: Long?, now: Long = System.currentTimeMillis()): Relative {
        if (at == null) return Relative.Never

        val elapsed = now - at
        // A clock that went backwards - a timezone change, an NTP correction, a
        // listener setting the date - would otherwise produce a negative age and
        // render as "-3 мин назад". "Только что" is both true enough and harmless.
        if (elapsed < MINUTE) return Relative.JustNow

        return when {
            elapsed < HOUR -> Relative.Minutes((elapsed / MINUTE).toInt())
            elapsed < DAY -> Relative.Hours((elapsed / HOUR).toInt())
            elapsed < WEEK -> Relative.Days((elapsed / DAY).toInt())
            else -> Relative.Older(at)
        }
    }

    /** The shapes [relativeSync] can take. The caller owns the words. */
    sealed interface Relative {
        data object Never : Relative
        data object JustNow : Relative
        data class Minutes(val count: Int) : Relative
        data class Hours(val count: Int) : Relative
        data class Days(val count: Int) : Relative
        data class Older(val at: Long) : Relative
    }

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR
    private const val WEEK = 7 * DAY
}
