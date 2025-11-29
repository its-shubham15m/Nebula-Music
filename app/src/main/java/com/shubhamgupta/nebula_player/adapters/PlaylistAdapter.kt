package com.shubhamgupta.nebula_player.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.shubhamgupta.nebula_player.R
import com.shubhamgupta.nebula_player.models.Playlist
import com.shubhamgupta.nebula_player.utils.DebugUtils

class PlaylistAdapter(
    private val onItemClick: (position: Int) -> Unit,
    private val onMenuClick: (position: Int, menuItem: String) -> Unit,
    private val getAlbumArtForPlaylist: (Playlist) -> Any?
) : ListAdapter<Playlist, PlaylistAdapter.PlaylistViewHolder>(PlaylistDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaylistViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_playlist, parent, false)
        return PlaylistViewHolder(view)
    }

    override fun onBindViewHolder(holder: PlaylistViewHolder, position: Int) {
        val playlist = getItem(position)
        holder.bind(playlist)

        holder.itemView.setOnClickListener {
            onItemClick(position)
        }

        holder.optionsButton.setOnClickListener { view ->
            showPopupMenu(view, position)
        }
    }

    private fun showPopupMenu(view: View, position: Int) {
        val popup = PopupMenu(view.context, view)
        popup.menuInflater.inflate(R.menu.playlist_options_menu, popup.menu)

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_play_playlist -> {
                    onMenuClick(position, "play")
                    true
                }
                R.id.menu_add_songs -> {
                    onMenuClick(position, "add_songs")
                    true
                }
                R.id.menu_rename_playlist -> {
                    onMenuClick(position, "rename")
                    true
                }
                R.id.menu_delete_playlist -> {
                    onMenuClick(position, "delete")
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    inner class PlaylistViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val playlistName: TextView = itemView.findViewById(R.id.tv_playlist_name)
        private val songCount: TextView = itemView.findViewById(R.id.tv_song_count)
        private val albumArt: ImageView = itemView.findViewById(R.id.iv_playlist_album_art)
        val optionsButton: ImageButton = itemView.findViewById(R.id.btn_playlist_options)

        fun bind(playlist: Playlist) {
            playlistName.text = playlist.name
            songCount.text = "${playlist.songIds.size} songs"

            // Load album art using the provided function
            val albumArtResource = getAlbumArtForPlaylist(playlist)
            loadAlbumArt(albumArtResource)
        }

        private fun loadAlbumArt(albumArtResource: Any?) {
            try {
                when (albumArtResource) {
                    is Int -> {
                        Glide.with(itemView.context)
                            .load(albumArtResource)
                            .placeholder(R.drawable.default_album_art)
                            .error(R.drawable.default_album_art)
                            .into(albumArt)
                    }
                    is ByteArray -> {
                        Glide.with(itemView.context)
                            .load(albumArtResource)
                            .placeholder(R.drawable.default_album_art)
                            .error(R.drawable.default_album_art)
                            .into(albumArt)
                    }
                    is android.net.Uri -> {
                        Glide.with(itemView.context)
                            .load(albumArtResource)
                            .placeholder(R.drawable.default_album_art)
                            .error(R.drawable.default_album_art)
                            .into(albumArt)
                    }
                    else -> {
                        Glide.with(itemView.context)
                            .load(R.drawable.default_album_art)
                            .into(albumArt)
                    }
                }
            } catch (e: Exception) {
                DebugUtils.logError("Error loading album art", e)
                Glide.with(itemView.context)
                    .load(R.drawable.default_album_art)
                    .into(albumArt)
            }
        }
    }

    class PlaylistDiffCallback : DiffUtil.ItemCallback<Playlist>() {
        override fun areItemsTheSame(oldItem: Playlist, newItem: Playlist): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Playlist, newItem: Playlist): Boolean {
            return oldItem == newItem
        }
    }
}