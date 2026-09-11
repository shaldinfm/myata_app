package com.example.musicplayerapp.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import androidx.annotation.VisibleForTesting
import com.example.musicplayerapp.R

/**
 * The frozen `Menu / Коллекция`, collection-overflow-menu 2517:2591.
 *
 * It replaces a platform `PopupMenu`, which is what FavoritesFragment's own note
 * called "deliberate and temporary". A platform menu draws a rectangle with the
 * platform's shadow, padding and row metrics, and no icons; the frozen menu is the
 * same rounded-card component as `Menu / Плеер` - r20 surface, 1px outline, no
 * shadow, 48 rows with a glyph slot and a 15/22 label - so it is built the same
 * way [PlayerOverflowMenu] is: a [PopupWindow] over an inflated layout that
 * draws its own surface.
 *
 * ## Where it hangs
 *
 * Off the trailing edge of the header control, like the PLAYER's, so both menus
 * share a right edge 16dp from the screen's. The frozen file places neither menu
 * relative to its header - they are drawn as standalone frames - so the vertical
 * offset is chosen for parity with the PLAYER rather than read from a node: the
 * PLAYER's menu drops from its 39dp control, whose glyph sits 23dp above the
 * control's bottom edge, while this 48dp control's glyph sits 27.5dp above its
 * own. Pulling this menu up by the 4.5dp difference puts both menus' top edges
 * the same 23dp below their ellipsis glyphs, which is the relationship the eye
 * actually compares.
 */
class CollectionOverflowMenu(
    private val onExportTxt: () -> Unit,
    private val onExportCsv: () -> Unit,
) {

    private var window: PopupWindow? = null

    fun show(anchor: View) {
        dismiss()

        val ctx = anchor.context
        val content = LayoutInflater.from(ctx).inflate(R.layout.menu_collection_overflow, null, false)

        val popup = PopupWindow(
            content,
            ctx.resources.getDimensionPixelSize(R.dimen.player_overflow_menu_width),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true,
        ).apply {
            // The surface is the layout's own r20 card and 1px outline; the
            // window must add neither a background nor a shadow of its own.
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = ctx.resources.getDimension(R.dimen.player_overflow_elevation)
        }

        content.findViewById<View>(R.id.collection_overflow_export_txt).setOnClickListener {
            popup.dismiss()
            onExportTxt()
        }
        content.findViewById<View>(R.id.collection_overflow_export_csv).setOnClickListener {
            popup.dismiss()
            onExportCsv()
        }

        window = popup
        val lift = -ctx.resources.getDimensionPixelSize(R.dimen.collection_overflow_menu_lift)
        popup.showAsDropDown(anchor, 0, lift, Gravity.END)
    }

    fun dismiss() {
        window?.dismiss()
        window = null
    }

    /** The open menu's content, or null - a PopupWindow is not in the activity's tree. */
    @VisibleForTesting
    fun contentForTest(): View? = window?.contentView
}
