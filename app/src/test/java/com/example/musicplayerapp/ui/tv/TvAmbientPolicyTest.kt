package com.example.musicplayerapp.ui.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ambient field's rule, pinned where it can be: no `android.graphics`, no
 * emulator, no pixels.
 *
 * What is checked here is the half of the slice that is a *policy* rather than a
 * picture - that nothing reaches the screen brighter or more saturated than the
 * readability limits allow, that a cover with nothing usable in it falls back to
 * the brand field instead of to grey, and that the same cover always reduces to
 * the same field so a repeated metadata tick cannot restart an animation.
 *
 * The HSV decomposition below is written out rather than borrowed from
 * [TvAmbientPolicy]: a test that used the policy's own arithmetic to check the
 * policy's own clamps would pass even if both were wrong, and
 * `android.graphics.Color` is a stub in a JVM test anyway.
 */
class TvAmbientPolicyTest {

    /**
     * The limits are enforced in float and the answer is 8-bit, so a colour can
     * land a rounding step outside them - about 1/255 of saturation at most. The
     * tolerance is that step and nothing more: a colour that is genuinely over the
     * limit is still caught.
     */
    private val ROUNDING = 0.01f

    // ==================== clamps ====================

    @Test
    fun excessive_saturation_is_clamped() {
        val palette = TvAmbientPolicy.fromSwatches(
            listOf(
                TvAmbientSwatch(red, 60),
                TvAmbientSwatch(green, 40),
            ),
        )

        assertFalse("a cover of two usable colours is not a fallback", palette.isFallback)
        for (colour in palette.colors) {
            assertTrue(
                "saturation ${hsv(colour).saturation} of #${hex(colour)} is above the limit",
                hsv(colour).saturation <= TvAmbientPolicy.MAX_SATURATION + ROUNDING,
            )
        }
    }

    @Test
    fun the_lightest_a_colour_can_be_is_the_ceiling() {
        val white = TvAmbientPolicy.normalize(0xFFFFFFFF.toInt())

        assertEquals(
            "a white cover must not take the field above the lightness ceiling",
            TvAmbientPolicy.MAX_LIGHTNESS,
            hsv(white).value,
            ROUNDING,
        )
    }

    @Test
    fun a_nearly_black_cover_is_lifted_to_the_floor() {
        val nearBlack = TvAmbientPolicy.normalize(0xFF0A0A0A.toInt())

        assertTrue(
            "a near-black cover must still produce something visible",
            hsv(nearBlack).value in TvAmbientPolicy.MIN_LIGHTNESS..
                (TvAmbientPolicy.MIN_LIGHTNESS + 0.05f),
        )
    }

    @Test
    fun the_clamps_leave_hue_alone() {
        // Hue is the one thing that says which cover this is, so a colour that is
        // only too bright or too saturated must come back the colour it was.
        assertEquals(0f, hsv(TvAmbientPolicy.normalize(red)).hue, 1.5f)
        assertEquals(120f, hsv(TvAmbientPolicy.normalize(green)).hue, 1.5f)
        assertEquals(240f, hsv(TvAmbientPolicy.normalize(0xFF0000FF.toInt())).hue, 1.5f)
    }

    @Test
    fun saturation_only_ever_comes_down() {
        // Hue and lightness are where the cover is; saturation is where the
        // readability limit is, so it is the one dimension that is clamped.
        val muted = 0xFF3B2A4C.toInt() // hue 270, saturation 0.45
        val vivid = 0xFF6C00FF.toInt() // hue 270, saturation 1.0

        assertEquals(0.45f, hsv(TvAmbientPolicy.normalize(muted)).saturation, 0.01f)
        assertEquals(TvAmbientPolicy.MAX_SATURATION, hsv(TvAmbientPolicy.normalize(vivid)).saturation, 0.01f)
        assertEquals(hsv(muted).hue, hsv(TvAmbientPolicy.normalize(muted)).hue, 1.5f)
    }

    @Test
    fun the_window_keeps_the_order_the_artwork_had() {
        // Three blues: sampled from a real sleeve on the TV AVD, where clamping to
        // a ceiling made all three the same colour and the policy fell back to the
        // brand field for a cover that is unmistakably blue.
        val deep = hsv(TvAmbientPolicy.normalize(0xFF082838.toInt())).value
        val mid = hsv(TvAmbientPolicy.normalize(0xFF305060.toInt())).value
        val bright = hsv(TvAmbientPolicy.normalize(0xFF08A0D8.toInt())).value

        assertTrue("deep $deep, mid $mid, bright $bright", deep < mid && mid < bright)
        for (value in listOf(deep, mid, bright)) {
            assertTrue(
                "lightness $value is outside the window",
                value in TvAmbientPolicy.MIN_LIGHTNESS..TvAmbientPolicy.MAX_LIGHTNESS,
            )
        }
    }

    // ==================== the field ====================

