package com.example.musicplayerapp.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.example.musicplayerapp.R
import com.example.musicplayerapp.StreamsViewModel
import com.squareup.picasso.Picasso
import jp.wasabeef.picasso.transformations.RoundedCornersTransformation


class PlaylistAdapter(private val playlists: List<StreamsViewModel.MyataPlaylist>, private val onItemClick: (position: Int) -> Unit):
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
        Picasso.get().load(playlists[position].img).transform(RoundedCornersTransformation(15,0)).resize(400,400).centerCrop().into(holder.iv)
        holder.iv.setTag(playlists[position].uri)
    }

    override fun getItemCount(): Int {
        return playlists.size
    }

}