package com.example.musicplayerapp

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.musicplayerapp.ui.PlayerControl
import com.example.musicplayerapp.ui.PlayerControlState
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The PLAYER's central control, measured as it is actually painted.
 *
 * `PlayerLayoutTest` measures `btn_play`'s bounds, which is where the frozen
 * control sits but not what the user sees: a view can report 80x80 and paint a
 * smaller shape, or paint nothing at all - which is exactly what connecting used
 * to do. So this test does not read a single view bound for the control's size.
 * It rasterises `player_controls` and finds the `primary`-coloured region, and
 * every claim about the control's size, radius and position is a claim about
 * those pixels.
 *
 * The frozen source, `PLAYER`/`PLAYER_dark` > `Player Section` > `Controls`:
 *
 *   play/pause             80 x 80, cornerRadius 20, fill `primary`
 *   play/pause > Container 23.33 x 23.33 at (28.33, 28.33) - centred at (40, 40)
 *   Container   > Icon     23.33 x 23.33, fill `on_primary`, hugged by Container
 *                          and therefore ink, not canvas
 *
 * All three faces are checked in both themes, and the fill has to be the same
 * rectangle in all three: Play -> connecting -> Pause must not move or resize
 * anything.
 */
@RunWith(AndroidJUnit4::class)
class PlayerControlRenderTest {

    /** The design width. The control is centred, so the others add nothing here. */
    private val widthDp = 390

    private val findings = mutableListOf<String>()
    private val log = mutableListOf<String>()

    @Test
    fun theControlPaintsTheFrozenShapeInEveryState() {
        onMainActivity { activity ->
            for (night in listOf(false, true)) {
                sweep(inflaterFor(activity, night), if (night) "dark" else "light")
            }
        }

        android.util.Log.i("PLAYERQA", "==== PLAYER control (API ${Build.VERSION.SDK_INT}) ====")
        log.forEach { android.util.Log.i("PLAYERQA", "  $it") }
        findings.forEach { android.util.Log.e("PLAYERQA", "  FINDING $it") }

        assertTrue(
            "PLAYER control findings on API ${Build.VERSION.SDK_INT}:\n" +
                findings.joinToString("\n") { "  $it" },
            findings.isEmpty(),
        )
    }

