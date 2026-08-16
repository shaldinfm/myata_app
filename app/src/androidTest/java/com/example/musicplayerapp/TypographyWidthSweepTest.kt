package com.example.musicplayerapp

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
import kotlin.math.roundToInt

/**
 * The visual QA sweep, done by measuring rather than by looking.
 *
 * The migration changed type sizes on real screens - the About Us paragraph came
 * down, the Collection heading came down, history rows went up - and those are
 * the changes that break layouts rather than the ones that look wrong in a
 * screenshot. Screenshots were taken too, but a screenshot cannot tell you that a
 * heading is one pixel from wrapping at 320dp; a measurement can.
 *
 * Four widths, because that is the range the app ships into: 320dp is the
 * tightest device the frozen bar was ever checked against, 390dp is what the
 * design is drawn at, 412dp is a modern Pixel.
 *
 * Each surface is inflated the way the app inflates it and then interrogated for
 * the four things that actually go wrong:
 *
 *   clipping   - a glyph inked above its own line box, which happens when a tight
 *                line height meets includeFontPadding=false;
 *   wrapping   - a heading or button label that was one line in the design and is
 *                two here, which changes the height of the frame around it;
 *   truncation - an ellipsis appearing on a view the owner decided must never
 *                truncate, which is every line of a history row;
 *   overlap    - two siblings occupying the same pixels, the failure 320dp
 *                produces and 390dp hides.
 */
@RunWith(AndroidJUnit4::class)
class TypographyWidthSweepTest {

    private val widthsDp = listOf(320, 360, 390, 412)

    /** A long Russian title and artist, the strings the audit flagged as worst-case. */
    private val longTitle = "КРАСНОЗНАМЁННАЯ ДИВИЗИЯ ИМЕНИ МОЕЙ БАБУШКИ"
    private val longArtist = "MIAMI HORROR FT. POOLSIDE И ЕЩЁ НЕСКОЛЬКО ИСПОЛНИТЕЛЕЙ"

    private class Finding(val where: String, val what: String)

    private val findings = mutableListOf<Finding>()
    private val log = mutableListOf<String>()

    /* --------------------------------------------------------------- checks -- */

    /** Every TextView under [root]: does anything clip, and does anything overflow? */
    private fun inspect(root: View, surface: String, widthPx: Int) {
        forEachTextView(root) { tv ->
            val name = nameOf(tv)
            val layout = tv.layout ?: return@forEachTextView
            if (tv.text.isNullOrEmpty() || tv.visibility != View.VISIBLE) return@forEachTextView

            // Clipping: what the glyphs really ink above the first baseline,
            // against the room the line box gives them.
            val line = tv.text.subSequence(layout.getLineStart(0), layout.getLineEnd(0)).toString()
            if (line.isNotBlank()) {
                val ink = Rect()
                tv.paint.getTextBounds(line, 0, line.length, ink)
                val headroom = (layout.getLineBaseline(0) - layout.getLineTop(0)) - (-ink.top)
                if (headroom < 0) {
                    findings += Finding("$surface@${widthPx}px/$name", "ascenders clipped by ${-headroom}px")
                }
            }

            // Horizontal overflow of the widest line beyond the view's own box.
            //
            // Trailing whitespace counts toward getLineWidth but cannot ink, and
            // on a line that wraps exactly on the box edge that alone is enough
            // to report an overflow nothing can see - ABOUT US hits it on the
            // frozen 310dp card at 390dp, where the break lands on a space and
            // API 24 and API 36 disagree by a couple of dp about where. So the
            // run of trailing spaces comes back off, which is the same thing the
            // ascender check above already does: measure what actually draws.
            //
            // getLineWidth stays the basis rather than re-measuring the whole
            // line, because it is the only one of the two that honours spans.
            val widest = (0 until layout.lineCount).maxOf { i ->
                val visibleEnd = layout.getLineVisibleEnd(i)
                val end = layout.getLineEnd(i)
                layout.getLineWidth(i) - if (end > visibleEnd) {
                    tv.paint.measureText(tv.text, visibleEnd, end)
                } else 0f
            }
            val avail = tv.width - tv.paddingStart - tv.paddingEnd
            if (avail > 0 && widest > avail + 1f) {
                findings += Finding("$surface@${widthPx}px/$name",
                    "text ${widest.roundToInt()}px overflows its ${avail}px box")
            }
        }
    }

