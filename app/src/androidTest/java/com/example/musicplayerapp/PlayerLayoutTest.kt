package com.example.musicplayerapp

import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The PLAYER upper section against the frozen canonical frame.
 *
 * The frozen `Player Section` is hand-positioned rather than auto-laid-out, so
 * every offset below is an absolute one read off the frame:
 *
 *   16   header row top     63   header row bottom / swipe top
 *   73   swipe bottom       79   page top
 *   95   album art          334  album art bottom      239x239 r20
 *   371  track title        421  artist bottom         24/24 and 18/18
 *   442  controls           522  controls bottom       play 80x80, slots 49x54
 *
 * The header and the swipe dots live in the shell and the rest in the page, so
 * the two are measured separately and the page's offsets are the frozen ones
 * minus 79.
 *
 * Phase B does not draw the Broadcast History card the frozen frame puts below
 * this; History stays in its bottom sheet until Phase C. Nothing here asserts
 * anything about the space it will occupy.
 */
@RunWith(AndroidJUnit4::class)
class PlayerLayoutTest {

    private val widthsDp = listOf(320, 360, 390, 412)

    private val longTitle = "КРАСНОЗНАМЁННАЯ ДИВИЗИЯ ИМЕНИ МОЕЙ БАБУШКИ"
    private val longArtist = "MIAMI HORROR FT. POOLSIDE И ЕЩЁ НЕСКОЛЬКО ИСПОЛНИТЕЛЕЙ"

    private val findings = mutableListOf<String>()
    private val log = mutableListOf<String>()

    @Test
    fun playerReproducesTheFrozenUpperSection() {
        onMainActivity { activity ->
            for (night in listOf(false, true)) {
                sweep(inflaterFor(activity, night), if (night) "dark" else "light")
            }
        }

        android.util.Log.i("PLAYERQA", "==== PLAYER (API ${Build.VERSION.SDK_INT}) ====")
        log.forEach { android.util.Log.i("PLAYERQA", "  $it") }
        findings.forEach { android.util.Log.e("PLAYERQA", "  FINDING $it") }

        assertTrue(
            "PLAYER findings on API ${Build.VERSION.SDK_INT}:\n" + findings.joinToString("\n") { "  $it" },
            findings.isEmpty(),
        )
    }