    private fun sweep(inflater: LayoutInflater, theme: String) {
        val context = inflater.context
        val density = context.resources.displayMetrics.density
        val dp = { v: Number -> v.toFloat() * density }
        val toDp = { px: Int -> "%.1f".format(px / density) }

        val primary = ContextCompat.getColor(context, R.color.primary)
        val onPrimary = ContextCompat.getColor(context, R.color.on_primary)
        val background = ContextCompat.getColor(context, R.color.background)

        val widthPx = dp(widthDp).roundToInt()
        val page = inflater.inflate(R.layout.fragment_myata_stream, null) as ViewGroup
        val button = page.findViewById<ImageView>(R.id.btn_play)
        val spinner = page.findViewById<ProgressBar>(R.id.loading_spinner)
        val controls = page.findViewById<ViewGroup>(R.id.player_controls)
        val favourite = page.findViewById<View>(R.id.btn_favorite)
        val history = page.findViewById<View>(R.id.btn_history)
        val control = PlayerControl(button, spinner)

        val fills = mutableMapOf<PlayerControlState, Rect>()

        for (state in PlayerControlState.values()) {
            val where = "$theme/$state"
            control.render(state)
            layout(page, widthPx)

            val raster = rasterise(controls, background)
            val fill = ink(raster, primary, null)
            if (fill == null) {
                // What #40 shipped for CONNECTING: the whole button was hidden, so
                // the frozen surface was not painted at all.
                findings += "$where: no `primary` surface is painted - the control is not on screen"
                continue
            }
            fills[state] = fill

            /* ---- 1. the painted surface is the frozen 80x80 ---- */

            expect(where, "painted control width", fill.width(), dp(80))
            expect(where, "painted control height", fill.height(), dp(80))

            // ...and it is the button itself, not a smaller child drawn inside an
            // 80x80 parent. Both have to be true: an 80dp box holding a 64dp
            // visible shape passes the bounds check and fails this one.
            val bounds = rectIn(button, controls)
            expect(where, "painted surface left vs btn_play", fill.left, bounds.left.toFloat())
            expect(where, "painted surface top vs btn_play", fill.top, bounds.top.toFloat())
            expect(where, "painted surface right vs btn_play", fill.right, bounds.right.toFloat())
            expect(where, "painted surface bottom vs btn_play", fill.bottom, bounds.bottom.toFloat())

            // Centred in the row, and clear of the two 49x54 slots.
            expect(where, "painted control centred", fill.centerX(), controls.width / 2f)
            noOverlap(where, "favourite", rectIn(favourite, controls), "control", fill)
            noOverlap(where, "control", fill, "history", rectIn(history, controls))

            /* ---- 2. radius 20 ---- */

            if (raster.getPixel(fill.left, fill.top) == primary) {
                findings += "$where: the surface's top-left pixel is filled - the 20dp corner is not rounded"
            }
            val radius = cornerRadius(raster, primary, fill)
            if (radius == null) {
                findings += "$where: the surface never reaches its full width - it is not a rounded rectangle"
            } else {
                expect(where, "corner radius", radius, dp(20), tolerance = dp(1.5f))
            }

            /* ---- 3. what sits in the middle ---- */

            when (state) {
                PlayerControlState.CONNECTING -> {
                    if (button.drawable != null) {
                        findings += "$where: the play/pause glyph is still set behind the progress indicator"
                    }
                    if (spinner.visibility != View.VISIBLE) {
                        findings += "$where: the progress indicator is not shown"
                    }
                    // The whole point: inside the control, not beside it.
                    val ring = rectIn(spinner, controls)
                    if (!fill.contains(ring)) {
                        findings += "$where: the progress indicator at $ring is not inside the painted control $fill"
                    }
                    expect(where, "progress indicator size", ring.width(), dp(23))
                    expect(where, "progress indicator centred in x", ring.centerX(), fill.centerX().toFloat())
                    expect(where, "progress indicator centred in y", ring.centerY(), fill.centerY().toFloat())
                    // It is legible only because the surface is behind it. The
                    // frozen palette gives it no contrast of its own: `on_primary`
                    // is #FFFFFF against a #F8F9FA `background` in Light and
                    // #0F253E against a #0F253E one in Dark, so an indicator drawn
                    // off the control is invisible in both themes - which is what
                    // it was. Against `primary` it reads in both.
                    val tint = spinner.indeterminateTintList?.defaultColor
                    if (tint != onPrimary) {
                        findings += "$where: the progress indicator is tinted $tint, not on_primary $onPrimary"
                    }
                    if (tint == primary) {
                        findings += "$where: the progress indicator is the same colour as the surface it sits on"
                    }
                    // ...and the surface really is under it: sample just outside
                    // the indicator's own box, where a round indicator paints
                    // nothing, and require the fill.
                    for ((x, y) in listOf(
                        ring.left - 2 to ring.top - 2, ring.right + 1 to ring.top - 2,
                        ring.left - 2 to ring.bottom + 1, ring.right + 1 to ring.bottom + 1,
                    )) {
                        if (raster.getPixel(x, y) != primary) {
                            findings += "$where: no surface behind the progress indicator at ($x,$y)"
                        }
                    }
                    log += "$where: control ${toDp(fill.width())}x${toDp(fill.height())}dp at " +
                        "(${fill.left},${fill.top}), indicator ${toDp(ring.width())}dp inside it"
                }
                else -> {
                    if (spinner.visibility != View.GONE) {
                        findings += "$where: the progress indicator is still shown"
                    }
                    // Searched inside the fill, and inset by the corner radius so
                    // the window is entirely surface: in Dark, `on_primary` and
                    // `background` are both #0F253E, so the page showing through
                    // the rounded corners would otherwise read as glyph. The
                    // window is still 40x40 around the centre, comfortably larger
                    // than the frozen 23.33 box - a glyph that outgrows it or
                    // wanders out of it fails these checks rather than escaping
                    // them.
                    val glyph = ink(
                        raster, onPrimary,
                        Rect(fill).apply { inset(dp(20).roundToInt(), dp(20).roundToInt()) },
                        tolerance = 48,
                    )
                    if (glyph == null) {
                        findings += "$where: no `on_primary` glyph is painted inside the control"
                    } else {
                        // The two frozen glyphs are not the same size. Pause is the
                        // 23.33 square the canonical snapshot records for
                        // `play/pause > Container`; play is its own node, the same
                        // width and 29.69 tall.
                        val glyphHeight = if (state == PlayerControlState.PLAY) 29.69f else 23.33f
                        // 1.5dp, because a pointed outline cannot be measured to
                        // the pixel by colour. The play glyph's corners are wedges
                        // that taper to nothing, so their outermost pixels are
                        // mostly surface and read as surface: 28.6 against 29.69
                        // on API 24, 29.0 on API 36. The error this check exists
                        // to catch was 13.4 against 29.69.
                        expect(where, "glyph width", glyph.width(), dp(23.33f), tolerance = dp(1.5f))
                        expect(where, "glyph height", glyph.height(), dp(glyphHeight), tolerance = dp(1.5f))
                        expect(where, "glyph centred in x", glyph.centerX(), fill.centerX().toFloat(), dp(1))
                        expect(where, "glyph centred in y", glyph.centerY(), fill.centerY().toFloat(), dp(1))
                        // Both frozen glyphs are outlines: the surface shows
                        // through the middle. A solid glyph - which is what stood
                        // in for them before the exact assets arrived - would fill
                        // its own centre with `on_primary`.
                        if (raster.getPixel(glyph.centerX(), glyph.centerY()) != primary) {
                            findings += "$where: the glyph is solid at its centre; the frozen one is an outline"
                        }
                        log += "$where: control ${toDp(fill.width())}x${toDp(fill.height())}dp at " +
                            "(${fill.left},${fill.top}), glyph ${toDp(glyph.width())}x${toDp(glyph.height())}dp"
                    }
                }
            }
            raster.recycle()
        }

        /* ---- 4. nothing moves between the three faces ---- */

        val distinct = fills.values.distinct()
        if (fills.size == PlayerControlState.values().size && distinct.size != 1) {
            findings += "$theme: the painted control changes between states - " +
                fills.entries.joinToString { "${it.key}=${it.value}" }
        }
    }

