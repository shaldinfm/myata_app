package com.example.musicplayerapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The executable half of the TrackKey v1 contract (`docs/TRACKKEY-V1.md`).
 *
 * Two kinds of assertion, and the difference matters:
 *
 *  - **Golden vectors** are hard-coded digests. They are not "what the code
 *    currently returns" - they were produced from an independent implementation of
 *    the written contract and are the reason a change to normalisation cannot land
 *    quietly. Every reaction ever recorded is filed under these keys, so if one of
 *    these tests fails, the fix is a v2 key and a migration, never a new expected
 *    value in this file.
 *  - **Equivalence and separation** assertions pin the *behaviour*: which spellings
 *    must collapse to one key, and which must stay apart. They are what the golden
 *    digests mean.
 */
class TrackKeyTest {

    // Golden vectors. See the class KDoc before changing any of these.
    private val depecheMode = "0e81089c8caec4294651945b2d7253272e4a7009fd6ce66b1a8a92ed24888651"
    private val zemfira = "5d28c0ac6f793f82d8038406324314ca7a1f1392efccd4c79b0c74051eda0c5b"
    private val calvinHarris = "fad7e5957d2e4432e60be40237abfa7f43c68ce37d736c326cc0ac161c06af82"
    private val beyonceAccented = "bee6dc791fa9680cec9aae24df9f1cccba9f8be897e2da1a65d3b792d86bfbea"
    private val beyoncePlain = "2fb3e3f65cbbf5141a17d23bd78631799704b6a25fcd5b7725c93f50e77dab2a"
    private val nickCave = "b419b6ea145f9e3e5a7ac280e027298fc261a888121601afce1326833baa0d01"
    private val acdc = "5ba97feb2d040f45bc5ff161994182d7249cad8e6d091cbb7e7a1cc6e6311539"

    // Non-ASCII inputs are written as escapes so the vectors do not depend on how
    // any tool in the chain reads this file.
    private val zemfiraArtist = "\u0417\u0435\u043C\u0444\u0438\u0440\u0430" // Земфира
    private val zemfiraTitle = "\u0418\u0441\u043A\u0430\u043B\u0430" // Искала
    private val beyonceComposed = "Beyonc\u00E9"

    // ==================== Golden vectors ====================

    @Test
    fun `golden vector - latin`() {
        assertEquals(depecheMode, TrackKey.of("Depeche Mode", "Enjoy the Silence"))
    }

    @Test
    fun `golden vector - cyrillic`() {
        assertEquals(zemfira, TrackKey.of(zemfiraArtist, zemfiraTitle))
    }

    @Test
    fun `golden vector - feat and parenthetical`() {
        assertEquals(
            calvinHarris,
            TrackKey.of("Calvin Harris feat. Rihanna", "This Is What You Came For (Radio Edit)")
        )
    }

    @Test
    fun `golden vector - diacritic`() {
        assertEquals(beyonceAccented, TrackKey.of(beyonceComposed, "Halo"))
        assertEquals(beyoncePlain, TrackKey.of("Beyonce", "Halo"))
    }

    @Test
    fun `golden vector - ascii hyphen in title`() {
        assertEquals(nickCave, TrackKey.of("Nick Cave", "Red Right Hand - Live"))
    }

    @Test
    fun `golden vector - punctuation in both fields`() {
        assertEquals(acdc, TrackKey.of("AC/DC", "T.N.T."))
    }

    // ==================== Shape ====================

