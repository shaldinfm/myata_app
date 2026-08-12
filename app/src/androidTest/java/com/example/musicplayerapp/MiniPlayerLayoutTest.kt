package com.example.musicplayerapp

import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The Mini Player's frozen geometry, measured rather than looked at.
 *
 * Two things this has to prove, and neither is visible in a screenshot:
 *
 *   1. **The 18px box is not reproduced.** The canonical page puts the title in a
 *      233x18 container with clipsContent on, against a 27.5 line height, so the
 *      glyphs are cut - a legacy finding, not part of the 3.6.6 design. Here the
 *      title box must be tall enough for the whole line, and no glyph may ink
 *      above it.
 *
 *   2. **Fixing that changed nothing outside the pill.** Figma's own frame hugs
 *      its content and the artwork is the tallest child at 48, so a title that
 *      grows from 18 to 27.5 takes the text column to 47.5 and leaves the pill at
 *      74. The assertion is the outer 74, at every shipping width, in both themes.
 *
 * The strings are the worst case the typography audit flagged, so "no clipping,
 * no wrapping" is asserted against real long Russian metadata rather than against
 * the design's short English sample.
 */
@RunWith(AndroidJUnit4::class)
class MiniPlayerLayoutTest {

    private val widthsDp = listOf(320, 360, 390, 412)

    /** The width the frozen design is drawn at; the only one with fixed anchors. */
    private val designWidthDp = 390

    private val longTitle = "КРАСНОЗНАМЁННАЯ ДИВИЗИЯ ИМЕНИ МОЕЙ БАБУШКИ"
    private val longArtist = "MIAMI HORROR FT. POOLSIDE И ЕЩЁ НЕСКОЛЬКО ИСПОЛНИТЕЛЕЙ"

    private val findings = mutableListOf<String>()
    private val log = mutableListOf<String>()

    @Test
    fun frozenGeometrySurvivesEveryWidthAndBothThemes() {
        // Both themes come from a Configuration overlay rather than a system
        // setting, so they resolve their real night resources on every API level -
        // including 24, where `cmd uimode` does not exist and the shell cannot
        // switch the theme at all.
        onMainActivity { activity ->
            for (night in listOf(false, true)) {
                sweep(inflaterFor(activity, night), if (night) "dark" else "light")
            }
        }

        android.util.Log.i("MINIQA", "==== MINI PLAYER (API ${Build.VERSION.SDK_INT}) ====")
        log.forEach { android.util.Log.i("MINIQA", "  $it") }
        findings.forEach { android.util.Log.e("MINIQA", "  FINDING $it") }

        assertTrue(
            "mini player findings on API ${Build.VERSION.SDK_INT}:\n" +
                findings.joinToString("\n") { "  $it" },
            findings.isEmpty(),
        )
    }

