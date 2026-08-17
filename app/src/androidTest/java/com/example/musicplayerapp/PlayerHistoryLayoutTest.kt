package com.example.musicplayerapp

import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.musicplayerapp.adapters.PlayerHistoryAdapter
import com.example.musicplayerapp.data.HistoryTrack
import com.example.musicplayerapp.ui.BroadcastHistoryState
import com.google.android.material.card.MaterialCardView
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The PLAYER's inline `Broadcast History Section` against the frozen canonical
 * frame - light node 2396:30784, dark 2444:18288.
 *
 * The section sits at 552 of a 1022-tall frame. The page starts at 79, so 473
 * here, which is 30 below where `Controls` ends. Every frozen number inside it is
 * reproduced:
 *
 *   17  padding   32  heading   16  gap   row x3   16  gap   54  button   17
 *
 *      13 across / 17 down  row padding   42  time (START)   12   40x40 r6 art   12   text
 *
 * The row carries no service actions: the owner-confirmed FINAL reference is
 * time -> artwork -> title/artist and nothing else. With nothing under the text,
 * one-line content measures the frozen 74 - 17 + 40 + 17 - and three rows and a
 * button make the frozen 374. That the row holds no hidden or reserved space for
 * the removed actions is asserted, not assumed.
 *
 * Four things here are deliberately NOT the frozen mock, and each is asserted as
 * such rather than skipped:
 *
 *  - **Row height is a floor, not a fix.** 74 is the height of the mock's one-line
 *    title over its one-line artist. Real metadata wraps, and the row is required
 *    to grow when it does - truncating a real track name to hold a mock's height
 *    is the defect this checks for.
 *
 *  - **The time column is 42, not the frozen 38.98.** Onest has no tabular
 *    figures and its digits are proportional, so the frozen box fitted some
 *    clock times and clipped others; 42 holds all 1440, measured through the
 *    view's own paint. Everything after the time therefore sits 3.02 further in.
 *
 *  - **The text block is top-aligned to the artwork, not centred against it.**
 *    The mock centres a 40 cover in a 48 text block, which puts it at y=4 - but
 *    that only holds while the text is exactly one line taller than the cover.
 *    A wrapped title slid the cover down by half the added line - 32 at three
 *    lines - and the title's first line stopped having anything to do with the top
 *    of the cover. By owner decision the first line and the cover's top edge now
 *    share a y at every line count.
 *
 *  - **The row's 74 is spent as 17 + 40 + 17, not the mock's 13 + 48 + 13.**
 *    Owner intent is that the normal one-line title over one-line artist reads
 *    inside the cover, so the text block is 40 rather than 48: the title on a 22
 *    line and the artist on an 18, both local to this surface and both the font's
 *    own natural line rather than the Figma leading. The row's vertical padding
 *    takes up the other 8, which also puts the cover back on the mock's y=17 and
 *    the timestamp on its y=27. Row and section heights are the frozen ones.
 */
@RunWith(AndroidJUnit4::class)
class PlayerHistoryLayoutTest {

    private val widthsDp = listOf(320, 360, 390, 412)

    private val longTitle = "КРАСНОЗНАМЁННАЯ ДИВИЗИЯ ИМЕНИ МОЕЙ БАБУШКИ"
    private val longArtist = "MIAMI HORROR FT. POOLSIDE И ЕЩЁ НЕСКОЛЬКО ИСПОЛНИТЕЛЕЙ"

    private val findings = mutableListOf<String>()
    private val log = mutableListOf<String>()

