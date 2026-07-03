package com.example.musicplayerapp.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.musicplayerapp.R
import com.example.musicplayerapp.data.HistoryTrack
import com.example.musicplayerapp.utils.MusicSearchHelper

/**
 * Adapter for displaying track history in a RecyclerView.
 */
class HistoryAdapter : ListAdapter<HistoryTrack, HistoryAdapter.ViewHolder>(DiffCallback()) {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvTime: TextView = itemView.findViewById(R.id.tv_time)
        val tvArtist: TextView = itemView.findViewById(R.id.tv_artist)
        val tvTitle: TextView = itemView.findViewById(R.id.tv_title)
        val btnSpotify: ImageView = itemView.findViewById(R.id.btn_spotify)
        val btnAppleMusic: ImageView = itemView.findViewById(R.id.btn_apple_music)
        val btnYandex: ImageView = itemView.findViewById(R.id.btn_yandex)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history_track, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val track = getItem(position)
        
        holder.tvTime.text = track.getFormattedTime()
        holder.tvArtist.text = track.artist.uppercase()
        holder.tvTitle.text = track.title
        
        val context = holder.itemView.context
        val artist = track.artist
        val title = track.title
        
        holder.btnSpotify.setOnClickListener {
            MusicSearchHelper.openSpotify(context, artist, title)
        }
        
        holder.btnAppleMusic.setOnClickListener {
            MusicSearchHelper.openAppleMusic(context, artist, title)
        }
        
        holder.btnYandex.setOnClickListener {
            MusicSearchHelper.openYandexMusic(context, artist, title)
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<HistoryTrack>() {
        override fun areItemsTheSame(oldItem: HistoryTrack, newItem: HistoryTrack): Boolean {
            return oldItem.playedAt == newItem.playedAt && oldItem.artist == newItem.artist
        }

        override fun areContentsTheSame(oldItem: HistoryTrack, newItem: HistoryTrack): Boolean {
            return oldItem == newItem
        }
    }
}
