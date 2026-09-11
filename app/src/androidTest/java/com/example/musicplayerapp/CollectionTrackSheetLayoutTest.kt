package com.example.musicplayerapp

import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.card.MaterialCardView
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The per-track sheet against the FINAL `Bottom Sheet / Действия с треком` (F1).
 *
 * Source: `collection-track-sheet` / `collection-track-sheet_dark` in
 * tools/figma-export/screens-3.6.6/baselines/, authored at spec/screens.mjs:374.
 *
 *   drag handle   (159,16)  40x4
 *   title          (24,40) 300x32     the TRACK
 *   subtitle       (24,78) 310x20     the ARTIST
 *   rows       y = 112 / 170 / 228 / 286,  358x56, pitch 58
 *   divider       (16,354) 326x1
 *   remove row     y = 367
 *   height                 447
 *
 * The anchors are asserted at every shipping width in both themes. Only the row
 * labels reflow horizontally; every vertical anchor is width-independent, which
 * is what this proves rather than assumes.
 *
 * The 358 width itself is asserted at 390dp only, because it is the frame width
 * minus the screen's own 16 margins - at 320 the sheet is 288 and correct.
 */
@RunWith(AndroidJUnit4::class)
class CollectionTrackSheetLayoutTest {

    private val widthsDp = listOf(320, 360, 390, 412)

    private val findings = mutableListOf<String>()
    private val log = mutableListOf<String>()

    /** Frozen y of each row, in order, and the label each one carries. */
    private val rows = listOf(
        Triple(R.id.row_spotify, 112, R.string.collection_sheet_spotify),
        Triple(R.id.row_apple_music, 170, R.string.collection_sheet_apple_music),
        Triple(R.id.row_youtube, 228, R.string.collection_sheet_youtube),
        Triple(R.id.row_yandex, 286, R.string.collection_sheet_yandex),
    )

    @Test
    fun sheetReproducesTheFinalAnchors() {
        onMainActivity { activity ->
            for (night in listOf(false, true)) {
                val theme = if (night) "dark" else "light"
                val inflater = inflaterFor(activity, night)
                for (widthDp in widthsDp) sweep(inflater, theme, widthDp)
            }
        }

        android.util.Log.i("COLLECTIONQA", "==== TRACK SHEET (API ${Build.VERSION.SDK_INT}) ====")
        log.forEach { android.util.Log.i("COLLECTIONQA", "  $it") }
        findings.forEach { android.util.Log.e("COLLECTIONQA", "  FINDING $it") }

        assertTrue(
            "Track sheet findings on API ${Build.VERSION.SDK_INT}:\n" +
                findings.joinToString("\n") { "  $it" },
            findings.isEmpty(),
        )
    }

