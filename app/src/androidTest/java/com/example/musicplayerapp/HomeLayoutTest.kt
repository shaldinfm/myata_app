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
 * **Those still hold, and where they hold is now part of the assertion.** HOME is
 * responsive on both axes: the frozen design is what a device at or above the
 * size it was drawn for gets, and smaller ones get a documented reduction of it.
 * So the sweep runs a matrix of viewports rather than a list of widths, and each
 * cell is checked against the geometry its own qualifiers select:
 *
 *   width  >= 390dp   frozen card 316x198        360..389dp  286x179 (w360 bucket)
 *                                                below 360   246x154 (values/)
 *   height >= 757dp   frozen gaps 16, row 197    below it     gaps 12, row 176
 *
 * **The width rule is a step function, and it is modelled as one.** The card is
 * not `screenWidth - 74`: Android has no "narrower than" qualifier, so a width is
 * answered by the narrowest bucket that still fits it and the card is one value
 * per bucket - 316 at 390 and up, 286 from 360, 246 below that. 375dp and 384dp
 * are in the matrix precisely because they sit *inside* the 360 bucket: a
 * continuous rule would hand them 301 and 310, the resources give them 286, and
 * the width the bucket does not spend on the card shows up as a wider peek (58
 * and 67, against the frozen 43). Each cell asserts the resolved dimen against
 * the bucket as well as the card against the bucket, so the model here cannot
 * drift away from the resource table. See [cardFor].
 *
 * The point of the matrix is that the canonical cells must come out byte for byte
 * identical to what the frozen frame says - a responsive rule that moves the
 * design on a Pixel is a bug, not a feature - while the compact cells must be
 * *proportionally* the same object rather than the same absolute one.
 *
 * Two things are asserted at every cell because they are what the reduction must
 * not buy its space from: the 154dp bottom clearance, and the card/target sizes.
 *
 * The bottom clearance is the other half. It is not a canonical anchor; it is
 * derived from the chrome that floats over HOME (navigation bar 76, gap 4, mini
 * player 74) and it is what stops the last playlist ending up under the pill on a
 * screen that scrolls.
 */
@RunWith(AndroidJUnit4::class)
class HomeLayoutTest {

    /** The frozen design's own size: at or above this, nothing may move. */
    private val designWidthDp = 390
    private val designHeightDp = 757

    /**
     * The shipping matrix. 320 and 360 are the two compact widths the app ships
     * into, 390 is the design, 412 a modern Pixel; the heights are the four
     * profiles the responsive audit measured plus 731, which is the API 24 QA
     * panel and the case that used to hide content behind the navigation bar.
     *
     * 375 and 384 are here because they are the widths *inside* the
     * `values-w360dp` bucket, where the card is 286 rather than the 301 and 310 a
     * continuous `screenWidth - 74` rule would give them. They are the cells that
     * would catch the formula creeping back in - see [cardFor].
     */
    private val viewports = listOf(
        360 to 640, 360 to 720, 360 to 915,
        320 to 640, 320 to 851,
        375 to 812, 384 to 854,
        390 to 757, 390 to 851,
        393 to 851, 411 to 731, 412 to 915,
    )

    /**
     * The stream card a width selects, by bucket - the rule Android can express.
     *
     * `values-w390dp` holds 316x198, `values-w360dp` holds 286x179, and
     * `values/dimens.xml` holds 246x154 for every width under 360. A qualifier
     * answers "at least this wide", so the widest rule that still fits is the one
     * that applies and a width in the middle of a bucket gets that bucket's card:
     * 375 and 384 get 286, not 301 and 310. The sweep checks the resolved dimen
     * against this table too, so the two cannot disagree silently.
     */
    private val cardBuckets = listOf(
        designWidthDp to StreamCardBucket(316, 198),
        360 to StreamCardBucket(286, 179),
        0 to StreamCardBucket(246, 154),
    )

    private fun cardFor(widthDp: Int): StreamCardBucket =
        cardBuckets.first { widthDp >= it.first }.second

    /** The card one resource bucket holds, in dp. */
    private data class StreamCardBucket(val widthDp: Int, val heightDp: Int)

    private val findings = mutableListOf<String>()
    private val log = mutableListOf<String>()

