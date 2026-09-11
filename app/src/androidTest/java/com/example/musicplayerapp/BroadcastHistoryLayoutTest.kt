package com.example.musicplayerapp

import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.musicplayerapp.adapters.BroadcastHistoryFooterAdapter
import com.example.musicplayerapp.adapters.PlayerHistoryAdapter
import com.example.musicplayerapp.data.HistoryTrack
import com.example.musicplayerapp.fragments.BroadcastHistoryRowGap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The full-screen История эфира against its four frozen frames (G4b), measured on
 * the real layouts at 320 / 360 / 390 / 412dp in both themes:
 *
 *   history-content 2523:23    history-loading 2517:2420
 *   history-empty   2517:2509  history-error   2517:2547   (+ the _dark twins)
 *
 * The screen is inflated and populated the way the fragment populates it - the
 * same adapter with the same row layout, the same footer, the same row-gap
 * decoration - and only the network is absent. Which frame is up is set directly;
 * `PlayerMissingFlowsTest` proves the fragment chooses it correctly.
 *
 * Deliberate differences from the frame, each asserted as the thing it is:
 *
 *  - **Time column 42, not 39**, the PLAYER section's measured reason. The cover
 *    and the text therefore sit 3 further in (64 / 120, not 61 / 117) and the
 *    action does not move (304 at 390).
 *  - **Typography "B": 16sp on a 20 line over 13sp on an 18, not the frame's
 *    17/28 over 14/20** - the owner's G4b decision, on G4a's multiline correction.
 *    One-line rows are still the frame's 76, because the 48 cover holds them
 *    there; wrapped rows grow by one 20 line.
 */
@RunWith(AndroidJUnit4::class)
class BroadcastHistoryLayoutTest {

    private val widthsDp = listOf(320, 360, 390, 412)
    private val findings = mutableListOf<String>()
    private val log = mutableListOf<String>()

    private val longTitle = "Краснознамённая дивизия имени моей бабушки"
    private val longArtist = "КРАСНОЗНАМЁННАЯ ДИВИЗИЯ ИМЕНИ МОЕЙ БАБУШКИ И ЕЩЁ НЕСКОЛЬКО ИСПОЛНИТЕЛЕЙ"

    // ==================== history-content ====================

    @Test
    fun contentReproducesHistoryContent() {
        sweep { inflater, where, night, widthDp -> content(inflater, where, night, widthDp) }
        report("CONTENT")
    }

    private fun content(inflater: LayoutInflater, where: String, night: Boolean, widthDp: Int) {
        val ctx = inflater.context
        val dp = dpIn(ctx)
        val root = screen(inflater, widthDp, tracks(3), show = R.id.history_list)
        val list = root.findViewById<RecyclerView>(R.id.history_list)

        // The band: back 24 at 12, the title at 56, 64 tall.
        val header = root.findViewById<View>(R.id.history_header)
        val back = root.findViewById<View>(R.id.history_back)
        val title = root.findViewById<TextView>(R.id.history_title)
        expect(where, "band height", header.height, dp(64))
        expect(where, "back x", leftIn(back, root), dp(12))
        expect(where, "back size", back.width, dp(24))
        expect(where, "title x", leftIn(title, root), dp(56))
        if (title.text.toString() != "История эфира") findings += "$where: band title is '${title.text}'"
        expectColour(where, "band title colour", title.currentTextColor, ctx.colour(R.color.text_heading))
        expect(where, "list top", topIn(list, root), dp(64))

        val row0 = list.getChildAt(0) as ViewGroup
        val row1 = list.getChildAt(1) as ViewGroup
        val time = row0.findViewById<TextView>(R.id.tv_time)
        val art = row0.findViewById<ImageView>(R.id.artwork)
        val tTitle = row0.findViewById<TextView>(R.id.tv_title)
        val tArtist = row0.findViewById<TextView>(R.id.tv_artist)
        val action = row0.findViewById<ImageView>(R.id.btn_row_action)

        // `Broadcast History List`: 16 in, 16 down, rows 8 apart, full width.
        expect(where, "first row x", leftIn(row0, list), dp(16))
        expect(where, "first row y", topIn(row0, list), dp(16))
        expect(where, "row width", row0.width, dp(widthDp - 32))
        expect(where, "row gap", topIn(row1, list) - (topIn(row0, list) + row0.height), dp(8))

        // 14 + 48 + 14: the frame's 76 on one line.
        expect(where, "one-line row height", row0.height, dp(76), tolerance = dp(1.5f))

        val rowMid = row0.height / 2f
        expect(where, "time x", leftIn(time, row0), dp(14))
        expect(where, "time width", time.width, dp(42))
        expect(where, "time centred on the row", topIn(time, row0) + time.height / 2f, rowMid, tolerance = dp(1f))
        if (time.layout != null && time.layout.getLineLeft(0) != 0f) {
            findings += "$where: the time is not START in its 42 box"
        }

        expect(where, "cover x", leftIn(art, row0), dp(64))
        expect(where, "cover size", art.width, dp(48))
        expect(where, "cover height", art.height, dp(48))
        expect(where, "cover y", topIn(art, row0), dp(14), tolerance = dp(1f))

        expect(where, "text x", leftIn(tTitle, row0), dp(120))
        val blockTop = topIn(tTitle, row0)
        val blockBottom = rectIn(tArtist, row0).bottom
        expect(where, "one-line text block", blockBottom - blockTop, dp(40), tolerance = dp(1.5f))
        expect(where, "text block centred", (blockTop + blockBottom) / 2f, rowMid, tolerance = dp(1f))
        expect(where, "artist follows title", topIn(tArtist, row0), rectIn(tTitle, row0).bottom.toFloat())
        if (widthDp == 390) expect(where, "text column at 390", tTitle.width, dp(176))
        // The owner's G4b typography "B".
        val sp = { v: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, ctx.resources.displayMetrics) }
        expect(where, "title size (B, 16sp)", tTitle.textSize, sp(16f), tolerance = 0.5f)
        expect(where, "artist size (B, 13sp)", tArtist.textSize, sp(13f), tolerance = 0.5f)