    private fun sweep(inflater: LayoutInflater, theme: String, widthDp: Int) {
        val ctx = inflater.context
        val dp = dpIn(ctx)
        val where = "$theme@${widthDp}dp/sheet"

        val root = sheet(inflater, widthDp) { r ->
            r.findViewById<TextView>(R.id.sheet_title).text = "HOMEWRECKER"
            r.findViewById<TextView>(R.id.sheet_subtitle).text = "SOMBR"
        }
        val card = root.findViewById<MaterialCardView>(R.id.sheet_card)
        val cardTop = topInRoot(card)
        val title = root.findViewById<TextView>(R.id.sheet_title)
        val subtitle = root.findViewById<TextView>(R.id.sheet_subtitle)
        val divider = root.findViewById<View>(R.id.sheet_divider)
        val remove = root.findViewById<View>(R.id.row_remove)

        // The sheet floats on the screen's own 16 margins with r28 all round.
        expect(where, "sheet leading margin", leftInRoot(card), dp(16))
        expect(where, "sheet corner", card.radius.roundToInt(), dp(28))
        expect(where, "sheet stroke width", card.strokeWidth, dp(1))
        expect(where, "sheet stroke colour",
            card.strokeColorStateList?.defaultColor ?: 0, colour(ctx, R.color.menu_outline))
        expect(where, "sheet fill",
            card.cardBackgroundColor.defaultColor, colour(ctx, R.color.menu_surface))
        if (widthDp == 390) expect(where, "sheet width", card.width, dp(358))

        // Header. The title is the TRACK and the subtitle the ARTIST.
        expect(where, "title y", topInRoot(title) - cardTop, dp(40), roundings = 3)
        expect(where, "title inset", leftInRoot(title) - leftInRoot(card), dp(24))
        expect(where, "subtitle y", topInRoot(subtitle) - cardTop, dp(78), roundings = 5)
        expect(where, "title colour", title.currentTextColor, colour(ctx, R.color.text_heading))
        expect(where, "subtitle colour", subtitle.currentTextColor, colour(ctx, R.color.text_secondary))
        if (title.text.toString() != "HOMEWRECKER") {
            findings += "$where: the sheet title is not the track"
        }
        if (subtitle.text.toString() != "SOMBR") {
            findings += "$where: the sheet subtitle is not the artist"
        }

        // The four service rows, in the owner-confirmed order, then the divider,
        // then the destructive row last and separated.
        var previous: View? = null
        for ((index, spec) in rows.withIndex()) {
            val (id, y, label) = spec
            val row = root.findViewById<View>(id)
            expect(where, "${ctx.getString(label)} row y", topInRoot(row) - cardTop, dp(y),
                roundings = 7 + 2 * index)
            expect(where, "${ctx.getString(label)} row height", row.height, dp(56))
            // The frozen 58 pitch, which is the 56 row and a 2 gap. Two roundings
            // apart, so this one is tight where the absolute anchor cannot be.
            previous?.let {
                expect(where, "${ctx.getString(label)} row pitch", topInRoot(row) - topInRoot(it), dp(58))
            }
            val text = (row as ViewGroup).findLabel()
            if (text?.text?.toString() != ctx.getString(label)) {
                findings += "$where: row at $y reads '${text?.text}', expected " +
                    "'${ctx.getString(label)}'"
            }
            expect(where, "${ctx.getString(label)} label inset",
                (text?.let { leftInRoot(it) } ?: 0) - leftInRoot(card), dp(76))
            expect(where, "${ctx.getString(label)} label colour",
                text?.currentTextColor ?: 0, colour(ctx, R.color.text_primary))
            previous?.let { noOverlap(where, "previous row", it, "row", row) }
            previous = row
        }

        expect(where, "divider y", topInRoot(divider) - cardTop, dp(354), roundings = 15)
        expect(where, "divider inset", leftInRoot(divider) - leftInRoot(card), dp(16))
        // The 12 either side of the divider - what "last and separated" is.
        val lastService = root.findViewById<View>(R.id.row_yandex)
        expect(where, "gap above the divider",
            topInRoot(divider) - (topInRoot(lastService) + lastService.height), dp(12))
        expect(where, "divider colour",
            (divider.background as? android.graphics.drawable.ColorDrawable)?.color ?: 0,
            colour(ctx, R.color.outline))

        // The destructive row is last, separated, and on `error` in both themes.
        expect(where, "remove row y", topInRoot(remove) - cardTop, dp(367), roundings = 17)
        expect(where, "remove row height", remove.height, dp(56))
        expect(where, "gap below the divider",
            topInRoot(remove) - (topInRoot(divider) + divider.height), dp(12))
        val removeLabel = (remove as ViewGroup).findLabel()
        if (removeLabel?.text?.toString() != ctx.getString(R.string.collection_sheet_remove)) {
            findings += "$where: the last row is not 'Удалить из коллекции'"
        }
        expect(where, "remove label colour",
            removeLabel?.currentTextColor ?: 0, colour(ctx, R.color.error))

        // 367 + 56 + a 24 bottom padding.
        expect(where, "sheet height", card.height, dp(447), roundings = 19)
        expect(where, "bottom padding",
            (cardTop + card.height) - (topInRoot(remove) + remove.height), dp(24))

        noClipping(title, "$where/title")
        noClipping(subtitle, "$where/subtitle")
        noOverlap(where, "subtitle", subtitle, "first row", root.findViewById(R.id.row_spotify))

        log += "$where: sheet ${card.width}x${card.height}, title@${topInRoot(title) - cardTop}, " +
            "rows@${rows.map { topInRoot(root.findViewById<View>(it.first)) - cardTop }}, " +
            "remove@${topInRoot(remove) - cardTop}"
    }

