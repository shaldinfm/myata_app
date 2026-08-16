package com.example.musicplayerapp.adapters

import android.graphics.Rect
import android.view.LayoutInflater
import android.view.TouchDelegate
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.musicplayerapp.R
import com.example.musicplayerapp.data.FavoriteTrack
import com.google.android.material.imageview.ShapeableImageView
import com.squareup.picasso.Picasso

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
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_favorite_track, parent, false)
        return ViewHolder(view).also { expandActionTouchTarget(it) }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val track = getItem(position)
        holder.boundTo = track

        holder.tvTrack.text = track.track
        holder.tvArtist.text = track.artist.uppercase()

        // Back to the bare plate first: a recycled holder still carries the
        // previous row's cover, and a lookup that finds nothing never paints.
        Picasso.get().cancelRequest(holder.artwork)
        holder.artwork.setImageDrawable(null)

        artworkFor(track) { url ->
            // The answer arrives after a round trip, by which time the holder may
            // have been rebound to a different track. Only paint if it has not.
            if (holder.boundTo != track || url.isNullOrBlank()) return@artworkFor
            Picasso.get()
                .load(url)
                .fit()
                .centerCrop()
                .into(holder.artwork)
        }

        holder.action.setOnClickListener { onActionClick(track) }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        holder.boundTo?.let(cancelArtwork)
        holder.boundTo = null
        Picasso.get().cancelRequest(holder.artwork)
        holder.artwork.setImageDrawable(null)
    }

    /**
     * The frozen control is a 40dp ring, which is below the platform's 48dp touch
     * minimum. Growing the view would grow the ring - it is the background - so
     * the drawn size stays 40 and the target is widened around it instead, which
     * is the same answer docs/COLLECTION-3.6.6.md reached for the header
     * overflow's 4x16 glyph in a 48dp slot.
     */
    private fun expandActionTouchTarget(holder: ViewHolder) {
        val parent = holder.action.parent as? View ?: return
        parent.post {
            val target = holder.action.resources
                .getDimensionPixelSize(R.dimen.collection_row_action_touch)
            val bounds = Rect().also { holder.action.getHitRect(it) }
            val growX = ((target - bounds.width()) / 2).coerceAtLeast(0)
            val growY = ((target - bounds.height()) / 2).coerceAtLeast(0)
            bounds.inset(-growX, -growY)
            parent.touchDelegate = TouchDelegate(bounds, holder.action)
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<FavoriteTrack>() {
        override fun areItemsTheSame(oldItem: FavoriteTrack, newItem: FavoriteTrack): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: FavoriteTrack, newItem: FavoriteTrack): Boolean {
            return oldItem == newItem
        }
    }
}
