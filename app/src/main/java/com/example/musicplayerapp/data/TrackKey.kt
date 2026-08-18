package com.example.musicplayerapp.data

import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/**
 * The stable identity of a track, derived on the device from artist and title.
 *
 * The station's metadata API returns no track id of any kind - `api_all_tracks.php`
 * gives an artist string and a title string and nothing else - so a listener's
 * reaction has to be keyed on the only two fields that exist. Room already does
 * this implicitly through the unique `(artist, track)` index on `favorites`, but on
 * the *raw* strings, so a trailing space or an en dash from the playout system
 * silently creates a second, unrelated row. This object is that same identity made
 * explicit, deterministic and shared, so the app, a future local reaction table and
 * a future backend all agree on what "the same track" means.
 *
 * Nothing in the app uses this yet: it is the identity contract landing on its own,
 * ahead of the code that will depend on it.
 *
 * ## The v1 contract
 *
 * Normalisation is deliberately **conservative**. It removes only differences that
 * are invisible to a reader - encoding forms, invisible control characters, dash
 * glyphs, stray whitespace, letter case - and preserves everything that carries
 * meaning: `feat.` credits, `(Radio Edit)` and other parentheticals, remix and
 * version text, diacritics, and script. Two spellings a listener would read as
 * different tracks must never collapse into one key.
 *
 * This is **not** [ArtworkRepository]'s normalisation, which strips `feat.`,
 * punctuation and connectors and then matches fuzzily. That is correct for finding
 * a cover and wrong for identity: it would merge distinct recordings. The two stay
 * separate, and [ArtworkRepository] is untouched.
 *
 * The exact steps, their order, and the reasons they cannot be changed in place are
 * documented in `docs/TRACKKEY-V1.md`. Any change to them produces different keys
 * for the same tracks, which orphans every reaction already recorded - so a change
 * is a **v2 key plus a migration**, never an edit here. The version string is inside
 * the hashed payload precisely so a v2 can never collide with a v1 key. The golden
 * vectors in `TrackKeyTest` are what make an accidental change fail the build.
 */
object TrackKey {

    /**
     * Hashed into the payload so keys carry their own version. A future v2 with
     * different rules produces a disjoint key space rather than silently colliding.
     */
    private const val PAYLOAD_PREFIX = "myata:trackkey:v1"

    /**
     * ASCII Unit Separator - not text, and stripped from both fields by [normalize]
     * as a control character, so it cannot occur inside either part of the payload.
     * That is what stops `("a b", "c")` and `("a", "b c")` hashing to one key.
     */
    private const val SEPARATOR = '\u001F'

    /**
     * The metadata API puts this in the artist field for jingles and station idents.
     * It is not a track and cannot be reacted to; `StreamsViewModel` already refuses
     * it on the PLAYER control, and having [of] refuse it too means every future
     * entry point inherits the guard instead of repeating it.
     */
    private const val JINGLE_ARTIST = "YOUR MUSIC! YOUR STATION!"

    /**
     * Dash glyphs folded to ASCII `-`.
     *
     * The playout system, the metadata API and the artwork providers all spell the
     * same separator differently, and no reader distinguishes them. NFKC already
     * folds the last three; they are listed anyway so the set reads as the whole
     * rule instead of depending on the JDK's Unicode version to be complete.
     */
    private val DASHES = setOf(
        '-', // HYPHEN-MINUS
        '\u2010', // HYPHEN
        '\u2011', // NON-BREAKING HYPHEN
        '\u2012', // FIGURE DASH
        '\u2013', // EN DASH
        '\u2014', // EM DASH
        '\u2015', // HORIZONTAL BAR
        '\u2212', // MINUS SIGN
        '\uFE58', // SMALL EM DASH
        '\uFE63', // SMALL HYPHEN-MINUS
        '\uFF0D', // FULLWIDTH HYPHEN-MINUS
    )

