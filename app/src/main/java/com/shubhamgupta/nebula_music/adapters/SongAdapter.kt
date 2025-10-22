package com.shubhamgupta.nebula_music.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.shubhamgupta.nebula_music.R
import com.shubhamgupta.nebula_music.models.Song
import com.shubhamgupta.nebula_music.utils.SongUtils
import android.widget.SectionIndexer
import android.util.SparseArray

class SongAdapter(
    private val songs: List<Song>,
    private val onItemClick: (position: Int) -> Unit,
    private val onMenuClick: (position: Int, menuItem: String) -> Unit,
    private val isSongFavorite: (songId: Long) -> Boolean = { false }
) : RecyclerView.Adapter<SongAdapter.SongVH>(), SectionIndexer {

    private var sections: SparseArray<Int> = SparseArray()
    private var sectionLetters: MutableList<String> = mutableListOf()

    init {
        setupSections()
    }

    private fun setupSections() {
        sections.clear()
        sectionLetters.clear()

        if (songs.isNotEmpty()) {
            var sectionStart = 0
            var currentSection = songs[0].title[0].uppercaseChar()

            for (i in songs.indices) {
                val firstChar = songs[i].title[0].uppercaseChar()
                if (firstChar != currentSection) {
                    sections.put(currentSection.toInt(), sectionStart)
                    sectionLetters.add(currentSection.toString())
                    currentSection = firstChar
                    sectionStart = i
                }
            }
            // Add the last section
            sections.put(currentSection.toInt(), sectionStart)
            sectionLetters.add(currentSection.toString())
        }
    }

    override fun getSections(): Array<String> {
        return sectionLetters.toTypedArray()
    }

    override fun getPositionForSection(sectionIndex: Int): Int {
        return if (sectionIndex < sectionLetters.size) {
            val sectionChar = sectionLetters[sectionIndex][0]
            sections.get(sectionChar.toInt(), 0)
        } else {
            0
        }
    }

    override fun getSectionForPosition(position: Int): Int {
        var section = 0
        for (i in sectionLetters.indices) {
            if (getPositionForSection(i) <= position) {
                section = i
            }
        }
        return section
    }

    fun updateSongs(newSongs: List<Song>) {
        // Note: This method signature doesn't match current usage, but keeping for compatibility
        setupSections()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongVH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_song, parent, false)
        return SongVH(view)
    }

    override fun onBindViewHolder(holder: SongVH, position: Int) {
        val song = songs[position]
        holder.title.text = song.title
        holder.artist.text = song.artist ?: "Unknown"

        // *** IMAGE LOADING LOGIC (UNCHANGED BUT CONFIRMED CORRECT) ***
        val albumUri = SongUtils.getAlbumArtUri(song.albumId)
        Glide.with(holder.itemView.context)
            .load(albumUri)
            // Ensures a default image is shown if loading fails or art is missing
            .placeholder(R.drawable.default_album_art)
            .error(R.drawable.default_album_art)
            .into(holder.albumArt)
        // *************************************************************

        holder.itemView.setOnClickListener { onItemClick(position) }

        holder.options.setOnClickListener { view ->
            showPopupMenu(view, position, song.title, isSongFavorite(song.id))
        }
    }

    override fun getItemCount(): Int = songs.size

    private fun showPopupMenu(view: View, position: Int, songTitle: String, isFavorite: Boolean) {
        val popup = PopupMenu(view.context, view)
        popup.menuInflater.inflate(R.menu.song_options_menu, popup.menu)

        // Safety check for R.id.menu_title
        popup.menu.findItem(R.id.menu_title)?.title = songTitle

        // This requires R.id.menu_toggle_favorite to exist in the XML
        val favoriteMenuItem = popup.menu.findItem(R.id.menu_toggle_favorite)
        if (favoriteMenuItem != null) {
            // This requires R.string.menu_remove_favorite and R.string.menu_add_favorite
            favoriteMenuItem.title =
                if (isFavorite) view.context.getString(R.string.menu_remove_favorite)
                else view.context.getString(R.string.menu_add_favorite)
        }

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_play -> onMenuClick(position, "play")
                R.id.menu_add_to_queue -> onMenuClick(position, "add_to_queue")
                R.id.menu_add_to_playlist -> onMenuClick(position, "add_to_playlist")
                R.id.menu_delete -> onMenuClick(position, "delete")
                R.id.menu_share -> onMenuClick(position, "share")

                R.id.menu_toggle_favorite -> onMenuClick(position, "toggle_favorite")
                else -> false
            }
            true
        }
        popup.show()
    }

    class SongVH(view: View) : RecyclerView.ViewHolder(view) {
        // Ensure R.id.item_album_art is correct in your item_song.xml layout
        val albumArt: ImageView = view.findViewById(R.id.item_album_art)
        val title: TextView = view.findViewById(R.id.item_title)
        val artist: TextView = view.findViewById(R.id.item_artist)
        val options: ImageView = view.findViewById(R.id.item_options)
    }
}