    private fun sweep(inflater: LayoutInflater, theme: String) {
        val dm = inflater.context.resources.displayMetrics
        val dp = { v: Number -> v.toFloat() * dm.density }

        for (widthDp in widthsDp) {
            val widthPx = dp(widthDp).roundToInt()
            val where = "$theme@${widthDp}dp"

            /* ---- the shell: header row and the swipe dots ---- */

            val shell = measured(inflater, R.layout.fragment_player, widthPx)
            val header = shell.findViewById<View>(R.id.player_header)
            val label = shell.findViewById<TextView>(R.id.player_header_label)
            val reserved = shell.findViewById<View>(R.id.player_header_action)
            val dots = shell.findViewById<View>(R.id.dots_indicator)
            val pager = shell.findViewById<View>(R.id.viewPager)

            expect(where, "header row y", topIn(header, shell), dp(16))
            expect(where, "header row height", header.height, dp(47))
            expect(where, "header row width", header.width, dp(widthDp - 32))
            expect(where, "swipe y", topIn(dots, shell), dp(63))
            expect(where, "swipe height", dots.height, dp(10))
            expect(where, "page top", topIn(pager, shell), dp(79))

            // The frozen label is textCase UPPER, so what is drawn is not what the
            // string says. Read the laid-out text, not the TextView's.
            val drawn = label.layout?.text?.toString().orEmpty()
            if (drawn.isNotEmpty() && drawn != drawn.uppercase()) {
                findings += "$where: header label is drawn as \"$drawn\"; the frozen frame upper-cases it"
            }

            /*
             * `swipe`: three 10 slots edge to edge, and the active page is a
             * different shape - a 8.53x8.42 nine-lobed cookie against 8.42 circles
             * - not just a different colour. The app drew three identical white
             * ovals filling the whole slot before the play/pause follow-up.
             */
            val markers = listOf(R.id.dot_1, R.id.dot_2, R.id.dot_3).map { shell.findViewById<View>(it) }
            markers.forEachIndexed { i, dot ->
                expect(where, "swipe slot ${i + 1} width", dot.width, dp(10))
                expect(where, "swipe slot ${i + 1} height", dot.height, dp(10))
            }
            for (i in 1 until markers.size) {
                expect(
                    where, "swipe slot ${i + 1} abuts the one before it",
                    leftIn(markers[i], shell), leftIn(markers[i - 1], shell) + dp(10),
                )
            }
            val active = (markers[0] as android.widget.ImageView).drawable
            val inactive = (markers[1] as android.widget.ImageView).drawable

            // Both drawables are the 10 component slot; the ink inside it is what
            // the frozen frame records, so the ink is what is measured. Rendered
            // at 400, one frozen unit is 40.
            val slot = 400
            val unit = slot / 10f
            val activeInk = paintedInk(active, slot)
            val inactiveInk = paintedInk(inactive, slot)
            expect(where, "active marker slot", active.intrinsicWidth, dp(10))
            expect(where, "inactive marker slot", inactive.intrinsicWidth, dp(10))
            expect(where, "active marker ink width", activeInk.width(), 8.53f * unit, tolerance = unit / 2)
            expect(where, "active marker ink height", activeInk.height(), 8.42f * unit, tolerance = unit / 2)
            expect(where, "inactive marker ink width", inactiveInk.width(), 8.42f * unit, tolerance = unit / 2)
            expect(where, "inactive marker ink height", inactiveInk.height(), 8.42f * unit, tolerance = unit / 2)

            // Ink size alone cannot tell them apart either - 8.53 against 8.42 -
            // so the shapes are compared as painted. A circle's radius does not
            // vary; the nine-lobed cookie's swings by about 16% of it.
            val activeSwing = radialSwing(active)
            val inactiveSwing = radialSwing(inactive)
            if (activeSwing < 0.10f) {
                findings += "$where: the active swipe marker is a plain disc (radius varies by " +
                    "${(activeSwing * 100).roundToInt()}%); the frozen one is a 9-sided cookie"
            }
            if (inactiveSwing > 0.05f) {
                findings += "$where: the inactive swipe marker is not a circle (radius varies by " +
                    "${(inactiveSwing * 100).roundToInt()}%)"
            }

            // The trailing slot is the frozen overflow control. It was a reserved,
            // empty Space until G1a - the box held open so the label stayed
            // centred, with nothing drawn in it because nothing behind it existed.
            // It has one action now (Настройки), so the box became the control the
            // frame draws in it.
            //
            // Only geometry is asserted here, and deliberately: this file inflates
            // the layout with no fragment behind it, so nothing has attached a
            // click listener and asking whether the control *works* would be asking
            // the wrong object. SettingsOverflowEntryTest asks the running screen.
            expect(where, "trailing overflow slot width", reserved.width, dp(32))
            // `Button:margin` is 32 wide but the `Button` inside it is 20, anchored
            // to the start, and the 4x16 dot column is centred in that 20. The
            // padding is what puts the glyph at the frozen x rather than 6dp right
            // of it.
            expect(where, "overflow glyph inset from the trailing edge", reserved.paddingEnd, dp(12))

            // The label is centred because both ends reserve a slot.
            val labelCentre = leftIn(label, header) + label.width / 2
            expect(where, "header label centred", labelCentre, header.width / 2f)
            requireOneLine(label, "$where/header label")

            /* ---- the page ---- */

            val page = measured(inflater, R.layout.fragment_myata_stream, widthPx) { r ->
                r.findViewById<TextView>(R.id.main_song).text = longTitle
                r.findViewById<TextView>(R.id.main_author).text = longArtist
            }
            val art = page.findViewById<View>(R.id.artwork_card)
            val title = page.findViewById<TextView>(R.id.main_song)
            val artist = page.findViewById<TextView>(R.id.main_author)
            val play = page.findViewById<View>(R.id.btn_play)
            val favorite = page.findViewById<View>(R.id.btn_favorite)
            val dislike = page.findViewById<View>(R.id.btn_dislike)

            expect(where, "album art y", topIn(art, page), dp(16))
            expect(where, "album art size", art.width, dp(239))
            expect(where, "album art height", art.height, dp(239))
            expect(where, "album art centred", leftIn(art, page) + art.width / 2, dp(widthDp) / 2f)

            expect(where, "artist y", topIn(artist, page), dp(324))

            /*
             * The frozen 292 belongs to a ONE-LINE title, and that is now measured
             * on a title that fits one line rather than on `longTitle`.
             *
             * The title is anchored by its bottom, just above the artist, so a
             * second line grows up into the gap under the artwork instead of down
             * over the artist. A one-line title therefore still lands on 292 - it
             * falls out of 324 - 3 - 29.3 - while a two-line one starts higher on
             * purpose. Asserting 292 against a wrapped title would be asserting the
             * old behaviour.
             */
            val onePage = measured(inflater, R.layout.fragment_myata_stream, widthPx) { r ->
                r.findViewById<TextView>(R.id.main_song).text = "FLAME"
                r.findViewById<TextView>(R.id.main_author).text = longArtist
            }
            val oneTitle = onePage.findViewById<TextView>(R.id.main_song)
            requireOneLine(oneTitle, "$where/one-line title")
            expect(where, "one-line track title y", topIn(oneTitle, onePage), dp(292))
            expect(where, "one-line artist y", topIn(onePage.findViewById(R.id.main_author), onePage), dp(324))

            // The frozen boxes are 24 and 18 - exactly the font size, which Figma
            // can draw and Android cannot without clipping. So the requirement is
            // the other way round here: the box must be at least the frozen line
            // height, and it must hold its own glyphs. What stays exact is where
            // the next block starts, asserted below.
            atLeast(where, "track title box", title.height, dp(24))
            atLeast(where, "artist box", artist.height, dp(18))

            expect(where, "controls y", topIn(play, page), dp(363))
            expect(where, "play size", play.width, dp(80))
            expect(where, "play height", play.height, dp(80))
            expect(where, "play centred", leftIn(play, page) + play.width / 2, dp(widthDp) / 2f)
            expect(where, "favourite slot width", favorite.width, dp(49))
            expect(where, "favourite slot height", favorite.height, dp(54))
            expect(where, "favourite x", leftIn(favorite, page), dp(48))

            /*
             * The frozen `like` glyph: 24.5x23.33 of ink, 12 below the top of its
             * own slot rather than centred in it - 12 above and 19 below, so it
             * sits 3.5 higher than the middle of the row. The app drew a 24dp
             * heart, fitCenter'd and centred, before this.
             */
            val likeIcon = (favorite as android.widget.ImageView).drawable
            expect(where, "like glyph width", likeIcon.intrinsicWidth, dp(24.5f))
            expect(where, "like glyph height", likeIcon.intrinsicHeight, dp(23.33f))
            // The frozen glyph is an outline, not a solid: it must leave a hole.
            if (!hasHole(likeIcon)) {
                findings += "$where: the like glyph paints solid; the frozen one is an outlined thumbs-up"
            }
            // scaleType=center draws it at its own size inside the padded box.
            val likeBoxTop = favorite.paddingTop
            val likeBoxHeight = favorite.height - favorite.paddingTop - favorite.paddingBottom
            val likeTop = likeBoxTop + (likeBoxHeight - likeIcon.intrinsicHeight) / 2f
            expect(where, "like glyph y in its slot", likeTop, dp(12), tolerance = dp(0.5f))
            /*
             * The frozen `dislike`, installed in this phase and reading exactly as
             * `like` does: the same 49x54 slot, the same 24.5x23.33 of ink placed 12
             * below the top of it. It held the History entry until now, which
             * fitCenter'd a 24dp glyph in a 12dp padded box instead.
             */
            expect(where, "dislike slot width", dislike.width, dp(49))
            expect(where, "dislike slot height", dislike.height, dp(54))
            expect(where, "dislike x", widthPx - leftIn(dislike, page) - dislike.width, dp(48))

            val dislikeIcon = (dislike as android.widget.ImageView).drawable
            expect(where, "dislike glyph width", dislikeIcon.intrinsicWidth, dp(24.5f))
            expect(where, "dislike glyph height", dislikeIcon.intrinsicHeight, dp(23.33f))
            if (!hasHole(dislikeIcon)) {
                findings += "$where: the dislike glyph paints solid; the frozen one is an outlined thumbs-down"
            }
            val dislikeBoxTop = dislike.paddingTop
            val dislikeBoxHeight = dislike.height - dislike.paddingTop - dislike.paddingBottom
            val dislikeTop = dislikeBoxTop + (dislikeBoxHeight - dislikeIcon.intrinsicHeight) / 2f
            expect(where, "dislike glyph y in its slot", dislikeTop, dp(12), tolerance = dp(0.5f))

            // The two side slots are mirrors: same size, same glyph box, same
            // vertical placement, same distance from their own edge.
            expect(where, "side slots agree on height", dislike.height.toFloat(), favorite.height.toFloat())
            expect(where, "side slots agree on glyph y", dislikeTop, likeTop, tolerance = dp(0.5f))

            /* ---- long Russian metadata: nothing cut, nothing piled up ---- */

            /*
             * The title takes up to two lines and the artist exactly one.
             *
             * Two is not a taste: measured at 390dp a line is 29.3 and a second
             * costs 23.8, and the gap under the artwork is 36.8 - so the second
             * line fits with 13.0 to spare and a third would start above the
             * artwork's own bottom edge. `noOverlap(album art, title)` below is
             * what actually holds that line, on every width in the sweep.
             */
            atMostLines(title, 2, "$where/title")
            requireOneLine(artist, "$where/artist")
            noClipping(title, "$where/title")
            noClipping(artist, "$where/artist")
            noOverlap(where, "album art", art, "title", title, page)
            noOverlap(where, "title", title, "artist", artist, page)
            noOverlap(where, "artist", artist, "play", play, page)
            noOverlap(where, "favourite", favorite, "play", play, page)
            noOverlap(where, "play", play, "dislike", dislike, page)

            log += "$where: header@${topIn(header, shell)} ${header.height}, swipe@${topIn(dots, shell)}, " +
                "page@${topIn(pager, shell)} | art@${topIn(art, page)} ${art.width}, " +
                "title@${topIn(title, page)} ${title.height} ${title.lineCount}L " +
                "(1-line@${topIn(oneTitle, onePage)}), " +
                "artist@${topIn(artist, page)} ${artist.height} ${artist.lineCount}L, " +
                "play@${topIn(play, page)} ${play.width}"
        }
    }