        // `Button / find track`: 40 ring, pinned 14 from the end, centred.
        expect(where, "action size", action.width, dp(40))
        expect(where, "action x", leftIn(action, row0), dp(widthDp - 32 - 14 - 40))
        expect(where, "action centred on the row", topIn(action, row0) + action.height / 2f, rowMid, tolerance = dp(1f))
        if (!action.isClickable || !action.hasOnClickListeners()) findings += "$where: the row action is not wired"
        if (action.contentDescription?.toString() != "Найти трек") {
            findings += "$where: the row action announces '${action.contentDescription}'"
        }
        noOverlap(where, "text", tTitle, "action", action, row0)
        noOverlap(where, "time", time, "cover", art, row0)

        // Colours, and that the pairs are pairs.
        expectColour(where, "title colour", tTitle.currentTextColor, ctx.colour(R.color.text_primary))
        expectColour(where, "time colour", time.currentTextColor, ctx.colour(R.color.text_secondary))
        expectColour(where, "artist colour", tArtist.currentTextColor, ctx.colour(R.color.text_secondary))
        expectColour(where, "action tint", ImageViewCompat.getImageTintList(action)?.defaultColor ?: 0,
            if (night) 0xFF5FD9B4.toInt() else 0xFF1C4771.toInt())
        // `Album art` is #1C4771 on both pages.
        expectColour(where, "cover plate", (art.background as? GradientDrawable)?.color?.defaultColor ?: 0,
            0xFF1C4771.toInt())

        // The footer, 24 under the last row, the list's own count.
        val footer = list.getChildAt(3) as TextView
        expect(where, "footer starts at the last row", topIn(footer, list),
            rectIn(list.getChildAt(2), list).bottom.toFloat())
        expect(where, "footer gap", footer.paddingTop, dp(24))
        if (footer.text.toString() != "Показаны последние 3 трека") {
            findings += "$where: footer reads '${footer.text}'"
        }
        expect(where, "list bottom padding", list.paddingBottom, dp(24))

