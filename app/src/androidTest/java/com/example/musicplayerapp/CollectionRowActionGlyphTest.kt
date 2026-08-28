package com.example.musicplayerapp

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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
 * The COLLECTION row's trailing action draws the canonical arrow, and not the one
 * that merely looks like it.
 *
 * ## What went wrong, and why a layout test did not catch it
 *
 * The frozen canonical node is an `arrow_forward` instance rotated 45 degrees inside
 * a 40x40 ring. `arrow_forward` names two different Material glyphs - the legacy
 * **Material Icons** one and the current **Material Symbols** one - and this
 * repository shipped the legacy drawing. Every geometry assertion in
 * `CollectionLayoutTest` still passed, because all of them are about the ring: its
 * 40dp box, its position against the cover, its 48dp touch target. None of them can
 * see the drawing inside it, and the two arrows share a shaft and a tip, so even
 * their rotated bounding boxes come out identical.
 *
 * ## What actually separates them
 *
 * The arrowhead, and therefore the ink:
 *
 *   Material Icons (legacy)   box 16 x 12 at (4,6)   area 55.368
 *   Material Symbols          box 16 x 16 at (4,4)   area 66.508
 *
 * The canonical export records the box and not the geometry, and all six instances -
 * 2409:31572 / 31560 / 31542 light, 2444:18398 / 18410 / 18422 dark - report 16x16 at
 * (4,4) on every edge. That is what identifies the generation; the areas above are
 * what a test can measure, and they differ by 20.1%, which no tolerance worth having
 * can straddle.
 *
 * So this rasterises the glyph and counts its ink. Rotation preserves area, so the
 * expected count is the path area scaled by the raster - it does not depend on the
 * 45 degrees, on the ring, or on where the row puts it.
 */
@RunWith(AndroidJUnit4::class)
class CollectionRowActionGlyphTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** The raster the ink is counted on. 10x the 24 viewport. */
    private val raster = 240

    /**
     * Shoelace area of Material Symbols `arrow_forward` in its 24 viewport, times the
     * raster scale squared. Derived in `ic_collection_row_action.xml`.
     */
    private val canonicalInk = (66.508 * 10 * 10).roundToInt()

    /** The same for the legacy arrow this used to be, which must not pass. */
    private val legacyInk = (55.368 * 10 * 10).roundToInt()

    /**
     * Anti-aliased edges are split either side of the alpha threshold, so the count
     * lands within a percent or so of the true area. 5% is comfortably inside the
     * 20.1% that separates the two candidates.
     */
    private val tolerance = 0.05

    // ==================== the resource ====================

    @Test
    fun the_glyph_resource_is_the_canonical_arrow() {
        val ink = inkOf(drawable(context, R.drawable.ic_collection_row_action))
        assertCanonical("ic_collection_row_action", ink)
    }

    @Test
    fun the_glyph_is_still_a_24dp_vector() {
        // The ring, its position and its touch target are CollectionLayoutTest's to
        // assert. This is the one dimension that belongs to the glyph itself, and it
        // is here so that "replace only the drawing" stays true: a 24dp box in, a
        // 24dp box out.
        val glyph = drawable(context, R.drawable.ic_collection_row_action)
        val expected = (24 * context.resources.displayMetrics.density).roundToInt()
        assertEquals("glyph width", expected, glyph.intrinsicWidth)
        assertEquals("glyph height", expected, glyph.intrinsicHeight)
    }

    // ==================== the row that uses it ====================

    @Test
    fun the_row_action_draws_the_canonical_arrow_in_both_themes() {
        onMainActivity { activity ->
            for (night in listOf(false, true)) {
                val theme = if (night) "dark" else "light"
                val action = rowAction(inflaterFor(activity, night))

                assertNotNull("$theme: the row must carry an action glyph", action.drawable)

                // The view tints with SRC_IN, which repaints the ink without moving
                // it, so the count is the same measurement in both themes - which is
                // also the assertion that the glyph has no per-theme variant hiding
                // behind it.
                assertCanonical("$theme row action", inkOf(action.drawable))
            }
        }
    }

    // ==================== helpers ====================

    private fun assertCanonical(what: String, ink: Int) {
        val low = (canonicalInk * (1 - tolerance)).roundToInt()
        val high = (canonicalInk * (1 + tolerance)).roundToInt()

        assertTrue(
            "$what: ink $ink is not the canonical Material Symbols arrow_forward " +
                "($canonicalInk +/- ${(tolerance * 100).roundToInt()}%, so $low..$high). " +
                "The legacy Material Icons arrow measures about $legacyInk here.",
            ink in low..high,
        )

        // Said separately so a failure names the actual regression rather than only a
        // number out of range. This is the exact drawing G-A7 live validation found.
        assertTrue(
            "$what: ink $ink matches the LEGACY Material Icons arrow ($legacyInk), " +
                "which is the glyph this test exists to keep out",
            abs(ink - legacyInk) > abs(ink - canonicalInk),
        )
    }

    /** Non-transparent pixels when the drawable is painted alone on nothing. */
    private fun inkOf(source: Drawable): Int {
        val glyph = source.constantState?.newDrawable()?.mutate() ?: source
        val bitmap = Bitmap.createBitmap(raster, raster, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.TRANSPARENT)

        glyph.setBounds(0, 0, raster, raster)
        glyph.draw(Canvas(bitmap))

        val pixels = IntArray(raster * raster)
        bitmap.getPixels(pixels, 0, raster, 0, 0, raster, raster)
        bitmap.recycle()

        // Half opacity as the boundary: an anti-aliased edge pixel that is more than
        // half covered counts, one that is less does not, which is what makes the
        // count approximate the area rather than the silhouette.
        return pixels.count { Color.alpha(it) > 128 }
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
