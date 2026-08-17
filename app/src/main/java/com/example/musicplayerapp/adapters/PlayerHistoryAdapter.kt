package com.example.musicplayerapp.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.widget.TextViewCompat
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

        init {
            // History-local text metrics: the title on a 22 line and the artist on
            // an 18, so the normal one-line-over-one-line block is 40 and reads
            // inside the 40 cover. The dimens carry where those numbers come from.
            //
            // includeFontPadding is the half that actually moved the number, and
            // finding that out is worth recording. These rows are inflated with
            // LayoutInflater.from(parent.context) below, which is NOT the inflater
            // MyataTypography.Factory is installed on - the factory is set on the
            // Activity's inflater, and this one is a different object. So the row's
            // text never received the typography contract at all: measured on
            // device it had includeFontPadding=true and lineSpacingExtra=0, and its
            // height came from the minHeight dimens alone. The font's own overshoot
            // was adding 2px to the title and 3px to the artist on top of that.
            //
            // With padding off, Onest Regular's line is descent-ascent = 58px at
            // 17sp and 47px at 14sp on a 2.625 device: 105px together, which is
            // exactly 40dp. The two setLineHeight calls are therefore usually
            // no-ops - the target equals the natural line, and TextView only
            // touches line spacing when they differ - and they stay because they
            // are what pins the intent if the font is ever replaced.
            //
            // Deliberately not fixed by routing this inflater through the factory:
            // that would apply the shared token's 28 and 20 and put the block back
            // to 48, which is the thing being corrected.
            val res = itemView.resources
            for (view in listOf(tvTitle, tvArtist)) {
                view.includeFontPadding = false
            }
            TextViewCompat.setLineHeight(
                tvTitle, res.getDimensionPixelSize(R.dimen.player_history_title_line_height)
            )
            TextViewCompat.setLineHeight(
                tvArtist, res.getDimensionPixelSize(R.dimen.player_history_artist_line_height)
            )
        }
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
