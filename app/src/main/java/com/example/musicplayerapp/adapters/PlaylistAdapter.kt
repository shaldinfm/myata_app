package com.example.musicplayerapp.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.example.musicplayerapp.R
import com.example.musicplayerapp.data.MyataPlaylist
import com.squareup.picasso.Picasso


class PlaylistAdapter(private val playlists: List<MyataPlaylist>, private val onItemClick: (position: Int) -> Unit):
    RecyclerView.Adapter<PlaylistAdapter.PlaylistViewHolder>() {

    class PlaylistViewHolder(itemView: View, private val onItemClick: (position: Int) -> Unit) : RecyclerView.ViewHolder(itemView), View.OnClickListener{
        val iv: ImageView = itemView.findViewById(R.id.iv)
        init {
            itemView.setOnClickListener {
                onItemClick(adapterPosition)
            }
        }
        override fun onClick(p0: View?) {
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaylistViewHolder {
        val itemView = LayoutInflater.from(parent.context).inflate(R.layout.rw_playlist_item,parent,false)
        return PlaylistViewHolder(itemView, onItemClick)
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

    override fun getItemCount(): Int {
        return playlists.size
    }

}