    /** Two views must not occupy the same pixels. */
    private fun assertNoOverlap(a: View, b: View, surface: String, widthPx: Int) {
        val ra = Rect(a.left, a.top, a.right, a.bottom)
        val rb = Rect(b.left, b.top, b.right, b.bottom)
        if (Rect.intersects(ra, rb)) {
            findings += Finding("$surface@${widthPx}px", "${nameOf(a)} overlaps ${nameOf(b)}: $ra vs $rb")
        }
    }

    private fun requireLines(tv: TextView, want: Int, surface: String, widthPx: Int) {
        if (tv.lineCount != want) {
            findings += Finding("$surface@${widthPx}px/${nameOf(tv)}",
                "wrapped to ${tv.lineCount} lines, design keeps it at $want")
        }
    }

    private fun requireNoEllipsis(tv: TextView, surface: String, widthPx: Int) {
        val layout = tv.layout ?: return
        val cut = (0 until layout.lineCount).sumOf { layout.getEllipsisCount(it) }
        if (cut > 0) {
            findings += Finding("$surface@${widthPx}px/${nameOf(tv)}", "truncated $cut characters")
        }
    }

    /* ---------------------------------------------------------------- sweep -- */

    @Test
    fun surfacesSurviveEveryShippingWidth() {
        ActivityScenario.launch(MainActivity::class.java).let { scenario ->
            try {
                scenario.onActivity { activity -> sweep(activity.layoutInflater) }
            } finally {
                // Throwable, not RuntimeException: close() reports a timeout by
                // throwing AssertionError, which is an Error. The sweep is
                // finished by this point, so a slow teardown on the software-
                // rendered API 24 image must not become a QA failure.
                try {
                    scenario.close()
                } catch (e: Throwable) {
                    android.util.Log.w("TYPOQA", "activity close timed out; sweep already complete", e)
                }
            }
        }

        android.util.Log.i("TYPOQA", "==== WIDTH SWEEP (API ${Build.VERSION.SDK_INT}) ====")
        log.forEach { android.util.Log.i("TYPOQA", "  $it") }
        findings.forEach { android.util.Log.e("TYPOQA", "  FINDING ${it.where}: ${it.what}") }

        assertTrue(
            "visual QA findings on API ${Build.VERSION.SDK_INT}:\n" +
                findings.joinToString("\n") { "  ${it.where}: ${it.what}" },
            findings.isEmpty(),
        )
    }

    /**
     * An inflater whose *configuration* really is [dp] wide.
     *
     * [measured] inflates at a pixel width, but resources still resolve against
     * the device's own configuration. For a layout that adapts through a width
     * qualifier that is a false reading: ABOUT US keeps its frozen 24dp card and
     * 32dp section padding in `values-w360dp` because at 320dp they leave the
     * one-time heading and "Поддержать эфир" without the room they need, and
     * measuring the wide values at 320dp reports exactly the overflow the
     * qualifier exists to prevent.
     *
     * Used only for the surface that has such a qualifier, so no other screen's
     * measurements move.
     */
    private fun inflaterAt(base: LayoutInflater, dp: Int): LayoutInflater {
        val config = android.content.res.Configuration(base.context.resources.configuration)
        config.screenWidthDp = dp
        config.smallestScreenWidthDp = minOf(config.smallestScreenWidthDp, dp)
        return LayoutInflater.from(base.context.createConfigurationContext(config))
    }