    @Test
    fun broadcastHistoryReproducesTheFrozenSection() {
        onMainActivity { activity ->
            for (night in listOf(false, true)) {
                for (widthDp in widthsDp) {
                    // screenWidthDp as well as the measure width, so anything
                    // width-qualified resolves the way it would on that device.
                    // The row itself is now one layout at every width.
                    sweep(inflaterFor(activity, night, widthDp), if (night) "dark" else "light", night, widthDp)
                }
            }
        }

        android.util.Log.i("PLAYERQA", "==== BROADCAST HISTORY (API ${Build.VERSION.SDK_INT}) ====")
        log.forEach { android.util.Log.i("PLAYERQA", "  $it") }
        findings.forEach { android.util.Log.e("PLAYERQA", "  FINDING $it") }

        assertTrue(
            "Broadcast History findings on API ${Build.VERSION.SDK_INT}:\n" +
                findings.joinToString("\n") { "  $it" },
            findings.isEmpty(),
        )
    }

    private fun sweep(inflater: LayoutInflater, theme: String, night: Boolean, widthDp: Int) {
        val dm = inflater.context.resources.displayMetrics
        val dp = { v: Number -> v.toFloat() * dm.density }

        run {
            val widthPx = dp(widthDp).roundToInt()
            val where = "$theme@${widthDp}dp"


            /* ---- the section, with the three rows the frozen frame draws ---- */

            val page = pageWith(inflater, widthPx, tracks(3))
            val section = page.findViewById<MaterialCardView>(R.id.history_section)
            val heading = page.findViewById<TextView>(R.id.history_heading)
            val list = page.findViewById<RecyclerView>(R.id.history_list)
            val more = page.findViewById<TextView>(R.id.history_show_more)
            val play = page.findViewById<View>(R.id.btn_play)

            // The one anchor that ties this to #42: the upper section must not
            // have moved. Its own test owns the rest of those numbers.
            expect(where, "controls y (unmoved by Phase C)", topIn(play, page), dp(363))

            expect(where, "section y", topIn(section, page), dp(473))
            expect(where, "section x", leftIn(section, page), dp(16))
            expect(where, "section width", section.width, dp(widthDp - 32))
            expect(where, "section radius", section.radius, dp(20))
            expect(where, "section stroke", section.strokeWidth, dp(1), tolerance = 1f)

            // 30 below where the controls end - the frozen gap between
            // `Player Section` and this one.
            expect(
                where, "gap below the controls",
                topIn(section, page) - (topIn(play, page) + play.height), dp(30),
            )

            expect(where, "heading y in section", topIn(heading, section), dp(17))
            expect(where, "heading x in section", leftIn(heading, section), dp(17))
            atLeast(where, "heading box", heading.height, dp(32))
            requireOneLine(heading, "$where/heading")
            noClipping(heading, "$where/heading")

            expect(where, "list y in section", topIn(list, section), dp(17 + 32 + 16))
            expect(where, "list width", list.width, dp(widthDp - 32 - 34))


            /* ---- one row's internals ---- */

            val row = list.getChildAt(0) as ViewGroup
            val time = row.findViewById<TextView>(R.id.tv_time)
            val art = row.findViewById<View>(R.id.artwork)
            val title = row.findViewById<TextView>(R.id.tv_title)
            val artist = row.findViewById<TextView>(R.id.tv_artist)

            // The service actions are gone from this surface, and gone means
            // absent: not GONE, not INVISIBLE, not a zero-width placeholder. The
            // ids still resolve - item_history_track.xml, the History bottom
            // sheet's row, uses all four - so finding nothing here is a real
            // check, not a vacuous one. Collection used to be the second holder
            // of these ids; F3 moved its service actions onto the per-track
            // sheet, so it no longer is.
            for (id in listOf(R.id.music_services, R.id.btn_spotify, R.id.btn_apple_music, R.id.btn_yandex)) {
                val leftover = row.findViewById<View>(id)
                if (leftover != null) {
                    findings += "$where: the row still holds ${resName(inflater.context, id)} " +
                        "(${leftover.width}x${leftover.height}, visibility=${leftover.visibility})"
                }
            }

            // The frozen 74: 13 + 48 + 13, with nothing under the text.
            // The frozen 74, spent as 17 + 40 + 17 rather than the mock's
            // 13 + 48 + 13. See the layout's Height note.
            expect(where, "row height (one-line content)", row.height, dp(74), tolerance = dp(1.5f))
            expect(where, "time x in row", leftIn(time, row), dp(13))
            expect(where, "time width", time.width, dp(42f))
            // START aligned inside its fixed 42, by owner correction: the text
            // begins at the box's own left edge, so every row's timestamp starts
            // on the same vertical line.
            if (time.layout != null && time.layout.getParagraphAlignment(0) != android.text.Layout.Alignment.ALIGN_NORMAL) {
                findings += "$where: the row's time is not start-aligned in its 42 box"
            }
            if (time.layout != null && time.layout.getLineLeft(0) != 0f) {
                findings += "$where: the row's time starts ${time.layout.getLineLeft(0)}px into its box, not at 0"
            }
            everyClockTimeFits(where, time)

            expect(where, "artwork size", art.width, dp(40))
            expect(where, "artwork height", art.height, dp(40))
            expect(where, "gap before artwork", leftIn(art, row) - (leftIn(time, row) + time.width), dp(12))
            expect(where, "gap after artwork", leftIn(title, row) - (leftIn(art, row) + art.width), dp(12))
            // Time and artwork are centred against each other, as the frozen
            // y=14/20 and y=4/40 in a 48 content row are.
            expect(
                where, "time centred on artwork",
                topIn(time, row) + time.height / 2f, topIn(art, row) + art.height / 2f,
                tolerance = dp(1.5f),
            )

            // The title's first line starts at the cover's top edge. Asserted on a
            // one-line row as well as the wrapped one below, because the defect
            // this replaces was invisible at one line: centring a 40 cover in a 48
            // text block is only 4 out, and it is the wrapped row that shows it.
            expect(
                where, "title top aligned to artwork top",
                topIn(title, row), topIn(art, row).toFloat(), tolerance = dp(1f),
            )
            // The cover is at the top of the content, not floated in the middle of
            // it - and that top is the mock's own y=17, because the row's vertical
            // padding is 17. The cover has not moved from where the frozen frame
            // draws it; the text came down to meet it.
            expect(where, "artwork y in row", topIn(art, row), dp(17), tolerance = dp(1.5f))

            // The title and artist boxes abut, with no gap between them.
            expect(where, "artist follows title", topIn(artist, row), (topIn(title, row) + title.height).toFloat())
            atLeast(where, "title box", title.height, dp(22))
            atLeast(where, "artist box", artist.height, dp(18))

            // The whole point of the local line heights: on normal one-line-over-
            // one-line metadata the text block reads inside the cover rather than
            // hanging 8 below it. 22 + 18 = 40, the cover's own height, so the
            // artist's bottom lands about level with the cover's bottom.
            expect(
                where, "one-line text block height", rectIn(artist, row).bottom - topIn(title, row),
                dp(40), tolerance = dp(1.5f),
            )
            expect(
                where, "artist bottom level with artwork bottom",
                rectIn(artist, row).bottom, rectIn(art, row).bottom.toFloat(), tolerance = dp(1.5f),
            )
            // ...and the lines are not squeezed below what the font needs. Onest
            // Regular measures 21.71dp at 17sp and 17.90dp at 14sp with font
            // padding off, so 22 and 18 are floors, not choices with slack.
            noClipping(title, "$where/title")
            noClipping(artist, "$where/artist")

            // Nothing is reserved below the text where the service line used to
            // be: the row ends at the artist's own bottom plus the row's padding.
            expect(
                where, "row ends at the text, no reserved line below it",
                row.height - rectIn(artist, row).bottom, dp(17), tolerance = dp(1.5f),
            )

            noOverlap(where, "time", time, "artwork", art, row)
            noOverlap(where, "artwork", art, "title", title, row)

            // The column has to be wide enough to set a real name on a line. At
            // the frozen width it is 192: the row's 324 less 13 padding, the 42
            // time column, two 12 gaps, the 40 artwork and 13 padding. Nothing
            // sits beside it, so this is the whole of the row's trailing space.
            atLeast(where, "title column", title.width, dp(120))
            if (widthDp == 390) {
                expect(where, "title column at the frozen width", title.width, dp(192), tolerance = dp(1.5f))
            }
            requireOneLine(title, "$where/title")
            requireOneLine(artist, "$where/artist")

            /* ---- colours, and that the two themes actually differ ---- */

            val ctx = inflater.context
            expectColor(where, "section fill", section.cardBackgroundColor.defaultColor, ctx.color(R.color.surface))
            expectColor(where, "section stroke", section.strokeColorStateList?.defaultColor ?: 0, ctx.color(R.color.outline))
            expectColor(where, "heading colour", heading.currentTextColor, ctx.color(R.color.text_primary))
            expectColor(where, "title colour", title.currentTextColor, ctx.color(R.color.text_primary))
            expectColor(where, "time colour", time.currentTextColor, ctx.color(R.color.text_secondary))
            expectColor(where, "artist colour", artist.currentTextColor, ctx.color(R.color.text_secondary))
            expectColor(where, "show-more label", more.currentTextColor, ctx.color(R.color.player_history_more_label))

            // The pair colours are pairs: each must be the frozen value for the
            // theme being drawn, not one value used in both.
            val frozenMoreLabel = if (night) 0xFF5FD9B4.toInt() else 0xFF003056.toInt()
            expectColor(where, "show-more label is the frozen pair", more.currentTextColor, frozenMoreLabel)
            val frozenSurface = if (night) 0xFF142D47.toInt() else 0xFFFFFFFF.toInt()
            expectColor(where, "section fill is the frozen pair", section.cardBackgroundColor.defaultColor, frozenSurface)

            /* ---- "Показать ещё": offered only when there is more ---- */

            if (more.visibility == View.VISIBLE) {
                findings += "$where: \"Показать ещё\" is offered over a history of 3, which is all of it"
            }
            if (!more.isClickable) {
                findings += "$where: \"Показать ещё\" is not clickable"
            }

            val many = pageWith(inflater, widthPx, tracks(30))
            val manySection = many.findViewById<View>(R.id.history_section)
            val manyMore = many.findViewById<TextView>(R.id.history_show_more)
            val manyList = many.findViewById<RecyclerView>(R.id.history_list)

            // Measured where it is actually drawn - a GONE control has no height.
            expect(where, "show-more height", manyMore.height, dp(54), tolerance = dp(1.5f))
            expect(where, "show-more width", manyMore.width, dp(widthDp - 32 - 34))
            expect(
                where, "gap above show-more",
                topIn(manyMore, manySection) - (topIn(manyList, manySection) + manyList.height), dp(16),
            )
            expect(
                where, "padding below show-more",
                manySection.height - (topIn(manyMore, manySection) + manyMore.height), dp(17),
            )
            requireOneLine(manyMore, "$where/show-more label")
            noClipping(manyMore, "$where/show-more label")

            /*
             * The section's own height, at the frozen width.
             *
             * This is the page the frozen frame draws and not the three-entry
             * one: it shows three rows with "Показать ещё" under them, which
             * means there is a fourth entry behind it. A history of exactly three
             * hides the button and the section is 304 - the same numbers, one
             * part fewer.
             *
             * Nothing is pinned. It is 17 + 32 + 16 + 3x74 + 16 + 54 + 17 = 374,
             * every term the frozen one including the row: the row's text block is
             * 40 rather than the mock's 48, but its vertical padding is 17 rather
             * than 13, so the row is still 74 and the section is still the frozen
             * figure.
             */
            if (widthDp == 390) {
                expect(where, "section height, 3 rows + button", manySection.height, dp(374), tolerance = dp(2f))
                expect(where, "section height with all of a 3-entry history up", section.height, dp(304), tolerance = dp(2f))
            }
            if (manyMore.visibility != View.VISIBLE) {
                findings += "$where: \"Показать ещё\" is hidden over a history of 30 with only " +
                    "${BroadcastHistoryState.INITIAL_ROWS} shown"
            }
            if (manyList.childCount != BroadcastHistoryState.INITIAL_ROWS) {
                findings += "$where: a history of 30 draws ${manyList.childCount} rows before any " +
                    "reveal; the frozen section shows ${BroadcastHistoryState.INITIAL_ROWS}"
            }

            // One entry: a row, and nothing offered.
            val one = pageWith(inflater, widthPx, tracks(1))
            if (one.findViewById<RecyclerView>(R.id.history_list).childCount != 1) {
                findings += "$where: a history of 1 does not draw exactly one row"
            }
            if (one.findViewById<View>(R.id.history_show_more).visibility == View.VISIBLE) {
                findings += "$where: \"Показать ещё\" is offered over a history of 1"
            }

            // Fully revealed: all 30 up, nothing left to offer.
            val revealed = pageWith(inflater, widthPx, tracks(30), revealed = 30)
            if (revealed.findViewById<RecyclerView>(R.id.history_list).childCount != 30) {
                findings += "$where: a fully revealed history does not draw all 30 rows"
            }
            if (revealed.findViewById<View>(R.id.history_show_more).visibility == View.VISIBLE) {
                findings += "$where: \"Показать ещё\" is still offered with all 30 rows up"
            }

            /* ---- long metadata: the row grows, it does not cut ---- */

            val long = pageWith(
                inflater, widthPx,
                listOf(HistoryTrack(artist = longArtist, track = longTitle, playedAt = 1L, playedAtFormatted = "23:59")),
            )
            val longRow = long.findViewById<RecyclerView>(R.id.history_list).getChildAt(0) as ViewGroup
            val longTitleView = longRow.findViewById<TextView>(R.id.tv_title)
            val longArtistView = longRow.findViewById<TextView>(R.id.tv_artist)
            val longArt = longRow.findViewById<View>(R.id.artwork)

            if (longTitleView.lineCount <= 1) {
                findings += "$where: a 42-character title is on one line in a ~192dp column - it is being cut"
            }
            // Measured against the one-line row rather than a constant: the point
            // is that real content makes it grow past the frozen 74, and pinning
            // the comparison to the row above keeps that true if 74 ever moves.
            if (longRow.height <= row.height + 1) {
                findings += "$where: the row stayed at ${longRow.height}px under long metadata " +
                    "against ${row.height}px for one line; rows must grow rather than truncate"
            }
            // Neither is capped and neither ellipsizes: a long title adds a line
            // and keeps its end, and the row grows to hold it.
            for ((view, name) in listOf(longTitleView to "long title", longArtistView to "long artist")) {
                val layout = view.layout ?: continue
                for (line in 0 until layout.lineCount) {
                    if (layout.getEllipsisCount(line) > 0) {
                        findings += "$where: the $name is ellipsized; History rows do not truncate"
                        break
                    }
                }
            }
            // The alignment fix has to hold when the title wraps - that is the case
            // it exists for.
            expect(
                where, "wrapped title top aligned to artwork top",
                topIn(longTitleView, longRow), topIn(longArt, longRow).toFloat(), tolerance = dp(1f),
            )
            expect(
                where, "wrapped row: artist follows title",
                topIn(longArtistView, longRow),
                (topIn(longTitleView, longRow) + longTitleView.height).toFloat(),
            )
            noClipping(longTitleView, "$where/long title")
            noClipping(longArtistView, "$where/long artist")
            noOverlap(where, "long title", longTitleView, "long artist", longArtistView, longRow)
            // The artwork stays the frozen 40 however tall the row gets.
            expect(where, "artwork size under long metadata", longArt.width, dp(40))

            /* ---- the named clock times, actually drawn ---- */

            // everyClockTimeFits measures all 1440 through the paint; this draws
            // the four the owner called out and reads back what the row rendered,
            // so the check covers binding and layout, not just measurement.
            val named = listOf("00:00", "08:08", "10:45", "23:59")
            val timesPage = pageWith(
                inflater, widthPx,
                named.mapIndexed { i, t ->
                    HistoryTrack(artist = "MUSE", track = "CRYOGEN", playedAt = i.toLong(), playedAtFormatted = t)
                },
                revealed = named.size,
            )
            val timesList = timesPage.findViewById<RecyclerView>(R.id.history_list)
            var firstLeft: Int? = null
            for (i in named.indices) {
                val r = timesList.getChildAt(i) as? ViewGroup ?: continue
                val tv = r.findViewById<TextView>(R.id.tv_time)
                if (tv.text.toString() != named[i]) {
                    findings += "$where: row $i shows \"${tv.text}\", expected \"${named[i]}\""
                }
                val drawn = tv.paint.measureText(tv.text.toString())
                if (drawn > tv.width - tv.paddingStart - tv.paddingEnd) {
                    findings += "$where: \"${named[i]}\" is clipped - draws ${"%.1f".format(drawn)}px " +
                        "in a ${tv.width - tv.paddingStart - tv.paddingEnd}px box"
                }
                requireOneLine(tv, "$where/time ${named[i]}")
                // All four begin on the same vertical line.
                val left = leftIn(tv, r) + (tv.layout?.getLineLeft(0)?.toInt() ?: 0)
                if (firstLeft == null) firstLeft = left
                else if (left != firstLeft) {
                    findings += "$where: \"${named[i]}\" starts at ${left}px, \"${named[0]}\" at ${firstLeft}px - " +
                        "timestamps do not share a left edge"
                }
            }

            log += "$where: section@${topIn(section, page)} ${section.width}x${section.height}, " +
                "heading ${heading.height}, row ${row.height} (long ${longRow.height}, " +
                "${longTitleView.lineCount}L/${longArtistView.lineCount}L), " +
                "more ${more.height} vis=${more.visibility == View.VISIBLE}"
        }
    }