    @Test
    fun four_usable_swatches_produce_four_areas() {
        val palette = TvAmbientPolicy.fromSwatches(
            listOf(
                TvAmbientSwatch(red, 25),
                TvAmbientSwatch(green, 25),
                TvAmbientSwatch(0xFF0000FF.toInt(), 25),
                TvAmbientSwatch(0xFFFFC300.toInt(), 25),
            ),
        )

        assertFalse(palette.isFallback)
        assertEquals(TvAmbientPolicy.BLOB_COUNT, palette.colors.size)
        assertEquals("four different covers colours are four areas", 4, palette.colors.toSet().size)
    }

    @Test
    fun a_two_colour_cover_still_fills_every_slot() {
        val palette = TvAmbientPolicy.fromSwatches(
            listOf(
                TvAmbientSwatch(red, 50),
                TvAmbientSwatch(green, 50),
            ),
        )

        assertFalse(palette.isFallback)
        assertEquals(TvAmbientPolicy.BLOB_COUNT, palette.colors.size)
        assertEquals("both covers colours are used", 2, palette.colors.toSet().size)
    }

    @Test
    fun a_cover_of_one_colour_is_still_an_artwork_field() {
        // A red sleeve, measured on the TV AVD: the palette offered the same red
        // several times, the field could not be built from one colour, and the
        // answer was the brand pink - a background for a red album that is not red.
        val palette = TvAmbientPolicy.fromSwatches(
            listOf(
                TvAmbientSwatch(0xFFD32F2F.toInt(), 25),
                TvAmbientSwatch(0xFFB71C1C.toInt(), 25),
                TvAmbientSwatch(0xFFC62828.toInt(), 25),
                TvAmbientSwatch(0xFFE53935.toInt(), 25),
            ),
        )

        assertFalse("a red cover became the brand field", palette.isFallback)
        assertEquals("the cover's colour at two lightnesses, not four times", 2, palette.colors.toSet().size)
        for (colour in palette.colors) {
            // Loose by a few degrees: the hue is re-derived from 8-bit channels, so
            // a red that is exactly 0 coming in can be a degree or two going out.
            assertEquals("the field left the cover's hue", 0f, hsv(colour).hue, 5f)
        }
    }

    @Test
    fun a_cover_of_one_hue_at_several_lightnesses_is_still_a_field() {
        // The measured palette of a blue sleeve, and the case that broke the first
        // version of this rule: one hue, four lightnesses, no second hue anywhere.
        val palette = TvAmbientPolicy.fromSwatches(
            swatches = listOf(
                TvAmbientSwatch(0xFF082838.toInt(), 2239),
                TvAmbientSwatch(0xFF305060.toInt(), 1136),
                TvAmbientSwatch(0xFF08A0D8.toInt(), 526),
                TvAmbientSwatch(0xFF588098.toInt(), 286),
            ),
            totalPopulation = 8776,
        )

        assertFalse("a blue cover became the brand field", palette.isFallback)
        assertTrue(
            "one hue at several lightnesses must still be more than one area",
            palette.colors.toSet().size >= 2,
        )
    }

    @Test
    fun the_most_vivid_swatch_leads_the_field() {
        // A washed-out sleeve whose colour is a small, vivid part of it. The field
        // leads with that colour: population alone would lead with the wash.
        val muted = TvAmbientSwatch(0xFF6E7B85.toInt(), 800)
        val vivid = TvAmbientSwatch(0xFF1565C0.toInt(), 200)

        val palette = TvAmbientPolicy.fromSwatches(listOf(muted, vivid))

        assertFalse(palette.isFallback)
        assertEquals(TvAmbientPolicy.normalize(vivid.argb), palette.colors.first())
    }

    @Test
    fun low_population_swatches_are_ignored() {
        val palette = TvAmbientPolicy.fromSwatches(
            listOf(
                TvAmbientSwatch(0xFF3F7BFF.toInt(), 900),
                TvAmbientSwatch(red, 100),
                TvAmbientSwatch(green, 9), // 0.9% of the cover: a highlight, not an area
            ),
        )

        assertFalse(palette.isFallback)
        assertTrue(
            "a 0.9% swatch became an area of the field",
            palette.colors.none { hueGap(hsv(it).hue, 120f) < 20f },
        )
    }

    @Test
    fun shares_are_taken_against_the_whole_cover() {
        // Two swatches that would be a third of a five-swatch palette each, but
        // are 1% each of the cover they came from: not areas of it.
        val palette = TvAmbientPolicy.fromSwatches(
            swatches = listOf(
                TvAmbientSwatch(red, 10),
                TvAmbientSwatch(green, 10),
            ),
            totalPopulation = 1000,
        )

        assertTrue(palette.isFallback)
    }

    // ==================== fallback ====================

