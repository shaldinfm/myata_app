package com.example.musicplayerapp.ui.tv

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The rule that turns the current cover into the TV player's ambient field.
 *
 * The field replaces a flat background that was the cover's most vibrant colour
 * darkened until white text sat on it. What replaces it is four large soft areas
 * over a constant dark base - and the constraint that mattered before still
 * matters, so the colours are not handed straight from the palette:
 *
 *   - **Dark.** Saturation is clamped down to at most [MAX_SATURATION] and
 *     lightness is mapped into [MIN_LIGHTNESS]..[MAX_LIGHTNESS] before anything is
 *     drawn, so a cover that is white, neon or photographic cannot take the screen
 *     to a brightness where the track line and the stream pills stop being
 *     legible. The dim and the scrim still sit on top of this.
 *   - **Several, not one.** Up to [BLOB_COUNT] colours, and two colours are only
 *     the same area when they are close in hue *and* in lightness, so a blue
 *     cover can still be a field - a deep blue area and a bright one are two
 *     areas, and collapsing them was what turned the first version of this policy
 *     back into the single wash it exists to replace.
 *   - **Only what the artwork is made of.** A swatch under [MIN_USABLE_SHARE] of
 *     the cover is a highlight, not an area.
 *   - **Fallback, not blank.** A monochrome sleeve, an extraction that failed or
 *     the resolver's no-cover marker falls back to the MYATA palette in [FALLBACK]
 *     rather than to grey. A cover that offers *one* usable colour is not that
 *     case: it gets a field built from its own colour at two lightnesses (see
 *     [siblingOf]), because answering a red sleeve with the brand pink is a
 *     background that visibly does not belong to the track.
 *
 * Everything here is integer and float maths with no `android.graphics` and no
 * `androidx.palette`, which is what lets the whole rule be pinned by JVM unit
 * tests: the clamps, the lightness window, the fallback and the "same cover, same
 * field" property are all checked there. Whether the result *looks* right is not
 * a unit test, and that is what the TV emulator pass is for.
 */
object TvAmbientPolicy {

    /** Blobs the view draws. Four slots, filled from however many colours survived. */
    const val BLOB_COUNT = 4

    /**
     * The base the blobs sit on.
     *
     * Constant, and never derived from the artwork: it is the one part of the
     * frame that does not change when the track does, so a cover change is a
     * change of light on a stable dark rather than the whole screen repainting.
     */
    const val BASE_ARGB = 0xFF06070A.toInt()

    /** No ambient colour may be more saturated than this. */
    const val MAX_SATURATION = 0.55f

    /**
     * The window every ambient colour's lightness ends up in.
     *
     * A swatch is *mapped* into this window rather than clamped to it, because
     * clamping destroys exactly what a field is made of: a blue cover offers four
     * blues, and clamping them to a ceiling leaves four identical colours that the
     * "is this a new area" rule below then throws away - which is what turned the
     * first version of this policy back into the flat wash it replaces. Mapped,
     * they keep the order the artwork had: a deep blue area and a bright one.
     *
     * The design's starting window was 0.14..0.34, and at 0.34 the field measured
     * *too dark to be a field at all* on the TV AVD: the brightest pixel of four
     * fully saturated swatches - the worst case this policy allows - came out at
     * luma 39 of 255, and the player's own dim then took it to 23, which is a black
     * screen with a rumour of colour on it. The window is 0.20..0.50 here, and the
     * bright end of it is what makes a photographic sleeve's own muted colours
     * visible instead of merely present.
     */
    const val MIN_LIGHTNESS = 0.20f
    const val MAX_LIGHTNESS = 0.50f

    /**
     * A swatch below this share of the cover is a highlight, not an area of it.
     *
     * Measured against real covers on the TV AVD rather than guessed: the palette
     * hands back five named swatches that together are about three quarters of the
     * artwork (the rest is in the unnamed buckets), so a 5% floor of the *cover*
     * was throwing away the vivid swatch on ordinary photographic sleeves - 526 of
     * 8776 pixels, 6%, on the first one measured - and leaving too little to build
     * a field from. 3% keeps that one and still drops a logo's highlight.
     */
    private const val MIN_USABLE_SHARE = 0.03f

