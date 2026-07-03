package com.example.musicplayerapp.adapters

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.musicplayerapp.R
import com.example.musicplayerapp.data.FavoriteTrack
import com.example.musicplayerapp.utils.MusicSearchHelper

/**
 * Adapter for displaying favorite tracks in a RecyclerView.
 */
class FavoritesAdapter(
    private val onDeleteClick: (FavoriteTrack) -> Unit
) : ListAdapter<FavoriteTrack, FavoritesAdapter.ViewHolder>(DiffCallback()) {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvArtist: TextView = itemView.findViewById(R.id.tv_artist)
        val tvTrack: TextView = itemView.findViewById(R.id.tv_track)
        val badgeStream: TextView = itemView.findViewById(R.id.badge_stream)
        val btnDelete: ImageView = itemView.findViewById(R.id.btn_delete)
        val btnSpotify: ImageView = itemView.findViewById(R.id.btn_spotify)
        val btnAppleMusic: ImageView = itemView.findViewById(R.id.btn_apple_music)
        val btnYandex: ImageView = itemView.findViewById(R.id.btn_yandex)
        val btnYouTube: ImageView = itemView.findViewById(R.id.btn_youtube)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_favorite_track, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val track = getItem(position)
        
        holder.tvArtist.text = track.artist.uppercase()
        holder.tvTrack.text = track.track
        
        // Unified artist color as per request
        holder.tvArtist.setTextColor(Color.parseColor("#00E5FF"))
        
        // Set stream badge with specific colors
        when (track.stream) {
            "myata" -> {
                holder.badgeStream.text = "MYATA"
                holder.badgeStream.background.setTint(Color.parseColor("#00E5FF"))
            }
            "gold" -> {
                holder.badgeStream.text = "GOLD"
                holder.badgeStream.background.setTint(Color.parseColor("#FFFF00"))
            }
            "myata_hits" -> {
                holder.badgeStream.text = "XTRA"
                holder.badgeStream.background.setTint(Color.parseColor("#FFCCFF"))
            }
            else -> {
                holder.badgeStream.text = track.stream.uppercase()
                holder.badgeStream.background.setTint(Color.parseColor("#FFFFFF"))
            }
        }
        
        holder.btnDelete.setOnClickListener {
            onDeleteClick(track)
        }
        
        val context = holder.itemView.context
        val artist = track.artist
        val title = track.track
        
        holder.btnSpotify.setOnClickListener {
            MusicSearchHelper.openSpotify(context, artist, title)
        }
        
        holder.btnAppleMusic.setOnClickListener {
            MusicSearchHelper.openAppleMusic(context, artist, title)
        }
        
        holder.btnYandex.setOnClickListener {
            MusicSearchHelper.openYandexMusic(context, artist, title)
        }
        
        holder.btnYouTube.setOnClickListener {
            MusicSearchHelper.openYouTube(context, artist, title)
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
