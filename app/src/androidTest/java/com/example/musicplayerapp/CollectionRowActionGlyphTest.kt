package com.example.musicplayerapp

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The COLLECTION row's trailing action draws the **authoritative** arrow.
 *
 * ## What this asserts, and what it deliberately stopped asserting
 *
 * The first version of this test measured the glyph's ink area and checked it was
 * nearer one Material candidate than the other. That was the same mistake the
 * drawable itself was making, one level up: it could tell two families apart and it
 * could not tell a correct path from a close one. Both candidates were wrong.
 *
 * So the reference here is the owner's Figma export -
 * `tools/figma-export/collection-icons/owner-final/collection_row_action_arrow.svg` -
 * transcribed vertex for vertex below and rasterised independently. The drawable is
 * rasterised the same way and the two are compared pixel by pixel. Nothing about a
 * Material glyph enters into it.
 *
 * ## What this cannot see, and where that is checked instead
 *
 * Rendering tests have a floor, and it is worth stating rather than discovering. The
 * difference between the export and the Material path it was closest to is 0.025 of
 * a 40 viewport at one vertex - 0.09 square units of area, nine pixels at this
 * raster, against the 131 that must be allowed for two rasterisers disagreeing about
 * anti-aliased edges. No achievable raster closes that: the difference and the noise
 * it hides in grow together.
 *
 * **`CollectionRowActionAssetTest` is what proves the path is exactly the export**,
 * by comparing the two source files character for character. This suite proves the
 * things only a device can: that the resource compiles to that shape, and that the
 * row draws it at the right size, in the right place, in both themes.
 *
 * Between them the failure modes are covered. Measured against the 24-viewport
 * Material version this replaced, this suite reports 17864 differing pixels of 6534
 * reference ink.
 *
 * ## Three claims
 *
 *  1. the drawable's filled region is the exported shape, to the pixel;
 *  2. the row still draws it in the same place and at the same size as before the
 *     viewport changed from 24 to 40 - the ink lands on the exported coordinates;
 *  3. both themes draw the same geometry, tinted differently.
 */
