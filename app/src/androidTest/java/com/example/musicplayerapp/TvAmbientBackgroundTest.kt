package com.example.musicplayerapp

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.ImageView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.example.musicplayerapp.service.AudioLevelRenderersFactory
import com.example.musicplayerapp.service.PlaybackAudioLevel
import com.example.musicplayerapp.ui.tv.TvAmbientBackgroundView
import com.example.musicplayerapp.ui.tv.TvAmbientPalette
import com.example.musicplayerapp.ui.tv.TvAmbientPolicy
import com.example.musicplayerapp.ui.tv.TvAmbientSwatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

/**
 * The TV player's ambient field, measured where it can be: the frame it occupies,
 * the darkness it is allowed to have, and the lifecycle that owns its animators.
 *
 * What a screenshot cannot settle, and each is one test:
 *
 *   1. **It is still the frame it replaced.** The view that used to be here was a
 *      full-screen `ImageView` holding a flat colour. The ambient view has to cover
 *      the same pixels at every validated TV geometry, and - the part that would
 *      rot silently - hiding it must not move a single other control, because a
 *      background that participates in layout is a background that can push the
 *      title off the screen.
 *   2. **It is bright enough, and not too bright.** The readability the flat
 *      background had was a property of one clamped colour; here it is a property
 *      of a field of translucent layers that overlap, and the direction of this
 *      slice is a *glow* - so "dark enough" is no longer the whole question. The
 *      field is drawn into a bitmap and read back: it has to be lit, lit from its
 *      core rather than evenly, not flat, and short of white - and the composited
 *      frame behind the title and the pills has to stay under the luma at which
 *      white text loses AA contrast.
 *   3. **It moves with the music.** The energy comes from the audio pipeline
 *      itself, so it is published here directly - an instrumentation test has no
 *      decoded audio to play - and has to make the field measurably brighter.
 *   4. **It stops.** An infinite animator on a TV player is exactly the kind of
 *      leak that is invisible in a screenshot and obvious in a battery report.
 *      The drift must be running only while the view is on screen, and
 *      `stopAmbient()` - what `onDestroyView` calls - must leave nothing behind.
 *
 * What is deliberately *not* here is any assertion about where the blobs are or
 * how far they travel. That is a picture, and pinning it as pixels would fail on
 * the next tuning pass while proving nothing.
 */
@RunWith(AndroidJUnit4::class)
class TvAmbientBackgroundTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private companion object {
        const val TAG = "TVAMBIENT"

        /** sRGB luma at which white text on this background is exactly 4.5:1. */
        const val CONTRAST_LUMA = 110

        /** Anything this bright is a glyph, a glyph's antialiasing, or the plate. */
        const val GLYPH_LUMA = 180