    /* ---------------------------------------------------------------- infra -- */

    /**
     * `playedAtFormatted` is "00:00" rather than the "10:0N" this used to build.
     * Onest's digits are proportional, so those two are not the same measurement
     * at all - "10:01" is 32.33dp against "00:00" at 40.78dp, the widest of the
     * 1440 - and the column was sized such that the first fitted and the second
     * did not. The rows measure the shape that is hardest to hold.
     */
    private fun tracks(n: Int) = (1..n).map {
        HistoryTrack(
            artist = "MUSE",
            track = "CRYOGEN",
            playedAt = it.toLong(),
            playedAtFormatted = "00:00",
        )
    }

    /**
     * The page with the section populated exactly as the fragment populates it:
     * the same adapter, the same projection, the real list cut to what is
     * revealed. Artwork is never answered - this measures layout, not network.
     */
    private fun pageWith(
        inflater: LayoutInflater,
        widthPx: Int,
        tracks: List<HistoryTrack>,
        revealed: Int = BroadcastHistoryState.INITIAL_ROWS,
    ): ViewGroup {
        val root = inflater.inflate(R.layout.fragment_myata_stream, null) as ViewGroup
        val list = root.findViewById<RecyclerView>(R.id.history_list)
        val adapter = PlayerHistoryAdapter(artworkFor = { _, _ -> }, cancelArtwork = {})
        list.layoutManager = LinearLayoutManager(inflater.context)
        list.adapter = adapter

        val state = BroadcastHistoryState.of(tracks.size, isLoading = false, revealed = revealed)
        list.visibility = if (state.mode == BroadcastHistoryState.Mode.POPULATED) View.VISIBLE else View.GONE
        root.findViewById<View>(R.id.history_show_more).visibility =
            if (state.isShowMoreVisible) View.VISIBLE else View.GONE
        // submitList is asynchronous; the rows have to be in before measuring.
        adapter.submitList(tracks.take(state.visibleCount))
        @Suppress("DEPRECATION")
        (list.adapter as PlayerHistoryAdapter).notifyDataSetChanged()

        root.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(widthPx * 8, View.MeasureSpec.AT_MOST),
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        return root
    }