    /** Below this saturation a colour is a grey, and greys do not make a field. */
    private const val MIN_USABLE_SATURATION = 0.12f

    /** Two colours this close in hue *and* in lightness are one area, not two. */
    private const val MIN_HUE_GAP = 18f
    private const val MIN_LIGHTNESS_GAP = 0.05f

    /** How far [siblingOf] moves a single-colour cover's second area. */
    private const val SIBLING_LIGHTNESS_STEP = 0.10f

    // MYATA's own colours, as the app already uses them: the pink of the station
    // cards, the navy of `primary`, and the cyan of the play glyph. A dark purple
    // stands in for the navy because inside the lightness window the navy and the
    // cyan land 21 degrees apart in hue and a step apart in lightness, so the
    // "is this a new area" rule would collapse them into one.
    private const val BRAND_PINK = 0xFFFF3F7B.toInt()
    private const val BRAND_PURPLE = 0xFF3B2A6B.toInt()
    private const val BRAND_CYAN = 0xFF00E5FF.toInt()

    /**
     * What is drawn when the artwork cannot supply a field: no cover, a cover
     * still on its way, an extraction that failed, or a sleeve with nothing
     * usable in it. Normalised through [normalize] like any other colour, so the
     * brand field obeys exactly the same readability limits as an artwork one.
     */
    val FALLBACK: TvAmbientPalette = spread(
        listOf(normalize(BRAND_PINK), normalize(BRAND_PURPLE), normalize(BRAND_CYAN)),
        isFallback = true,
    )

    /**
     * The ambient field for this cover, or [FALLBACK] when it cannot supply one.
     *
     * @param swatches the candidate colours, in any order.
     * @param totalPopulation the pixel count the shares are taken against - the
     *   whole palette's, not just the named swatches', so that "5% of the cover"
     *   means the cover. Defaults to the swatches' own sum, which is what a test
     *   with a hand-built palette wants.
     */
    fun fromSwatches(
        swatches: List<TvAmbientSwatch>,
        totalPopulation: Int = swatches.sumOf { it.population.coerceAtLeast(0) },
    ): TvAmbientPalette {
        if (totalPopulation <= 0) return FALLBACK

        // Most vivid first, not largest: on a photographic sleeve the biggest area
        // is often the muted one - grey sky, worn paper, a dark stage - and leading
        // the field with it is how the cover's own colour gets dropped in favour of
        // the colour it is printed on. Population is kept as the tie-break, so the
        // same cover still reduces to the same field every time.
        val candidates = swatches
            .filter { it.population > 0 }
            .filter { it.population.toFloat() / totalPopulation >= MIN_USABLE_SHARE }
            .filter { saturationOf(it.argb) >= MIN_USABLE_SATURATION }
            .sortedWith(
                compareByDescending<TvAmbientSwatch> { vividnessOf(it.argb) }
                    .thenByDescending { it.population },
            )

        val picked = ArrayList<Int>(BLOB_COUNT)
        for (candidate in candidates) {
            val colour = normalize(candidate.argb)
            if (picked.any { !readsAsItsOwnArea(it, colour) }) continue
            picked += colour
            if (picked.size == BLOB_COUNT) break
        }

        return when (picked.size) {
            // Several areas: the cover's own colours.
            2, 3, 4 -> spread(picked, isFallback = false)
            // One colour is most of a photographic sleeve - a red one, a blue one -
            // and answering it with the brand field is a background that visibly
            // does not belong to the track. Its own colour at a second lightness
            // is still its colour, and two tones are a field where one is a fill.
            1 -> spread(listOf(picked[0], siblingOf(picked[0])), isFallback = false)
            // Nothing usable at all: a monochrome or unreadable cover. That is the
            // brand field's job, not a grey version of the cover.
            else -> FALLBACK
        }
    }