    private fun sweep(inflater: LayoutInflater, theme: String) {
        val dm = inflater.context.resources.displayMetrics
        val dp = { v: Number -> v.toFloat() * dm.density }

        for (widthDp in widthsDp) {
            val widthPx = dp(widthDp).roundToInt()
            val shell = measuredShell(inflater, widthPx)
            val pill = shell.findViewById<ViewGroup>(R.id.mini_player)
            val artwork = shell.findViewById<ImageView>(R.id.mini_player_artwork)
            val title = shell.findViewById<TextView>(R.id.mini_player_title)
            val artist = shell.findViewById<TextView>(R.id.mini_player_artist)
            val button = shell.findViewById<ImageView>(R.id.mini_player_play_pause)
            val where = "$theme@${widthDp}dp"

            /* ---- the outer pill: 74 tall, 16 from each side, above the bar ---- */

            expect(where, "pill height", pill.height, dp(74))
            expect(where, "pill width", pill.width, dp(widthDp - 32))
            expect(where, "pill left margin", pill.left, dp(16))

            val bar = shell.findViewById<View>(R.id.bottomNavView)
            expect(where, "gap above the bottom nav", bar.top - pill.bottom, dp(4))

            /* ---- the row: artwork 48, button 27, both fixed at every width ---- */

            expect(where, "artwork size", artwork.width, dp(48))
            expect(where, "artwork height", artwork.height, dp(48))
            expect(where, "button width", button.width, dp(27))

            // Canonical anchors, which only hold at the width they were drawn at.
            // Measured in pill space: the text column is a child of a child, so
            // its own `left` is 0 and says nothing.
            if (widthDp == designWidthDp) {
                expect(where, "artwork x", leftInPill(artwork), dp(13))
                expect(where, "title x", leftInPill(title), dp(73))
                expect(where, "text column width", title.width, dp(233))
                expect(where, "button x", leftInPill(button), dp(318))
                expect(where, "trailing edge", pill.width - leftInPill(button) - button.width, dp(13))
            }

            /* ---- the title box is big enough for 27.5, and nothing is cut ---- */

            val lineHeightPx = title.paint.fontMetricsInt.let { it.descent - it.ascent } +
                title.lineSpacingExtra.roundToInt()
            if (title.height < dp(27.5) - 1f) {
                findings += "$where: title box ${title.height}px is under the 27.5 line " +
                    "(${dp(27.5).roundToInt()}px) - the 18px canonical bug"
            }
            if (title.height < lineHeightPx) {
                findings += "$where: title box ${title.height}px cannot hold its own " +
                    "${lineHeightPx}px line"
            }
            noClipping(title, "$where/title")
            noClipping(artist, "$where/artist")

            /* ---- long Russian metadata: one line each, ellipsis not wrap ---- */

            requireOneLine(title, "$where/title")
            requireOneLine(artist, "$where/artist")
            noOverlap(where, "artwork", artwork, "title", title)
            noOverlap(where, "title", title, "button", button)
            noOverlap(where, "artist", artist, "button", button)
            if (title.bottom > artist.top) {
                findings += "$where: title overlaps artist vertically"
            }

            log += "$where: pill ${pill.width}x${pill.height}, artwork@${leftInPill(artwork)}, " +
                "title@${leftInPill(title)} ${title.width}x${title.height} ${title.lineCount}L, " +
                "artist ${artist.height}px ${artist.lineCount}L, " +
                "button@${leftInPill(button)} ${button.width}px"
        }
    }

    /**
     * The glyph sizes are the icons' whole provenance - 11x14 for play and 12x14
     * for pause is what identifies them as Material's `play_arrow` and `pause` -
     * so a redraw that changed them would be a silent substitution.
     */
    @Test
    fun playAndPauseGlyphsKeepTheirCanonicalSize() {
        val ctx = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()
        val density = ctx.resources.displayMetrics.density
        val dp = { v: Number -> v.toFloat() * density }

        val play = androidx.core.content.ContextCompat
            .getDrawable(ctx, R.drawable.ic_mini_player_play)!!
        val pause = androidx.core.content.ContextCompat
            .getDrawable(ctx, R.drawable.ic_mini_player_pause)!!

        expect("icons", "play width", play.intrinsicWidth, dp(11))
        expect("icons", "play height", play.intrinsicHeight, dp(14))
        expect("icons", "pause width", pause.intrinsicWidth, dp(12))
        expect("icons", "pause height", pause.intrinsicHeight, dp(14))

        assertTrue(findings.joinToString("\n"), findings.isEmpty())
    }

    /**
     * The button follows the player, not a flag of its own.
     *
     * Driven through `StreamsViewModel.isPlaying` - the LiveData the MediaController
     * listener posts to - rather than by tapping, because a tap only proves the
     * icon changes if the stream actually connects, and an emulator that cannot
     * reach the stream host would make this untestable. Setting the same LiveData
     * the service sets is the state the pill really consumes.
     */
    @Test
    fun theButtonFollowsThePlayersState() {
        onMainActivity { activity ->
            val button = activity.findViewById<ImageView>(R.id.mini_player_play_pause)
            val play = activity.getString(R.string.mini_player_play)
            val pause = activity.getString(R.string.mini_player_pause)

            activity.viewModel.isPlaying.value = false
            expectDesc("idle", button, play)
            activity.viewModel.isPlaying.value = true
            expectDesc("playing", button, pause)
            activity.viewModel.isPlaying.value = false
            expectDesc("paused", button, play)
        }
        assertTrue(findings.joinToString("\n"), findings.isEmpty())
    }