    /**
     * Invisible characters removed outright, named rather than left to
     * [Character.getType].
     *
     * They are all `Cf` today and the category check below would catch them, but
     * their categories have moved between Unicode versions (U+200B was `Zs` before
     * Unicode 4.0.1) and this app runs on runtimes spanning API 24 to 36. A key that
     * depends on which Unicode table the device ships is not a stable key, so the
     * ones that actually turn up in this data are pinned here. U+FEFF is not
     * hypothetical: [MetadataRepository] already trims it out of the playlist feed.
     */
    private val INVISIBLES = setOf(
        '\u00AD', // SOFT HYPHEN
        '\u200B', // ZERO WIDTH SPACE
        '\u200C', // ZERO WIDTH NON-JOINER
        '\u200D', // ZERO WIDTH JOINER
        '\u200E', // LEFT-TO-RIGHT MARK
        '\u200F', // RIGHT-TO-LEFT MARK
        '\uFEFF', // ZERO WIDTH NO-BREAK SPACE / BOM
    )

    /**
     * Control characters that are whitespace, and so fold to a space rather than
     * being deleted. `"Artist\nName"` is two words, not `"artistname"`.
     */
    private val CONTROL_WHITESPACE = setOf(
        '\t', // TAB
        '\n', // LINE FEED
        '\u000B', // LINE TABULATION
        '\u000C', // FORM FEED
        '\r', // CARRIAGE RETURN
        '\u0085', // NEXT LINE
    )

    /**
     * The key for a track, or null if this is not one.
     *
     * Null means "no reaction can be recorded": either field empty after
     * normalisation - the metadata API defaults both to `""` while a stream is
     * between tracks - or the [JINGLE_ARTIST] sentinel. Callers treat null as an
     * inert control, not as an error.
     *
     * @return 64 lowercase hex characters, or null.
     */
    fun of(artist: String?, title: String?): String? {
        val normalizedArtist = normalize(artist)
        val normalizedTitle = normalize(title)

        if (normalizedArtist.isEmpty() || normalizedTitle.isEmpty()) return null
        if (normalizedArtist == normalize(JINGLE_ARTIST)) return null

        return sha256Hex(
            PAYLOAD_PREFIX + SEPARATOR + normalizedArtist + SEPARATOR + normalizedTitle
        )
    }

    /**
     * One field, canonicalised. Public because it is half the contract: the tests
     * pin what it produces, and the migration that will re-key existing rows has to
     * be able to show its work.
     *
     * The order is fixed - NFKC first, so compatibility forms are already unpacked
     * when characters are inspected, and casing last, so it cannot affect any
     * earlier decision - and the single pass applies, in this precedence:
     *
     *  1. named [INVISIBLES] -> removed;
     *  2. [CONTROL_WHITESPACE] and Unicode separators (`Zs`, `Zl`, `Zp`) -> space;
     *  3. any other control or format character (`Cc`, `Cf`) -> removed;
     *  4. [DASHES] -> `-`;
     *  5. everything else, surrogate pairs included, kept as it is.
     *
     * Then runs of spaces collapse to one, the ends are trimmed, and the result is
     * lowercased with [Locale.ROOT] - never the default locale, which would hand a
     * Turkish device a different key for the same track.
     */
    fun normalize(value: String?): String {
        if (value.isNullOrEmpty()) return ""

        val nfkc = Normalizer.normalize(value, Normalizer.Form.NFKC)

        val folded = StringBuilder(nfkc.length)
        for (ch in nfkc) {
            when {
                ch in INVISIBLES -> Unit
                ch in CONTROL_WHITESPACE -> folded.append(' ')
                isSeparator(ch) -> folded.append(' ')
                isControlOrFormat(ch) -> Unit
                ch in DASHES -> folded.append('-')
                else -> folded.append(ch)
            }
        }

        return folded.toString()
            .split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .lowercase(Locale.ROOT)
    }

    private fun isSeparator(ch: Char): Boolean = when (Character.getType(ch)) {
        Character.SPACE_SEPARATOR.toInt(),
        Character.LINE_SEPARATOR.toInt(),
        Character.PARAGRAPH_SEPARATOR.toInt() -> true
        else -> false
    }

    private fun isControlOrFormat(ch: Char): Boolean = when (Character.getType(ch)) {
        Character.CONTROL.toInt(),
        Character.FORMAT.toInt() -> true
        else -> false
    }

    private fun sha256Hex(payload: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(Charsets.UTF_8))

        val hex = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val value = byte.toInt() and 0xFF
            hex.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
        return hex.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