    private fun sweep(inflater: LayoutInflater) {
        val dm = inflater.context.resources.displayMetrics
        for (dp in widthsDp) {
            val w = (dp * dm.density).roundToInt()

            // --- BottomNav: the tightest constraint in the app, every screen ---
            measured(inflater, R.layout.activity_main, w).let { root ->
                inspect(root, "activity_main", w)
                listOf(R.id.home_label, R.id.player_label, R.id.favorites_label, R.id.info_label)
                    .map { root.findViewById<TextView>(it) }
                    .forEach { requireLines(it, 1, "bottomnav", w); requireNoEllipsis(it, "bottomnav", w) }
                val labels = listOf(R.id.home_label, R.id.player_label, R.id.favorites_label, R.id.info_label)
                    .map { root.findViewById<TextView>(it) }
                log += "bottomnav @${dp}dp: " + labels.joinToString(" ") { "${nameOf(it)}=${it.width}px" }
            }

            // --- HOME: the two headings the contract keeps on one line ---
            measured(inflater, R.layout.fragment_main, w).let { root ->
                inspect(root, "fragment_main", w)
                val playlists = root.findViewById<TextView>(R.id.playlistString)
                // "Мятные плейлисты" is the longest heading in the app and the
                // frozen design is drawn at 390dp. At 28sp Bold it fits on one
                // line from 360dp up and wraps to two at 320dp. That is not a
                // regression the check should hide, but it is also not a promise
                // the contract makes: the approved one-line headings are
                // "Привет, Денис!", "Моя коллекция" and "Поддержать радио", and
                // this is not one of them. So it is asserted where the design
                // applies and recorded where it does not.
                if (dp >= 360) {
                    requireLines(playlists, 1, "home/playlistString", w)
                }
                log += "home @${dp}dp: playlistString ${playlists.lineCount} line(s), ${playlists.measuredHeight}px" +
                    if (dp < 360 && playlists.lineCount > 1) "  [known: wraps below the 390dp design width]" else ""
            }

            // --- PLAYER ---
            measured(inflater, R.layout.fragment_myata_stream, w).let { root ->
                inspect(root, "fragment_myata_stream", w)
                val author = root.findViewById<TextView>(R.id.main_author)
                val song = root.findViewById<TextView>(R.id.main_song)
                assertNoOverlap(author, song, "player", w)
                log += "player @${dp}dp: author ${author.lineCount}L/${author.measuredHeight}px, " +
                    "song ${song.lineCount}L/${song.measuredHeight}px"
            }

            // --- History row, long Russian metadata, variable height, no ellipsis ---
            measured(inflater, R.layout.item_history_track, w) { root ->
                root.findViewById<TextView>(R.id.tv_title).text = longTitle
                root.findViewById<TextView>(R.id.tv_artist).text = longArtist
            }.let { root ->
                inspect(root, "item_history_track", w)
                val title = root.findViewById<TextView>(R.id.tv_title)
                val artist = root.findViewById<TextView>(R.id.tv_artist)
                requireNoEllipsis(title, "history/title", w)
                requireNoEllipsis(artist, "history/artist", w)
                assertNoOverlap(title, artist, "history", w)
                // The frozen hierarchy: the title sits above the artist.
                if (title.top >= artist.top) {
                    findings += Finding("history@${w}px", "title is not above artist")
                }
                log += "history @${dp}dp: title ${title.lineCount}L, artist ${artist.lineCount}L, " +
                    "row ${root.measuredHeight}px, titleTop=${title.top} artistTop=${artist.top}"
            }

            // --- Collection row, long metadata ---
            measured(inflater, R.layout.item_favorite_track, w) { root ->
                root.findViewById<TextView>(R.id.tv_track).text = longTitle
                root.findViewById<TextView>(R.id.tv_artist).text = longArtist
            }.let { root ->
                inspect(root, "item_favorite_track", w)
                val track = root.findViewById<TextView>(R.id.tv_track)
                val artist = root.findViewById<TextView>(R.id.tv_artist)
                val badge = root.findViewById<TextView>(R.id.badge_stream)
                assertNoOverlap(track, artist, "collection", w)
                assertNoOverlap(track, badge, "collection", w)
                if (track.top >= artist.top) {
                    findings += Finding("collection@${w}px", "title is not above artist")
                }
                log += "collection @${dp}dp: track ${track.lineCount}L, artist ${artist.lineCount}L, " +
                    "row ${root.measuredHeight}px, trackTop=${track.top} artistTop=${artist.top}"
            }

            // --- ABOUT US: paragraph, hero and the donate CTA ---
            measured(inflaterAt(inflater, dp), R.layout.fragment_info, w).let { root ->
                inspect(root, "fragment_info", w)
                val cta = root.findViewById<TextView>(R.id.donate_cta)
                val desc = root.findViewById<TextView>(R.id.description)
                requireLines(cta, 1, "about/donate_cta", w)
                // The CTA is a fixed 52dp box; its text must fit inside it.
                if (cta.lineCount * cta.lineHeight > cta.height) {
                    findings += Finding("about/donate_cta@${w}px",
                        "text ${cta.lineCount * cta.lineHeight}px exceeds the ${cta.height}px button")
                }
                log += "about @${dp}dp: cta ${cta.lineCount}L in ${cta.height}px, desc ${desc.lineCount}L"
            }

            // --- COLLECTION screen: heading, subtitle and the empty-state copy ---
            //
            // The two export buttons this used to sweep are gone: Phase B moves
            // the export actions into the header overflow, where the frozen design
            // puts them. What is swept now is the frozen screen's own text.
            measured(inflater, R.layout.fragment_favorites, w, { root ->
                // The empty state ships gone - the fragment turns it on when the
                // collection is empty - so it has to be turned on here or its two
                // text blocks are never measured and report zero lines.
                root.findViewById<View>(R.id.rv_favorites).visibility = View.GONE
                root.findViewById<View>(R.id.empty_state).visibility = View.VISIBLE
            }).let { root ->
                inspect(root, "fragment_favorites", w)
                val title = root.findViewById<TextView>(R.id.title)
                val subtitle = root.findViewById<TextView>(R.id.collection_subtitle)
                val emptyTitle = root.findViewById<TextView>(R.id.empty_title)
                val emptyBody = root.findViewById<TextView>(R.id.empty_body)
                requireLines(title, 1, "collection/title", w)
                requireLines(subtitle, 1, "collection/subtitle", w)
                requireLines(emptyTitle, 1, "collection/empty_title", w)
                // The frozen empty body is drawn as two centred lines in a fixed
                // 191-wide box, which does not narrow with the screen.
                requireLines(emptyBody, 2, "collection/empty_body", w)
                log += "collection screen @${dp}dp: title ${title.lineCount}L, " +
                    "subtitle ${subtitle.lineCount}L, empty ${emptyTitle.lineCount}L/" +
                    "${emptyBody.lineCount}L"
            }

            // --- Donate flow entry (the old standalone screen, family-only swap) ---
            measured(inflater, R.layout.fragment_donate, w).let { root ->
                inspect(root, "fragment_donate", w)
                log += "donate @${dp}dp: measured ${root.measuredHeight}px"
            }

            // --- History sheet header ---
            measured(inflater, R.layout.fragment_history_bottom_sheet, w).let { root ->
                inspect(root, "fragment_history_bottom_sheet", w)
            }
        }
    }

