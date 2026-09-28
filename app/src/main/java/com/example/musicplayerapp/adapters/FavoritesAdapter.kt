package com.example.musicplayerapp.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.example.musicplayerapp.ui.RowActionTouchTarget
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.musicplayerapp.R
import com.example.musicplayerapp.data.FavoriteTrack
import com.google.android.material.imageview.ShapeableImageView
import com.example.musicplayerapp.ui.CoverArt

/**
 * Adapter for the COLLECTION list, on the FINAL 3.6.6 row (F3).
 *
 * Two things changed here with the row.
 *
 * **Artwork.** The frozen row is built around a 64x64 cover and [FavoriteTrack]
 * has nowhere to put one - but it does not need one: ArtworkRepository is keyed
 * on artist and track, which the entity already stores, so this is a view of
 * data the collection already has and not a second store beside it. The request
 * is the caller's, passed in as [artworkFor], exactly as [PlayerHistoryAdapter]
 * states it: this adapter says which row wants a cover, not where covers come
 * from.
 *
 * **One action, not five.** The four service buttons and the delete cross are
 * gone from the row and are rows on the per-track sheet the trailing control
 * opens. Nothing about their behaviour moved with them - see CollectionTrackSheet.
 *
 * @param artworkFor asks for a cover for one track. Called on bind, answered
 * whenever the answer arrives.
 * @param cancelArtwork withdraws a request whose row has been recycled.
 * @param onActionClick the row's single circular control, which opens the sheet.
 */
class FavoritesAdapter(
    private val artworkFor: (FavoriteTrack, (String?) -> Unit) -> Unit,
    private val cancelArtwork: (FavoriteTrack) -> Unit,
    private val onActionClick: (FavoriteTrack) -> Unit,
) : ListAdapter<FavoriteTrack, FavoritesAdapter.ViewHolder>(DiffCallback()) {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvArtist: TextView = itemView.findViewById(R.id.tv_artist)
        val tvTrack: TextView = itemView.findViewById(R.id.tv_track)
        val artwork: ShapeableImageView = itemView.findViewById(R.id.artwork)
        val action: ImageView = itemView.findViewById(R.id.btn_row_action)

        /** The track this holder is currently bound to, for late artwork. */
        var boundTo: FavoriteTrack? = null

        /** The track key whose cover this holder is showing or loading. */
        var coverKey: String? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_favorite_track, parent, false)
        return ViewHolder(view).also { RowActionTouchTarget.expand(it.action) }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val track = getItem(position)
        holder.boundTo = track

        holder.tvTrack.text = track.track
        holder.tvArtist.text = track.artist.uppercase()

        // The same track rebound - a content change, which DiffCallback keeps on
        // this holder - keeps the cover it already has. Resetting it would flash
        // the placeholder on every row a sync touches.
        if (holder.coverKey != track.trackKey) {
            // Back to the placeholder first: a recycled holder still carries the
            // previous row's cover, and it is also what stays when the lookup finds
            // nothing - the same plate as every other artwork surface.
            CoverArt.clearRow(holder.artwork)
            holder.coverKey = track.trackKey

            artworkFor(track) { url ->
                // The answer arrives after a round trip, by which time the holder may
                // have been rebound to a different track. Only paint if it has not.
                if (holder.boundTo != track || url.isNullOrBlank()) return@artworkFor
                CoverArt.loadRow(holder.artwork, url) { holder.coverKey = null }
            }
        }

        holder.action.setOnClickListener { onActionClick(track) }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        holder.boundTo?.let(cancelArtwork)
        holder.boundTo = null
        holder.coverKey = null
        CoverArt.clearRow(holder.artwork)
    }

    private class DiffCallback : DiffUtil.ItemCallback<FavoriteTrack>() {
        override fun areItemsTheSame(oldItem: FavoriteTrack, newItem: FavoriteTrack): Boolean {
            // The track key, not a row id: it is the identity of the track itself,
            // so a row that leaves the Collection and comes back through Undo is
            // recognised as the same item.
            return oldItem.trackKey == newItem.trackKey
        }

        override fun areContentsTheSame(oldItem: FavoriteTrack, newItem: FavoriteTrack): Boolean {
            return oldItem == newItem
        }

        // Any payload at all tells the item animator the changed row can be rebound
        // in place, instead of crossfading it with a second holder that starts from
        // the placeholder and loads the cover again.
        override fun getChangePayload(oldItem: FavoriteTrack, newItem: FavoriteTrack): Any = Unit
    }
}
