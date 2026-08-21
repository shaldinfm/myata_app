package com.example.musicplayerapp.adapters

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.example.musicplayerapp.R
import com.example.musicplayerapp.data.MyataPlaylist
import com.squareup.picasso.Picasso

/**
 * The HOME playlist row.
 *
 * Two things changed when HOME stopped being able to assume its data had already
 * arrived, and both are about safety rather than appearance - the card, its
 * layout, its corner and its Picasso call are exactly as they were.
 *
 * **It owns its list.** The adapter used to be constructed around whatever
 * `playlistList.value` happened to be at `onCreateView`, which meant HOME could
 * only be built after the data existed; if it was built earlier there was no
 * adapter at all and nothing ever created one. Now it starts empty and takes
 * [submit], so the fragment can attach it immediately and fill it whenever the
 * load lands.
 *
 * **The click carries the item, not its index.** The callback used to hand back a
 * position that the fragment looked up in `playlistList.value!!` - a second read
 * of a separate source, which is both a `!!` on a nullable and an assumption that
 * the two lists still agree. Passing the [MyataPlaylist] removes the lookup, so
 * there is nothing left to be out of date or null.
 */
class PlaylistAdapter(
    private val onItemClick: (playlist: MyataPlaylist) -> Unit,
) : RecyclerView.Adapter<PlaylistAdapter.PlaylistViewHolder>() {

    private val playlists = mutableListOf<MyataPlaylist>()

    /**
     * Replaces the row's contents.
     *
     * `notifyDataSetChanged` rather than a diff: the row is a handful of promo
     * cards that arrive once per launch, so there is no partial update worth
     * computing and no animation to preserve. The suppression is the same
     * statement to lint: the specific-change events it suggests would describe a
     * diff that never happens.
     */
    @SuppressLint("NotifyDataSetChanged")
    fun submit(items: List<MyataPlaylist>) {
        playlists.clear()
        playlists.addAll(items)
        notifyDataSetChanged()
    }

    class PlaylistViewHolder(
        itemView: View,
        private val itemAt: (Int) -> MyataPlaylist?,
        private val onItemClick: (playlist: MyataPlaylist) -> Unit,
    ) : RecyclerView.ViewHolder(itemView) {

        val iv: ImageView = itemView.findViewById(R.id.iv)

        init {
            itemView.setOnClickListener {
                // bindingAdapterPosition, and a null-returning lookup: a tap can
                // land in the same frame as a submit(), and NO_POSITION or a stale
                // index must do nothing rather than throw.
                itemAt(bindingAdapterPosition)?.let(onItemClick)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaylistViewHolder {
        val itemView = LayoutInflater.from(parent.context)
            .inflate(R.layout.rw_playlist_item, parent, false)
        return PlaylistViewHolder(itemView, ::itemAtOrNull, onItemClick)
    }

    override fun onBindViewHolder(holder: PlaylistViewHolder, position: Int) {
        // The card clips to the frozen radius 20 itself, so the bitmap is loaded
        // plain. It used to be rounded again here at radius 15 in source pixels,
        // which on a 160dp card is neither 20dp nor the same on two densities.
        // fit() rather than a fixed 400: the card is a known 160dp, so the view
        // measures to the exact pixels the device needs.
        Picasso.get()
            .load(playlists[position].img)
            .fit()
            .centerCrop()
            .into(holder.iv)
        holder.iv.setTag(playlists[position].uri)
    }

    override fun getItemCount(): Int = playlists.size

    private fun itemAtOrNull(position: Int): MyataPlaylist? = playlists.getOrNull(position)
}
