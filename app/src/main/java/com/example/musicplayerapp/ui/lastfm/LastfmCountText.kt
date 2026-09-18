package com.example.musicplayerapp.ui.lastfm

/**
 * The connected card's count line: `1 248 скробблов`.
 *
 * ## The number is Last.fm's lifetime total, not ours
 *
 * `user.getInfo` returns the account's whole playcount - everything that account
 * has ever scrobbled, from every client it has ever used. This app's contribution
 * is some unknowable fraction of it. That is why the label around this string says
 * `Всего в Last.fm:` rather than anything that could be read as "you scrobbled
 * this many from Myata": the honest label is the one that names whose number it
 * is. The frozen card's `f0ul482 · 22 258 скробблов` left that ambiguous; the
 * owner's decision resolves it.
 *
 * ## Why the plural is chosen here and not by `<plurals>`
 *
 * Android's `<plurals>` selects its form from the **device** locale, not from the
 * language of the string being formatted. This app's UI is Russian on every
 * device, so on an English-locale phone `<plurals>` would apply English rules to
 * Russian words and print `30 трека`. That is not hypothetical - it happened in
 * `HistoryFooterText`, and this is the same fix: Russian agreement decided in
 * Kotlin, against the number, regardless of what locale the phone is in.
 *
 * Pure strings in, string out: no Context, no resources, no Locale. The caller
 * passes the three forms it read from resources.
 */
object LastfmCountText {

    /**
     * `1 248 скробблов` - the grouped number and its agreeing noun.
     *
     * @param count the lifetime playcount. Negative is treated as 0; the service
     *   cannot return one, and a minus sign in this line would be nonsense.
     * @param one form for 1, 21, 31 … (`скроббл`)
     * @param few form for 2-4, 22-24 … (`скроббла`)
     * @param many form for 0, 5-20, 25-30 … (`скробблов`)
     */
    fun scrobbles(count: Long, one: String, few: String, many: String): String {
        val n = if (count < 0) 0L else count
        return group(n) + NBSP + noun(n, one, few, many)
    }

    /**
     * Russian agreement for [count].
     *
     * The rule in full, and the two exceptions are the point of it: 11-14 take the
     * many form even though they end in 1-4, which is why `% 100` is checked before
     * `% 10` decides anything.
     */
    fun noun(count: Long, one: String, few: String, many: String): String {
        val n = if (count < 0) 0L else count
        val mod100 = n % 100
        if (mod100 in 11..14) return many
        return when (n % 10) {
            1L -> one
            2L, 3L, 4L -> few
            else -> many
        }
    }

    /**
     * Thousands grouped the way the frozen card draws them: `22 258`, with a
     * **non-breaking** space.
     *
     * Not `NumberFormat`: its separator follows the runtime locale, so the same
     * count would render `22,258` on an English-locale device inside an otherwise
     * Russian line. The group separator here is a property of the language the
     * string is written in, which is fixed.
     */
    fun group(count: Long): String {
        val digits = (if (count < 0) 0L else count).toString()
        val out = StringBuilder(digits.length + digits.length / 3)
        for ((i, ch) in digits.withIndex()) {
            if (i > 0 && (digits.length - i) % 3 == 0) out.append(NBSP)
            out.append(ch)
        }
        return out.toString()
    }

    /** U+00A0. The count and its noun never wrap apart, and neither do the groups. */
    private const val NBSP = ' '
}