    /* ---------------------------------------------------------------- infra -- */

    private fun measured(
        inflater: LayoutInflater,
        layout: Int,
        widthPx: Int,
        prepare: ((ViewGroup) -> Unit)? = null,
    ): ViewGroup {
        val root = inflater.inflate(layout, null) as ViewGroup
        prepare?.invoke(root)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(widthPx * 2, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        return root
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

    private fun topIn(v: View, ancestor: View) = rectIn(v, ancestor).top
    private fun leftIn(v: View, ancestor: View) = rectIn(v, ancestor).left

    private fun expect(where: String, what: String, actual: Int, expected: Float, tolerance: Float = 1f) {
        expect(where, what, actual.toFloat(), expected, tolerance)
    }

    private fun expect(where: String, what: String, actual: Float, expected: Float, tolerance: Float = 1f) {
        if (abs(actual - expected) > tolerance) {
            findings += "$where: $what is ${actual}px, frozen design says ${expected.roundToInt()}px"
        }
    }

    private fun atLeast(where: String, what: String, actual: Int, floor: Float) {
        if (actual < floor - 1f) {
            findings += "$where: $what is ${actual}px, under the frozen ${floor.roundToInt()}px line"
        }
    }

    /** The drawable rendered on its own, at [size] square. */
    private fun render(drawable: android.graphics.drawable.Drawable, size: Int): android.graphics.Bitmap {
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val copy = drawable.constantState?.newDrawable()?.mutate() ?: drawable
        copy.setBounds(0, 0, size, size)
        copy.draw(android.graphics.Canvas(bitmap))
        return bitmap
    }

    /** The bounding box of what a drawable actually paints, rendered at [size]. */
    private fun paintedInk(drawable: android.graphics.drawable.Drawable, size: Int): Rect {
        val bitmap = render(drawable, size)
        val pixels = IntArray(size * size)
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
        bitmap.recycle()
        var left = size; var top = size; var right = -1; var bottom = -1
        for (i in pixels.indices) {
            if (android.graphics.Color.alpha(pixels[i]) < 128) continue
            val x = i % size
            val y = i / size
            if (x < left) left = x
            if (x > right) right = x
            if (y < top) top = y
            if (y > bottom) bottom = y
        }
        return if (right < 0) Rect() else Rect(left, top, right + 1, bottom + 1)
    }

    /**
     * Whether a drawable paints a hole - transparent pixels enclosed by ink on
     * every side. That is what separates the frozen outlined glyphs from the
     * solid shapes that stood in for them.
     */
    private fun hasHole(drawable: android.graphics.drawable.Drawable): Boolean {
        val size = 256
        val bitmap = render(drawable, size)
        val pixels = IntArray(size * size)
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
        bitmap.recycle()
        val ink = { x: Int, y: Int -> android.graphics.Color.alpha(pixels[y * size + x]) >= 128 }

        for (y in 0 until size) {
            var x = 0
            // walk to the first ink on this row, then look for a gap that has ink
            // on both sides of it horizontally and vertically
            while (x < size && !ink(x, y)) x++
            while (x < size) {
                while (x < size && ink(x, y)) x++
                val gap = x
                while (x < size && !ink(x, y)) x++
                if (x >= size) break
                val mid = (gap + x) / 2
                var above = false
                var below = false
                for (yy in 0 until y) if (ink(mid, yy)) { above = true; break }
                for (yy in y + 1 until size) if (ink(mid, yy)) { below = true; break }
                if (above && below && x - gap > 2) return true
            }
        }
        return false
    }

    /**
     * How much a drawable's painted outline varies from a disc: it renders the
     * drawable large, walks out from the centre at 360 angles until the ink ends,
     * and returns (max - min) / mean of those radii. A circle gives ~0; the frozen
     * nine-lobed cookie gives about 0.16.
     *
     * The walk stops at the first gap, so an outlined glyph would measure its
     * stroke rather than its silhouette - fine here, where both markers are solid.
     */
    private fun radialSwing(drawable: android.graphics.drawable.Drawable): Float {
        val size = 256
        val bitmap = render(drawable, size)

        val cx = size / 2f
        val cy = size / 2f
        val radii = mutableListOf<Float>()
        for (deg in 0 until 360) {
            val a = Math.toRadians(deg.toDouble())
            var r = 0f
            while (r < size / 2f) {
                val x = (cx + r * kotlin.math.cos(a)).toInt()
                val y = (cy + r * kotlin.math.sin(a)).toInt()
                if (x !in 0 until size || y !in 0 until size) break
                if (android.graphics.Color.alpha(bitmap.getPixel(x, y)) < 128) break
                r += 0.5f
            }
            radii += r
        }
        bitmap.recycle()
        val mean = radii.average().toFloat()
        return if (mean <= 0f) 0f else (radii.max() - radii.min()) / mean
    }

    /**
     * A ceiling rather than an exact count: a short title still occupies one line,
     * and the rule is "up to", not "always".
     */
    private fun atMostLines(tv: TextView, max: Int, where: String) {
        if (tv.lineCount > max) {
            findings += "$where: wrapped to ${tv.lineCount} lines; at most $max is allowed here"
        }
    }

    private fun requireOneLine(tv: TextView, where: String) {
        if (tv.lineCount != 1) {
            findings += "$where: wrapped to ${tv.lineCount} lines; the frozen box holds one"
        }
    }

    private fun noClipping(tv: TextView, where: String) {
        val layout = tv.layout ?: return
        if (tv.text.isNullOrEmpty()) return
        val line = tv.text.subSequence(layout.getLineStart(0), layout.getLineEnd(0)).toString()
        if (line.isBlank()) return
        val ink = Rect()
        tv.paint.getTextBounds(line, 0, line.length, ink)
        val headroom = (layout.getLineBaseline(0) - layout.getLineTop(0)) - (-ink.top)
        if (headroom < 0) findings += "$where: ascenders clipped by ${-headroom}px"
        if (layout.getLineBottom(layout.lineCount - 1) > tv.height + 1) {
            findings += "$where: text runs past the bottom of its box"
        }
    }

    private fun noOverlap(where: String, an: String, a: View, bn: String, b: View, root: View) {
        val ra = rectIn(a, root)
        val rb = rectIn(b, root)
        if (Rect.intersects(ra, rb)) findings += "$where: $an overlaps $bn ($ra vs $rb)"
    }
}