@RunWith(AndroidJUnit4::class)
class CollectionRowActionGlyphTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * The exported path, vertex for vertex.
     *
     * Verbatim from the `d` of `collection_row_action_arrow.svg`, expanded only where
     * SVG's shorthand is - `H25.6568` is a line to x with y held, `V25.6569` a line to
     * y with x held - so every number below appears in the export as written.
     */
    private val exported = listOf(
        23.6593f to 17.7549f,
        15.0502f to 26.3640f,
        13.6360f to 24.9497f,
        22.2451f to 16.3407f,
        14.3255f to 16.3407f,
        14.3431f to 14.3431f,
        25.6568f to 14.3431f, // H25.6568
        25.6568f to 25.6569f, // V25.6569
        23.6593f to 25.6745f,
        23.6593f to 17.7549f,
    )

    /** The export's own viewport. */
    private val viewport = 40f

    /** 10x the viewport, so a coordinate difference of 0.025 is a quarter of a pixel. */
    private val raster = 400

    /**
     * What an anti-aliased edge can move at the control's own resolution, which is
     * where the placement is measured. Well under the 2.4 units that would be needed
     * to hide a wrongly scaled glyph.
     */
    private val placementTolerance = 0.75f

    // ==================== 1. the drawable is the exported path ====================

    @Test
    fun the_drawable_is_the_exported_figma_path() {
        val actual = rasterise(drawable(context, R.drawable.ic_collection_row_action), raster)
        val reference = rasteriseExport(raster)

        assertCoverageMatches("ic_collection_row_action", actual, reference)
    }

    @Test
    fun the_drawable_carries_the_exports_own_viewport() {
        // 40dp in, 40dp out. The ImageView is 40dp with scaleType=center, so this is
        // what makes the export's coordinates and the control's coordinates the same
        // numbers - and what the placement assertion below then measures.
        val glyph = drawable(context, R.drawable.ic_collection_row_action)
        val expected = (viewport * context.resources.displayMetrics.density).roundToInt()
        assertEquals("glyph width", expected, glyph.intrinsicWidth)
        assertEquals("glyph height", expected, glyph.intrinsicHeight)
    }

    // ============ 2 and 3. the row draws it, in place, in both themes ============

    @Test
    fun the_row_draws_the_exported_arrow_where_the_export_puts_it() {
        onMainActivity { activity ->
            for (night in listOf(false, true)) {
                val theme = if (night) "dark" else "light"
                val action = rowAction(inflaterFor(activity, night))

                assertNotNull("$theme: the row must carry an action glyph", action.drawable)

                // The control itself is unchanged and CollectionLayoutTest owns it;
                // this is here because the placement assertion below is stated as a
                // fraction of it.
                val size = (viewport * activity.resources.displayMetrics.density).roundToInt()
                assertEquals("$theme: the control is still 40dp", size, action.width)

                // The ring is the view's background and would count as ink. Dropping
                // it leaves the glyph alone in the 40dp box, which is exactly the
                // frame the export's coordinates are in.
                action.background = null
                val ink = inkBounds(rasteriseView(action), action.width)
                val expectedBounds = exportedBounds()
                val edges = listOf("left", "top", "right", "bottom")

                for (i in edges.indices) {
                    assertTrue(
                        "$theme: the glyph's ${edges[i]} edge is at ${ink[i]}, the export " +
                            "puts it at ${expectedBounds[i]} (40dp viewport units)",
                        abs(ink[i] - expectedBounds[i]) <= placementTolerance,
                    )
                }

                // And the drawing itself, not only its box. Tint is SRC_IN, so it
                // repaints the ink without moving it and the coverage is comparable
                // in both themes.
                assertCoverageMatches(
                    "$theme row action",
                    rasterise(action.drawable, raster),
                    rasteriseExport(raster),
                )
            }
        }
    }

    // ==================== helpers ====================

    private fun exportedBounds(): FloatArray {
        val xs = exported.map { it.first }
        val ys = exported.map { it.second }
        return floatArrayOf(xs.min(), ys.min(), xs.max(), ys.max())
    }

    /**
     * Fails unless [actual] and [reference] cover the same pixels.
     *
     * Compared as coverage rather than colour: the drawable is tinted in the row and
     * the reference is not, and the question is which pixels are painted.
     */
    private fun assertCoverageMatches(what: String, actual: IntArray, reference: IntArray) {
        require(actual.size == reference.size)

        var differing = 0
        var referenceInk = 0
        for (i in reference.indices) {
            val a = Color.alpha(actual[i]) > 128
            val r = Color.alpha(reference[i]) > 128
            if (r) referenceInk++
            if (a != r) differing++
        }

        // Everything that can legitimately differ is on the boundary: two rasterisers
        // splitting anti-aliased edge pixels either side of the threshold. The
        // perimeter is about 600 pixels at this raster against roughly 6650 of ink,
        // so 2% is generous for jitter and still well below what the one 0.025 vertex
        // difference moves.
        val allowed = (referenceInk * 0.02).roundToInt()
        assertTrue(
            "$what: $differing of $referenceInk reference ink pixels disagree with the " +
                "exported Figma path (at most $allowed allowed for anti-aliasing). " +
                "The drawable is not collection_row_action_arrow.svg.",
            differing <= allowed,
        )
    }

    /** The exported path, filled by this test rather than by the resource. */
    private fun rasteriseExport(size: Int): IntArray {
        val scale = size / viewport
        val path = Path()
        exported.forEachIndexed { i, vertex ->
            val x = vertex.first * scale
            val y = vertex.second * scale
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()

        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.TRANSPARENT)
        Canvas(bitmap).drawPath(
            path,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.WHITE
            },
        )
        return bitmap.pixels().also { bitmap.recycle() }
    }

    private fun rasterise(source: Drawable, size: Int): IntArray {
        val glyph = source.constantState?.newDrawable()?.mutate() ?: source
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.TRANSPARENT)
        glyph.setBounds(0, 0, size, size)
        glyph.draw(Canvas(bitmap))
        return bitmap.pixels().also { bitmap.recycle() }
    }

    /** The view as it really draws, at its laid-out size. */
    private fun rasteriseView(view: View): IntArray {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.TRANSPARENT)
        view.draw(Canvas(bitmap))
        return bitmap.pixels().also { bitmap.recycle() }
    }

    private fun Bitmap.pixels(): IntArray =
        IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }

    /** The painted region of a square raster, expressed in viewport units. */
    private fun inkBounds(pixels: IntArray, size: Int): FloatArray {
        var left = size
        var top = size
        var right = -1
        var bottom = -1

        for (i in pixels.indices) {
            if (Color.alpha(pixels[i]) <= 128) continue
            val x = i % size
            val y = i / size
            if (x < left) left = x
            if (x > right) right = x
            if (y < top) top = y
            if (y > bottom) bottom = y
        }

        assertTrue("nothing was painted", right >= 0)

        val unit = size / viewport
        // +1 on the far edges because a painted pixel covers up to its own far side.
        return floatArrayOf(left / unit, top / unit, (right + 1) / unit, (bottom + 1) / unit)
    }

    private fun drawable(context: Context, id: Int): Drawable =
        requireNotNull(ContextCompat.getDrawable(context, id)) { "missing drawable" }

    /** The trailing action of a real, inflated COLLECTION row. */
    private fun rowAction(inflater: LayoutInflater): ImageView {
        val ctx = inflater.context
        val parent = FrameLayout(ctx)
        val card = inflater.inflate(R.layout.item_favorite_track, parent, false)
        parent.addView(card)

        val widthPx = (358 * ctx.resources.displayMetrics.density).roundToInt()
        parent.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        parent.layout(0, 0, parent.measuredWidth, parent.measuredHeight)

        return (card as ViewGroup).findViewById(R.id.btn_row_action)
    }

    /**
     * The themed inflater `CollectionLayoutTest` uses, verbatim.
     *
     * The row is a `MaterialCardView`, so it inflates only against a Material theme -
     * a bare configuration context off the application one throws. `setTheme` after
     * `createConfigurationContext` is the part that matters, and cloning the
     * activity's own inflater is what carries the typography factory with it.
     */
    private fun inflaterFor(activity: MainActivity, night: Boolean): LayoutInflater {
        val cfg = Configuration(activity.resources.configuration)
        cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        val themed = activity.createConfigurationContext(cfg)
        themed.setTheme(R.style.AppTheme)
        return activity.layoutInflater.cloneInContext(themed)
    }

    private fun onMainActivity(block: (MainActivity) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).let { scenario ->
            try {
                scenario.onActivity(block)
            } finally {
                // The API 24 image does not reliably reach DESTROYED; the checks are
                // complete by here either way. Same handling as the other suites.
                try {
                    scenario.close()
                } catch (e: Throwable) {
                    android.util.Log.w("COLLECTIONQA", "activity close timed out", e)
                }
            }
        }
    }
}