    @Test
    fun homeReproducesTheFrozenAnchors() {
        onMainActivity { activity ->
            for (night in listOf(false, true)) {
                val theme = if (night) "dark" else "light"
                for ((w, h) in viewports) sweep(activity, night, theme, w, h)
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

    private fun sweep(
        activity: MainActivity,
        night: Boolean,
        theme: String,
        widthDp: Int,
        heightDp: Int,
    ) {
        val inflater = inflaterFor(activity, night, widthDp, heightDp)
        val res = inflater.context.resources
        val dm = res.displayMetrics
        val dp = { v: Number -> v.toFloat() * dm.density }

        // What this viewport's qualifiers select. These are the rules under test,
        // written out here rather than read back from the resources, so that a
        // dimens edit that changes them fails instead of redefining the target -
        // and the card half of it is a bucket lookup rather than a formula,
        // because no qualifier can express `screenWidth - 74`. See cardFor.
        val canonicalHeight = heightDp >= designHeightDp
        val card = cardFor(widthDp)
        val cardW = card.widthDp
        val cardH = card.heightDp
        val gap = if (canonicalHeight) 16 else 12
        val playlistRow = if (canonicalHeight) 197 else 176

        run {
            val widthPx = dp(widthDp).roundToInt()
            val heightPx = dp(heightDp).roundToInt()
            val root = measured(inflater, widthPx, heightPx)
            val where = "$theme@${widthDp}x${heightDp}dp"

            val greeting = root.findViewById<TextView>(R.id.home_greeting)
            // The band is the header row, not the greeting. Until G-A3 the greeting
            // *was* the band - it was the only thing in it - and then the 40x40
            // profile control landed on its trailing edge, so the greeting became
            // wrap_content inside a 64dp row. The band's height is unchanged and so
            // is the greeting's baseline, which is why "Main starts under the
            // header" below still reads 64 and nothing under it moved.
            val band = root.findViewById<View>(R.id.home_header)
            val scroll = root.findViewById<View>(R.id.home_scroll)
            val streams = root.findViewById<View>(R.id.streams)
            val playlistHeading = root.findViewById<TextView>(R.id.playlistString)
            val playlists = root.findViewById<View>(R.id.playlists)
            val streamsHeading = firstHeadingAbove(streams)

            /* ---- the header band, and Main starting under it ---- */

            expect(where, "header band height", band.height, dp(64))
            expect(where, "Main starts under the header", topInRoot(scroll), dp(64))

            /* ---- the frozen vertical anchors, at the size they were drawn ---- */

            // At the design's own size every canonical anchor must land exactly.
            // This is the cell that proves the responsive rules did not move the
            // design; the compact cells below only get the relationships.
            if (widthDp >= designWidthDp && canonicalHeight) {
                expect(where, "Наши потоки y", topInRoot(streamsHeading), dp(80))
                expect(where, "streams row y", topInRoot(streams), dp(132))
                expect(where, "streams row height", streams.height, dp(215))
                expect(where, "playlists row height", playlists.height, dp(197))
            }
            if (widthDp == designWidthDp && canonicalHeight) {
                expect(where, "Мятные плейлисты y", topInRoot(playlistHeading), dp(363))
                expect(where, "playlists row y", topInRoot(playlists), dp(415))
            }

            /* ---- the rules, at every cell ---- */

            // The streams row is the card plus the shadow slack, which is 17 at
            // every size - it is sized by where the shadow's tail has faded, not
            // by the card - so this also pins that the slack was not spent.
            expect(where, "streams row height", streams.height, dp(cardH + 17))
            expect(where, "playlists row height", playlists.height, dp(playlistRow))

            // `Мятные плейлисты` is the longest heading in the app and wraps to two
            // lines below 360dp - recorded by TypographyWidthSweepTest as expected,
            // not a regression. A wrapped heading is taller, so everything under it
            // moves down; the frozen y therefore only holds at the width the design
            // is drawn at. What has to hold everywhere is that the row follows its
            // heading by exactly one section gap and keeps its own height.
            val headingGap = topInRoot(playlists) - (topInRoot(playlistHeading) + playlistHeading.height)
            expect(where, "gap under Мятные плейлисты", headingGap, dp(gap))
            expect(where, "gap above the streams row",
                topInRoot(streams) - (topInRoot(streamsHeading) + streamsHeading.height), dp(gap))
            expect(where, "Main's top padding", scrollContent(root).paddingTop, dp(gap))

            /* ---- the card scales, and keeps the frozen aspect and composition ---- */

            val cards = listOf(R.id.myata_stream_banner_container, R.id.gold_stream_banner_container,
                R.id.xtra_stream_banner_container).map { root.findViewById<View>(it) }
            cards.forEachIndexed { i, c ->
                expect(where, "stream card $i width", c.width, dp(cardW))
                expect(where, "stream card $i height", c.height, dp(cardH))
            }
            // And the bucket the model assumed is the bucket the resource table
            // really resolved under this configuration. Without this the sweep
            // could agree with itself: a card that fell into a different bucket
            // than cardFor predicts already fails on its size, but a dimens edit
            // that moved a bucket *and* this file's table together would pass -
            // and the table, not the resource, is the hand-written half.
            expect(where, "declared card width",
                res.getDimensionPixelSize(R.dimen.home_stream_card_width), dp(cardW))
            expect(where, "declared card height",
                res.getDimensionPixelSize(R.dimen.home_stream_card_height), dp(cardH))

            // The composition the width is spent on: a 16 lead and a 15 gap, both
            // constants at every width, and then whatever the viewport has left
            // over for the next card to peek by. The peek is the only part of the
            // three that tracks the screen width continuously, so it is asserted
            // as the remainder rather than as the frozen 43: 43 is what that
            // remainder comes to at each bucket's own first width, while 375 and
            // 384 - inside the 360 bucket - get 58 and 67.
            expect(where, "first stream card x", leftInRoot(cards[0]), dp(16))
            expect(where, "stream card gap", leftInRoot(cards[1]) - (leftInRoot(cards[0]) + cards[0].width), dp(15))
            expect(where, "next card peek",
                widthPx - leftInRoot(cards[1]), dp(widthDp - 31 - cardW))
            expect(where, "playlists row leading inset", playlists.paddingStart, dp(16))

            // The playlist card never scales - only the row's dead space does - so
            // the row must always still clear it.
            if (playlists.height < dp(160)) {
                findings += "$where: playlists row ${playlists.height}px is shorter than the 160dp card"
            }

            /* ---- clearance: nav 76 + gap 4 + pill 74, at every cell ---- */

            expect(where, "bottom clearance", scroll.paddingBottom, dp(154))

            /* ---- nothing clipped, nothing overlapping ---- */

            for (tv in listOf(greeting, streamsHeading, playlistHeading)) noClipping(tv, "$where/${nameOf(tv)}")
            noOverlap(where, "streams row", streams, "Мятные плейлисты", playlistHeading)
            noOverlap(where, "Мятные плейлисты", playlistHeading, "playlists row", playlists)

            log += "$where: streams@${topInRoot(streams)} ${streams.height} " +
                "card ${cards[0].width}x${cards[0].height}, " +
                "плейлисты@${topInRoot(playlistHeading)} ${playlistHeading.lineCount}L, " +
                "playlists@${topInRoot(playlists)} ${playlists.height}, " +
                "gap=${headingGap}, clearance=${scroll.paddingBottom}"
        }
    }

    /** The `Main` column inside the scroll view - the view that carries the top padding. */
    private fun scrollContent(root: ViewGroup): View =
        (root.findViewById<ViewGroup>(R.id.home_scroll)).getChildAt(0)

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
            // 320x851: the narrowest width, at a height that still selects the
            // frozen 197 row, so the number this pins is the canonical one and a
            // failure means the wrap ate the row rather than that the height
            // qualifier trimmed it.
            val inflater = inflaterFor(activity, false, 320, 851)
            val dm = inflater.context.resources.displayMetrics
            val widthPx = (320 * dm.density).roundToInt()
            val heightPx = (851 * dm.density).roundToInt()
            val root = measured(inflater, widthPx, heightPx) { r ->
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

    /**
     * The reduction must never be paid for out of the things it is not allowed to
     * touch, and the promo slot's future home must stay collapsible.
     *
     * Three invariants, checked across the whole matrix in one place because each
     * is a *comparison between* cells rather than a fact about one of them:
     *
     *  - the 154dp clearance is identical everywhere, so the last playlist clears
     *    the navigation bar and the mini player on a compact screen exactly as it
     *    does on a tall one;
     *  - the playlist card and the profile control are the same size at every
     *    viewport - no touch target shrinks to buy height;
     *  - `playlist_state` and the row it stands in for occupy the same band, so
     *    nothing below them moves when the load lands. That band is what future
     *    promo content sits under, and it has to be stable before anything is put
     *    there.
     */
    @Test
    fun theReductionNeverComesOutOfTargetsOrClearance() {
        onMainActivity { activity ->
            val sizes = mutableSetOf<String>()
            for ((w, h) in viewports) {
                val inflater = inflaterFor(activity, false, w, h)
                val dm = inflater.context.resources.displayMetrics
                val dp = { v: Number -> v.toFloat() * dm.density }
                val root = measured(inflater, dp(w).roundToInt(), dp(h).roundToInt())
                val where = "${w}x${h}dp"

                val scroll = root.findViewById<View>(R.id.home_scroll)
                val playlists = root.findViewById<View>(R.id.playlists)
                val profile = root.findViewById<View>(R.id.profile_entry)

                expect(where, "bottom clearance", scroll.paddingBottom, dp(154))
                expect(where, "profile control", profile.height, dp(40))

                // The same band whichever of the two is showing. `playlist_state`
                // is GONE at rest, so it has to be measured in its own pass with
                // the row swapped out - which is exactly the swap the loaded state
                // performs, and the thing that must move nothing.
                val loading = measured(inflater, dp(w).roundToInt(), dp(h).roundToInt()) { r ->
                    r.findViewById<View>(R.id.playlists).visibility = View.GONE
                    r.findViewById<View>(R.id.playlist_state).visibility = View.VISIBLE
                }
                val state = loading.findViewById<View>(R.id.playlist_state)
                expect(where, "playlist_state height", state.height, playlists.height.toFloat())
                expect(where, "playlist_state y", topInRoot(state), topInRoot(playlists).toFloat())

                sizes += "profile=${profile.height} clearance=${scroll.paddingBottom}"
                log += "$where: state ${state.height}px @${topInRoot(state)} vs " +
                    "row ${playlists.height}px @${topInRoot(playlists)}"
            }
            if (sizes.size != 1) {
                findings += "a touch target or the clearance changed with the viewport: $sizes"
            }
        }
        assertTrue(findings.joinToString("\n"), findings.isEmpty())
    }

    /* ---------------------------------------------------------------- infra -- */

    private fun measured(
        inflater: LayoutInflater,
        widthPx: Int,
        heightPx: Int,
        prepare: ((ViewGroup) -> Unit)? = null,
    ): ViewGroup {
        val root = inflater.inflate(R.layout.fragment_main, null) as ViewGroup
        prepare?.invoke(root)
        // A real screen height: HOME is a scrolling screen now, and measuring it
        // UNSPECIFIED would hand the scroll view an unbounded viewport and make
        // every number below the fold fiction.
        root.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        return root
    }

    /**
     * An inflater whose *resources* believe they are on a [widthDp] x [heightDp]
     * screen, not just one measured at that size.
     *
     * This is the difference between sweeping widths and testing the responsive
     * rules. Measuring a view at 360dp while the context still reports the
     * device's 411 resolves every qualified dimen from the device's bucket, so a
     * `values-w360dp` value would never be read and the sweep would pass whatever
     * the qualifiers did. Overriding screenWidthDp/screenHeightDp on the
     * configuration is what makes `createConfigurationContext` select the bucket
     * under test - and `smallestScreenWidthDp` with it, since the shell carries a
     * `layout-sw320dp`.
     */
    private fun inflaterFor(
        activity: MainActivity,
        night: Boolean,
        widthDp: Int,
        heightDp: Int,
    ): LayoutInflater {
        val cfg = Configuration(activity.resources.configuration)
        cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        cfg.screenWidthDp = widthDp
        cfg.screenHeightDp = heightDp
        cfg.smallestScreenWidthDp = minOf(widthDp, heightDp)
        cfg.orientation =
            if (widthDp > heightDp) Configuration.ORIENTATION_LANDSCAPE
            else Configuration.ORIENTATION_PORTRAIT
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