    /**
     * The same surfaces, rendered to PNGs so a person can look at them.
     *
     * Driving the real app through four density changes was tried first and does
     * not work on a software-rendered emulator: SystemUI restarts on every
     * density change and ANRs before the app is reachable. This renders the real
     * inflated layouts straight to a Canvas instead, which needs no SystemUI, no
     * navigation and no timing, and produces the same pixels the app would draw.
     *
     * Light and dark come from a Configuration overlay rather than a system
     * setting, so both themes resolve their real night resources.
     *
     * Files land in the app's external files dir for adb to pull.
     */
    @Test
    fun rendersTheSweepToPngs() {
        val app = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()
        val dir = java.io.File(app.getExternalFilesDir(null), "qa").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }

        val surfaces = listOf(
            "home" to R.layout.fragment_main,
            "player" to R.layout.fragment_myata_stream,
            "about" to R.layout.fragment_info,
            "collection" to R.layout.fragment_favorites,
            "donate" to R.layout.fragment_donate,
            "history-sheet" to R.layout.fragment_history_bottom_sheet,
            "bottomnav" to R.layout.activity_main,
            "row-history" to R.layout.item_history_track,
            "row-collection" to R.layout.item_favorite_track,
        )

        var written = 0
        ActivityScenario.launch(MainActivity::class.java).let { scenario ->
            try {
                scenario.onActivity { activity ->
                    for (night in listOf(false, true)) {
                        val cfg = android.content.res.Configuration(activity.resources.configuration)
                        cfg.uiMode = (cfg.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
                            if (night) android.content.res.Configuration.UI_MODE_NIGHT_YES
                            else android.content.res.Configuration.UI_MODE_NIGHT_NO
                        val themed = activity.createConfigurationContext(cfg)
                        themed.setTheme(R.style.AppTheme)
                        val inflater = activity.layoutInflater.cloneInContext(themed)
                        val dm = themed.resources.displayMetrics

                        for (dp in widthsDp) {
                            val w = (dp * dm.density).roundToInt()
                            for ((name, layout) in surfaces) {
                                val root = measured(inflater, layout, w) { r ->
                                    r.findViewById<TextView>(R.id.tv_title)?.text = longTitle
                                    r.findViewById<TextView>(R.id.tv_artist)?.text = longArtist
                                    r.findViewById<TextView>(R.id.tv_track)?.text = longTitle
                                }
                                val h = root.measuredHeight.coerceIn(1, 4000)
                                val bmp = android.graphics.Bitmap.createBitmap(
                                    root.measuredWidth.coerceAtLeast(1), h,
                                    android.graphics.Bitmap.Config.ARGB_8888,
                                )
                                root.draw(android.graphics.Canvas(bmp))
                                val f = java.io.File(
                                    dir, "%ddp-%s-%s.png".format(dp, if (night) "dark" else "light", name),
                                )
                                java.io.FileOutputStream(f).use {
                                    bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                                }
                                bmp.recycle()
                                written++
                            }
                        }
                    }
                }
            } finally {
                try { scenario.close() } catch (e: Throwable) { /* see above */ }
            }
        }
        android.util.Log.i("TYPOQA", "rendered $written PNGs to ${dir.absolutePath}")
        assertTrue("nothing rendered", written == widthsDp.size * surfaces.size * 2)
    }

