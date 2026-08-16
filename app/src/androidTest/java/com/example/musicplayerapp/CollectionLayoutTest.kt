package com.example.musicplayerapp

import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.card.MaterialCardView
import com.google.android.material.imageview.ShapeableImageView
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * COLLECTION against the frozen canonical frames, measured rather than eyeballed.
 *
 * All four frames - `COLLECTION`, `COLLECTION_dark`, `COLLECTION pusto`,
 * `COLLECTION pusto_dark` - are 390x740 with identical geometry, so the anchors
 * below are asserted in both themes off one layout:
 *
 *   Header - TopAppBar   0..64
 *   subtitle             y=80
 *   list / empty card    y=132
 *   Main bottom padding  154
 *
 * The vertical anchors hold at every shipping width: nothing on this screen
 * reflows horizontally except the text columns, which grow and shrink inside
 * fixed rows. The empty card's own anchors are asserted relative to the card,
 * for the same reason.
 *
 * The frozen 98dp row height IS asserted now. Phase B could not: the 98 is
 * 17 + a 64 cover + 17, and without a cover to draw there was nothing to hold
 * the row open. F3 draws it, so the height is a measurement again - see
 * docs/COLLECTION-3.6.6.md.
 */
@RunWith(AndroidJUnit4::class)
class CollectionLayoutTest {

    private val widthsDp = listOf(320, 360, 390, 412)

    private val findings = mutableListOf<String>()
    private val log = mutableListOf<String>()

    @Test
    fun collectionReproducesTheFrozenAnchors() {
        onMainActivity { activity ->
            for (night in listOf(false, true)) {
                val theme = if (night) "dark" else "light"
                val inflater = inflaterFor(activity, night)
                sweepPopulated(inflater, theme)
                sweepEmpty(inflater, theme)
                sweepRow(inflater, theme)
            }
            clearanceCoversTheChrome(inflaterFor(activity, false))
        }

        android.util.Log.i("COLLECTIONQA", "==== COLLECTION (API ${Build.VERSION.SDK_INT}) ====")
        log.forEach { android.util.Log.i("COLLECTIONQA", "  $it") }
        findings.forEach { android.util.Log.e("COLLECTIONQA", "  FINDING $it") }

        assertTrue(
            "COLLECTION findings on API ${Build.VERSION.SDK_INT}:\n" +
                findings.joinToString("\n") { "  $it" },
            findings.isEmpty(),
        )
    }

    /* ------------------------------------------------- the populated screen -- */

    private fun sweepPopulated(inflater: LayoutInflater, theme: String) {
        val ctx = inflater.context
        val dp = dpIn(ctx)
        for (widthDp in widthsDp) {
            val where = "$theme@${widthDp}dp/populated"
            val root = screen(inflater, widthDp)

            val header = root.findViewById<View>(R.id.collection_header)
            val title = root.findViewById<TextView>(R.id.title)
            val overflow = root.findViewById<View>(R.id.collection_overflow)
            val subtitle = root.findViewById<TextView>(R.id.collection_subtitle)
            val list = root.findViewById<View>(R.id.rv_favorites)

            expect(where, "header band height", header.height, dp(64))
            expect(where, "title leading inset", leftInRoot(title), dp(16))
            expect(where, "subtitle y", topInRoot(subtitle), dp(80))
            expect(where, "subtitle leading inset", leftInRoot(subtitle) + subtitle.paddingStart, dp(16))
            expect(where, "list y", topInRoot(list), dp(132))
            expect(where, "list leading inset", leftInRoot(list) + list.paddingStart, dp(16))
            expect(where, "bottom clearance", list.paddingBottom, dp(154))

            // The trailing action's 48dp box on the 16 margin, which puts the 4x16
            // dot column's centre at 350 against the frozen 352.
            expect(where, "overflow size", overflow.width, dp(48))
            expect(
                where, "overflow trailing edge",
                root.width - (leftInRoot(overflow) + overflow.width), dp(16),
            )

            expect(where, "title colour", title.currentTextColor, colour(ctx, R.color.text_heading))
            expect(where, "subtitle colour", subtitle.currentTextColor, colour(ctx, R.color.text_secondary))

            noClipping(title, "$where/title")
            noClipping(subtitle, "$where/subtitle")
            noOverlap(where, "header", header, "subtitle", subtitle)
            noOverlap(where, "subtitle", subtitle, "list", list)

            log += "$where: header ${header.height}, subtitle@${topInRoot(subtitle)} " +
                "${subtitle.lineCount}L, list@${topInRoot(list)}, clearance=${list.paddingBottom}"
        }
    }