    /**
     * A long title has to grow the sheet rather than run into the first row: the
     * header is stacked as gaps, not absolute tops, precisely so it can.
     */
    @Test
    fun aLongTitleGrowsTheSheetInsteadOfOverlapping() {
        val longTitle = "Очень длинное название трека которое точно не помещается в одну строку"
        val longArtist = "ИСПОЛНИТЕЛЬ С ОЧЕНЬ ДЛИННЫМ НАЗВАНИЕМ И ЕЩЁ ОДНИМ ПРИГЛАШЁННЫМ АРТИСТОМ"

        onMainActivity { activity ->
            for (night in listOf(false, true)) {
                val inflater = inflaterFor(activity, night)
                for (widthDp in widthsDp) {
                    val where = "${if (night) "dark" else "light"}@${widthDp}dp/sheet-long"
                    val root = sheet(inflater, widthDp) { r ->
                        r.findViewById<TextView>(R.id.sheet_title).text = longTitle
                        r.findViewById<TextView>(R.id.sheet_subtitle).text = longArtist
                    }
                    val title = root.findViewById<TextView>(R.id.sheet_title)
                    val subtitle = root.findViewById<TextView>(R.id.sheet_subtitle)
                    val first = root.findViewById<View>(R.id.row_spotify)

                    if (title.lineCount > 2) {
                        findings += "$where: title took ${title.lineCount} lines, expected at most 2"
                    }
                    // Two since the G4b owner decision, and never more.
                    if (subtitle.lineCount !in 1..2) {
                        findings += "$where: subtitle took ${subtitle.lineCount} lines, expected at most 2"
                    }
                    noClipping(title, "$where/title")
                    noClipping(subtitle, "$where/subtitle")
                    noOverlap(where, "title", title, "subtitle", subtitle)
                    noOverlap(where, "subtitle", subtitle, "first row", first)

                    log += "$where: ${title.lineCount}L/${subtitle.lineCount}L, " +
                        "sheet ${root.findViewById<View>(R.id.sheet_card).height}px"
                }
            }
        }

        log.forEach { android.util.Log.i("COLLECTIONQA", "  $it") }
        assertTrue(findings.joinToString("\n") { "  $it" }, findings.isEmpty())
    }

    /**
     * `Bottom Sheet / Найти трек` - find-track-sheet 2517:2524 (G4b).
     *
     * The PLAYER / History variant: the same card and the same four rows as the
     * sheet above, at the same anchors, and nothing below them - so it ends 24
     * under the last service at the frozen 366. Bound through [FindTrackRows], the
     * binder both sheets use, so this is also the header wiring's test.
     */
    @Test
    fun findTrackSheetIsTheSharedRowsWithoutTheDestructiveRow() {
        onMainActivity { activity ->
            for (night in listOf(false, true)) {
                val theme = if (night) "dark" else "light"
                val inflater = inflaterFor(activity, night)
                for (widthDp in widthsDp) sweepFindTrack(inflater, theme, widthDp)
            }
        }

        android.util.Log.i("COLLECTIONQA", "==== FIND-TRACK SHEET (API ${Build.VERSION.SDK_INT}) ====")
        log.forEach { android.util.Log.i("COLLECTIONQA", "  $it") }
        findings.forEach { android.util.Log.e("COLLECTIONQA", "  FINDING $it") }
        assertTrue(
            "Find-track sheet findings on API ${Build.VERSION.SDK_INT}:\n" +
                findings.joinToString("\n") { "  $it" },
            findings.isEmpty(),
        )
    }