    /**
     * The second area for a cover that offered only one colour.
     *
     * Same hue and saturation, a neighbouring lightness - which is what the
     * artwork would have shown if a little more or less light had fallen on it.
     * The step is twice the gap at which two colours stop reading as one area, so
     * the result is a field rather than the same colour twice.
     */
    private fun siblingOf(colour: Int): Int {
        val middle = (MIN_LIGHTNESS + MAX_LIGHTNESS) / 2f
        val shifted = if (valueOf(colour) >= middle) {
            valueOf(colour) - SIBLING_LIGHTNESS_STEP
        } else {
            valueOf(colour) + SIBLING_LIGHTNESS_STEP
        }
        return hsvToArgb(
            hue = hueOf(colour),
            saturation = saturationOf(colour),
            value = shifted.coerceIn(MIN_LIGHTNESS, MAX_LIGHTNESS),
        )
    }

    /**
     * [argb] as an ambient colour: saturation clamped down to [MAX_SATURATION],
     * lightness mapped into [MIN_LIGHTNESS]..[MAX_LIGHTNESS], alpha forced opaque.
     *
     * Hue is never touched - it is the one thing that says which cover this is -
     * and lightness keeps its order, so two swatches the artwork drew as different
     * stay different here. Every caller passes a colour in exactly once: this is a
     * mapping, not a clamp, so applying it twice would squeeze the lightness again.
     */
    fun normalize(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)

        return hsvToArgb(
            hue = hueOf(r, g, b, max, min),
            saturation = min(if (max == 0) 0f else (max - min) / max.toFloat(), MAX_SATURATION),
            value = MIN_LIGHTNESS + (max / 255f) * (MAX_LIGHTNESS - MIN_LIGHTNESS),
        )
    }

    // ==================== colour maths ====================

    private fun hueOf(argb: Int): Float {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return hueOf(r, g, b, maxOf(r, g, b), minOf(r, g, b))
    }

    private fun hueOf(r: Int, g: Int, b: Int, max: Int, min: Int): Float {
        if (max == min) return 0f
        val span = (max - min).toFloat()
        val sector = when (max) {
            r -> (g - b) / span
            g -> (b - r) / span + 2f
            else -> (r - g) / span + 4f
        }
        return (sector * 60f + 360f) % 360f
    }

    private fun saturationOf(argb: Int): Float {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val max = maxOf(r, g, b)
        if (max == 0) return 0f
        return (max - minOf(r, g, b)) / max.toFloat()
    }

    private fun hueGap(a: Float, b: Float): Float {
        val straight = abs(a - b) % 360f
        return min(straight, 360f - straight)
    }

    /**
     * Whether [candidate] is an area of the field in its own right next to [kept].
     *
     * Different enough in hue, or different enough in lightness. Both matter: a
     * cover built from one hue at three lightnesses - which is most photographic
     * sleeves - is a field of three areas, while the same colour twice is not.
     */
    private fun readsAsItsOwnArea(kept: Int, candidate: Int): Boolean =
        hueGap(hueOf(kept), hueOf(candidate)) >= MIN_HUE_GAP ||
            abs(valueOf(kept) - valueOf(candidate)) >= MIN_LIGHTNESS_GAP

    /** How much colour a swatch brings, before any clamping. */
    private fun vividnessOf(argb: Int): Float = saturationOf(argb) * valueOf(argb)

    private fun valueOf(argb: Int): Float =
        maxOf((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF) / 255f

    private fun hsvToArgb(hue: Float, saturation: Float, value: Float): Int {
        val chroma = value * saturation
        val sector = (((hue % 360f) + 360f) % 360f) / 60f
        val second = chroma * (1f - abs(sector % 2f - 1f))

        var r = 0f
        var g = 0f
        var b = 0f
        when (sector.toInt()) {
            0 -> { r = chroma; g = second }
            1 -> { r = second; g = chroma }
            2 -> { g = chroma; b = second }
            3 -> { g = second; b = chroma }
            4 -> { r = second; b = chroma }
            else -> { r = chroma; b = second }
        }

        val base = value - chroma
        return 0xFF000000.toInt() or
            (level(r + base) shl 16) or (level(g + base) shl 8) or level(b + base)
    }

    private fun level(channel: Float): Int = (channel * 255f).roundToInt().coerceIn(0, 255)

    /** Exactly [BLOB_COUNT] colours, cycling when the cover offered fewer than four. */
    private fun spread(colors: List<Int>, isFallback: Boolean): TvAmbientPalette =
        TvAmbientPalette(List(BLOB_COUNT) { colors[it % colors.size] }, isFallback)
}
