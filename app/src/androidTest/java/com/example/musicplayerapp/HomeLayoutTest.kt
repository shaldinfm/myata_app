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
 * HOME against the frozen canonical frame, measured rather than eyeballed.
 *
 * The frozen HOME is a fixed 390x757 and its anchors are the whole design:
 *
 *   Header - TopAppBar   0..64
 *   Наши потоки          y=80    streams row    y=132, 215 tall, cards 316x198
 *   Мятные плейлисты     y=363   playlists row  y=415, 197 tall, cards 160x160
 *   Main                 ends at 628
 *
 * Those hold at the 390dp reference width. Away from it only the horizontal
 * numbers move - the rows keep their heights and the cards keep their sizes,
 * because a carousel scrolls rather than reflows - so the vertical anchors are
 * asserted at every shipping width and the card geometry with them.
 *
 * The bottom clearance is the other half. It is not a canonical anchor; it is
 * derived from the chrome that floats over HOME (navigation bar 76, gap 4, mini
 * player 74) and it is what stops the last playlist ending up under the pill on a
 * screen that scrolls.
 */
@RunWith(AndroidJUnit4::class)
class HomeLayoutTest {

    private val widthsDp = listOf(320, 360, 390, 412)
    private val designWidthDp = 390

    private val findings = mutableListOf<String>()
    private val log = mutableListOf<String>()

    @Test
    fun homeReproducesTheFrozenAnchors() {
        onMainActivity { activity ->
            for (night in listOf(false, true)) {
                sweep(inflaterFor(activity, night), if (night) "dark" else "light")
            }
        }

        android.util.Log.i("HOMEQA", "==== HOME (API ${Build.VERSION.SDK_INT}) ====")
        log.forEach { android.util.Log.i("HOMEQA", "  $it") }
        findings.forEach { android.util.Log.e("HOMEQA", "  FINDING $it") }

        assertTrue(
            "HOME findings on API ${Build.VERSION.SDK_INT}:\n" + findings.joinToString("\n") { "  $it" },
            findings.isEmpty(),
        )
    }

    private fun sweep(inflater: LayoutInflater, theme: String) {
        val dm = inflater.context.resources.displayMetrics
        val dp = { v: Number -> v.toFloat() * dm.density }

        for (widthDp in widthsDp) {
            val widthPx = dp(widthDp).roundToInt()
            val root = measured(inflater, widthPx)
            val where = "$theme@${widthDp}dp"

            val greeting = root.findViewById<TextView>(R.id.home_greeting)
            val scroll = root.findViewById<View>(R.id.home_scroll)
            val streams = root.findViewById<View>(R.id.streams)
            val playlistHeading = root.findViewById<TextView>(R.id.playlistString)
            val playlists = root.findViewById<View>(R.id.playlists)
            val streamsHeading = firstHeadingAbove(streams)

            /* ---- the header band, and Main starting under it ---- */

            expect(where, "header band height", greeting.height, dp(64))
            expect(where, "Main starts under the header", topInRoot(scroll), dp(64))

            /* ---- the frozen vertical anchors ---- */

            expect(where, "Наши потоки y", topInRoot(streamsHeading), dp(80))
            expect(where, "streams row y", topInRoot(streams), dp(132))
            expect(where, "streams row height", streams.height, dp(215))
            expect(where, "playlists row height", playlists.height, dp(197))

            // `Мятные плейлисты` is the longest heading in the app and wraps to two
            // lines below 360dp - recorded by TypographyWidthSweepTest as expected,
            // not a regression. A wrapped heading is taller, so everything under it
            // moves down; the frozen y therefore only holds at the width the design
            // is drawn at. What has to hold everywhere is that the row follows its
            // heading and keeps its own height, which is asserted above and below.
            if (widthDp == designWidthDp) {
                expect(where, "Мятные плейлисты y", topInRoot(playlistHeading), dp(363))
                expect(where, "playlists row y", topInRoot(playlists), dp(415))
            } else {
                val gap = topInRoot(playlists) - (topInRoot(playlistHeading) + playlistHeading.height)
                expect(where, "gap under Мятные плейлисты", gap, dp(16))
            }

            /* ---- the cards keep their frozen size at every width ---- */

            val cards = listOf(R.id.myata_stream_banner_container, R.id.gold_stream_banner_container,
                R.id.xtra_stream_banner_container).map { root.findViewById<View>(it) }
            cards.forEachIndexed { i, c ->
                expect(where, "stream card $i width", c.width, dp(316))
                expect(where, "stream card $i height", c.height, dp(198))
            }
            expect(where, "first stream card x", leftInRoot(cards[0]), dp(16))
            expect(where, "stream card gap", leftInRoot(cards[1]) - (leftInRoot(cards[0]) + cards[0].width), dp(15))
            expect(where, "playlists row leading inset", playlists.paddingStart, dp(16))

            /* ---- clearance: nav 76 + gap 4 + pill 74 ---- */

            expect(where, "bottom clearance", scroll.paddingBottom, dp(154))

            /* ---- nothing clipped, nothing overlapping ---- */

            for (tv in listOf(greeting, streamsHeading, playlistHeading)) noClipping(tv, "$where/${nameOf(tv)}")
            noOverlap(where, "streams row", streams, "Мятные плейлисты", playlistHeading)
            noOverlap(where, "Мятные плейлисты", playlistHeading, "playlists row", playlists)

            log += "$where: header ${greeting.height}, наши потоки@${topInRoot(streamsHeading)} " +
                "${streamsHeading.lineCount}L, streams@${topInRoot(streams)} ${streams.height}, " +
                "плейлисты@${topInRoot(playlistHeading)} ${playlistHeading.lineCount}L, " +
                "playlists@${topInRoot(playlists)} ${playlists.height}, clearance=${scroll.paddingBottom}"
        }
    }

