package com.shubhamgupta.nebula_player.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.shubhamgupta.nebula_player.R
import com.shubhamgupta.nebula_player.models.Album
import com.shubhamgupta.nebula_player.utils.SongUtils

class AlbumAdapter(
    private val onAlbumClick: (position: Int) -> Unit
) : ListAdapter<Album, AlbumAdapter.AlbumVH>(AlbumDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlbumVH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_album, parent, false)
        return AlbumVH(view)
    }

    override fun onBindViewHolder(holder: AlbumVH, position: Int) {
        val album = getItem(position)
        holder.albumName.text = album.name
        holder.albumArtist.text = album.artist
        holder.songCount.text = "${album.songCount} songs"

        // Load album art
        val albumArtUri = SongUtils.getAlbumArtUri(album.albumId)
        Glide.with(holder.itemView.context)
            .load(albumArtUri)
            .placeholder(R.drawable.default_album_art)
            .error(R.drawable.default_album_art)
            .into(holder.albumArt)

        holder.itemView.setOnClickListener {
            onAlbumClick(position)
        }
    }

    class AlbumVH(view: View) : RecyclerView.ViewHolder(view) {
        val albumArt: ImageView = view.findViewById(R.id.album_art)
        val albumName: TextView = view.findViewById(R.id.album_name)
        val albumArtist: TextView = view.findViewById(R.id.album_artist)
        val songCount: TextView = view.findViewById(R.id.album_song_count)
    }

    class AlbumDiffCallback : DiffUtil.ItemCallback<Album>() {
        override fun areItemsTheSame(oldItem: Album, newItem: Album): Boolean {
            return oldItem.name == newItem.name && oldItem.artist == newItem.artist
        }

        override fun areContentsTheSame(oldItem: Album, newItem: Album): Boolean {
            return oldItem == newItem
        }
    }
}