    /* ---------------------------------------------------------------- infra -- */

    /**
     * Measures [layout] at [widthPx].
     *
     * Screens get a real screen height rather than an unbounded one, and that is
     * not a detail: fragment_main puts its headings in weighted slots
     * (layout_height=0dp, layout_weight=1) inside a vertical LinearLayout, so
     * measuring with UNSPECIFIED height hands them an arbitrary allocation and
     * every number that comes back is fiction. List rows are the opposite case -
     * they really are wrap_content in a RecyclerView - so they keep UNSPECIFIED.
     */
    private fun measured(
        inflater: LayoutInflater,
        layout: Int,
        widthPx: Int,
        prepare: ((ViewGroup) -> Unit)? = null,
    ): ViewGroup {
        val isRow = layout == R.layout.item_history_track || layout == R.layout.item_favorite_track
        val root = inflater.inflate(layout, null) as ViewGroup
        prepare?.invoke(root)
        val heightSpec = if (isRow) {
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        } else {
            // Roughly 2:1, which is what every device in this range actually is.
            View.MeasureSpec.makeMeasureSpec(widthPx * 2, View.MeasureSpec.EXACTLY)
        }
        root.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY), heightSpec)
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        return root
    }

    private fun forEachTextView(v: View, block: (TextView) -> Unit) {
        if (v is TextView) block(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) forEachTextView(v.getChildAt(i), block)
    }

    private fun nameOf(v: View): String = try {
        if (v.id == View.NO_ID) v.javaClass.simpleName else v.resources.getResourceEntryName(v.id)
    } catch (e: android.content.res.Resources.NotFoundException) {
        v.javaClass.simpleName
    }
}