    private fun inflaterFor(activity: MainActivity, night: Boolean, widthDp: Int): LayoutInflater {
        val cfg = Configuration(activity.resources.configuration)
        cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        cfg.screenWidthDp = widthDp
        cfg.smallestScreenWidthDp = minOf(cfg.smallestScreenWidthDp, widthDp)
        val themed = activity.createConfigurationContext(cfg)
        themed.setTheme(R.style.AppTheme)
        return activity.layoutInflater.cloneInContext(themed)
    }

    private fun android.content.Context.color(id: Int) = ContextCompat.getColor(this, id)

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

    private fun expectColor(where: String, what: String, actual: Int, expected: Int) {
        if (actual != expected) {
            findings += "$where: $what is #${Integer.toHexString(actual)}, " +
                "frozen design says #${Integer.toHexString(expected)}"
        }
    }

    private fun atLeast(where: String, what: String, actual: Int, floor: Float) {
        if (actual < floor - 1f) {
            findings += "$where: $what is ${actual}px, under the frozen ${floor.roundToInt()}px line"
        }
    }

    private fun requireOneLine(t: TextView, what: String) {
        if (t.lineCount > 1) findings += "$what wrapped onto ${t.lineCount} lines"
    }

    /** A readable name for an id, so a leftover view names itself. */
    private fun resName(ctx: android.content.Context, id: Int): String =
        runCatching { ctx.resources.getResourceEntryName(id) }.getOrDefault("id/$id")

