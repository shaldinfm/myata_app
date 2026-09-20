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
                hsv(colour).saturation <= TvAmbientPolicy.MAX_SATURATION + 0.002f,
            )
        }
    }

    @Test
    fun excessive_brightness_is_clamped() {
        val white = TvAmbientPolicy.normalize(0xFFFFFFFF.toInt())

        assertEquals(
            "a white cover must not take the field above the lightness ceiling",
            TvAmbientPolicy.MAX_VALUE,
            hsv(white).value,
            0.002f,
        )
    }

    @Test
    fun a_nearly_black_cover_is_lifted_to_the_floor() {
        val nearBlack = TvAmbientPolicy.normalize(0xFF0A0A0A.toInt())

        assertEquals(
            "a near-black cover must still produce something visible",
            TvAmbientPolicy.MIN_VALUE,
            hsv(nearBlack).value,
            0.002f,
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
    fun a_colour_inside_the_limits_is_left_where_it_is() {
        val colour = 0xFF3B2A4C.toInt() // hue 270, saturation 0.45, lightness 0.30
        val once = TvAmbientPolicy.normalize(colour)
        val twice = TvAmbientPolicy.normalize(once)

        assertEquals("normalising is idempotent", once, twice)
        assertEquals("the colour was already inside the limits", hsv(colour).hue, hsv(once).hue, 1.5f)
        assertEquals(hsv(colour).saturation, hsv(once).saturation, 0.01f)
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
    fun two_swatches_of_one_hue_are_one_area_not_two() {
        // The same red twice, as a photographic sleeve often offers it: this is
        // the flat wash the ambient field exists to replace.
        val palette = TvAmbientPolicy.fromSwatches(
            listOf(
                TvAmbientSwatch(0xFFD32F2F.toInt(), 50),
                TvAmbientSwatch(0xFFB71C1C.toInt(), 50),
            ),
        )

        assertTrue("one usable colour is not enough for a field", palette.isFallback)
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
            "a single usable colour" to listOf(TvAmbientSwatch(red, 100)),
            "a monochrome sleeve" to listOf(
                TvAmbientSwatch(0xFF8A8A8A.toInt(), 60),
                TvAmbientSwatch(0xFF3B3B3B.toInt(), 40),
            ),
            "every swatch a rounding error" to List(25) { TvAmbientSwatch(red, 4) },
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
            assertTrue("saturation ${hsv.saturation} of #${hex(colour)}", hsv.saturation <= TvAmbientPolicy.MAX_SATURATION + 0.002f)
            assertTrue("lightness ${hsv.value} of #${hex(colour)}", hsv.value in TvAmbientPolicy.MIN_VALUE - 0.002f..TvAmbientPolicy.MAX_VALUE + 0.002f)
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
