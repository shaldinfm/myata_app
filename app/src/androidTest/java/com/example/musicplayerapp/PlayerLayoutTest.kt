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

            // The trailing slot is reserved, not a control: it must take up the
            // frozen space and must not be clickable.
            expect(where, "reserved trailing slot width", reserved.width, dp(32))
            if (reserved.isClickable || reserved.hasOnClickListeners()) {
                findings += "$where: the reserved header slot is clickable - it has no action until D/E"
            }
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
            val history = page.findViewById<View>(R.id.btn_history)

            expect(where, "album art y", topIn(art, page), dp(16))
            expect(where, "album art size", art.width, dp(239))
            expect(where, "album art height", art.height, dp(239))
            expect(where, "album art centred", leftIn(art, page) + art.width / 2, dp(widthDp) / 2f)

            expect(where, "track title y", topIn(title, page), dp(292))
            expect(where, "artist y", topIn(artist, page), dp(324))

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
            expect(where, "history slot width", history.width, dp(49))
            expect(where, "history x", widthPx - leftIn(history, page) - history.width, dp(48))

            /* ---- long Russian metadata: one line each, nothing cut or piled up ---- */

            requireOneLine(title, "$where/title")
            requireOneLine(artist, "$where/artist")
            noClipping(title, "$where/title")
            noClipping(artist, "$where/artist")
            noOverlap(where, "album art", art, "title", title, page)
            noOverlap(where, "title", title, "artist", artist, page)
            noOverlap(where, "artist", artist, "play", play, page)
            noOverlap(where, "favourite", favorite, "play", play, page)
            noOverlap(where, "play", play, "history", history, page)

            log += "$where: header@${topIn(header, shell)} ${header.height}, swipe@${topIn(dots, shell)}, " +
                "page@${topIn(pager, shell)} | art@${topIn(art, page)} ${art.width}, " +
                "title@${topIn(title, page)} ${title.height} ${title.lineCount}L, " +
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

    private fun expect(where: String, what: String, actual: Int, expected: Float) {
        if (abs(actual - expected) > 1f) {
            findings += "$where: $what is ${actual}px, frozen design says ${expected.roundToInt()}px"
        }
    }

    private fun atLeast(where: String, what: String, actual: Int, floor: Float) {
        if (actual < floor - 1f) {
            findings += "$where: $what is ${actual}px, under the frozen ${floor.roundToInt()}px line"
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