    private fun sweepFindTrack(inflater: LayoutInflater, theme: String, widthDp: Int) {
        val ctx = inflater.context
        val dp = dpIn(ctx)
        val where = "$theme@${widthDp}dp/find-track"

        val root = sheet(inflater, widthDp, R.layout.sheet_find_track) { r ->
            com.example.musicplayerapp.ui.FindTrackRows.bind(r, artist = "MUSE", title = "CRYOGEN") {}
        }
        val card = root.findViewById<MaterialCardView>(R.id.sheet_card)
        val cardTop = topInRoot(card)
        val title = root.findViewById<TextView>(R.id.sheet_title)
        val subtitle = root.findViewById<TextView>(R.id.sheet_subtitle)

        // No divider and no `Удалить из коллекции`: absent, not hidden. The ids
        // resolve - the Collection sheet has both - so null here is a real check.
        if (root.findViewById<View>(R.id.row_remove) != null) {
            findings += "$where: the find-track sheet carries the Collection delete row"
        }
        if (root.findViewById<View>(R.id.sheet_divider) != null) {
            findings += "$where: the find-track sheet carries the Collection divider"
        }

        expect(where, "sheet leading margin", leftInRoot(card), dp(16))
        expect(where, "sheet corner", card.radius.roundToInt(), dp(28))
        expect(where, "sheet stroke width", card.strokeWidth, dp(1))
        expect(where, "sheet stroke colour",
            card.strokeColorStateList?.defaultColor ?: 0, colour(ctx, R.color.menu_outline))
        expect(where, "sheet fill",
            card.cardBackgroundColor.defaultColor, colour(ctx, R.color.menu_surface))
        if (widthDp == 390) expect(where, "sheet width", card.width, dp(358))

        // The frame's own text - the track over the artist - arrived through the
        // shared binder.
        if (title.text.toString() != "CRYOGEN") findings += "$where: the title is not the track"
        if (subtitle.text.toString() != "MUSE") findings += "$where: the subtitle is not the artist"
        expect(where, "title y", topInRoot(title) - cardTop, dp(40), roundings = 3)
        expect(where, "subtitle y", topInRoot(subtitle) - cardTop, dp(78), roundings = 5)
        expect(where, "title colour", title.currentTextColor, colour(ctx, R.color.text_heading))
        expect(where, "subtitle colour", subtitle.currentTextColor, colour(ctx, R.color.text_secondary))

        var previous: View? = null
        for ((index, spec) in rows.withIndex()) {
            val (id, y, label) = spec
            val row = root.findViewById<View>(id)
            expect(where, "${ctx.getString(label)} row y", topInRoot(row) - cardTop, dp(y),
                roundings = 7 + 2 * index)
            expect(where, "${ctx.getString(label)} row height", row.height, dp(56))
            previous?.let {
                expect(where, "${ctx.getString(label)} row pitch", topInRoot(row) - topInRoot(it), dp(58))
            }
            val text = (row as ViewGroup).findLabel()
            if (text?.text?.toString() != ctx.getString(label)) {
                findings += "$where: row at $y reads '${text?.text}'"
            }
            expect(where, "${ctx.getString(label)} label inset",
                (text?.let { leftInRoot(it) } ?: 0) - leftInRoot(card), dp(76))
            val glyph = (0 until row.childCount).mapNotNull { row.getChildAt(it) as? android.widget.ImageView }
                .firstOrNull()
            if (glyph?.drawable == null) findings += "$where: ${ctx.getString(label)} has no glyph"
            if (!row.isClickable || !row.hasOnClickListeners()) {
                findings += "$where: ${ctx.getString(label)} is not wired to a search"
            }
            previous = row
        }

        // 286 + 56 + the sheet's 24 of bottom padding = the frozen 366.
        val last = root.findViewById<View>(R.id.row_yandex)
        expect(where, "sheet height", card.height, dp(366), roundings = 15)
        expect(where, "bottom padding",
            (cardTop + card.height) - (topInRoot(last) + last.height), dp(24))

        log += "$where: sheet ${card.width}x${card.height}, " +
            "rows@${rows.map { topInRoot(root.findViewById<View>(it.first)) - cardTop }}"

        // ---- a deliberately long artist: two lines, an ellipsis, nothing broken ----
        val longArtist = "КРАСНОЗНАМЁННАЯ ДИВИЗИЯ ИМЕНИ МОЕЙ БАБУШКИ FEAT. НАУТИЛУС " +
            "ПОМПИЛИУС, АУКЦЫОН И ЕЩЁ НЕСКОЛЬКО ПРИГЛАШЁННЫХ ИСПОЛНИТЕЛЕЙ"
        val long = sheet(inflater, widthDp, R.layout.sheet_find_track) { r ->
            com.example.musicplayerapp.ui.FindTrackRows.bind(r, artist = longArtist, title = "ФАК Ю") {}
        }
        val lCard = long.findViewById<MaterialCardView>(R.id.sheet_card)
        val lSub = long.findViewById<TextView>(R.id.sheet_subtitle)
        val lFirst = long.findViewById<View>(R.id.row_spotify)
        val lLast = long.findViewById<View>(R.id.row_yandex)
        val w = "$where/long-artist"
        if (lSub.lineCount != 2) findings += "$w: artist took ${lSub.lineCount} lines, the rule is 2"
        if ((lSub.layout?.getEllipsisCount(1) ?: 0) == 0) findings += "$w: a too-long artist is not ellipsised on line 2"
        noClipping(lSub, "$w/artist")
        noOverlap(w, "artist", lSub, "first row", lFirst)
        // The rows move down by exactly the one extra artist line and keep their
        // own rhythm: same pitch, same height, same 24 under the last one. "One
        // line" is the subtitle's own rendered line pitch, read from its layout -
        // not an assumed number: the first run of this assumed the token's 20 and
        // the text sets on its natural line.
        val extra = lSub.height - subtitle.height
        val linePitch = lSub.layout.let { it.getLineBottom(1) - it.getLineBottom(0) }
        expect(w, "extra artist line = one line of the artist", extra, linePitch.toFloat())
        expect(w, "first row y", topInRoot(lFirst) - topInRoot(lCard), (topInRoot(root.findViewById(R.id.row_spotify)) - cardTop + extra).toFloat())
        expect(w, "row height", lFirst.height, dp(56))
        expect(w, "bottom padding", (topInRoot(lCard) + lCard.height) - (topInRoot(lLast) + lLast.height), dp(24))
        log += "$w: artist ${lSub.lineCount}L, sheet ${lCard.height}px (+${lCard.height - card.height})"
    }