    /* ---------------------------------------------------------------- infra -- */

    private fun layout(root: ViewGroup, widthPx: Int) {
        root.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(widthPx * 2, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
    }

    /** The view as pixels, over the page's own background so contrast is real. */
    private fun rasterise(view: View, background: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(background)
        view.draw(canvas)
        return bitmap
    }

    /**
     * The bounding box of every pixel matching [colour], optionally restricted to
     * [within].
     *
     * [tolerance] is 0 for the surface: an exact match excludes anti-aliased
     * edges, which is harmless for a rectangle whose straight edges are a full
     * pixel wide, and is what lets the corner checks see the rounding. The glyphs
     * need a tolerance instead. The frozen play glyph is an outlined triangle
     * whose apexes taper to nothing, so its extreme rows are never exactly
     * `on_primary` and an exact match reads it a pixel or two short - by 3px on
     * API 24 and 2 on API 36, from anti-aliasing alone.
     */
    private fun ink(bitmap: Bitmap, colour: Int, within: Rect?, tolerance: Int = 0): Rect? {
        val area = within ?: Rect(0, 0, bitmap.width, bitmap.height)
        val w = area.width()
        val pixels = IntArray(w * area.height())
        bitmap.getPixels(pixels, 0, w, area.left, area.top, w, area.height())

        fun matches(p: Int): Boolean {
            if (tolerance == 0) return p == colour
            return maxOf(
                kotlin.math.abs(android.graphics.Color.red(p) - android.graphics.Color.red(colour)),
                kotlin.math.abs(android.graphics.Color.green(p) - android.graphics.Color.green(colour)),
                kotlin.math.abs(android.graphics.Color.blue(p) - android.graphics.Color.blue(colour)),
            ) <= tolerance
        }

        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = -1
        var bottom = -1
        for (i in pixels.indices) {
            if (!matches(pixels[i])) continue
            val x = area.left + i % w
            val y = area.top + i / w
            if (x < left) left = x
            if (x > right) right = x
            if (y < top) top = y
            if (y > bottom) bottom = y
        }
        return if (right < 0) null else Rect(left, top, right + 1, bottom + 1)
    }

    /**
     * How far below its top edge the fill first spans its whole width. For a
     * rounded rectangle that is the corner radius: the arc reaches the left edge
     * exactly `r` below the top.
     */
    private fun cornerRadius(bitmap: Bitmap, colour: Int, fill: Rect): Int? {
        for (dy in 0 until fill.height()) {
            val y = fill.top + dy
            if (bitmap.getPixel(fill.left, y) == colour &&
                bitmap.getPixel(fill.right - 1, y) == colour
            ) {
                return dy
            }
        }
        return null
    }

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
                try { scenario.close() } catch (e: Throwable) {
                    android.util.Log.w("PLAYERQA", "activity close timed out; checks already complete", e)
                }
            }
        }
    }

    private fun rectIn(v: View, ancestor: View): Rect {
        val r = Rect(v.left, v.top, v.right, v.bottom)
        var p = v.parent
        while (p is View && p !== ancestor) { r.offset(p.left, p.top); p = p.parent }
        return r
    }

    private fun expect(where: String, what: String, actual: Int, expected: Float, tolerance: Float = 1f) {
        if (abs(actual - expected) > tolerance) {
            findings += "$where: $what is ${actual}px, frozen design says ${expected.roundToInt()}px"
        }
    }

    private fun noOverlap(where: String, an: String, a: Rect, bn: String, b: Rect) {
        if (Rect.intersects(a, b)) findings += "$where: $an overlaps $bn ($a vs $b)"
    }
}
