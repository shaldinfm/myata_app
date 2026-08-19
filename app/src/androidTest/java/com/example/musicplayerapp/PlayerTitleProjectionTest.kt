package com.example.musicplayerapp

import android.graphics.Rect
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the PLAYER title actually does to a long track name, measured rather than
 * guessed.
 *
 * The frozen `Player Section` is hand-positioned: title, artist and controls are
 * each anchored by their own absolute offset from the page top, so a title that
 * needs a second line has nowhere to put it by default - it would draw straight
 * over the artist. This measures the real bands at 390dp so the rule can be chosen
 * from numbers instead of from a screenshot.
 *
 * Mostly reported rather than asserted - `PlayerLayoutTest` owns the frozen
 * geometry. The one thing pinned here is the complaint that started it: at 390dp
 * the reported title must render whole, not "WHERE CAN I PUT ALL MY ...".
 */
@RunWith(AndroidJUnit4::class)
class PlayerTitleProjectionTest {

    private val widthDp = 390

    private val cases = listOf(
        "short" to "FLAME",
        "medium" to "ENJOY THE SILENCE",
        "reported" to "WHERE CAN I PUT ALL MY LOVE",
        "very long" to "КРАСНОЗНАМЁННАЯ ДИВИЗИЯ ИМЕНИ МОЕЙ БАБУШКИ",
        "very long en" to "THE MAN WHO SOLD THE WORLD AND EVERYTHING ELSE HE COULD FIND",
    )

    @Test
    fun projectTitleGeometryAt390dp() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val inflater = activity.layoutInflater
                val dm = activity.resources.displayMetrics
                val density = dm.density
                val widthPx = (widthDp * density).toInt()
                fun toDp(px: Int) = px / density

                val out = mutableListOf<String>()
                out += "==== PLAYER TITLE PROJECTION  API ${Build.VERSION.SDK_INT}  " +
                    "${widthDp}dp @ ${dm.densityDpi}dpi ===="

                // The fixed bands, from the layout itself.
                val ref = measured(inflater, widthPx) {}
                val art = ref.findViewById<View>(R.id.artwork_card)
                val artist = ref.findViewById<View>(R.id.main_author)
                val controls = ref.findViewById<View>(R.id.player_controls)
                val artBottom = toDp(rectIn(art, ref).bottom)
                val artistTop = toDp(rectIn(artist, ref).top)
                val controlsTop = toDp(rectIn(controls, ref).top)
                out += "bands: artworkBottom=%.1f  artistTop=%.1f  controlsTop=%.1f".format(
                    artBottom, artistTop, controlsTop
                )

                for (maxLines in listOf(1, 2, 3)) {
                    out += "---- maxLines=$maxLines ----"
                    for ((label, text) in cases) {
                        val page = measured(inflater, widthPx) { root ->
                            val t = root.findViewById<TextView>(R.id.main_song)
                            t.maxLines = maxLines
                            t.text = text
                        }
                        val title = page.findViewById<TextView>(R.id.main_song)
                        val r = rectIn(title, page)
                        val top = toDp(r.top)
                        val bottom = toDp(r.bottom)
                        val layout = title.layout
                        val ellipsised = (0 until (layout?.lineCount ?: 0))
                            .sumOf { layout!!.getEllipsisCount(it) }
                        val shown = layout?.let { l ->
                            (0 until l.lineCount).joinToString(" | ") { i ->
                                title.text.subSequence(l.getLineStart(i), l.getLineEnd(i)).toString().trim()
                            }
                        } ?: ""
                        val overlap = bottom - artistTop
                        out += ("  %-13s lines=%d top=%.1f bottom=%.1f h=%.1f ellipsis=%d " +
                            "overlapArtist=%+.1f  [%s]").format(
                            label, title.lineCount, top, bottom, toDp(title.height),
                            ellipsised, overlap, shown.take(70)
                        )
                    }
                }

                out.forEach { android.util.Log.i("TITLEQA", it) }

                // The reported case, against the layout as it actually ships: two
                // lines, nothing cut, and clear of the artwork above it.
                val shipped = measured(inflater, widthPx) { root ->
                    root.findViewById<TextView>(R.id.main_song).text =
                        "WHERE CAN I PUT ALL MY LOVE"
                }
                val t = shipped.findViewById<TextView>(R.id.main_song)
                val ell = t.layout?.let { l ->
                    (0 until l.lineCount).sumOf { l.getEllipsisCount(it) }
                } ?: 0
                val top = toDp(rectIn(t, shipped).top)

                org.junit.Assert.assertEquals(
                    "the reported title should use both available lines", 2, t.lineCount
                )
                org.junit.Assert.assertEquals(
                    "the reported title should not be ellipsised at ${widthDp}dp", 0, ell
                )
                org.junit.Assert.assertTrue(
                    "a two-line title must stay clear of the artwork: top=$top artworkBottom=$artBottom",
                    top >= artBottom,
                )
            }
        }
    }

    private fun measured(
        inflater: LayoutInflater,
        widthPx: Int,
        prepare: (ViewGroup) -> Unit,
    ): ViewGroup {
        val root = inflater.inflate(R.layout.fragment_myata_stream, null) as ViewGroup
        prepare(root)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(widthPx * 3, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        return root
    }

    private fun rectIn(v: View, ancestor: View): Rect {
        val r = Rect(v.left, v.top, v.right, v.bottom)
        var p = v.parent
        while (p is View && p !== ancestor) { r.offset(p.left, p.top); p = p.parent }
        return r
    }
}
