package com.example.musicplayerapp

import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.musicplayerapp.ui.profile.ProfileAvatars
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * profile-avatar against its frames, measured rather than eyeballed.
 *
 * 2523:137 / 2517:3678, a fixed 390x702, extended from 4x4 to 4x6 by repeating the
 * frame's own row geometry (the one approved structural change):
 *
 *     Header - TopAppBar    0..64     back 24x24 at (12,20); heading at x=56, 24/32
 *     Current              96..192    96x96 centred, 2dp inside ring
 *     caption             208..228    x=16, 358 wide, 14/20, centred
 *     grid                244..790    76dp cells, 18dp column and row gutters, 6 rows
 *     Сохранить           814..866    358x52 r12, 24 below the last row, label 21/28
 *     scroll end          890         24 below the button (the frame's 702 - 678)
 *
 * Built like [ProfileAuthenticatedLayoutTest]: the layout is inflated and measured at
 * several widths, gaps are asserted as a chain, absolute offsets only where rounding
 * cannot accumulate, and colour is checked per theme. The fragment builds the cells in
 * code, so this test adds them the same way, from the same cell layout.
 *
 * Every measured value is logged in dp under PROFILEQA, which is what the fidelity
 * audit table is read from.
 */
@RunWith(AndroidJUnit4::class)
class ProfileAvatarLayoutTest {

    private val widthsDp = listOf(320, 360, 390, 412)
    private val designWidthDp = 390

    private val findings = mutableListOf<String>()
    private val log = mutableListOf<String>()

    @Test
    fun profileAvatarReproducesTheFrozenFrame() {
        onMainActivity { activity ->
            for (night in listOf(false, true)) {
                sweep(inflaterFor(activity, night), if (night) "dark" else "light")
            }
        }

        android.util.Log.i(TAG, "==== PROFILE-AVATAR (API ${Build.VERSION.SDK_INT}) ====")
        log.forEach { android.util.Log.i(TAG, "  $it") }
        findings.forEach { android.util.Log.e(TAG, "  FINDING $it") }

        assertTrue(
            "PROFILE-AVATAR findings on API ${Build.VERSION.SDK_INT}:\n" +
                findings.joinToString("\n") { "  $it" },
            findings.isEmpty(),
        )
    }

    private fun sweep(inflater: LayoutInflater, theme: String) {
        val dm = inflater.context.resources.displayMetrics
        val dp = { v: Number -> v.toFloat() * dm.density }
        val asDp = { px: Int -> "%.1f".format(px / dm.density) }
        val ctx = inflater.context

        for (widthDp in widthsDp) {
            val root = measured(inflater, dp(widthDp).roundToInt())
            val where = "avatar/$theme@${widthDp}dp"

            val band = root.find(R.id.avatar_header)
            val back = root.find(R.id.avatar_back)
            val title = root.text(R.id.avatar_title)
            val scroll = root.find(R.id.avatar_scroll)
            val current = root.find(R.id.avatar_current)
            val caption = root.text(R.id.avatar_caption)
            val grid = root.find(R.id.avatar_grid) as ViewGroup
            val save = root.text(R.id.avatar_save)
            val cells = (0 until grid.childCount).map { grid.getChildAt(it) }
            val selected = cells[5]
            val badge = selected.findViewById<View>(R.id.avatar_cell_badge)

            /* ---- the band ---- */
            expect(where, "header band height", band.height, dp(64))
            expect(where, "back icon size", back.width, dp(24))
            expect(where, "back icon x", leftInRoot(back), dp(12))
            expect(where, "back icon y", topInRoot(back), dp(20))
            expect(where, "heading x", leftInRoot(title), dp(56))
            // The frame's heading box is 16..48 with a 32 line; Android's single-line box is
            // the font's own height, centred in the band. What must match is the centre -
            // the baseline follows from it - not the box.
            expect(where, "heading centre", topInRoot(title) + title.height / 2, dp(32))
            expect(where, "scroll starts under the band", topInRoot(scroll), dp(64))

            /* ---- preview and caption ---- */
            expect(where, "Current size", current.width, dp(96))
            expect(where, "Current height", current.height, dp(96))
            expect(where, "Current centred", leftInRoot(current), (dp(widthDp) - dp(96)) / 2)
            expect(where, "band to Current", topInRoot(current) - (topInRoot(band) + band.height), dp(32))
            expect(where, "Current to caption", gap(current, caption), dp(16))
            expect(where, "caption box", caption.height, dp(20))
            expect(where, "caption x", leftInRoot(caption), dp(16))
            expect(where, "caption width", caption.width, dp(widthDp - 32))
            if (caption.gravity and Gravity.HORIZONTAL_GRAVITY_MASK != Gravity.CENTER_HORIZONTAL) {
                findings += "$where: caption is not centred"
            }

            /* ---- grid ---- */
            expect(where, "caption to grid", gap(caption, grid), dp(16))
            expect(where, "cell count", cells.size, ProfileAvatars.all.size.toFloat())
            expect(where, "rows", (cells.size + 3) / 4, 6f)
            val contentPx = (dp(widthDp).roundToInt() - 2 * dp(16).roundToInt()).toFloat()
            val cellPx = com.example.musicplayerapp.ui.profile.AvatarGridLayout.cellSize(contentPx, dp(76), dp(18))
            val drawnCellPx = cellPx.roundToInt()
            val colGapPx = (contentPx - 4 * drawnCellPx) / 3
            for ((i, cell) in cells.withIndex()) {
                expect(where, "cell ${i + 1} width", cell.width, cellPx)
                expect(where, "cell ${i + 1} height", cell.height, cellPx)
                // Absolute, from the grid's own origin: rounding must not accumulate.
                expect(where, "cell ${i + 1} x", leftInRoot(cell) - leftInRoot(grid), (i % 4) * (drawnCellPx + colGapPx))
                expect(where, "cell ${i + 1} y", topInRoot(cell) - topInRoot(grid), (i / 4) * (cellPx + dp(18)))
            }
            if (widthDp == designWidthDp) {
                expect(where, "cell is the frame's 76dp at the frame's width", cells[0].width, dp(76))
                expect(where, "grid y", topInRoot(grid), dp(244))
                // End of a chain of ten independently rounded dp boxes: within 2px, not 1.
                expect(where, "Сохранить y", topInRoot(save), dp(814), tolerancePx = 2f)
                expect(where, "content end", topInRoot(save) + save.height + scroll.paddingBottom, dp(890), tolerancePx = 2f)
            }
            expect(where, "first column x", leftInRoot(cells[0]), dp(16))
            expect(where, "last column right edge", leftInRoot(cells[3]) + cells[3].width, dp(widthDp - 16))
            expect(where, "column gutter", leftInRoot(cells[1]) - (leftInRoot(cells[0]) + cells[0].width), colGapPx)
            for (row in 1 until 6) {
                // 18dp is a fractional pixel count at most densities; rows land 47/47/46px apart
                // so that the pitch, asserted below, does not drift.
                expect(where, "row gutter above row ${row + 1}", gap(cells[(row - 1) * 4], cells[row * 4]), dp(18), tolerancePx = 1.5f)
            }
            expect(where, "row pitch x5 (no drift)", topInRoot(cells[20]) - topInRoot(cells[0]), 5 * (cellPx + dp(18)))

            /* ---- selected state ---- */
            expect(where, "badge size", badge.width, dp(24))
            // The badge is drawn above the ring, as in Figma: a ring drawn on top crosses
            // the badge at the check's joint and cuts the check in two.
            val cellGroup = selected as ViewGroup
            val ring = selected.findViewById<View>(R.id.avatar_cell_ring)
            if (selected.foreground != null) findings += "$where: the cell draws a foreground over its badge"
            if (cellGroup.indexOfChild(badge) < cellGroup.indexOfChild(ring)) findings += "$where: the ring is drawn above the badge"
            expect(where, "badge x in cell", leftInRoot(badge) - leftInRoot(selected), selected.width - dp(24))
            expect(where, "badge y in cell", topInRoot(badge) - topInRoot(selected), selected.height - dp(24))

            /* ---- Сохранить ---- */
            expect(where, "last row to Сохранить", gap(cells.last(), save), dp(24))
            expect(where, "Сохранить height", save.height, dp(52))
            expect(where, "Сохранить x", leftInRoot(save), dp(16))
            expect(where, "Сохранить width", save.width, dp(widthDp - 32))
            expect(where, "scroll end below Сохранить (before the nav bar inset)", scroll.paddingBottom, dp(24))

            if (widthDp == designWidthDp) {
                log += "$where band=${asDp(band.height)} back=(${asDp(leftInRoot(back))},${asDp(topInRoot(back))}) ${asDp(back.width)} " +
                    "heading=(${asDp(leftInRoot(title))},${asDp(topInRoot(title))}) box ${asDp(title.height)} " +
                    "size ${"%.1f".format(title.textSize / dm.scaledDensity)}sp lh ${asDp(title.lineHeight)} " +
                    "typeface ${title.typeface?.style}"
                log += "$where Current=(${asDp(leftInRoot(current))},${asDp(topInRoot(current))}) ${asDp(current.width)} " +
                    "caption=(${asDp(leftInRoot(caption))},${asDp(topInRoot(caption))}) ${asDp(caption.width)}x${asDp(caption.height)} " +
                    "size ${"%.1f".format(caption.textSize / dm.scaledDensity)}sp lh ${asDp(caption.lineHeight)}"
                log += "$where grid y=${asDp(topInRoot(grid))} cell=${asDp(cells[0].width)} " +
                    "cols x=${cells.take(4).joinToString("/") { asDp(leftInRoot(it)) }} " +
                    "rows y=${cells.filterIndexed { i, _ -> i % 4 == 0 }.joinToString("/") { asDp(topInRoot(it)) }} " +
                    "badge=(${asDp(leftInRoot(badge) - leftInRoot(selected))},${asDp(topInRoot(badge) - topInRoot(selected))}) ${asDp(badge.width)}"
                log += "$where save=(${asDp(leftInRoot(save))},${asDp(topInRoot(save))}) ${asDp(save.width)}x${asDp(save.height)} " +
                    "label ${"%.1f".format(save.textSize / dm.scaledDensity)}sp lh ${asDp(save.lineHeight)} " +
                    "gap grid->save ${asDp(gap(cells.last(), save))} scroll padding bottom ${asDp(scroll.paddingBottom)} " +
                    "content ends ${asDp(topInRoot(save) + save.height + scroll.paddingBottom)}"
                log += "$where colours bg=${bg(root).hex()} heading=${title.currentTextColor.hex()} " +
                    "caption=${caption.currentTextColor.hex()} save label=${save.currentTextColor.hex()} " +
                    "outline=${ctx.tone(R.color.outline).hex()} primary=${ctx.tone(R.color.primary).hex()} " +
                    "badge check=${ctx.tone(R.color.avatar_badge_check).hex()} back=${ctx.tone(R.color.profile_back_icon).hex()}"
            }

            /* ---- type ---- */
            type(where, "heading", title, 24f, 32f, dm)
            type(where, "caption", caption, 14f, 20f, dm)
            type(where, "Сохранить", save, 21f, 28f, dm)

            /* ---- colour ---- */
            val night = theme == "dark"
            colour(where, "background", bg(root), if (night) 0xFF0F253E.toInt() else 0xFFF8F9FA.toInt())
            colour(where, "heading", title.currentTextColor, if (night) 0xFFF5F7FA.toInt() else 0xFF003056.toInt())
            colour(where, "caption", caption.currentTextColor, if (night) 0xFFB3C4D1.toInt() else 0xFF42474E.toInt())
            colour(where, "Сохранить label", save.currentTextColor, if (night) 0xFF0F253E.toInt() else 0xFFE3E8ED.toInt())
            colour(where, "cell ring (outline)", ctx.tone(R.color.outline), if (night) 0xFF466D8F.toInt() else 0xFFE1E3E4.toInt())
            colour(where, "selected ring / badge / Current ring / button (primary)", ctx.tone(R.color.primary), if (night) 0xFF5FD9B4.toInt() else 0xFF1C4771.toInt())
            colour(where, "button container", ctx.tone(R.color.profile_primary_button_container), if (night) 0xFF5FD9B4.toInt() else 0xFF1C4771.toInt())
            // Owner-specified (G6a): #F5F7FA light, #0F253E dark, opaque.
            colour(where, "badge check", ctx.tone(R.color.avatar_badge_check), if (night) 0xFF0F253E.toInt() else 0xFFF5F7FA.toInt())
            colour(where, "back glyph", ctx.tone(R.color.profile_back_icon), if (night) 0xFFF5F7FA.toInt() else 0xFF191C1D.toInt())
        }
    }

    // ==================== helpers ====================

    private fun ViewGroup.find(id: Int): View = findViewById(id)
    private fun ViewGroup.text(id: Int): TextView = findViewById(id)
    private fun android.content.Context.tone(id: Int): Int = resources.getColor(id, theme)
    private fun Int.hex(): String = String.format("#%08X", this)
    private fun gap(a: View, b: View): Int = topInRoot(b) - (topInRoot(a) + a.height)
    private fun bg(root: View): Int = (root.background as? android.graphics.drawable.ColorDrawable)?.color ?: 0

    private fun type(
        where: String, what: String, tv: TextView,
        sizeSp: Float, lineSp: Float, dm: android.util.DisplayMetrics,
    ) {
        expect(where, "$what text size", tv.textSize.roundToInt(), sizeSp * dm.scaledDensity)
        expect(where, "$what line height", tv.lineHeight, lineSp * dm.density)
    }

    private fun colour(where: String, what: String, actual: Int, expected: Int) {
        if (actual != expected) findings += "$where: $what is ${actual.hex()}, frame says ${expected.hex()}"
    }

    /** The picker's layout, with the 24 cells added the way the fragment adds them. */
    private fun measured(inflater: LayoutInflater, widthPx: Int): ViewGroup {
        val root = inflater.inflate(R.layout.fragment_profile_avatar, null) as ViewGroup
        val grid = root.findViewById<ViewGroup>(R.id.avatar_grid)
        for ((i, avatar) in ProfileAvatars.all.withIndex()) {
            val cell = inflater.inflate(R.layout.item_profile_avatar_cell, grid, false)
            cell.findViewById<android.widget.ImageView>(R.id.avatar_cell_image).setImageResource(avatar.drawable)
            if (i == 5) {
                cell.findViewById<View>(R.id.avatar_cell_ring).background = inflater.context.getDrawable(R.drawable.bg_avatar_cell_ring_selected)
                cell.findViewById<View>(R.id.avatar_cell_badge).visibility = View.VISIBLE
            }
            grid.addView(cell)
        }
        // The runtime adds the navigation bar inset on top; the resting layout carries 24.
        root.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(widthPx * 4, View.MeasureSpec.EXACTLY),
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
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity(block)
        } finally {
            try {
                scenario.close()
            } catch (e: Throwable) {
                android.util.Log.w(TAG, "activity close timed out; checks already complete", e)
            }
        }
    }

    private fun offsetToRoot(v: View, r: Rect) {
        var p = v.parent
        while (p is View) { r.offset(p.left, p.top); p = p.parent }
    }

    private fun topInRoot(v: View): Int =
        Rect(v.left, v.top, v.right, v.bottom).also { offsetToRoot(v, it) }.top - scrollOffset(v)

    private fun scrollOffset(v: View): Int {
        var p = v.parent; var s = 0
        while (p is View) { s += p.scrollY; p = p.parent }
        return s
    }

    private fun leftInRoot(v: View): Int =
        Rect(v.left, v.top, v.right, v.bottom).also { offsetToRoot(v, it) }.left

    private fun expect(where: String, what: String, actual: Int, expected: Float, tolerancePx: Float = 1f) {
        if (abs(actual - expected) > tolerancePx) {
            findings += "$where: $what is ${actual}px, frozen design says ${expected.roundToInt()}px"
        }
    }

    private companion object {
        const val TAG = "PROFILEQA"
    }
}