    @Test
    fun a_cover_with_nothing_usable_falls_back() {
        val cases: Map<String, List<TvAmbientSwatch>> = mapOf(
            "no swatches at all" to emptyList(),
            "a monochrome sleeve" to listOf(
                TvAmbientSwatch(0xFF8A8A8A.toInt(), 60),
                TvAmbientSwatch(0xFF3B3B3B.toInt(), 40),
            ),
            "every swatch below the usable share" to List(50) { TvAmbientSwatch(red, 4) },
            "no population to divide by" to listOf(TvAmbientSwatch(red, 0)),
        )

        for ((what, swatches) in cases) {
            assertEquals(what, TvAmbientPolicy.FALLBACK, TvAmbientPolicy.fromSwatches(swatches))
        }
    }

    @Test
    fun the_fallback_obeys_the_same_limits_as_artwork() {
        assertEquals(TvAmbientPolicy.BLOB_COUNT, TvAmbientPolicy.FALLBACK.colors.size)
        assertTrue(TvAmbientPolicy.FALLBACK.isFallback)

        for (colour in TvAmbientPolicy.FALLBACK.colors) {
            val hsv = hsv(colour)
            assertTrue(
                "saturation ${hsv.saturation} of #${hex(colour)}",
                hsv.saturation <= TvAmbientPolicy.MAX_SATURATION + ROUNDING,
            )
            assertTrue(
                "lightness ${hsv.value} of #${hex(colour)}",
                hsv.value in TvAmbientPolicy.MIN_LIGHTNESS - ROUNDING..
                    TvAmbientPolicy.MAX_LIGHTNESS + ROUNDING,
            )
        }

        // The brand field is three areas, not one colour repeated: pink, the
        // purple that stands in for the navy, and the cyan accent.
        assertEquals(3, TvAmbientPolicy.FALLBACK.colors.toSet().size)
    }

    @Test
    fun the_base_is_dark_enough_to_be_a_base() {
        assertTrue(
            "the base is a base only while nothing on it is legible as a colour of its own",
            hsv(TvAmbientPolicy.BASE_ARGB).value <= 0.06f,
        )
    }

    // ==================== stability ====================

    @Test
    fun the_same_cover_reduces_to_the_same_field() {
        val cover = listOf(
            TvAmbientSwatch(0xFF1F6FEB.toInt(), 400),
            TvAmbientSwatch(0xFFE8C547.toInt(), 300),
            TvAmbientSwatch(0xFF7B2D8E.toInt(), 200),
            TvAmbientSwatch(0xFF2E8B57.toInt(), 100),
        )

        assertEquals(
            "the same cover must not produce a palette that differs by a rounding step",
            TvAmbientPolicy.fromSwatches(cover),
            TvAmbientPolicy.fromSwatches(cover),
        )
    }

    @Test
    fun a_swatch_too_small_to_use_does_not_change_the_field() {
        val cover = listOf(
            TvAmbientSwatch(0xFF1F6FEB.toInt(), 400),
            TvAmbientSwatch(0xFFE8C547.toInt(), 300),
            TvAmbientSwatch(0xFF7B2D8E.toInt(), 200),
        )

        // The ambient view skips the crossfade when the palette it is handed is
        // equal to the one it is showing. That is only worth anything if a swatch
        // that cannot be drawn also cannot change the answer.
        assertEquals(
            TvAmbientPolicy.fromSwatches(cover),
            TvAmbientPolicy.fromSwatches(cover + TvAmbientSwatch(0xFF00FF00.toInt(), 2)),
        )
    }

    @Test
    fun a_different_cover_does_change_the_field() {
        val warm = listOf(TvAmbientSwatch(0xFFE07A5F.toInt(), 50), TvAmbientSwatch(0xFFF2CC8F.toInt(), 50))
        val cool = listOf(TvAmbientSwatch(0xFF2B4C9B.toInt(), 50), TvAmbientSwatch(0xFF6FD3E0.toInt(), 50))

        assertNotEquals(TvAmbientPolicy.fromSwatches(warm), TvAmbientPolicy.fromSwatches(cool))
    }

    // ==================== helpers ====================

    private val red = 0xFFFF0000.toInt()
    private val green = 0xFF00FF00.toInt()

    private class Hsv(val hue: Float, val saturation: Float, val value: Float)

    private fun hsv(argb: Int): Hsv {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val span = max - min

        val hue = when {
            span == 0f -> 0f
            max == r -> 60f * (((g - b) / span) % 6f)
            max == g -> 60f * (((b - r) / span) + 2f)
            else -> 60f * (((r - g) / span) + 4f)
        }

        return Hsv(
            hue = (hue + 360f) % 360f,
            saturation = if (max == 0f) 0f else span / max,
            value = max,
        )
    }

    private fun hueGap(a: Float, b: Float): Float {
        val straight = kotlin.math.abs(a - b) % 360f
        return minOf(straight, 360f - straight)
    }

    private fun hex(argb: Int): String = Integer.toHexString(argb and 0xFFFFFF)
}
