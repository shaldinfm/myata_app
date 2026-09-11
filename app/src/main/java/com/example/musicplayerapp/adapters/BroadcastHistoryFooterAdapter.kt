package com.example.musicplayerapp.adapters

import android.content.res.Resources
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.musicplayerapp.R
import com.example.musicplayerapp.ui.HistoryFooterText

/**
 * history-content's `Footer` - "Показаны последние 30 треков" - as the last item
 * of the full-screen list (G4b), so it scrolls with the rows it describes.
 *
 * One item while there are rows and none otherwise. The count is the list's real
 * length: 30 reads as the frame does, and a shorter history is not told it is 30.
 */
class BroadcastHistoryFooterAdapter : RecyclerView.Adapter<BroadcastHistoryFooterAdapter.ViewHolder>() {

    class ViewHolder(val text: TextView) : RecyclerView.ViewHolder(text)

    /** How many rows the list above is showing. */
    var shownCount: Int = 0
        set(value) {
            if (field == value) return
            val had = field > 0
            field = value
            when {
                had && value > 0 -> notifyItemChanged(0)
                had -> notifyItemRemoved(0)
                value > 0 -> notifyItemInserted(0)
            }
        }

    override fun getItemCount(): Int = if (shownCount > 0) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_broadcast_history_footer, parent, false) as TextView
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.text.text = textFor(holder.text.resources, shownCount)
    }

    companion object {
        /**
         * The footer for [count] rows, in the Russian form [HistoryFooterText]
         * chooses - never the device locale's, see there.
         */
        fun textFor(res: Resources, count: Int): String = res.getString(
            when (HistoryFooterText.formOf(count)) {
                HistoryFooterText.Form.ONE -> R.string.history_screen_footer_one
                HistoryFooterText.Form.FEW -> R.string.history_screen_footer_few
                HistoryFooterText.Form.MANY -> R.string.history_screen_footer_many
            },
            count,
        )
    }
}
