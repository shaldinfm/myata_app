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
 *   - **Dark.** Saturation is clamped to at most [MAX_SATURATION] and lightness
 *     into [MIN_VALUE]..[MAX_VALUE] before anything is drawn, so a cover that is
 *     white, neon or photographic cannot take the screen to a brightness where
 *     the track line and the stream pills stop being legible. The dim and the
 *     scrim are unchanged and still sit on top of this.
 *   - **Several, not one.** Up to [BLOB_COUNT] colours, deduplicated by hue, so a
 *     red cover with a second, slightly different red does not produce the same
 *     single wash the ambient field exists to replace.
 *   - **Only what the artwork is made of.** A swatch under [MIN_USABLE_SHARE] of
 *     the cover is a highlight, not an area.
 *   - **Fallback, not blank.** Fewer than two usable colours - a monochrome
 *     sleeve, an extraction that failed, or the resolver's no-cover marker -
 *     falls back to the MYATA palette in [FALLBACK] rather than to grey.
 *
 * Everything here is integer and float maths with no `android.graphics` and no
 * `androidx.palette`, which is what lets the whole rule be pinned by JVM unit
 * tests: the clamps, the fallback and the "same cover, same field" property are
 * all checked there. Whether the result *looks* right is not a unit test, and
 * that is what the TV emulator pass is for.
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

    /** No ambient colour may be darker than this, or brighter than [MAX_VALUE]. */
    const val MIN_VALUE = 0.16f
    const val MAX_VALUE = 0.34f

    /** A swatch below this share of the cover is a highlight, not an area of it. */
    private const val MIN_USABLE_SHARE = 0.05f

    /** Below this saturation a colour is a grey, and greys do not make a field. */
    private const val MIN_USABLE_SATURATION = 0.12f

    /** Two colours closer than this in hue are one area of the field, not two. */
    private const val MIN_HUE_GAP = 18f

    // MYATA's own colours, as the app already uses them: the pink of the station
    // cards, the navy of `primary`, and the cyan of the play glyph. A dark purple
    // stands in for the navy because after clamping - which pulls the navy and the
    // cyan towards each other in lightness - the two brand blues land 21 degrees
    // apart in hue and would be collapsed into one area by MIN_HUE_GAP.
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

        // Largest first: the colour the cover is mostly made of is the one that
        // leads the field, and it is the one that survives a hue collision with a
        // smaller swatch of nearly the same colour.
        val picked = ArrayList<Int>(BLOB_COUNT)
        for (swatch in swatches.sortedByDescending { it.population }) {
            if (swatch.population <= 0) continue
            if (swatch.population.toFloat() / totalPopulation < MIN_USABLE_SHARE) continue

            val colour = normalize(swatch.argb)
            if (saturationOf(colour) < MIN_USABLE_SATURATION) continue
            if (picked.any { hueGap(hueOf(it), hueOf(colour)) < MIN_HUE_GAP }) continue

            picked += colour
            if (picked.size == BLOB_COUNT) break
        }

        // One colour is the flat wash this replaces; none is a cover we could not
        // read. Both are the brand field's job, not a dimmer version of the cover.
        return if (picked.size < 2) FALLBACK else spread(picked, isFallback = false)
    }

    /**
     * [argb] with its saturation clamped down to [MAX_SATURATION] and its
     * lightness clamped into [MIN_VALUE]..[MAX_VALUE], and its alpha forced
     * opaque.
     *
     * Hue is never touched: it is the one thing that says which cover this is.
     * The clamps are idempotent, which is what makes normalising an already
     * normalised colour - as [FALLBACK] and [spread] do - a no-op.
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
            value = (max / 255f).coerceIn(MIN_VALUE, MAX_VALUE),
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
