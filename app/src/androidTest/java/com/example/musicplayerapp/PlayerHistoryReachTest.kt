package com.example.musicplayerapp

import android.content.res.Configuration
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.musicplayerapp.adapters.PlayerHistoryAdapter
import com.example.musicplayerapp.data.HistoryTrack
import com.example.musicplayerapp.ui.BroadcastHistoryState
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

/**
 * "Показать ещё" can actually be reached on a real screen.
 *
 * `PlayerHistoryLayoutTest` already proves the button is made VISIBLE over a
 * history of 30 - and it was. It measures the page on its own, though, and on a
 * device the page is not on its own: the pager is constrained to the bottom of
 * the screen and the shell's BottomNavBar is drawn *over* its last ~76dp. The
 * page's own bottom padding was 16dp, so the end of the scrolled page lived
 * underneath the bar. The button is the last thing on the page and 54dp tall,
 * which put essentially all of it under the bar - visible to a test, invisible
 * and untappable to a reader, which is exactly the report.
 *
 * So this measures the two together. The page is given the viewport the pager
 * really gives it - the shell's height less the header, the swipe dots and the
 * gap under them - it is scrolled to the end, and the button's bottom edge is
 * compared against the top of the bar in the same coordinates.
 *
 * Heights as well as widths, because the amount of the page that ends up under
 * the bar does not depend on the width at all: what matters is that the last
 * screenful is the one with the button in it, on every shipping viewport.
 */
@RunWith(AndroidJUnit4::class)
class PlayerHistoryReachTest {

    private val widthsDp = listOf(320, 360, 390, 412)
    private val heightsDp = listOf(568, 640, 732, 800, 915)

    private val findings = mutableListOf<String>()
    private val log = mutableListOf<String>()

    @Test
    fun showMoreClearsTheBottomNavigationBar() {
        ActivityScenario.launch(MainActivity::class.java).let { scenario ->
            try {
                scenario.onActivity { activity ->
                    for (night in listOf(false, true)) {
                        val inflater = inflaterFor(activity, night)
                        for (widthDp in widthsDp) {
                            for (heightDp in heightsDp) {
                                check(inflater, if (night) "dark" else "light", widthDp, heightDp)
                            }
                        }
                    }
                }
            } finally {
                // See TypographyWidthSweepTest: close() reports a teardown timeout
                // by throwing on the software-rendered API 24 image.
                try { scenario.close() } catch (e: Throwable) {
                    android.util.Log.w("HISTQA", "activity close timed out; checks complete", e)
                }
            }
        }

        android.util.Log.i("HISTQA", "==== HISTORY REACH (API ${Build.VERSION.SDK_INT}) ====")
        log.forEach { android.util.Log.i("HISTQA", "  $it") }
        findings.forEach { android.util.Log.e("HISTQA", "  FINDING $it") }

        assertTrue(
            "\"Показать ещё\" is unreachable on API ${Build.VERSION.SDK_INT}:\n" +
                findings.joinToString("\n") { "  $it" },
            findings.isEmpty(),
        )
    }

