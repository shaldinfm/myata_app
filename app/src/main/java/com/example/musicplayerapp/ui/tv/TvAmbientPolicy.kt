package com.example.musicplayerapp.ui.tv

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The rule that turns the current cover into the TV player's ambient field.
 *
 * The field replaces a flat background that was the cover's most vibrant colour
 * darkened until white text sat on it. What replaces it is a luminous field: four
 * large light masses, one much brighter core and two small highlights over a dark
 * base - and the constraint that mattered before still matters, so the colours are
 * not handed straight from the palette:
 *
 *   - **Bright, but bounded.** Saturation is clamped down and lightness is mapped
 *     into two windows - [MASS_MIN_LIGHTNESS]..[MASS_MAX_LIGHTNESS] for the masses
 *     and the much lighter [CORE_MIN_LIGHTNESS]..[CORE_MAX_LIGHTNESS] for the core
 *     and the highlights - so a cover that is white, neon or photographic cannot
 *     take the screen to a brightness where the track line and the stream pills
 *     stop being legible. The dim and the scrim still sit on top of this, and what
 *     they leave behind is measured by `TvAmbientBackgroundTest`, not eyeballed.
 *   - **Several, not one.** Up to [BLOB_COUNT] colours, and two colours are only
 *     the same area when they are close in hue *and* in lightness, so a blue
 *     cover can still be a field - a deep blue area and a bright one are two
 *     areas, and collapsing them was what turned the first version of this policy
 *     back into the single wash it exists to replace.
 *   - **Colour separation, but only where it is missing.** Lightness is not
 *     colour: three blues are one colour as far as the eye is concerned, which is
 *     why real covers kept coming out as one brown wash or one blue wash. So the
 *     hues are counted, and when the artwork offers only one, the field gets its
 *     separation from restrained *analogous* neighbours of that hue - see
 *     [ACCENT_PLUS_DEGREES] - never from a complementary jump into a colour the
 *     cover does not contain. When the artwork already has two or more real hues,
 *     they are used and nothing is synthesised on top of them.
 *   - **Only what the artwork is made of.** A swatch under [MIN_USABLE_SHARE] of
 *     the cover is a highlight, not an area.
     *   - **Fallback, not blank.** A monochrome sleeve, an extraction that failed or
     *     the resolver's no-cover marker falls back to the MYATA palette in [FALLBACK]
     *     rather than to grey. A cover that offers *one* usable colour is not that
     *     case: it keeps its colour and gains accents around it, because answering
     *     a red sleeve with the brand pink is a background that visibly does not
     *     belong to the track.
 *
 * Everything here is integer and float maths with no `android.graphics` and no
 * `androidx.palette`, which is what lets the whole rule be pinned by JVM unit
 * tests: the clamps, both lightness windows, the fallback and the "same cover,
 * same field" property are all checked there. Whether the result *looks* right is
 * not a unit test, and that is what the TV emulator pass is for.
 */
object TvAmbientPolicy {

    /**
     * Light masses the view draws, filled from however many colours survived.
     *
     * Three, not four. The fourth mass cost a third of the field's fill area and
     * bought the least visible area of the frame - the bottom-right corner, under
     * the scrim - and on the software-rendered TV AVD the heavier field was enough
     * to take the emulator's graphics stack down during long 1080p playback. The
     * cover's four colours are still all considered; it is the fourth *area* that
     * went, and with it the least vivid of the colours.
     */
    const val BLOB_COUNT = 3

    /**
     * The base the blobs sit on.
     *
     * Constant, and never derived from the artwork: it is the one part of the
     * frame that does not change when the track does, so a cover change is a
     * change of light on a stable dark rather than the whole screen repainting.
     */
    const val BASE_ARGB = 0xFF06070A.toInt()

    /**
     * No mass may be more saturated than this, the core a little less.
     *
     * Driven up from the 0.55 the first version used, because "still restrained"
     * and "reads as an aura" pulled in opposite directions and the second one won:
     * at 0.55 a saturated cover and a muted one produced fields that were hard to
     * tell apart, which is most of what "stronger colour identity from the cover"
     * means. The core stays lower than the masses so the brightest thing on screen
     * is not also the loudest.
     */
    const val MASS_MAX_SATURATION = 0.62f
    const val CORE_MAX_SATURATION = 0.55f

    /**
     * The window the masses' lightness ends up in, and the window the glow does.
     *
     * A swatch is *mapped* into this window rather than clamped to it, because
     * clamping destroys exactly what a field is made of: a blue cover offers four
     * blues, and clamping them to a ceiling leaves four identical colours that the
     * "is this a new area" rule below then throws away - which is what turned the
     * first version of this policy back into the flat wash it replaces. Mapped,
     * they keep the order the artwork had: a deep blue area and a bright one.
     *
     * The two windows are what the visual brief is made of. The design's starting
     * window was 0.14..0.34, and at 0.34 the field measured *too dark to be a field
     * at all* on the TV AVD - the brightest pixel of four fully saturated swatches
     * came out at luma 39 of 255 and the player's own dim took that to 23, which is
     * a black screen with a rumour of colour on it. 0.30..0.60 gives the masses
     * real colour presence; the core's own, much lighter window is what the eye
     * reads as a field that is lit from the inside rather than as coloured glass.
     */
    const val MASS_MIN_LIGHTNESS = 0.32f
    const val MASS_MAX_LIGHTNESS = 0.68f
    const val CORE_MIN_LIGHTNESS = 0.68f
    const val CORE_MAX_LIGHTNESS = 0.88f

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
    private const val MIN_LIGHTNESS_GAP = 0.07f