    /* ----------------------------------------------------- the empty screen -- */

    private fun sweepEmpty(inflater: LayoutInflater, theme: String) {
        val ctx = inflater.context
        val dp = dpIn(ctx)
        for (widthDp in widthsDp) {
            val where = "$theme@${widthDp}dp/empty"
            val root = screen(inflater, widthDp) { r ->
                r.findViewById<View>(R.id.rv_favorites).visibility = View.GONE
                r.findViewById<View>(R.id.empty_state).visibility = View.VISIBLE
                r.findViewById<View>(R.id.collection_overflow).visibility = View.GONE
            }

            val scroll = root.findViewById<View>(R.id.empty_state)
            val card = root.findViewById<MaterialCardView>(R.id.empty_card)
            val illo = root.findViewById<View>(R.id.empty_illustration)
            val title = root.findViewById<TextView>(R.id.empty_title)
            val body = root.findViewById<TextView>(R.id.empty_body)

            expect(where, "empty card y", topInRoot(card), dp(132))
            expect(where, "empty card height", card.height, dp(359))
            expect(where, "empty card leading inset", leftInRoot(card), dp(16))
            expect(where, "bottom clearance", scroll.paddingBottom, dp(154))
            if (widthDp == 390) expect(where, "empty card width", card.width, dp(358))

            val cardTop = topInRoot(card)
            expect(where, "illustration top in card", topInRoot(illo) - cardTop, dp(55))
            expect(where, "illustration size", illo.width, dp(166))
            expect(where, "illustration centred", centreXInRoot(illo), centreXInRoot(card).toFloat())

            expect(where, "empty title box centre", centreYInRoot(title) - cardTop, dp(250))
            expect(where, "empty body box centre", centreYInRoot(body) - cardTop, dp(284))
            // Two lines, on the frozen break, centred in a box inset 16 from
            // each edge of the card. NOT the frozen 191 width: that is a Muller
            // measurement and Onest sets the string wider - see the layout.
            expect(where, "empty body lines", body.lineCount, 2f)
            expect(where, "empty body box", body.width, card.width - 2 * dp(16))
            val widest = (0 until body.lineCount).maxOf { body.layout.getLineWidth(it) }
            if (widest > body.width) {
                findings += "$where: the empty body's text is ${widest.roundToInt()} in a " +
                    "${body.width} box"
            }

            expect(where, "empty title colour", title.currentTextColor, colour(ctx, R.color.text_primary))
            expect(where, "empty body colour", body.currentTextColor, colour(ctx, R.color.text_secondary))

            cardIsTheFrozenSurface(ctx, where, "empty card", card)
            noClipping(title, "$where/title")
            noClipping(body, "$where/body")
            noOverlap(where, "illustration", illo, "title", title)
            noOverlap(where, "title", title, "body", body)

            log += "$where: card@${topInRoot(card)} ${card.width}x${card.height}, " +
                "illo@${topInRoot(illo) - cardTop}, title centre ${centreYInRoot(title) - cardTop}, " +
                "body centre ${centreYInRoot(body) - cardTop} ${body.lineCount}L"
        }
    }

    /* -------------------------------------------------------------- the row -- */