    /* ---------------------------------------------------------------- infra -- */

    /** The label is the one child of a sheet row that carries text. */
    private fun ViewGroup.findLabel(): TextView? =
        (0 until childCount).mapNotNull { getChildAt(it) as? TextView }.firstOrNull()

    private fun sheet(
        inflater: LayoutInflater,
        widthDp: Int,
        layout: Int = R.layout.sheet_collection_track,
        prepare: ((ViewGroup) -> Unit)? = null,
    ): ViewGroup {
        val widthPx = dpIn(inflater.context)(widthDp).roundToInt()
        val root = inflater.inflate(layout, null) as ViewGroup
        prepare?.invoke(root)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
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

    /**
     * @param roundings how many independently rounded dp values lie between the
     * card's top edge and this anchor. The sheet's header and rows are stacked as
     * gaps, so a vertical anchor is the SUM of that many `getDimensionPixelSize`
     * results, and each one can sit up to half a pixel off its exact value at a
     * fractional density - 420dpi is 2.625, where 4dp is 10.5px and 20dp is
     * 52.5px. The tolerance is therefore half a pixel per rounding, which is the
     * arithmetic bound and not a number fitted to what this happened to measure.
     * Sizes and horizontal insets are single values and keep the default 1px.
     *
     * The absolute anchors get loose deep down the stack, so the gaps that
     * actually carry the design - the 58 pitch, the 12 either side of the
     * divider, the 24 of bottom padding - are asserted separately and tightly,
     * where they are only ever two roundings apart.
     */
    private fun expect(where: String, what: String, actual: Int, expected: Float, roundings: Int = 2) {
        val tolerance = (0.5f * roundings).coerceAtLeast(1f)
        if (abs(actual - expected) > tolerance) {
            findings += "$where: $what is $actual, FINAL design says ${expected.roundToInt()}"
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
