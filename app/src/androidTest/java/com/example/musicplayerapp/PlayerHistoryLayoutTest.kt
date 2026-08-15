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
 *      13  row padding   38.98  time (RIGHT)   12   40x40 r6 art   12   text
 *                        12   service links, on their own line
 *
 * The row is the one part that is not the frozen figure, so the section adds up
 * to 512 rather than 374 with three one-line rows in it.
 *
 * Two things here are deliberately NOT the frozen numbers, and both are asserted
 * as such rather than skipped:
 *
 *  - **Row height is a floor, not a fix.** 74 is the height of the mock's one-line
 *    title over its one-line artist. Real metadata wraps, and the row is required
 *    to grow when it does - truncating a real track name to hold a mock's height
 *    is the defect this checks for.
 *
 *  - **The service links are under the text, at every width.** The frozen slot
 *    holds two buttons named "Mock platform icons using generic material symbols
 *    for layout"; the app's three real ones go in the frozen 22x34 box and measure
 *    82. Beside the text that left the title column 113 at the frozen width and
 *    real names wrapped hard, so by owner decision real content wins over the
 *    mock's slot. The row is 120 on one-line content - 13 + 48 + 12 + 34 + 13 -
 *    and the section 512 with three rows in it. The frozen slot could not be taken
 *    literally anyway: on `History Item 1` (2399:31072) the hugging text column
 *    pushes it to a right edge of 336.15 inside a row whose content ends at 311.
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
            val services = row.findViewById<ViewGroup>(R.id.music_services)

            // 120, not the frozen 74: 13 + 48 + 12 + 34 + 13, the links having
            // moved below the text. Stated, not a floor being quietly missed.
            expect(where, "row height (one-line content)", row.height, dp(120), tolerance = dp(1.5f))
            expect(where, "time x in row", leftIn(time, row), dp(13))
            expect(where, "time width", time.width, dp(38.98f))
            // The frozen time box is RIGHT aligned inside its fixed 38.98.
            if (time.layout != null && time.layout.getParagraphAlignment(0) != android.text.Layout.Alignment.ALIGN_OPPOSITE) {
                findings += "$where: the row's time is not right-aligned in its 38.98 box"
            }

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

            // The title and artist boxes abut, as the frozen 0..28 and 28..48 do.
            expect(where, "artist follows title", topIn(artist, row), (topIn(title, row) + title.height).toFloat())
            atLeast(where, "title box", title.height, dp(28))
            atLeast(where, "artist box", artist.height, dp(20))

            // Three service links in the frozen 22x34 button box, keeping the
            // frozen row's trailing edge on their own line.
            expect(where, "service group width", services.width, dp(22 * 3 + 8 * 2))
            expect(where, "service group right edge", leftIn(services, row) + services.width, row.width - dp(13))
            for (id in listOf(R.id.btn_spotify, R.id.btn_apple_music, R.id.btn_yandex)) {
                val button = row.findViewById<View>(id)
                expect(where, "service button width", button.width, dp(22))
                expect(where, "service button height", button.height, dp(34))
            }

            // Under the text, at every width - that is the whole point of the
            // change, so it is asserted rather than inferred.
            if (rectIn(services, row).top < rectIn(artist, row).bottom) {
                findings += "$where: the service links are still beside a ${
                    (title.width / dm.density).roundToInt()
                }dp text column"
            }
            expect(
                where, "gap between text and service links",
                rectIn(services, row).top - rectIn(artist, row).bottom, dp(12), tolerance = dp(1.5f),
            )

            noOverlap(where, "time", time, "artwork", art, row)
            noOverlap(where, "artwork", art, "title", title, row)
            noOverlap(where, "title", title, "services", services, row)
            noOverlap(where, "artist", artist, "services", services, row)

            // The column has to be wide enough to set a real name on a line. At
            // the frozen width it is 195 with the links moved off it, against the
            // 113 they left when they were beside it.
            atLeast(where, "title column", title.width, dp(120))
            if (widthDp == 390) {
                expect(where, "title column at the frozen width", title.width, dp(195), tolerance = dp(1.5f))
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
             * hides the button and the section is 442 - the same numbers, one
             * part fewer.
             *
             * Nothing is pinned. It is 17 + 32 + 16 + 3x120 + 16 + 54 + 17, every
             * term the frozen one except the row, which carries the service links
             * on their own line and so measures 120 against the mock's 74.
             */
            if (widthDp == 390) {
                expect(where, "section height, 3 rows + button", manySection.height, dp(512), tolerance = dp(2f))
                expect(where, "section height with all of a 3-entry history up", section.height, dp(442), tolerance = dp(2f))
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
                findings += "$where: a 42-character title is on one line in a ~101dp column - it is being cut"
            }
            if (longRow.height <= dp(120) + 1) {
                findings += "$where: the row stayed at ${longRow.height}px under long metadata; " +
                    "rows must grow rather than truncate"
            }
            // The owner decision on History rows is no ellipsis at all: a long
            // title adds a line and keeps its end.
            for ((view, name) in listOf(longTitleView to "long title", longArtistView to "long artist")) {
                val layout = view.layout ?: continue
                for (line in 0 until layout.lineCount) {
                    if (layout.getEllipsisCount(line) > 0) {
                        findings += "$where: the $name is ellipsized; History rows do not truncate"
                        break
                    }
                }
            }
            noClipping(longTitleView, "$where/long title")
            noClipping(longArtistView, "$where/long artist")
            noOverlap(where, "long title", longTitleView, "long artist", longArtistView, longRow)
            noOverlap(where, "long artist", longArtistView, "services", longRow.findViewById(R.id.music_services), longRow)
            // The artwork stays the frozen 40 however tall the row gets.
            expect(where, "artwork size under long metadata", longArt.width, dp(40))

            log += "$where: section@${topIn(section, page)} ${section.width}x${section.height}, " +
                "heading ${heading.height}, row ${row.height} (long ${longRow.height}, " +
                "${longTitleView.lineCount}L/${longArtistView.lineCount}L), " +
                "more ${more.height} vis=${more.visibility == View.VISIBLE}"
        }
    }

    /* ---------------------------------------------------------------- infra -- */

    private fun tracks(n: Int) = (1..n).map {
        HistoryTrack(
            artist = "MUSE",
            track = "CRYOGEN",
            playedAt = it.toLong(),
            playedAtFormatted = "10:%02d".format(it % 60),
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