    private fun sweepRow(inflater: LayoutInflater, theme: String) {
        val ctx = inflater.context
        val dp = dpIn(ctx)
        for (widthDp in widthsDp) {
            val where = "$theme@${widthDp}dp/row"
            // The row lives inside the list's 16dp side padding.
            val rowWidthPx = (dp(widthDp) - 2 * dp(16)).roundToInt()
            val card = row(inflater, rowWidthPx)
            val content = card.getChildAt(0) as ViewGroup
            val title = card.findViewById<TextView>(R.id.tv_track)
            val artist = card.findViewById<TextView>(R.id.tv_artist)
            val cover = card.findViewById<ShapeableImageView>(R.id.artwork)
            val action = card.findViewById<View>(R.id.btn_row_action)

            if (widthDp == 390) expect(where, "row width", card.width, dp(358))
            expect(where, "row inter-row gap",
                (card.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin, dp(16))
            expect(where, "row padding", content.paddingLeft, dp(17))

            cardIsTheFrozenSurface(ctx, where, "row", card)

            // The frozen 98 - 17 + a 64 cover + 17 - measured on one-line content.
            expect(where, "row height", card.height, dp(98))

            // `Container` (17,17) 64x64 r20.
            val cardTop = topInRoot(card)
            expect(where, "cover size", cover.width, dp(64))
            expect(where, "cover square", cover.height, cover.width.toFloat())
            expect(where, "cover x", leftInRoot(cover) - leftInRoot(card), dp(17))
            expect(where, "cover y", topInRoot(cover) - cardTop, dp(17))
            expect(where, "cover corner",
                cover.shapeAppearanceModel.topLeftCornerSize.getCornerSize(
                    RectF(0f, 0f, cover.width.toFloat(), cover.height.toFloat()),
                ).roundToInt(),
                dp(20),
            )

            // `Container > Button` (301,29) 40x40 on `primary`, its centre on the
            // cover's centre, its trailing edge on the row's own 17 padding.
            expect(where, "action size", action.width, dp(40))
            expect(where, "action trailing inset",
                card.width - (leftInRoot(action) - leftInRoot(card) + action.width), dp(17))
            expect(where, "action centred on the cover",
                centreYInRoot(action), centreYInRoot(cover).toFloat())
            expect(where, "action tint",
                (action as android.widget.ImageView).imageTintList?.defaultColor ?: 0,
                colour(ctx, R.color.primary))

            // `Container` (97,32) 188x34: 17 + the 64 cover + the frozen 16 gutter.
            val column = card.findViewById<View>(R.id.text_column)
            expect(where, "text column x", leftInRoot(title) - leftInRoot(card), dp(97))

            // The block is asserted on its CENTRE - the frozen 32 + 34/2 = 49 -
            // and not on its top, for the reason the empty state's two text
            // blocks are: the frozen 34 is an 18 box over a 16 box against 28
            // and 20 line heights, which Figma can draw and Android cannot. The
            // real block is 48, so its top lands at 25 while its centre stays
            // exactly on the frozen 49, which is also the cover's centre.
            expect(where, "text block centre", centreYInRoot(column) - cardTop, dp(49))
            expect(where, "text block centred on the cover",
                centreYInRoot(column), centreYInRoot(cover).toFloat())

            expect(where, "title colour", title.currentTextColor, colour(ctx, R.color.text_primary))
            expect(where, "artist colour", artist.currentTextColor, colour(ctx, R.color.text_secondary))

            // The frozen 18 and 16 boxes are applied as minima, so the tokens' real
            // 28 and 20 line boxes are honoured and nothing is clipped.
            expect(where, "title box", title.height, dp(28))
            if (artist.lineCount == 1) expect(where, "artist box", artist.height, dp(20))

            noClipping(title, "$where/title")
            noClipping(artist, "$where/artist")
            noOverlap(where, "cover", cover, "title", title)
            noOverlap(where, "artist", artist, "action", action)

            log += "$where: row ${card.width}x${card.height}, padding ${content.paddingLeft}, " +
                "cover ${cover.width}@${topInRoot(cover) - cardTop}, action ${action.width}, " +
                "title ${title.height} ${title.lineCount}L, artist ${artist.height} ${artist.lineCount}L"
        }
    }

    /**
     * A long Russian title and artist must not clip, must not push the row's own
     * control out of it, and must keep the truncation the screen already had -
     * one line for the title and two for the artist. Converting Collection to
     * History's variable-height, never-truncate rule is a separate decision and
     * this asserts that F3 did not take it by accident either.
     *
     * The cover and the action must also stay put: both are anchored on the row's
     * own 17 padding rather than on the text, so a second artist line grows the
     * row without moving either of them off the frozen margins.
     */
    @Test
    fun aLongRussianTrackKeepsItsTruncationAndDoesNotClip() {
        val longTitle = "Очень длинное название трека которое точно не помещается в одну строку"
        val longArtist = "ИСПОЛНИТЕЛЬ С ОЧЕНЬ ДЛИННЫМ НАЗВАНИЕМ И ЕЩЁ ОДНИМ ПРИГЛАШЁННЫМ АРТИСТОМ"

        onMainActivity { activity ->
            for (night in listOf(false, true)) {
                val inflater = inflaterFor(activity, night)
                val dp = dpIn(inflater.context)
                for (widthDp in widthsDp) {
                    val where = "${if (night) "dark" else "light"}@${widthDp}dp/long"
                    val rowWidthPx = (dp(widthDp) - 2 * dp(16)).roundToInt()
                    val card = row(inflater, rowWidthPx) { c ->
                        c.findViewById<TextView>(R.id.tv_track).text = longTitle
                        c.findViewById<TextView>(R.id.tv_artist).text = longArtist
                    }
                    val title = card.findViewById<TextView>(R.id.tv_track)
                    val artist = card.findViewById<TextView>(R.id.tv_artist)
                    val cover = card.findViewById<View>(R.id.artwork)
                    val action = card.findViewById<View>(R.id.btn_row_action)

                    if (title.lineCount != 1) {
                        findings += "$where: title took ${title.lineCount} lines, expected 1"
                    }
                    if (artist.lineCount > 2) {
                        findings += "$where: artist took ${artist.lineCount} lines, expected at most 2"
                    }
                    noClipping(title, "$where/title")
                    noClipping(artist, "$where/artist")
                    noOverlap(where, "cover", cover, "title", title)
                    noOverlap(where, "artist", artist, "action", action)

                    val cardLeft = leftInRoot(card)
                    expect(where, "cover stays on the margin", topInRoot(cover) - topInRoot(card), dp(17))
                    expect(where, "action stays on the margin",
                        card.width - (leftInRoot(action) - cardLeft + action.width), dp(17))
                    if (leftInRoot(action) - cardLeft + action.width > card.width) {
                        findings += "$where: the row action is pushed outside the row"
                    }

                    log += "$where: ${title.lineCount}L/${artist.lineCount}L, row ${card.height}px"
                }
            }
        }

        log.forEach { android.util.Log.i("COLLECTIONQA", "  $it") }
        assertTrue(findings.joinToString("\n") { "  $it" }, findings.isEmpty())
    }

    /**
     * The reserved band has to cover the whole chrome stack the shell floats over
     * the content - BottomNavBar, the 4dp gap and the Mini Player - or the last
     * row ends up under the pill however carefully the rest is anchored.
     */
    private fun clearanceCoversTheChrome(inflater: LayoutInflater) {
        val ctx = inflater.context
        val dp = dpIn(ctx)
        val widthPx = dp(390).roundToInt()
        val shell = inflater.inflate(R.layout.activity_main, null) as ViewGroup
        shell.findViewById<View>(R.id.mini_player).visibility = View.VISIBLE
        shell.findViewById<View>(R.id.bottomNavView).visibility = View.VISIBLE
        shell.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dp(740).roundToInt(), View.MeasureSpec.EXACTLY),
        )
        shell.layout(0, 0, shell.measuredWidth, shell.measuredHeight)

