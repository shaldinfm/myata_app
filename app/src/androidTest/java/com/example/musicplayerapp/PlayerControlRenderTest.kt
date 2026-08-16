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
 *
 * The glyph inside it is no longer the canonical snapshot's: the owner-supplied
 * FINAL assets in tools/figma-export/player-icons/owner-final supersede it, and
 * they are 4-wide round-joined STROKES rather than filled outlines. Painted, with
 * the stroke reaching 2 past the centreline on every side:
 *
 *   play    27.33 x 33.9957   centred at (39.665, 40.0001) in the 80 box
 *   pause   27.33 x 27.33     centred at (40, 40)
 *
 * Play sits a third of a unit left of the box centre and the supplied file says
 * so; the drawables take the 80 box as their viewport so it lands where the file
 * puts it rather than being centred for tidiness.
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
        val glyphColour = ContextCompat.getColor(context, R.color.player_play_glyph)
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
                    expect(where, "progress indicator size", ring.width(), dp(27.33f), tolerance = dp(1))
                    expect(where, "progress indicator centred in x", ring.centerX(), fill.centerX().toFloat())
                    expect(where, "progress indicator centred in y", ring.centerY(), fill.centerY().toFloat())
                    // It stands in for the glyph, so it takes the glyph's colour,
                    // and it is legible only because the surface is behind it: the
                    // palette gives it no contrast of its own - #F8F9FA against a
                    // #F8F9FA `background` in Light and #0F253E against a #0F253E
                    // one in Dark - so an indicator drawn off the control is
                    // invisible in both themes, which is what it was. Against
                    // `primary` it reads in both.
                    val tint = spinner.indeterminateTintList?.defaultColor
                    if (tint != glyphColour) {
                        findings += "$where: the progress indicator is tinted $tint, not player_play_glyph $glyphColour"
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
                    // the window is entirely surface: in Dark, the glyph colour
                    // and `background` are both #0F253E, so the page showing
                    // through the rounded corners would otherwise read as glyph.
                    // The window is 40x40 around the centre, still larger than the
                    // taller of the two glyphs at 27.33 x 33.9957 - one that
                    // outgrows it or wanders out of it fails these checks rather
                    // than escaping them.
                    val glyph = ink(
                        raster, glyphColour,
                        Rect(fill).apply { inset(dp(20).roundToInt(), dp(20).roundToInt()) },
                        tolerance = 48,
                    )
                    if (glyph == null) {
                        findings += "$where: no glyph is painted inside the control"
                    } else {
                        // The two supplied glyphs share a width and differ in
                        // height: play's path is 29.9957 tall against pause's
                        // 23.33, and the 4-wide stroke adds 2 to every side of
                        // both. Neither reads as a size change in use - the 80x80
                        // surface is what is seen, and it belongs to the button.
                        val glyphHeight = if (state == PlayerControlState.PLAY) 33.9957f else 27.33f
                        // 1.5dp, because a pointed outline cannot be measured to
                        // the pixel by colour: the play glyph's apex is a round
                        // join whose outermost pixels are mostly surface and read
                        // as surface. The error this check exists to catch was
                        // 13.4 against 29.69.
                        expect(where, "glyph width", glyph.width(), dp(27.33f), tolerance = dp(1.5f))
                        expect(where, "glyph height", glyph.height(), dp(glyphHeight), tolerance = dp(1.5f))
                        // Pause is centred in the box; play is 0.335 left of it,
                        // which is what play_light.svg and play_dark.svg state -
                        // their path spans x 28..51.33, whose centre is 39.665.
                        val centreOffsetX = if (state == PlayerControlState.PLAY) dp(-0.335f) else 0f
                        expect(
                            where, "glyph x against the box centre",
                            glyph.centerX(), fill.centerX() + centreOffsetX, dp(1),
                        )
                        expect(where, "glyph centred in y", glyph.centerY(), fill.centerY().toFloat(), dp(1))
                        // Both supplied glyphs are strokes, so the surface shows
                        // through the middle of each. A solid glyph - which is what
                        // stood in for them before any exact asset arrived - would
                        // fill its own centre with the glyph colour.
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
     * need a tolerance instead. The play glyph is a stroked triangle whose apexes
     * are round joins, so its extreme rows are a curve's worth of partial cover
     * and are never exactly the glyph colour; an exact match reads the box a pixel
     * or two short from anti-aliasing alone.
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