    private fun check(inflater: LayoutInflater, theme: String, widthDp: Int, heightDp: Int) {
        val dm = inflater.context.resources.displayMetrics
        val widthPx = (widthDp * dm.density).roundToInt()
        val heightPx = (heightDp * dm.density).roundToInt()
        val where = "$theme@${widthDp}x${heightDp}dp"

        /* ---- the shell: where the bar is, and how tall it is ---- */

        val shell = inflater.inflate(R.layout.activity_main, null) as ViewGroup
        measure(shell, widthPx, heightPx)
        val bar = shell.findViewById<View>(R.id.bottomNavView)
        val barTop = bar.top
        val barHeight = bar.height

        /* ---- the pager's real viewport inside the PLAYER shell ---- */

        val playerShell = inflater.inflate(R.layout.fragment_player, null) as ViewGroup
        measure(playerShell, widthPx, heightPx)
        val pager = playerShell.findViewById<View>(R.id.viewPager)
        val pageTop = pager.top
        val pageHeight = pager.height

        /* ---- the page, with a history that has more behind it ---- */

        val page = populatedPage(inflater, widthPx, pageHeight)
        val scroll = page.findViewById<NestedScrollView>(R.id.stream_scroll)
        val more = page.findViewById<View>(R.id.history_show_more)

        if (more.visibility != View.VISIBLE) {
            findings += "$where: the button is not even offered over a history of 30"
            return
        }

        // What MyataStreamFragment.applyBottomChromeInset does, from the height
        // this shell really measured. Applied after the first layout for the same
        // reason the fragment applies it from a layout listener: the bar is
        // content-sized and its height is not known before it is measured.
        scroll.setPadding(scroll.paddingLeft, scroll.paddingTop, scroll.paddingRight, barHeight)
        measure(page, widthPx, pageHeight)

        // All the way down, which is where the button lives.
        //
        // The scroll range is computed rather than asked for with a large y:
        // NestedScrollView clamps by testing `my + n > child`, which overflows on
        // anything near Int.MAX_VALUE and hands back the sentinel unclamped.
        val content = scroll.getChildAt(0)
        val maxScroll = (content.height + scroll.paddingTop + scroll.paddingBottom - scroll.height)
            .coerceAtLeast(0)
        scroll.scrollTo(0, maxScroll)

        val moreBottomOnScreen = pageTop + topIn(more, scroll) + more.height - scroll.scrollY
        val hidden = moreBottomOnScreen - barTop

        log += "$where: bar top $barTop (h $barHeight), page top $pageTop h $pageHeight, " +
            "button bottom $moreBottomOnScreen, clearance ${-hidden}px"

        if (hidden > 0) {
            findings += "$where: ${hidden}px of \"Показать ещё\" is under the bottom nav bar - " +
                "it cannot be seen or tapped at the end of the page"
        }

        // And it must be on screen at all, not merely above the bar.
        if (moreBottomOnScreen - more.height < 0) {
            findings += "$where: the button's top is off the top of the viewport at full scroll"
        }
    }

    /** The page with 30 entries and the default three revealed - the real state. */
    private fun populatedPage(inflater: LayoutInflater, widthPx: Int, heightPx: Int): ViewGroup {
        val page = inflater.inflate(R.layout.fragment_myata_stream, null) as ViewGroup
        val list = page.findViewById<RecyclerView>(R.id.history_list)
        val adapter = PlayerHistoryAdapter(artworkFor = { _, _ -> }, cancelArtwork = {})
        list.layoutManager = LinearLayoutManager(inflater.context)
        list.adapter = adapter

        val tracks = List(30) {
            HistoryTrack(
                artist = "ARTIST $it",
                track = "TRACK $it",
                playedAt = 1_700_000_000L - it * 200L,
                playedAtFormatted = "00:00",
            )
        }
        val state = BroadcastHistoryState.of(tracks.size, isLoading = false)
        list.visibility = View.VISIBLE
        page.findViewById<View>(R.id.history_show_more).visibility =
            if (state.isShowMoreVisible) View.VISIBLE else View.GONE
        adapter.submitList(tracks.take(state.visibleCount))
        @Suppress("DEPRECATION")
        adapter.notifyDataSetChanged()

        measure(page, widthPx, heightPx)
        return page
    }

    private fun measure(view: View, widthPx: Int, heightPx: Int) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    /** [view]'s top in [ancestor]'s content coordinates, before scrolling. */
    private fun topIn(view: View, ancestor: View): Int {
        var top = 0
        var current: View = view
        while (current !== ancestor) {
            top += current.top
            current = current.parent as? View ?: return top
        }
        return top
    }

    private fun inflaterFor(activity: MainActivity, night: Boolean): LayoutInflater {
        val cfg = Configuration(activity.resources.configuration)
        cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        val themed = activity.createConfigurationContext(cfg)
        themed.setTheme(R.style.AppTheme)
        return activity.layoutInflater.cloneInContext(themed)
    }
}