        val pill = shell.findViewById<View>(R.id.mini_player)
        val chrome = shell.height - topInRoot(pill)
        val clearance = ctx.resources.getDimensionPixelSize(R.dimen.content_bottom_clearance)
        // 1px of tolerance, the same the anchor assertions take: the clearance is
        // one 154dp rounded once, and the stack is 76 + 4 + 74 rounded three
        // times, so on a fractional density they can differ by a pixel without
        // anything being wrong. Anything larger is a real hole.
        if (clearance < chrome - 1) {
            findings += "clearance ${clearance}px does not cover the ${chrome}px chrome stack " +
                "(nav + gap + mini player)"
        }
        log += "clearance: ${clearance}px reserved for a ${chrome}px chrome stack"
    }

    /* ---------------------------------------------------------------- infra -- */

    private fun screen(
        inflater: LayoutInflater,
        widthDp: Int,
        prepare: ((ViewGroup) -> Unit)? = null,
    ): ViewGroup {
        val widthPx = dpIn(inflater.context)(widthDp).roundToInt()
        val root = inflater.inflate(R.layout.fragment_favorites, null) as ViewGroup
        prepare?.invoke(root)
        // A real screen height: the list and the empty state are scroll containers,
        // and measuring UNSPECIFIED would hand them an unbounded viewport.
        root.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(widthPx * 2, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        return root
    }

    private fun row(
        inflater: LayoutInflater,
        widthPx: Int,
        prepare: ((MaterialCardView) -> Unit)? = null,
    ): MaterialCardView {
        val ctx = inflater.context
        val card = inflater
            .inflate(R.layout.item_favorite_track, null) as MaterialCardView
        // The item's own margins are layout params of a parent it does not have
        // when inflated with a null root, so they are restored here - the
        // inter-row gap is one of the things this test is asserting.
        card.layoutParams = ViewGroup.MarginLayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).also { it.bottomMargin = ctx.resources.getDimensionPixelSize(R.dimen.collection_row_gap) }
        prepare?.invoke(card)
        card.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        card.layout(0, 0, card.measuredWidth, card.measuredHeight)
        return card
    }

    private fun cardIsTheFrozenSurface(
        ctx: Context,
        where: String,
        what: String,
        card: MaterialCardView,
    ) {
        val dp = dpIn(ctx)
        expect(where, "$what corner", card.radius.roundToInt(), dp(24))
        expect(where, "$what stroke width", card.strokeWidth, dp(1))
        expect(where, "$what stroke colour", card.strokeColorStateList?.defaultColor ?: 0,
            colour(ctx, R.color.outline))
        expect(where, "$what fill", card.cardBackgroundColor.defaultColor,
            colour(ctx, R.color.surface))
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
                    android.util.Log.w("COLLECTIONQA", "activity close timed out; checks complete", e)
                }
            }
        }
    }

    private fun dpIn(ctx: Context): (Number) -> Float {
        val density = ctx.resources.displayMetrics.density
        return { v -> v.toFloat() * density }
    }

    private fun colour(ctx: Context, id: Int): Float = ContextCompat.getColor(ctx, id).toFloat()

    private fun offsetToRoot(v: View, r: Rect) {
        var p = v.parent
        while (p is View) { r.offset(p.left, p.top); p = p.parent }
    }

    private fun rectInRoot(v: View): Rect =
        Rect(v.left, v.top, v.right, v.bottom).also { offsetToRoot(v, it) }

    private fun topInRoot(v: View): Int = rectInRoot(v).top
    private fun leftInRoot(v: View): Int = rectInRoot(v).left
    private fun centreYInRoot(v: View): Int = rectInRoot(v).centerY()
    private fun centreXInRoot(v: View): Int = rectInRoot(v).centerX()

    private fun expect(where: String, what: String, actual: Int, expected: Float) {
        if (abs(actual - expected) > 1f) {
            findings += "$where: $what is $actual, frozen design says ${expected.roundToInt()}"
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
        val ra = rectInRoot(a)
        val rb = rectInRoot(b)
        if (Rect.intersects(ra, rb)) findings += "$where: $an overlaps $bn ($ra vs $rb)"
    }
}