    @Test
    fun `key is 64 lowercase hex characters`() {
        val key = TrackKey.of("Depeche Mode", "Enjoy the Silence")!!
        assertEquals(64, key.length)
        assertTrue(key, key.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `key is deterministic across calls`() {
        assertEquals(
            TrackKey.of("Depeche Mode", "Enjoy the Silence"),
            TrackKey.of("Depeche Mode", "Enjoy the Silence")
        )
    }

    // ==================== NFKC ====================

    @Test
    fun `nfkc - decomposed and composed diacritics are the same track`() {
        // "Beyonce" + COMBINING ACUTE ACCENT composes to the accented form.
        assertEquals(beyonceAccented, TrackKey.of("Beyonce\u0301", "Halo"))
    }

    @Test
    fun `nfkc - fullwidth forms fold to ascii`() {
        assertEquals("abba", TrackKey.normalize("\uFF21\uFF22\uFF22\uFF21")) // ＡＢＢＡ
        assertEquals(TrackKey.of("ABBA", "SOS"), TrackKey.of("\uFF21\uFF22\uFF22\uFF21", "SOS"))
    }

    @Test
    fun `nfkc - ligature folds to its letters`() {
        assertEquals("definition", TrackKey.normalize("De\uFB01nition")) // ﬁ
    }

    @Test
    fun `nfkc - non-breaking space folds to a plain space`() {
        assertEquals(depecheMode, TrackKey.of("Depeche\u00A0Mode", "Enjoy the Silence"))
    }

    // ==================== BOM and zero-width characters ====================

    @Test
    fun `invisibles - bom is stripped`() {
        // The playlist feed already ships a BOM; MetadataRepository trims it by hand.
        assertEquals(depecheMode, TrackKey.of("\uFEFFDepeche Mode", "Enjoy the Silence\uFEFF"))
    }

    @Test
    fun `invisibles - zero width characters are removed not spaced`() {
        assertEquals("depeche mode", TrackKey.normalize("De\u200Bpeche Mode"))
        assertEquals("depeche mode", TrackKey.normalize("De\u200Cpeche\u200D Mode"))
        assertEquals(depecheMode, TrackKey.of("De\u200Bpeche Mode", "Enjoy the Silence"))
    }

    @Test
    fun `invisibles - soft hyphen is removed`() {
        assertEquals("depeche mode", TrackKey.normalize("De\u00ADpeche Mode"))
    }

    @Test
    fun `invisibles - bidi marks are removed`() {
        assertEquals("depeche mode", TrackKey.normalize("\u200EDepeche Mode\u200F"))
    }

    // ==================== Dash variants ====================

    @Test
    fun `dashes - every variant folds to ascii hyphen`() {
        val variants = listOf(
            '-', '\u2010', '\u2011', '\u2012', '\u2013',
            '\u2014', '\u2015', '\u2212', '\uFE58', '\uFE63', '\uFF0D'
        )
        for (dash in variants) {
            assertEquals(
                "dash U+%04X".format(dash.code),
                nickCave,
                TrackKey.of("Nick Cave", "Red Right Hand $dash Live")
            )
        }
    }

    @Test
    fun `dashes - a dash inside a word folds too`() {
        assertEquals("jean-michel jarre", TrackKey.normalize("Jean\u2013Michel Jarre"))
    }

    // ==================== Whitespace folding ====================

    @Test
    fun `whitespace - ends are trimmed and runs collapse`() {
        assertEquals(depecheMode, TrackKey.of("  Depeche   Mode ", "\tEnjoy  the Silence\n"))
    }

    @Test
    fun `whitespace - control whitespace becomes a space`() {
        assertEquals("depeche mode", TrackKey.normalize("Depeche\nMode"))
        assertEquals("depeche mode", TrackKey.normalize("Depeche\r\nMode"))
        assertEquals("depeche mode", TrackKey.normalize("Depeche\u000BMode"))
        assertEquals("depeche mode", TrackKey.normalize("Depeche\u000CMode"))
        assertEquals("depeche mode", TrackKey.normalize("Depeche\u0085Mode"))
    }

    @Test
    fun `whitespace - unicode separators become a space`() {
        assertEquals("depeche mode", TrackKey.normalize("Depeche\u2003Mode")) // EM SPACE
        assertEquals("depeche mode", TrackKey.normalize("Depeche\u3000Mode")) // IDEOGRAPHIC SPACE
        assertEquals("depeche mode", TrackKey.normalize("Depeche\u2028Mode")) // LINE SEPARATOR
        assertEquals("depeche mode", TrackKey.normalize("Depeche\u2029Mode")) // PARAGRAPH SEPARATOR
    }

    // ==================== Step 3 before step 4 ====================

    @Test
    fun `controls that are not whitespace are removed, not spaced`() {
        assertEquals("depechemode", TrackKey.normalize("Depeche\u0000Mode"))
        assertEquals("depechemode", TrackKey.normalize("Depeche\u0007Mode"))
        assertEquals("depechemode", TrackKey.normalize("Depeche\u001FMode"))
    }

    @Test
    fun `whitespace wins over control removal for controls that are both`() {
        // U+000B and U+0001 are both Cc. Step 3 runs before step 4, so the one that
        // is also whitespace becomes a space and the other disappears. Getting this
        // order wrong turns "Artist\nName" into one word.
        assertEquals("a b", TrackKey.normalize("a\u000Bb"))
        assertEquals("ab", TrackKey.normalize("a\u0001b"))
    }

    @Test
    fun `the field separator cannot survive normalisation`() {
        assertEquals("ab", TrackKey.normalize("a\u001Fb"))
    }

    // ==================== Locale ====================

    @Test
    fun `casing uses Locale ROOT not the device locale`() {
        val original = Locale.getDefault()
        try {
            // A Turkish default locale lowercases "I" to a dotless i, which would give
            // this device a different key for the same track.
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("istanbul", TrackKey.normalize("ISTANBUL"))
            assertEquals(depecheMode, TrackKey.of("DEPECHE MODE", "ENJOY THE SILENCE"))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `casing - cyrillic uppercase lowercases to the same key`() {
        assertEquals(zemfira, TrackKey.of(zemfiraArtist.uppercase(), zemfiraTitle.uppercase()))
    }

    // ==================== Script and lookalikes ====================

    @Test
    fun `cyrillic and latin lookalikes are different tracks`() {
        // An accepted v1 limitation, pinned so it is a known result and not a surprise:
        // Cyrillic "Ве" and Latin "Be" are not folded together.
        assertNotEquals(
            TrackKey.of("\u0412\u0435\u0442\u0435\u0440", "Song"), // Ветер, Cyrillic В
            TrackKey.of("B\u0435\u0442\u0435\u0440", "Song") // Latin B, Cyrillic rest
        )
    }

    // ==================== Semantic content preserved ====================

    @Test
    fun `feat credit is preserved`() {
        assertNotEquals(
            TrackKey.of("Calvin Harris feat. Rihanna", "This Is What You Came For"),
            TrackKey.of("Calvin Harris", "This Is What You Came For")
        )
    }

    @Test
    fun `feat spellings are not unified`() {
        // Also an accepted v1 limitation: these are two keys, not one.
        assertNotEquals(
            TrackKey.of("Calvin Harris feat. Rihanna", "This Is What You Came For"),
            TrackKey.of("Calvin Harris ft. Rihanna", "This Is What You Came For")
        )
    }

    @Test
    fun `parentheses and version text are preserved`() {
        assertNotEquals(
            TrackKey.of("Calvin Harris", "This Is What You Came For (Radio Edit)"),
            TrackKey.of("Calvin Harris", "This Is What You Came For")
        )
        assertEquals(
            "this is what you came for (radio edit)",
            TrackKey.normalize("This Is What You Came For (Radio Edit)")
        )
    }

    @Test
    fun `remix text is preserved`() {
        assertNotEquals(
            TrackKey.of("Depeche Mode", "Enjoy the Silence (Ewan Pearson Remix)"),
            TrackKey.of("Depeche Mode", "Enjoy the Silence")
        )
    }

    @Test
    fun `ampersand and punctuation are preserved`() {
        assertEquals("simon & garfunkel", TrackKey.normalize("Simon & Garfunkel"))
        assertNotEquals(
            TrackKey.of("Simon & Garfunkel", "America"),
            TrackKey.of("Simon and Garfunkel", "America")
        )
    }

    @Test
    fun `diacritics are preserved`() {
        assertNotEquals(TrackKey.of(beyonceComposed, "Halo"), TrackKey.of("Beyonce", "Halo"))
        assertEquals("beyonc\u00E9", TrackKey.normalize(beyonceComposed))
    }

    @Test
    fun `astral characters survive`() {
        assertEquals("track \uD83C\uDFB5", TrackKey.normalize("Track \uD83C\uDFB5"))
    }

    // ==================== Field separation ====================

    @Test
    fun `fields cannot bleed into each other`() {
        assertNotEquals(TrackKey.of("a b", "c"), TrackKey.of("a", "b c"))
    }

    @Test
    fun `a separator inside a field is stripped, not honoured`() {
        assertEquals(TrackKey.of("ab", "c"), TrackKey.of("a\u001Fb", "c"))
    }

    // ==================== Guards ====================

    @Test
    fun `null or blank fields have no key`() {
        assertNull(TrackKey.of(null, "Enjoy the Silence"))
        assertNull(TrackKey.of("Depeche Mode", null))
        assertNull(TrackKey.of("", ""))
        assertNull(TrackKey.of("   ", "Enjoy the Silence"))
        assertNull(TrackKey.of("Depeche Mode", "\uFEFF \u200B"))
    }

    @Test
    fun `the jingle sentinel has no key`() {
        assertNull(TrackKey.of("YOUR MUSIC! YOUR STATION!", "Enjoy the Silence"))
        assertNull(TrackKey.of("  your music! your station!  ", "Anything"))
    }

    @Test
    fun `normalize of null or empty is empty`() {
        assertEquals("", TrackKey.normalize(null))
        assertEquals("", TrackKey.normalize(""))
        assertEquals("", TrackKey.normalize("   \n\t"))
    }
}