    /**
     * How far the synthesised accents sit from a single-hue cover's own hue.
     *
     * Analogous, not complementary: a blue sleeve gets a cyan-leaning and a
     * violet-leaning neighbour of *its* blue, which reads as that blue lit in more
     * than one way. A complementary jump would read as a different cover.
     */
    private const val ACCENT_PLUS_DEGREES = 30f
    private const val ACCENT_MINUS_DEGREES = -22f

    /**
     * Accents are quieter than the colour they come from, so the source hue stays
     * the subject rather than one of three equals.
     */
    private const val ACCENT_SATURATION_SCALE = 0.85f

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
     * usable in it. Normalised through the same windows as any other palette, so
     * the brand field obeys exactly the same readability limits as an artwork one.
     *
     * Its core is the brand pink rather than the cyan: the heart of the field is
     * where the warmth should come from, and the cyan reads as an accent rather
     * than as the source of the light.
     */
    val FALLBACK: TvAmbientPalette = TvAmbientPalette(
        colors = spread(listOf(normalize(BRAND_PINK), normalize(BRAND_PURPLE), normalize(BRAND_CYAN))),
        core = glow(BRAND_PINK),
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

        // The heart of the field is the cover's most vivid tone, pushed into the
        // glow window. It is chosen from the same ordered list the masses come
        // from, so it belongs to the same colour the masses lead with.
        val core = if (picked.isEmpty()) FALLBACK.core else glow(candidates.first().argb)

        // Nothing usable at all: a monochrome or unreadable cover. That is the
        // brand field's job, not a grey version of the cover.
        if (picked.isEmpty()) return FALLBACK

        // How many *hues* the artwork actually offered. Lightness does not count:
        // three blues are one colour, and a field of three blues is the monochrome
        // wash the brief is about.
        if (distinctHueCount(picked) >= 2) {
            // The cover has real colours of its own, so they are the field and
            // nothing is synthesised on top of them.
            return TvAmbientPalette(spread(picked), core, isFallback = false)
        }

        // One hue: keep it - it is what the cover is - and give the field the
        // separation it would otherwise lack from restrained analogous neighbours.
        // The source leads: it is the first mass, which is the largest and the
        // brightest, and the core is its hue as well.
        val source = picked.first()
        return TvAmbientPalette(
            colors = spread(
                listOf(
                    source,
                    accentOf(source, ACCENT_PLUS_DEGREES),
                    accentOf(source, ACCENT_MINUS_DEGREES),
                ),
            ),
            core = core,
            isFallback = false,
        )
    }

    /** How many hues [colours] holds, counting hues a [MIN_HUE_GAP] apart. */
    private fun distinctHueCount(colours: List<Int>): Int {
        val hues = ArrayList<Float>(colours.size)
        for (colour in colours) {
            val hue = hueOf(colour)
            if (hues.none { hueGap(it, hue) < MIN_HUE_GAP }) hues += hue
        }
        return hues.size
    }

    /**
     * An analogue of [colour]: the same lightness and a quieter saturation, turned
     * [degrees] around the wheel and wrapped.
     */
    private fun accentOf(colour: Int, degrees: Float): Int = hsvToArgb(
        hue = (hueOf(colour) + degrees + 360f) % 360f,
        saturation = saturationOf(colour) * ACCENT_SATURATION_SCALE,
        value = valueOf(colour),
    )

    /**
     * A cover colour as the *light* in the field: the same hue, its lightness
     * pushed up into the glow window and its saturation held back a little.
     *
     * This is the one place a colour is deliberately made lighter than the artwork
     * showed it. A sleeve's brightest swatch is almost always mid-tone - the
     * measured blue sleeve's best tone was 0.42 of full scale - and an aura built
     * only from those is a picture of a glow rather than a glow.
     */
    fun glow(argb: Int): Int = ambientTone(
        argb = argb,
        minLightness = CORE_MIN_LIGHTNESS,
        maxLightness = CORE_MAX_LIGHTNESS,
        maxSaturation = CORE_MAX_SATURATION,
    )

    /**
     * [argb] as a mass of the field: saturation clamped down to [MASS_MAX_SATURATION],
     * lightness mapped into the mass window, alpha forced opaque.
     *
     * Hue is never touched - it is the one thing that says which cover this is -
     * and lightness keeps its order, so two swatches the artwork drew as different
     * stay different here. Every caller passes a colour in exactly once: this is a
     * mapping, not a clamp, so applying it twice would squeeze the lightness again.
     */
    fun normalize(argb: Int): Int = ambientTone(
        argb = argb,
        minLightness = MASS_MIN_LIGHTNESS,
        maxLightness = MASS_MAX_LIGHTNESS,
        maxSaturation = MASS_MAX_SATURATION,
    )

    private fun ambientTone(
        argb: Int,
        minLightness: Float,
        maxLightness: Float,
        maxSaturation: Float,
    ): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)

        return hsvToArgb(
            hue = hueOf(r, g, b, max, min),
            saturation = min(if (max == 0) 0f else (max - min) / max.toFloat(), maxSaturation),
            value = minLightness + (max / 255f) * (maxLightness - minLightness),
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
    private fun spread(colors: List<Int>): List<Int> =
        List(BLOB_COUNT) { colors[it % colors.size] }
}