        log += "$where: row ${row0.height}px, cover@${leftIn(art, row0)}, text@${leftIn(tTitle, row0)} " +
            "w${tTitle.width}, action@${leftIn(action, row0)}"
    }

    // ==================== multiline ====================

    /**
     * The frame wraps a title and an artist on purpose. Both keep every word, and
     * the row grows by the row's own line - 20 for the 16sp title (typography "B")
     * - not by the 28 the token would repeat. That is the G4a correction and this
     * is where it would silently come undone.
     */
    @Test
    fun wrappedRowsGrowByTheNaturalLine() {
        sweep { inflater, where, _, widthDp ->
            val ctx = inflater.context
            val dp = dpIn(ctx)
            val sp = { v: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, ctx.resources.displayMetrics) }
            val root = screen(
                inflater, widthDp,
                listOf(
                    HistoryTrack("MUSE", "CRYOGEN", 3L, "10:45"),
                    HistoryTrack("FRANC MOODY", longTitle, 2L, "10:27"),
                    HistoryTrack(longArtist, "ФАК Ю", 1L, "10:41"),
                ),
                show = R.id.history_list,
            )
            val list = root.findViewById<RecyclerView>(R.id.history_list)
            val one = list.getChildAt(0) as ViewGroup

            for ((index, what) in listOf(1 to "wrapped title", 2 to "wrapped artist")) {
                val row = list.getChildAt(index) as ViewGroup
                val t = row.findViewById<TextView>(R.id.tv_title)
                val a = row.findViewById<TextView>(R.id.tv_artist)
                val wrapped = if (index == 1) t else a

                if (wrapped.lineCount < 2) findings += "$where/$what: did not wrap (${wrapped.lineCount} line)"
                for (v in listOf(t, a)) {
                    val layout = v.layout ?: continue
                    for (line in 0 until layout.lineCount) {
                        if (layout.getEllipsisCount(line) > 0) findings += "$where/$what: ellipsized - rows never truncate"
                    }
                }
                if (wrapped.lineCount >= 2) {
                    val pitch = wrapped.layout.getLineTop(1) - wrapped.layout.getLineTop(0)
                    // G4b typography "B": a 20 title line, an 18 artist line.
                    val natural = if (wrapped === t) sp(20f) else sp(18f)
                    expect("$where/$what", "line pitch", pitch, natural, tolerance = 1.5f)
                }
                // Grows by real lines, around the same 14 of padding.
                val block = rectIn(a, row).bottom - topIn(t, row)
                expect("$where/$what", "row = block + 28", row.height, block + dp(28), tolerance = dp(1.5f))
                if (row.height <= one.height) findings += "$where/$what: the row did not grow"
                val mid = row.height / 2f
                val art = row.findViewById<View>(R.id.artwork)
                val action = row.findViewById<View>(R.id.btn_row_action)
                val time = row.findViewById<View>(R.id.tv_time)
                expect("$where/$what", "cover centred", topIn(art, row) + art.height / 2f, mid, tolerance = dp(1f))
                expect("$where/$what", "action centred", topIn(action, row) + action.height / 2f, mid, tolerance = dp(1f))
                expect("$where/$what", "time centred", topIn(time, row) + time.height / 2f, mid, tolerance = dp(1f))
                expect("$where/$what", "text x unchanged", leftIn(t, row), leftIn(one.findViewById(R.id.tv_title), one).toFloat())
                noOverlap("$where/$what", "text", t, "action", action, row)
                log += "$where/$what: ${wrapped.lineCount} lines, row ${row.height}px"
            }
        }
        report("MULTILINE")
    }

    // ==================== history-loading ====================

    @Test
    fun skeletonKeepsTheLoadedRowsAnchors() {
        sweep { inflater, where, _, widthDp ->
            val dp = dpIn(inflater.context)
            val loaded = screen(inflater, widthDp, tracks(1), show = R.id.history_list)
            val lRow = loaded.findViewById<RecyclerView>(R.id.history_list).getChildAt(0) as ViewGroup

            val root = screen(inflater, widthDp, emptyList(), show = R.id.history_skeleton)
            val skeleton = root.findViewById<ViewGroup>(R.id.history_skeleton)
            if (skeleton.childCount != 8) findings += "$where: ${skeleton.childCount} skeleton rows, the frame draws 8"
            if (descendants(root).any { it is ProgressBar }) {
                findings += "$where: a platform spinner is in the tree - it degrades to a refresh arrow with animations off"
            }

            val row0 = skeleton.getChildAt(0) as ViewGroup
            val row1 = skeleton.getChildAt(1) as ViewGroup
            expect(where, "first skeleton y", topIn(row0, skeleton), dp(16))
            expect(where, "skeleton x", leftIn(row0, skeleton), dp(16))
            expect(where, "skeleton height", row0.height, dp(76))
            expect(where, "skeleton gap", topIn(row1, skeleton) - rectIn(row0, skeleton).bottom, dp(8))

            val specs = listOf(
                Triple(R.id.skeleton_time, "time bar", floatArrayOf(14f, 32f, 34f, 12f, 0.45f)),
                Triple(R.id.skeleton_art, "cover", floatArrayOf(64f, 14f, 48f, 48f, 0.5f)),
                Triple(R.id.skeleton_title, "title bar", floatArrayOf(120f, 20f, 150f, 16f, 0.5f)),
                Triple(R.id.skeleton_artist, "artist bar", floatArrayOf(120f, 46f, 96f, 12f, 0.35f)),
                Triple(R.id.skeleton_action, "action ring", floatArrayOf((widthDp - 32 - 54).toFloat(), 18f, 40f, 40f, 0.5f)),
            )
            for ((id, name, s) in specs) {
                val v = row0.findViewById<View>(id)
                expect(where, "$name x", leftIn(v, row0), dp(s[0]))
                expect(where, "$name y", topIn(v, row0), dp(s[1]))
                expect(where, "$name width", v.width, dp(s[2]))
                expect(where, "$name height", v.height, dp(s[3]))
                if (abs(v.alpha - s[4]) > 0.001f) findings += "$where: $name opacity ${v.alpha}, frame ${s[4]}"
            }

            // Nothing moves sideways when the data lands.
            expect(where, "cover x = loaded cover x",
                leftIn(row0.findViewById(R.id.skeleton_art), row0), leftIn(lRow.findViewById(R.id.artwork), lRow).toFloat())
            expect(where, "title bar x = loaded text x",
                leftIn(row0.findViewById(R.id.skeleton_title), row0), leftIn(lRow.findViewById(R.id.tv_title), lRow).toFloat())
            expect(where, "ring x = loaded action x",
                leftIn(row0.findViewById(R.id.skeleton_action), row0), leftIn(lRow.findViewById(R.id.btn_row_action), lRow).toFloat())
            expect(where, "skeleton height = loaded one-line height", row0.height, lRow.height.toFloat(), tolerance = dp(1.5f))
        }
        report("LOADING")
    }

    // ==================== history-empty / history-error ====================

    @Test
    fun emptyAndErrorReproduceTheirFrames() {
        sweep { inflater, where, night, widthDp ->
            val ctx = inflater.context
            val dp = dpIn(ctx)
            for (state in listOf("empty", "error")) {
                val w = "$where/$state"
                val panelId = if (state == "empty") R.id.history_empty else R.id.history_error
                val root = screen(inflater, widthDp, emptyList(), show = panelId)
                val panel = root.findViewById<View>(panelId)
                val ill = root.findViewById<View>(
                    if (state == "empty") R.id.history_empty_illustration else R.id.history_error_illustration)
                val headline = root.findViewById<TextView>(
                    if (state == "empty") R.id.history_empty_title else R.id.history_error_title)
                val body = root.findViewById<TextView>(
                    if (state == "empty") R.id.history_empty_body else R.id.history_error_body)
                val button = root.findViewById<TextView>(
                    if (state == "empty") R.id.history_empty_action else R.id.history_error_action)
                val icon = (ill as ViewGroup).getChildAt(0) as ImageView

                expect(w, "panel top", topIn(panel, root), dp(64))
                // 168 / 296 / 340 / 412 in the frame, minus the 64 band.
                expect(w, "illustration y", topIn(ill, panel), dp(104))
                expect(w, "illustration size", ill.width, dp(96))
                expect(w, "illustration centred", leftIn(ill, panel) + ill.width / 2f, dp(widthDp) / 2f, tolerance = 1f)
                expect(w, "icon size", icon.width, dp(24))
                expect(w, "headline y", topIn(headline, panel), dp(232), tolerance = dp(1.5f))
                expect(w, "body y", topIn(body, panel), dp(276), tolerance = dp(2f))
                expect(w, "button y", topIn(button, panel), dp(348), tolerance = dp(2.5f))
                expect(w, "button height", button.height, dp(52))
                expect(w, "button width", button.width, dp(widthDp - 32))
                expect(w, "button x", leftIn(button, panel), dp(16))

                val (h, b, l) = if (state == "empty") {
                    Triple("Пока нет истории", "Треки появятся здесь, как только\nначнётся эфир.", "Обновить")
                } else {
                    Triple("Не удалось загрузить", "Проверьте подключение\nи попробуйте ещё раз.", "Повторить")
                }
                if (headline.text.toString() != h) findings += "$w: headline '${headline.text}'"
                if (body.text.toString() != b) findings += "$w: body '${body.text}'"
                if (button.text.toString() != l) findings += "$w: button '${button.text}'"
                if (body.lineCount != 2) findings += "$w: body is ${body.lineCount} lines, the frame draws 2"
                if (headline.lineCount != 1 && widthDp >= 360) findings += "$w: headline wrapped"
                if (!button.isClickable) findings += "$w: the button is not clickable"

                expectColour(w, "headline colour", headline.currentTextColor, ctx.colour(R.color.text_heading))
                expectColour(w, "body colour", body.currentTextColor, ctx.colour(R.color.text_secondary))
                expectColour(w, "illustration fill",
                    (ill.background as? GradientDrawable)?.color?.defaultColor ?: 0,
                    if (night) 0xFF1C4771.toInt() else 0xFFE8EAED.toInt())
                val iconColour = if (state == "empty") {
                    if (night) 0xFF5FD9B4.toInt() else 0xFF1C4771.toInt()
                } else {
                    if (night) 0xFFFF8A80.toInt() else 0xFFB3261E.toInt()
                }
                expectColour(w, "icon colour", ImageViewCompat.getImageTintList(icon)?.defaultColor ?: 0, iconColour)
                val (fill, label) = if (state == "empty") {
                    // Outline: surface fill, `text_heading` label.
                    (if (night) 0xFF142D47.toInt() else 0xFFF8F9FA.toInt()) to
                        (if (night) 0xFFF5F7FA.toInt() else 0xFF003056.toInt())
                } else {
                    // Primary: `primary` fill, its label.
                    (if (night) 0xFF5FD9B4.toInt() else 0xFF1C4771.toInt()) to
                        (if (night) 0xFF0F253E.toInt() else 0xFFFFFFFF.toInt())
                }
                expectColour(w, "button fill", (button.background as? GradientDrawable)?.color?.defaultColor ?: 0, fill)
                expectColour(w, "button label", button.currentTextColor, label)

                log += "$w: ill@${topIn(ill, panel)} head@${topIn(headline, panel)} body@${topIn(body, panel)} " +
                    "button@${topIn(button, panel)}"
            }
        }
        report("STATES")
    }

    // ==================== the footer's count ====================

    /**
     * Through the adapter's own text, on whatever locale this device is in. The QA
     * emulators run English, which is exactly the case a `<plurals>` resource got
     * wrong ("30 трека") and the first run of this test caught.
     */
    @Test
    fun theFooterReadsAsTheFrameAtThirtyAndStaysTrueBelowIt() {
        onMainActivity { activity ->
            val res = activity.resources
            val footer = { n: Int -> BroadcastHistoryFooterAdapter.textFor(res, n) }
            assertEquals("Показаны последние 30 треков", footer(30))
            assertEquals("Показан последний 1 трек", footer(1))
            assertEquals("Показаны последние 3 трека", footer(3))
            assertEquals("Показан последний 21 трек", footer(21))
            assertEquals("Показаны последние 12 треков", footer(12))
        }
    }

    /* ---------------------------------------------------------------- infra -- */

    private fun sweep(body: (LayoutInflater, String, Boolean, Int) -> Unit) {
        onMainActivity { activity ->
            for (night in listOf(false, true)) {
                for (widthDp in widthsDp) {
                    body(inflaterFor(activity, night, widthDp), "${if (night) "dark" else "light"}@${widthDp}dp", night, widthDp)
                }
            }
        }
    }

    private fun tracks(n: Int) = (1..n).map { HistoryTrack("MUSE", "CRYOGEN", it.toLong(), "00:00") }

    /**
     * The screen as the fragment builds it, with one frame made visible. Artwork is
     * never answered: this measures layout, not network.
     */
    private fun screen(inflater: LayoutInflater, widthDp: Int, tracks: List<HistoryTrack>, show: Int): ViewGroup {
        val ctx = inflater.context
        val dp = dpIn(ctx)
        val root = inflater.inflate(R.layout.fragment_broadcast_history, FrameLayout(ctx), false) as ViewGroup
        val list = root.findViewById<RecyclerView>(R.id.history_list)
        val rows = PlayerHistoryAdapter(
            artworkFor = { _, _ -> },
            cancelArtwork = {},
            rowLayout = R.layout.item_broadcast_history_track,
            onFindTrack = {},
        )
        val footer = BroadcastHistoryFooterAdapter()
        list.layoutManager = LinearLayoutManager(ctx)
        list.adapter = ConcatAdapter(rows, footer)
        list.addItemDecoration(BroadcastHistoryRowGap(ctx.resources.getDimensionPixelSize(R.dimen.history_screen_row_gap)))
        rows.submitList(tracks)
        footer.shownCount = tracks.size

        for (id in listOf(R.id.history_list, R.id.history_skeleton, R.id.history_empty, R.id.history_error)) {
            root.findViewById<View>(id).visibility = if (id == show) View.VISIBLE else View.GONE
        }
        root.measure(
            View.MeasureSpec.makeMeasureSpec(dp(widthDp).roundToInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dp(1600).roundToInt(), View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        return root
    }

    /**
     * A themed inflater that inflates the way the running screen does.
     *
     * A ContextThemeWrapper over the activity, not a bare configuration context.
     * The adapter inflates its rows with `LayoutInflater.from(parent.context)`, and
     * only a wrapper hands that call the activity's own inflater - with the
     * AppCompat factory that turns `ImageView` into `AppCompatImageView` and makes
     * `app:tint` mean something. A bare configuration context hands back a plain
     * inflater: the ring's arrow comes out untinted, which the running app, whose
     * rows sit in the activity's own context, never does. The first run of this
     * test measured exactly that and called it a tint bug.
     */
    private fun inflaterFor(activity: MainActivity, night: Boolean, widthDp: Int): LayoutInflater {
        val cfg = Configuration(activity.resources.configuration)
        cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        cfg.screenWidthDp = widthDp
        cfg.smallestScreenWidthDp = minOf(cfg.smallestScreenWidthDp, widthDp)
        val themed = android.view.ContextThemeWrapper(activity, R.style.AppTheme)
        themed.applyOverrideConfiguration(cfg)
        return LayoutInflater.from(themed)
    }

    private fun onMainActivity(block: (MainActivity) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).let { scenario ->
            try {
                scenario.onActivity(block)
            } finally {
                try { scenario.close() } catch (e: Throwable) {
                    android.util.Log.w("HISTORYQA", "activity close timed out; checks already complete", e)
                }
            }
        }
    }

    private fun descendants(v: View): List<View> =
        listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { descendants(g.getChildAt(it)) } } ?: emptyList())

    private fun dpIn(ctx: Context): (Number) -> Float {
        val density = ctx.resources.displayMetrics.density
        return { v -> v.toFloat() * density }
    }

    private fun Context.colour(id: Int) = ContextCompat.getColor(this, id)

    private fun rectIn(v: View, ancestor: View): Rect {
        val r = Rect(v.left, v.top, v.right, v.bottom)
        var p = v.parent
        while (p is View && p !== ancestor) { r.offset(p.left, p.top); p = p.parent }
        return r
    }

    private fun topIn(v: View, ancestor: View) = rectIn(v, ancestor).top
    private fun leftIn(v: View, ancestor: View) = rectIn(v, ancestor).left

    private fun expect(where: String, what: String, actual: Int, expected: Float, tolerance: Float = 1f) =
        expect(where, what, actual.toFloat(), expected, tolerance)

    private fun expect(where: String, what: String, actual: Float, expected: Float, tolerance: Float = 1f) {
        if (abs(actual - expected) > tolerance) {
            findings += "$where: $what is ${actual}px, frozen design says ${expected.roundToInt()}px"
        }
    }

    private fun expectColour(where: String, what: String, actual: Int, expected: Int) {
        if (actual != expected) {
            findings += "$where: $what is #${Integer.toHexString(actual)}, frozen design says #${Integer.toHexString(expected)}"
        }
    }

    private fun noOverlap(where: String, an: String, a: View, bn: String, b: View, root: View) {
        if (Rect.intersects(rectIn(a, root), rectIn(b, root))) findings += "$where: $an overlaps $bn"
    }

    private fun report(tag: String) {
        android.util.Log.i("HISTORYQA", "==== $tag (API ${Build.VERSION.SDK_INT}) ====")
        log.forEach { android.util.Log.i("HISTORYQA", "  $it") }
        findings.forEach { android.util.Log.e("HISTORYQA", "  FINDING $it") }
        assertTrue(
            "$tag findings on API ${Build.VERSION.SDK_INT}:\n" + findings.joinToString("\n") { "  $it" },
            findings.isEmpty(),
        )
    }
}
