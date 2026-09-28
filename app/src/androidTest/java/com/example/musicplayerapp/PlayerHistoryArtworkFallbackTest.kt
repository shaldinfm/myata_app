package com.example.musicplayerapp

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayerapp.adapters.PlayerHistoryAdapter
import com.example.musicplayerapp.data.HistoryTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

/**
 * A История эфира row with no cover shows the branded plate, never a blank tile.
 *
 * Both rows - the PLAYER's inline one and the full-screen one - are drawn by
 * [PlayerHistoryAdapter], so every case runs against both layouts. The lookup is
 * answered by hand, which is what lets each case pin the moment it is about: on
 * bind before any answer, after a null, after a load that fails, after one that
 * succeeds, and after the row is recycled.
 */
@RunWith(AndroidJUnit4::class)
class PlayerHistoryArtworkFallbackTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context by lazy { ContextThemeWrapper(instrumentation.targetContext, R.style.AppTheme) }

    private val rows = listOf(R.layout.item_player_history_track, R.layout.item_broadcast_history_track)

    private val first = HistoryTrack("FOALS", "WHAT WENT DOWN", 2L, "10:22")
    private val second = HistoryTrack("MUSE", "CRYOGEN", 1L, "10:10")

    private val goodUrl get() =
        "android.resource://${instrumentation.targetContext.packageName}/${R.drawable.zaglushka_1_img}"
    private val badUrl = "file:///data/local/tmp/myata-no-such-cover.png"

    @Test
    fun the_plate_is_up_as_soon_as_the_row_binds() = forEachRow { row ->
        main { assertPlate(row, "on bind, before the lookup answers") }
    }

    @Test
    fun a_null_answer_keeps_the_plate() = forEachRow { row ->
        main {
            row.answer(first, null)
            assertPlate(row, "after a lookup that found nothing")
        }
    }

    @Test
    fun a_failed_load_returns_to_the_plate() = forEachRow { row ->
        main {
            row.answer(first, badUrl)
            // The load is in flight and cannot have finished inside this block:
            // starting it must not take the plate down.
            assertPlate(row, "while a cover is loading")
            // Stand in for whatever the row showed before the failure lands. Only
            // the load's own error handling can put the plate back over this.
            row.holder.artwork.setImageDrawable(ColorDrawable(Color.MAGENTA))
        }
        await("the failed load to put the plate back") { isPlate(row) }
    }

    @Test
    fun a_real_cover_replaces_the_plate() = forEachRow { row ->
        main { row.answer(first, goodUrl) }
        await("the cover to load") { row.holder.artwork.drawable != null && !isPlate(row) }
    }

    @Test
    fun a_recycled_row_is_back_on_the_plate_before_its_next_lookup() = forEachRow { row ->
        main { row.answer(first, goodUrl) }
        await("the cover to load") { row.holder.artwork.drawable != null && !isPlate(row) }

        main {
            row.adapter.onViewRecycled(row.holder)
            assertPlate(row, "after recycling")
            assertEquals("recycling withdraws the row's lookup", listOf(first), row.cancelled)

            row.adapter.bindViewHolder(row.holder, 1)
            assertPlate(row, "rebound to another track, before its lookup answers")

            // A late answer for the track the row no longer holds paints nothing.
            row.answer(first, goodUrl)
            assertPlate(row, "after a late answer for the previous track")
        }
    }

    /**
     * A content change rebinds the same play on the same holder (DiffCallback's
     * change payload). It must keep the cover it has, not flash the plate and
     * load it again - the list half of the UI polish pass's placeholder rule.
     */
    @Test
    fun rebinding_the_same_play_keeps_its_cover() = forEachRow { row ->
        main { row.answer(first, goodUrl) }
        await("the cover to load") { row.holder.artwork.drawable != null && !isPlate(row) }
        main {
            val cover = row.holder.artwork.drawable
            val lookups = row.requests.size
            row.requests.clear()
            row.adapter.bindViewHolder(row.holder, 0)
            assertTrue("the rebound row went back to the plate", row.holder.artwork.drawable === cover)
            assertTrue("the rebound row looked its cover up again (had $lookups)", row.requests.isEmpty())
        }
    }

    // ==================== harness ====================

    private inner class Row(layout: Int) {
        val requests = mutableMapOf<HistoryTrack, (String?) -> Unit>()
        val cancelled = mutableListOf<HistoryTrack>()
        val adapter = PlayerHistoryAdapter(
            artworkFor = { track, onResult -> requests[track] = onResult },
            cancelArtwork = { cancelled += it },
            rowLayout = layout,
        )
        val holder: PlayerHistoryAdapter.ViewHolder

        init {
            // A fresh ListAdapter commits its first list synchronously.
            adapter.submitList(listOf(first, second))
            holder = adapter.createViewHolder(FrameLayout(context), 0)
            adapter.bindViewHolder(holder, 0)
            // Laid out, so a load that fits to the view has a size to fit to.
            val width = (360 * context.resources.displayMetrics.density).roundToInt()
            holder.itemView.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            holder.itemView.layout(0, 0, holder.itemView.measuredWidth, holder.itemView.measuredHeight)
        }

        fun answer(track: HistoryTrack, url: String?) {
            (requests[track] ?: error("no lookup was requested for ${track.title}"))(url)
        }
    }

    private fun forEachRow(body: (Row) -> Unit) {
        for (layout in rows) {
            var row: Row? = null
            main { row = Row(layout) }
            try {
                body(row!!)
            } catch (e: AssertionError) {
                throw AssertionError("${context.resources.getResourceEntryName(layout)}: ${e.message}", e)
            } finally {
                main { row!!.adapter.onViewRecycled(row!!.holder) }
            }
        }
    }

    // The plate is the vector artwork_placeholder and every cover is a bitmap.
    // Vector drawables do not share state between inflations, so identity cannot
    // be compared; the drawable's kind can.
    private fun isPlate(row: Row): Boolean =
        row.holder.artwork.drawable is android.graphics.drawable.VectorDrawable

    private fun assertPlate(row: Row, moment: String) {
        assertTrue("the branded plate is not up $moment", isPlate(row))
    }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun await(what: String, timeoutMs: Long = 10_000, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            var ok = false
            main { ok = check() }
            if (ok) return
            Thread.sleep(25)
        }
        throw AssertionError("timed out after ${timeoutMs}ms waiting for $what")
    }
}
