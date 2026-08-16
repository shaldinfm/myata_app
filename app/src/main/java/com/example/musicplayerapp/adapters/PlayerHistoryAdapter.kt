package com.example.musicplayerapp.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.musicplayerapp.R
import com.example.musicplayerapp.data.HistoryTrack
import com.google.android.material.imageview.ShapeableImageView
import com.squareup.picasso.Picasso

/**
 * The PLAYER's inline Broadcast History rows (Phase C).
 *
 * Deliberately separate from [HistoryAdapter], which keeps drawing the History
 * bottom sheet's own row. The two draw different layouts - the frozen inline row
 * against the sheet's - so one adapter would need a view type per surface, and
 * the sheet is not part of this migration. What they share is the thing that
 * matters: both are fed from `StreamsViewModel.historyTracks`, so there is one
 * history in the app and two views of it.
 *
 * Artwork is the one thing a [HistoryTrack] cannot supply. The model carries
 * artist, track and a formatted timestamp and nothing else, while the frozen row
 * draws a cover, so the cover is resolved from the artist and track through
 * ArtworkRepository - the app's single source of truth for artwork, already held
 * by the ViewModel and already backed by an in-memory cache. That resolution is
 * the caller's, passed in as [artworkFor]: this adapter states which row wants a
 * cover and takes a URL back, and knows nothing about how it is found.
 *
 * @param artworkFor asks for a cover for one track. Called on bind, answered
 *   later on the main thread with a URL or null; a null leaves the frozen plate.
 * @param cancelArtwork withdraws a request whose row has been recycled.
 */
class PlayerHistoryAdapter(
    private val artworkFor: (HistoryTrack, (String?) -> Unit) -> Unit,
    private val cancelArtwork: (HistoryTrack) -> Unit,
) : ListAdapter<HistoryTrack, PlayerHistoryAdapter.ViewHolder>(DiffCallback()) {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvTime: TextView = itemView.findViewById(R.id.tv_time)
        val tvTitle: TextView = itemView.findViewById(R.id.tv_title)
        val tvArtist: TextView = itemView.findViewById(R.id.tv_artist)
        val artwork: ShapeableImageView = itemView.findViewById(R.id.artwork)

        /** The track this holder is currently bound to, for late artwork. */
        var boundTo: HistoryTrack? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_player_history_track, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val track = getItem(position)
        holder.boundTo = track

        holder.tvTime.text = track.getFormattedTime()
        holder.tvTitle.text = track.title
        holder.tvArtist.text = track.artist

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
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        holder.boundTo?.let(cancelArtwork)
        holder.boundTo = null
        Picasso.get().cancelRequest(holder.artwork)
        holder.artwork.setImageDrawable(null)
    }

    /**
     * The same identity [HistoryAdapter] uses: a play is identified by when it
     * happened and by whom, which is what the API's own ordering is on.
     */
    private class DiffCallback : DiffUtil.ItemCallback<HistoryTrack>() {
        override fun areItemsTheSame(oldItem: HistoryTrack, newItem: HistoryTrack): Boolean =
            oldItem.playedAt == newItem.playedAt && oldItem.artist == newItem.artist

        override fun areContentsTheSame(oldItem: HistoryTrack, newItem: HistoryTrack): Boolean =
            oldItem == newItem
    }
}