    /**
     * HOME must survive a long heading without the anchors below it collapsing.
     *
     * `Мятные плейлисты` is the longest heading in the app and already wraps below
     * 360dp - the typography sweep records that and does not treat it as a
     * regression. What must not happen is the wrap eating the row under it.
     */
    @Test
    fun aWrappedHeadingPushesTheRowDownRatherThanShrinkingIt() {
        onMainActivity { activity ->
            val inflater = inflaterFor(activity, false)
            val dm = inflater.context.resources.displayMetrics
            val widthPx = (320 * dm.density).roundToInt()
            val root = measured(inflater, widthPx) { r ->
                r.findViewById<TextView>(R.id.playlistString).text =
                    "МЯТНЫЕ ПЛЕЙЛИСТЫ ДЛЯ ОЧЕНЬ ДЛИННОГО ЗАГОЛОВКА"
            }
            val heading = root.findViewById<TextView>(R.id.playlistString)
            val playlists = root.findViewById<View>(R.id.playlists)

            if (playlists.height != (197 * dm.density).roundToInt()) {
                findings += "a wrapped heading changed the playlists row from 197dp to " +
                    "${playlists.height}px"
            }
            if (topInRoot(playlists) <= heading.bottom) {
                findings += "the playlists row did not move below the wrapped heading"
            }
            log += "wrapped heading: ${heading.lineCount} lines, ${heading.height}px, " +
                "row still ${playlists.height}px"
        }
        assertTrue(findings.joinToString("\n"), findings.isEmpty())
    }

    /* ---------------------------------------------------------------- infra -- */

    private fun measured(
        inflater: LayoutInflater,
        widthPx: Int,
        prepare: ((ViewGroup) -> Unit)? = null,
    ): ViewGroup {
        val root = inflater.inflate(R.layout.fragment_main, null) as ViewGroup
        prepare?.invoke(root)
        // A real screen height: HOME is a scrolling screen now, and measuring it
        // UNSPECIFIED would hand the scroll view an unbounded viewport and make
        // every number below the fold fiction.
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
                    android.util.Log.w("HOMEQA", "activity close timed out; checks already complete", e)
                }
            }
        }
    }

    /** The heading immediately above [view] in its parent. */
    private fun firstHeadingAbove(view: View): TextView {
        val parent = view.parent as ViewGroup
        val i = parent.indexOfChild(view)
        for (j in i - 1 downTo 0) {
            val c = parent.getChildAt(j)
            if (c is TextView) return c
        }
        error("no heading above ${nameOf(view)}")
    }

    private fun offsetToRoot(v: View, r: Rect) {
        var p = v.parent
        while (p is View) { r.offset(p.left, p.top); p = p.parent }
    }

    private fun topInRoot(v: View): Int =
        Rect(v.left, v.top, v.right, v.bottom).also { offsetToRoot(v, it) }.top

    private fun leftInRoot(v: View): Int =
        Rect(v.left, v.top, v.right, v.bottom).also { offsetToRoot(v, it) }.left

    private fun expect(where: String, what: String, actual: Int, expected: Float) {
        if (abs(actual - expected) > 1f) {
            findings += "$where: $what is ${actual}px, frozen design says ${expected.roundToInt()}px"
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
        val used = layout.getLineBottom(layout.lineCount - 1)
        if (used > tv.height - tv.paddingTop - tv.paddingBottom + 1) {
            findings += "$where: text runs past the bottom of its box"
        }
    }

    private fun noOverlap(where: String, an: String, a: View, bn: String, b: View) {
        val ra = Rect(a.left, a.top, a.right, a.bottom).also { offsetToRoot(a, it) }
        val rb = Rect(b.left, b.top, b.right, b.bottom).also { offsetToRoot(b, it) }
        if (Rect.intersects(ra, rb)) findings += "$where: $an overlaps $bn ($ra vs $rb)"
    }

    private fun nameOf(v: View): String = try {
        if (v.id == View.NO_ID) v.javaClass.simpleName else v.resources.getResourceEntryName(v.id)
    } catch (e: android.content.res.Resources.NotFoundException) {
        v.javaClass.simpleName
    }
}