    /**
     * The time box holds every clock time there is, not just the one on screen.
     *
     * This is the check that was missing when the column was the frozen 38.98.
     * [noClipping] only ever looked at height, and the fixture only ever fed it
     * "10:0N" - 32.33dp, near the bottom of the range, since Onest advances '1'
     * 363 units against '0' at 665 - so a box too small for "00:00" at 40.78dp
     * measured fine against both. The section shows whatever the station played,
     * so the box has to hold the widest of the 1440, and the real paint is what
     * decides that: measureText applies this font's kerning and letter spacing,
     * which a width computed from advances alone would miss.
     */
    private fun everyClockTimeFits(where: String, t: TextView) {
        val room = t.width - t.paddingStart - t.paddingEnd
        if (room <= 0) return
        var worst = ""
        var worstWidth = 0f
        for (h in 0..23) for (m in 0..59) {
            val s = "%02d:%02d".format(h, m)
            val w = t.paint.measureText(s)
            if (w > worstWidth) { worstWidth = w; worst = s }
        }
        if (worstWidth > room) {
            findings += "$where: the time box cannot hold \"$worst\" - needs " +
                "${"%.1f".format(worstWidth)}px, has ${room}px"
        }
    }

    /** The box holds its own glyphs: nothing is cut off top or bottom. */
    private fun noClipping(t: TextView, what: String) {
        val layout = t.layout ?: return
        val needed = layout.height + t.paddingTop + t.paddingBottom
        if (needed > t.height + 1) {
            findings += "$what is clipped: needs ${needed}px, has ${t.height}px"
        }
    }

    private fun noOverlap(where: String, aName: String, a: View, bName: String, b: View, root: View) {
        val ra = rectIn(a, root)
        val rb = rectIn(b, root)
        if (Rect.intersects(ra, rb)) {
            findings += "$where: $aName $ra overlaps $bName $rb"
        }
    }
}
