package com.example.musicplayerapp.ui.profile

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import com.example.musicplayerapp.R
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The avatar grid on `profile-avatar`: four columns, 76dp cells, 18dp gutters, as many rows
 * as there are avatars (six for the approved 24; the frame drew four).
 *
 * The frame is 390 wide, where 4·76 + 3·18 is exactly the 358dp content width. A
 * device is rarely 390: the default QA emulator is 411. Rather than leave 22dp of
 * unexplained space at the right edge, the cells keep their 76dp and the three column
 * gutters share the extra, so the grid lines up with the `Сохранить` button under it
 * at both edges. Below 358 the cells shrink instead, so four always fit. Row gutters
 * stay 18: nothing vertical has to line up with anything.
 *
 * ## Whole pixels only at the end
 *
 * Sizes are kept in exact pixels (floats) and rounded once per edge. Rounding the cell
 * and the gutter first made the frame's own width fail its own fit test - at 2.625x,
 * 76dp is 199.5px and 18dp is 47.25px, so 4·200 + 3·47 overshoots 940 by a pixel and the
 * cells shrank to 199 - and then six rows of whole-pixel steps drifted `Сохранить` up by
 * about 4px. Positions now come from the exact pitch, so the last row and the last
 * column land where the frame puts them, within one pixel.
 *
 * [cellSize] is the pure rule, so it is testable without a view.
 */
class AvatarGridLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {

    private val preferredCell = resources.getDimension(R.dimen.avatar_cell_size)
    private val gutter = resources.getDimension(R.dimen.avatar_cell_gutter)

    private var cell = preferredCell
    private var columnPitch = preferredCell + gutter

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).toFloat()
        cell = cellSize(width, preferredCell, gutter)
        val cellPx = cell.roundToInt()
        // From the drawn (rounded) cell, so the last column ends exactly on the content edge.
        columnPitch = if (COLUMNS > 1) (width - cellPx) / (COLUMNS - 1) else 0f

        val exactly = MeasureSpec.makeMeasureSpec(cellPx, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(exactly, exactly)

        val rows = (childCount + COLUMNS - 1) / COLUMNS
        val height = if (rows == 0) 0 else rowTop(rows - 1) + cellPx
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            resolveSize(height + paddingTop + paddingBottom, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        val width = r - l
        val cellPx = cell.roundToInt()
        for (i in 0 until childCount) {
            val offset = (i % COLUMNS * columnPitch).roundToInt()
            val left = if (rtl) width - paddingRight - offset - cellPx else paddingLeft + offset
            val top = paddingTop + rowTop(i / COLUMNS)
            getChildAt(i).layout(left, top, left + cellPx, top + cellPx)
        }
    }

    private fun rowTop(row: Int): Int = (row * (cell + gutter)).roundToInt()

    companion object {
        const val COLUMNS = 4

        /**
         * The frame's cell when four of them and three gutters fit in [width]; smaller when
         * not. Up to a pixel of shortfall still counts as fitting - that is rounding, not a
         * narrower screen - and the column gutters absorb it.
         */
        fun cellSize(width: Float, preferred: Float, gutter: Float): Float {
            val needed = COLUMNS * preferred + (COLUMNS - 1) * gutter
            if (width >= needed - 1f) return preferred
            return min(preferred, ((width - (COLUMNS - 1) * gutter) / COLUMNS).coerceAtLeast(0f))
        }
    }
}