    /**
     * Only the screens the frozen design draws it on.
     *
     * PLAYER is the one that matters: it is the single canonical screen with no
     * mini player, because it already shows all of this full size.
     */
    @Test
    fun thePillAppearsOnlyOnTheScreensTheDesignDrawsItOn() {
        onMainActivity { activity ->
            val pill = activity.findViewById<View>(R.id.mini_player)
            for ((screen, visible) in listOf(
                "main" to true,
                "player" to false,
                "favorites" to true,
                "info" to true,
                "donate" to false,
            )) {
                activity.viewModel.isInSplitMode.value = false
                activity.viewModel.currentFragmentLiveData.value = screen
                val shown = pill.visibility == View.VISIBLE
                if (shown != visible) {
                    findings += "on \"$screen\" the pill is ${if (shown) "shown" else "hidden"}, " +
                        "the frozen design says ${if (visible) "shown" else "hidden"}"
                }
            }

            // Split screen hides the bottom nav today, and the pill floats on it.
            activity.viewModel.currentFragmentLiveData.value = "main"
            activity.viewModel.isInSplitMode.value = true
            if (pill.visibility == View.VISIBLE) {
                findings += "the pill stays up in split screen, where the bar it floats on is gone"
            }
            activity.viewModel.isInSplitMode.value = false
        }
        assertTrue(findings.joinToString("\n"), findings.isEmpty())
    }

    /* ---------------------------------------------------------------- infra -- */

    private fun expectDesc(state: String, v: View, want: String) {
        if (v.contentDescription?.toString() != want) {
            findings += "$state: button says \"${v.contentDescription}\", expected \"$want\""
        }
    }

    private fun onMainActivity(block: (MainActivity) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).let { scenario ->
            try {
                scenario.onActivity(block)
            } finally {
                try { scenario.close() } catch (e: Throwable) {
                    android.util.Log.w("MINIQA", "activity close timed out; checks already complete", e)
                }
            }
        }
    }

    /**
     * The shell, measured at [widthPx] with the pill forced visible.
     *
     * The real layout rather than `view_mini_player` on its own, because the two
     * 16dp margins and the 4dp above the bar are constraints on the include, not
     * properties of the pill. Visibility is production state, driven by
     * MiniPlayer from the current destination; here it is set directly so the
     * geometry can be measured without navigating.
     */
    private fun measuredShell(inflater: LayoutInflater, widthPx: Int): ViewGroup {
        val root = inflater.inflate(R.layout.activity_main, null) as ViewGroup
        root.findViewById<View>(R.id.mini_player).visibility = View.VISIBLE
        root.findViewById<TextView>(R.id.mini_player_title).text = longTitle
        root.findViewById<TextView>(R.id.mini_player_artist).text = longArtist
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

    /** Tolerance is 1px: the frozen numbers include a 13 that is 1 stroke + 12. */
    private fun expect(where: String, what: String, actual: Int, expected: Float) {
        if (abs(actual - expected) > 1f) {
            findings += "$where: $what is ${actual}px, frozen design says ${expected.roundToInt()}px"
        }
    }

    /** A glyph inking above its own line box is what the 18px container caused. */
    private fun noClipping(tv: TextView, where: String) {
        val layout = tv.layout ?: return
        val line = tv.text.subSequence(layout.getLineStart(0), layout.getLineEnd(0)).toString()
        if (line.isBlank()) return
        val ink = Rect()
        tv.paint.getTextBounds(line, 0, line.length, ink)
        val headroom = (layout.getLineBaseline(0) - layout.getLineTop(0)) - (-ink.top)
        if (headroom < 0) findings += "$where: ascenders clipped by ${-headroom}px"
        if (layout.getLineBottom(layout.lineCount - 1) > tv.height) {
            findings += "$where: last line runs ${layout.getLineBottom(layout.lineCount - 1) - tv.height}px " +
                "past the bottom of its box"
        }
    }

    private fun requireOneLine(tv: TextView, where: String) {
        if (tv.lineCount != 1) {
            findings += "$where: wrapped to ${tv.lineCount} lines; the pill is a fixed-height row"
        }
    }

    private fun noOverlap(where: String, an: String, a: View, bn: String, b: View) {
        val ra = Rect(a.left, a.top, a.right, a.bottom)
        val rb = Rect(b.left, b.top, b.right, b.bottom)
        // Siblings of different parents inside the pill: compare in pill space.
        offsetToPill(a, ra)
        offsetToPill(b, rb)
        if (Rect.intersects(ra, rb)) findings += "$where: $an overlaps $bn ($ra vs $rb)"
    }

    private fun offsetToPill(v: View, r: Rect) {
        var p = v.parent
        while (p is View && p.id != R.id.mini_player) {
            r.offset(p.left, p.top)
            p = p.parent
        }
    }

    /** [v]'s left edge in the pill's coordinates, which is what the frozen x values are. */
    private fun leftInPill(v: View): Int {
        val r = Rect(v.left, v.top, v.right, v.bottom)
        offsetToPill(v, r)
        return r.left
    }
}