        /**
         * Draws the field is advanced by, to let the loudness envelope settle: at
         * the view's eight-millisecond floor that is a third of a second, which is
         * past the attack and well into the release.
         */
        const val ENERGY_SETTLE_FRAMES = 40
    }

    /**
     * The three geometries the TV layout was validated at, with the artwork
     * dimension each bucket resolves to - the cheap proof that the configuration
     * below really is the viewport it claims to be, rather than a phone in a hat.
     */
    private data class Geometry(
        val name: String,
        val widthPx: Int,
        val heightPx: Int,
        val densityDpi: Int,
        val artworkDp: Int,
        val minTopAirDp: Int,
    )

    private val geometries = listOf(
        Geometry("1080p@320", 1920, 1080, 320, artworkDp = 280, minTopAirDp = 12),
        Geometry("720p@240", 1280, 720, 240, artworkDp = 200, minTopAirDp = 48),
        Geometry("1080p@480", 1920, 1080, 480, artworkDp = 132, minTopAirDp = 32),
    )

    // ==================== the frame ====================

    @Test
    fun the_field_fills_the_player_frame_and_moves_nothing() {
        for (geometry in geometries) {
            val context = tvContext(geometry)
            val root = inflatePlayer(context)
            layout(root, geometry.widthPx, geometry.heightPx)

            val ambient = root.findViewById<TvAmbientBackgroundView>(R.id.view_ambient)
            assertEquals(
                "${geometry.name}: the field is not the whole player frame",
                Rect(0, 0, geometry.widthPx, geometry.heightPx),
                inRoot(root, ambient),
            )

            // Proof that this context is the viewport it says it is: the artwork
            // is the one dimension the height buckets actually differ in.
            val artwork = root.findViewById<ImageView>(R.id.iv_album_art)
            val density = geometry.densityDpi / 160f
            assertEquals(
                "${geometry.name}: the layout did not resolve this bucket's metrics",
                geometry.artworkDp * density,
                artwork.width.toFloat(),
                1f,
            )

            // The air above the artwork, which is what the constrained buckets were
            // rebuilt for: the column is anchored at the bottom, so the slack in it
            // is what the artwork floats in.
            assertTrue(
                "${geometry.name}: the artwork is glued to the top " +
                    "(${artwork.top}px of ${geometry.minTopAirDp * density}px expected)",
                artwork.top >= geometry.minTopAirDp * density - 1f,
            )

            val withField = everyOtherView(root, ambient)
            ambient.visibility = View.GONE
            layout(root, geometry.widthPx, geometry.heightPx)
            val withoutField = everyOtherView(root, ambient)
            ambient.visibility = View.VISIBLE

            assertEquals(
                "${geometry.name}: hiding the background moved another control",
                withField,
                withoutField,
            )

            Log.i(
                TAG,
                "${geometry.name}: frame ${geometry.widthPx}x${geometry.heightPx}, " +
                    "artwork ${artwork.width}px starting ${artwork.top}px down, " +
                    "${withField.size} controls unmoved",
            )
        }
    }

    // ==================== what it looks like ====================

    /**
     * What the field itself is allowed to be, with the palette the policy is
     * willing to hand it.
     *
     * This used to assert that the field stayed dark, because the first version of
     * it was too dark to see. The direction since is a *glowing* field, so what is
     * asserted now is the shape of that glow rather than its absence: bright enough
     * to be light, short of white, never flat, never a wash of one loud colour.
     * Readability is not this test's job - the field drawn here has no dim and no
     * scrim over it, and [the_title_and_the_pills_keep_their_contrast] is where the
     * composited frame is measured.
     */
    @Test
    fun the_field_glows_without_going_white_or_flat() {
        val cases = linkedMapOf(
            // The brand field: what is on screen before any cover has decoded.
            "MYATA fallback" to TvAmbientPolicy.FALLBACK,
            "four vivid colours" to worstCaseArtwork,
        )

        assertFalse(
            "four vivid swatches must not be reduced to the fallback",
            cases.getValue("four vivid colours").isFallback,
        )

        // A white sleeve offers nothing the field can use: one area of no colour
        // is not a field, and the answer is the brand field rather than a very
        // bright grey wash.
        assertEquals(
            "a white sleeve must draw the brand field",
            TvAmbientPolicy.FALLBACK,
            TvAmbientPolicy.fromSwatches(listOf(TvAmbientSwatch(0xFFFFFFFF.toInt(), 100))),
        )

        for ((what, palette) in cases) {
            val stats = statsOf(render(palette, 960, 540))

            Log.i(
                TAG,
                "$what: mean luma=${
                    "%.1f".format(java.util.Locale.ROOT, stats.meanLuma)
                } max=${stats.maxLuma} p10=${stats.p10} p90=${stats.p90} mean sat=${
                    "%.2f".format(java.util.Locale.ROOT, stats.meanSaturation)
                }",
            )

            assertTrue(
                "$what: the field is still a near-black screen (${stats.meanLuma})",
                stats.meanLuma >= 25f,
            )
            assertTrue(
                "$what: the field is as bright as text would be (${stats.maxLuma})",
                stats.maxLuma <= 180f,
            )
            assertTrue(
                "$what: the field is a flat fill, not a field (p90-p10 ${stats.p90 - stats.p10})",
                stats.p90 - stats.p10 >= 20,
            )
            assertTrue(
                "$what: the field is a wash of one loud colour (${stats.meanSaturation})",
                stats.meanSaturation <= 0.60f,
            )
        }
    }

    /**
     * The half of readability the field alone cannot answer.
     *
     * The field's own brightness is bounded by the policy, but what the viewer
     * actually reads text on is the field *plus* the player's dim and the bottom
     * scrim - and those are on top of the title line and the stream pills, not on
     * top of the brightest corner. So the whole frame is drawn, with the worst
     * palette the policy allows, and the two bands are read back.
     *
     * The threshold is not a taste: white text on a background of sRGB luma 119 is
     * exactly 4.5:1, so 110 is "the background behind the title and behind the
     * pills cannot push white text below AA contrast" - with the glyphs themselves
     * excluded from the measurement, which is why the filter below drops anything
     * bright enough to be text.
     */
    @Test
    fun the_title_and_the_pills_keep_their_contrast() {
        val context = tvContext(geometries.first())
        val root = inflatePlayer(context)
        val ambient = root.findViewById<TvAmbientBackgroundView>(R.id.view_ambient)
        onMainThread {
            ambient.setAmbientPalette(worstCaseArtwork)
            ambient.stopAmbient()
        }
        layout(root, geometries.first().widthPx, geometries.first().heightPx)

        val frame = Bitmap.createBitmap(
            geometries.first().widthPx,
            geometries.first().heightPx,
            Bitmap.Config.ARGB_8888,
        )
        root.draw(Canvas(frame))

        val bands = listOf(
            "title" to root.findViewById<View>(R.id.tv_track_info),
            "pills" to root.findViewById<View>(R.id.layout_streams),
        )
        for ((what, band) in bands) {
            val background = backgroundLuma(frame, inRoot(root, band))
            Log.i(
                TAG,
                "$what band: background luma p50=${background.p50} " +
                    "p99=${background.p99} max=${background.max} over ${background.counted}px",
            )
            assertTrue(
                "$what: the background behind it reaches luma ${background.p99}, " +
                    "which is below AA contrast for white text",
                background.p99 <= CONTRAST_LUMA,
            )
        }
    }

    /**
     * The field has a light source, and it is not the middle of the screen.
     *
     * A field of equal masses would read as a wash; what makes it an aura is that
     * the core is brighter than everything around it and sits off to one side. The
     * two regions below are the core's own anchor and the opposite corner, both
     * measured on the ambient view alone - no cover, no dim, no scrim.
     */
    @Test
    fun the_field_is_lit_from_its_core_rather_than_evenly() {
        val frame = render(worstCaseArtwork, 960, 540)

        val core = statsOf(frame, Rect(150, 40, 460, 260))
        val corner = statsOf(frame, Rect(640, 340, 940, 520))
        val whole = statsOf(frame)

        Log.i(
            TAG,
            "core region mean luma ${core.meanLuma} max ${core.maxLuma}; " +
                "opposite corner mean ${corner.meanLuma} max ${corner.maxLuma}; " +
                "whole frame mean ${whole.meanLuma}",
        )
        assertTrue(
            "the core region (${core.meanLuma}) is no brighter than the frame's own " +
                "average (${whole.meanLuma})",
            core.meanLuma > whole.meanLuma * 1.15f,
        )
        assertTrue(
            "the far corner (${corner.meanLuma}) is as bright as the core's own region",
            corner.meanLuma < core.meanLuma,
        )
    }

    /**
     * The music-reactive half: the same palette, drawn with the pipeline quiet and
     * with it loud.
     *
     * [PlaybackAudioLevel] is what the audio tap publishes into, so this is the
     * same signal the shipped field moves with - published here directly, because
     * an instrumentation test has no decoded audio to play.
     */
    @Test
    fun the_field_lights_up_with_the_music() {
        val quiet = statsOf(render(worstCaseArtwork, 960, 540, rms = 0f))
        val loud = statsOf(render(worstCaseArtwork, 960, 540, rms = 0.3f))

        Log.i(
            TAG,
            "quiet: mean ${quiet.meanLuma} p90 ${quiet.p90}; " +
                "loud: mean ${loud.meanLuma} p90 ${loud.p90}",
        )
        assertTrue(
            "loudness did not light the field up (${quiet.meanLuma} -> ${loud.meanLuma})",
            loud.meanLuma > quiet.meanLuma * 1.05f,
        )
    }

    /**
     * The audio tap builds on a real device.
     *
     * This is the part of the music-reactive path that could take the player down
     * with it: the sink is assembled from Media3 internals, and if that assembly
     * fails at runtime, playback fails with it. The tap is therefore built the same
     * way the service builds it, on this device, and has to come back as the same
     * kind of sink Media3 would have made.
     */
    @OptIn(UnstableApi::class)
    @Test
    fun the_audio_tap_builds_the_same_kind_of_sink_media3_would() {
        val context = instrumentation.targetContext
        val tapped: AudioSink? = AudioLevelRenderersFactory(context).tappedSink(context, false, false)
        val stock: AudioSink? = StockSinkProbe(context).sink(context)

        assertNotNull("the stock sink could not be built either", stock)
        assertNotNull("the tapped sink could not be built", tapped)
        assertEquals(
            "the tapped path did not produce the sink Media3 would have built",
            stock!!.javaClass,
            tapped!!.javaClass,
        )
        assertTrue(
            "the tapped sink is not a DefaultAudioSink, so the chain was not installed",
            tapped is DefaultAudioSink,
        )
    }

    // ==================== lifecycle ====================

    @Test
    fun the_drift_runs_only_while_the_view_is_on_screen() {
        // The host is the TV activity itself, because the view has to be attached
        // to the window the player actually lives in - and because `MainActivity`
        // finishes on a TV device the moment it recognises one, which is a
        // destroyed activity to attach to. Nothing here selects a station, so the
        // playback service is never started: the splash hands over to the stream
        // selection screen and stops there.
        ActivityScenario.launch(TvMainActivity::class.java).let { scenario ->
            try {
                lateinit var ambient: TvAmbientBackgroundView
                scenario.onActivity { activity ->
                    ambient = TvAmbientBackgroundView(activity)
                    activity.findViewById<ViewGroup>(android.R.id.content).addView(
                        ambient,
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        ),
                    )
                }
                instrumentation.waitForIdleSync()

                assertTrue(
                    "the field was not drifting while it was on screen",
                    onMainThread { ambient.isDrifting() },
                )

                scenario.onActivity { ambient.visibility = View.GONE }
                instrumentation.waitForIdleSync()
                assertFalse(
                    "the field kept drifting after it was hidden",
                    onMainThread { ambient.isDrifting() },
                )

                scenario.onActivity { ambient.visibility = View.VISIBLE }
                instrumentation.waitForIdleSync()
                assertTrue(
                    "the field did not come back when it was shown again",
                    onMainThread { ambient.isDrifting() },
                )

                scenario.onActivity { ambient.stopAmbient() }
                assertFalse(
                    "stopAmbient() - what onDestroyView calls - left an animator running",
                    onMainThread { ambient.isDrifting() },
                )

                scenario.onActivity { ambient.visibility = View.VISIBLE }
                instrumentation.waitForIdleSync()
                scenario.onActivity { (ambient.parent as ViewGroup).removeView(ambient) }
                instrumentation.waitForIdleSync()
                assertFalse(
                    "detaching the view left an animator running",
                    onMainThread { ambient.isDrifting() },
                )
            } finally {
                // See TypographyWidthSweepTest: close() can report a teardown
                // timeout on the software-rendered images long after the checks
                // above are done, and that is not a failure of this test.
                try {
                    scenario.close()
                } catch (e: Throwable) {
                    Log.w(TAG, "activity close timed out; checks already complete", e)
                }
            }
        }
    }

    // ==================== helpers ====================

    /**
     * A TV viewport as a real configuration: density and both dp dimensions,
     * because the player's metrics come from `-hNNNdp` buckets, and those match
     * the configuration rather than the pixels.
     */
    private fun tvContext(geometry: Geometry): Context {
        val base = instrumentation.targetContext
        val density = geometry.densityDpi / 160f
        val configuration = Configuration(base.resources.configuration).apply {
            densityDpi = geometry.densityDpi
            screenWidthDp = (geometry.widthPx / density).roundToInt()
            screenHeightDp = (geometry.heightPx / density).roundToInt()
            orientation = Configuration.ORIENTATION_LANDSCAPE
        }
        return ContextThemeWrapper(
            base.createConfigurationContext(configuration),
            R.style.TvTheme,
        )
    }

    private fun inflatePlayer(context: Context): ViewGroup =
        LayoutInflater.from(context).inflate(R.layout.fragment_tv_player, null) as ViewGroup

    private fun layout(root: View, width: Int, height: Int) {
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, width, height)
    }

    /** [view]'s bounds in [root]'s coordinates. */
    private fun inRoot(root: View, view: View): Rect {
        val bounds = Rect(view.left, view.top, view.right, view.bottom)
        var parent: ViewParent? = view.parent
        while (parent is View && parent !== root) {
            bounds.offset(parent.left, parent.top)
            parent = parent.parent
        }
        return bounds
    }

    /**
     * Every view in the tree except [excluded], keyed by where it sits in the
     * hierarchy and what it is, mapped to where it ended up. Compared between two
     * layout passes, this is "the background moved nothing".
     */
    private fun everyOtherView(root: ViewGroup, excluded: View): Map<String, Rect> {
        val found = LinkedHashMap<String, Rect>()
        fun walk(view: View, path: String) {
            if (view !== excluded) {
                found["$path:${view.javaClass.simpleName}:${view.id}"] = inRoot(root, view)
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    walk(view.getChildAt(index), "$path.$index")
                }
            }
        }
        walk(root, "root")
        return found
    }

    /**
     * The field as it is drawn with [palette] in place and the drift stopped, so
     * the bitmap is the palette's own frame rather than whatever phase an
     * animator happened to be at.
     */
    private fun render(
        palette: TvAmbientPalette,
        width: Int,
        height: Int,
        rms: Float = 0f,
    ): Bitmap {
        val context = tvContext(geometries.first())
        val view = onMainThread {
            TvAmbientBackgroundView(context).apply {
                setAmbientPalette(palette)
                stopAmbient()
            }
        }
        layout(view, width, height)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // The field follows loudness over time, so it is drawn repeatedly with the
        // pipeline held at [rms] rather than once: one frame of a five-hundred
        // millisecond envelope is not the state a viewer ever sees.
        for (frame in 0 until ENERGY_SETTLE_FRAMES) {
            PlaybackAudioLevel.publish(rms, SystemClock.uptimeMillis())
            view.draw(canvas)
        }
        PlaybackAudioLevel.publishSilence(SystemClock.uptimeMillis())
        return bitmap
    }

    private class Stats(
        val meanLuma: Float,
        val maxLuma: Float,
        val p10: Int,
        val p90: Int,
        val meanSaturation: Float,
    )

    private fun statsOf(bitmap: Bitmap): Stats {
        return statsOf(bitmap, Rect(0, 0, bitmap.width, bitmap.height))
    }

    private fun statsOf(bitmap: Bitmap, region: Rect): Stats {
        val width = region.width()
        val height = region.height()
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, region.left, region.top, width, height)

        val luma = IntArray(pixels.size)
        var total = 0L
        var saturation = 0f
        for (index in pixels.indices) {
            val pixel = pixels[index]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val value = (0.2126f * r + 0.7152f * g + 0.0722f * b).roundToInt().coerceIn(0, 255)
            luma[index] = value
            total += value

            val max = maxOf(r, g, b)
            if (max > 0) saturation += (max - minOf(r, g, b)) / max.toFloat()
        }
        luma.sort()

        return Stats(
            meanLuma = total.toFloat() / luma.size,
            maxLuma = luma.last().toFloat(),
            p10 = luma[luma.size / 10],
            p90 = luma[luma.size * 9 / 10],
            meanSaturation = saturation / pixels.size,
        )
    }

    private class BackgroundLuma(val p50: Int, val p99: Int, val max: Int, val counted: Int)

    /**
     * The luma of the background inside [rect], with anything bright enough to be
     * a glyph left out of it - the question is what the text is drawn *on*.
     */
    private fun backgroundLuma(frame: Bitmap, rect: Rect): BackgroundLuma {
        val pixels = IntArray(rect.width() * rect.height())
        frame.getPixels(pixels, 0, rect.width(), rect.left, rect.top, rect.width(), rect.height())

        val background = pixels
            .map { lumaOf(it) }
            .filter { it <= GLYPH_LUMA }
            .sorted()
        assertTrue(
            "no background pixels found in $rect; the band was measured wrong",
            background.isNotEmpty(),
        )

        return BackgroundLuma(
            p50 = background[background.size / 2],
            p99 = background[background.size * 99 / 100],
            max = background.last(),
            counted = background.size,
        )
    }

    private fun lumaOf(pixel: Int): Int {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return (0.2126f * r + 0.7152f * g + 0.0722f * b).roundToInt().coerceIn(0, 255)
    }

    /** Four fully saturated, fully bright colours: every one clamped to the ceiling. */
    private val worstCaseArtwork: TvAmbientPalette
        get() = TvAmbientPolicy.fromSwatches(
            listOf(
                TvAmbientSwatch(0xFFFF0000.toInt(), 25),
                TvAmbientSwatch(0xFF00FF00.toInt(), 25),
                TvAmbientSwatch(0xFF0000FF.toInt(), 25),
                TvAmbientSwatch(0xFFFFC300.toInt(), 25),
            ),
        )

    private fun <T> onMainThread(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    /** Media3's own audio sink, built the way Media3 builds it, for comparison. */
    @OptIn(UnstableApi::class)
    private class StockSinkProbe(context: Context) : DefaultRenderersFactory(context) {
        fun sink(context: Context): AudioSink? = buildAudioSink(context, false, false)
    }
}
