package com.example.musicplayerapp.ui

import android.graphics.Rect
import android.view.TouchDelegate
import android.view.View
import com.example.musicplayerapp.R

/**
 * The 48dp touch target around a frozen 40dp circular row action.
 *
 * The COLLECTION row and the full-screen История эфира row draw the same control -
 * `Button / find track` in `history-content` is the same `arrow_forward` component
 * (2409:31540) inside the same 40dp ring as the Collection track item's - and both
 * sit below the platform's 48dp minimum. Growing the view would grow the ring,
 * because the ring is its background, so the drawn size stays 40 and the target
 * is widened around it instead. That is the answer docs/COLLECTION-3.6.6.md
 * reached for the header overflow's 4x16 glyph in a 48dp slot.
 *
 * Moved here from FavoritesAdapter in G4b so the second row gets the same target
 * rather than a copy of the code that computes it.
 */
object RowActionTouchTarget {

    /**
     * Installs, and keeps current, a delegate on [action]'s parent.
     *
     * On layout rather than on a post: a holder is created before its row has been
     * measured, so a rect read from a posted runnable can still be the empty one
     * and would install a delegate over nothing. Recomputing on every layout also
     * keeps the target right when the row grows for a wrapped line, which moves
     * the control.
     */
    fun expand(action: View) {
        val parent = action.parent as? View ?: return
        action.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            val target = view.resources.getDimensionPixelSize(R.dimen.collection_row_action_touch)
            val bounds = Rect().also { view.getHitRect(it) }
            if (bounds.isEmpty) return@addOnLayoutChangeListener
            val growX = ((target - bounds.width()) / 2).coerceAtLeast(0)
            val growY = ((target - bounds.height()) / 2).coerceAtLeast(0)
            bounds.inset(-growX, -growY)
            parent.touchDelegate = TouchDelegate(bounds, view)
        }
    }
}
