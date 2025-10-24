package com.shubhamgupta.nebula_music.fragments

import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.shubhamgupta.nebula_music.MainActivity
import com.shubhamgupta.nebula_music.R
import com.shubhamgupta.nebula_music.adapters.PlaylistAdapter
import com.shubhamgupta.nebula_music.adapters.SongSelectionAdapter
import com.shubhamgupta.nebula_music.models.Playlist
import com.shubhamgupta.nebula_music.models.Song
import com.shubhamgupta.nebula_music.repository.SongRepository
import com.shubhamgupta.nebula_music.service.MusicService
import com.shubhamgupta.nebula_music.utils.PreferenceManager
import com.shubhamgupta.nebula_music.utils.SongUtils

class PlaylistsFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var btnCreatePlaylist: Button
    private lateinit var btnBack: ImageButton
    private var musicService: MusicService? = null
    private val playlists = mutableListOf<Playlist>()
    private lateinit var adapter: PlaylistAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_playlists, container, false)
        initializeViews(view)
        loadPlaylists()
        return view
    }

    override fun onResume() {
        super.onResume()
        // Lock drawer when fragment is visible
        (requireActivity() as MainActivity).setDrawerLocked(true)
        loadPlaylists()
    }

    override fun onPause() {
        super.onPause()
        // Unlock drawer when leaving fragment
        (requireActivity() as MainActivity).setDrawerLocked(false)
    }

    private fun initializeViews(view: View) {
        recyclerView = view.findViewById(R.id.recycler_view_playlists)
        tvEmpty = view.findViewById(R.id.tv_empty_playlists)
        btnCreatePlaylist = view.findViewById(R.id.btn_create_playlist)
        btnBack = view.findViewById(R.id.btn_back)

        val shuffleCard = view.findViewById<com.google.android.material.card.MaterialCardView>(R.id.shuffle_all_card)

        btnBack.setOnClickListener {
            requireActivity().supportFragmentManager.popBackStack()
        }

        shuffleCard.setOnClickListener {
            shuffleAllPlaylists()
        }

        btnCreatePlaylist.setOnClickListener {
            showCreatePlaylistDialog()
        }

        try {
            musicService = (requireActivity() as MainActivity).getMusicService()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadPlaylists() {
        playlists.clear()
        playlists.addAll(PreferenceManager.getPlaylists(requireContext()).sortedByDescending { it.createdAt })

        if (playlists.isEmpty()) {
            recyclerView.visibility = View.GONE
            tvEmpty.visibility = View.VISIBLE
            view?.findViewById<com.google.android.material.card.MaterialCardView>(R.id.shuffle_all_card)?.visibility = View.GONE
        } else {
            recyclerView.visibility = View.VISIBLE
            tvEmpty.visibility = View.GONE
            view?.findViewById<com.google.android.material.card.MaterialCardView>(R.id.shuffle_all_card)?.visibility = View.VISIBLE
        }

        setupRecyclerView()
    }

    private fun setupRecyclerView() {
        // Use GridLayoutManager with 2 columns
        recyclerView.layoutManager = GridLayoutManager(context, 2)
        adapter = PlaylistAdapter(playlists,
            onItemClick = { position ->
                // Open playlist songs page instead of playing directly
                openPlaylistSongs(position)
            },
            onMenuClick = { pos, menuItem ->
                handleMenuAction(pos, menuItem)
            },
            getAlbumArtForPlaylist = { playlist ->
                getPlaylistAlbumArt(playlist)
            }
        )
        recyclerView.adapter = adapter
    }

    // Add this method to PlaylistsFragment
    private fun openPlaylistSongs(position: Int) {
        val playlist = playlists[position]
        val fragment = PlaylistSongsFragment.newInstance(playlist)
        requireActivity().supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .addToBackStack("playlist_songs")
            .commit()
    }

    private fun getPlaylistAlbumArt(playlist: Playlist): Any? {
        if (playlist.songIds.isEmpty()) {
            return R.drawable.default_album_art
        }

        // Get the first song in the playlist for album art
        val firstSongId = playlist.songIds.first()
        val allSongs = SongRepository.getAllSongs(requireContext())
        val firstSong = allSongs.firstOrNull { it.id == firstSongId }

        return if (firstSong != null) {
            if (firstSong.embeddedArtBytes != null) {
                // Return embedded art bytes
                firstSong.embeddedArtBytes
            } else {
                // Return album art URI
                SongUtils.getAlbumArtUri(firstSong.albumId)
            }
        } else {
            // Return default album art resource
            R.drawable.default_album_art
        }
    }

    private fun handleMenuAction(position: Int, menuItem: String) {
        when (menuItem) {
            "play" -> playPlaylist(position)
            "rename" -> renamePlaylist(position)
            "delete" -> deletePlaylist(position)
            "add_songs" -> showAddSongsToPlaylistDialog(position, false)
        }
    }

    private fun showCreatePlaylistDialog() {
        try {
            val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_create_playlist, null)
            val input = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.et_playlist_name)

            // Set dialog background
            dialogView.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.dialog_background))

            val dialog = AlertDialog.Builder(requireContext(), R.style.AlertDialogTheme)
                .setTitle("Create New Playlist")
                .setView(dialogView)
                .setPositiveButton("CREATE", null)
                .setNegativeButton("CANCEL") { dialog, _ ->
                    dialog.dismiss()
                }
                .create()

            // Apply theme fixes
            applyDialogThemeFix(dialog)

            dialog.setOnShowListener {
                val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                positiveButton.setOnClickListener {
                    try {
                        val name = input.text.toString().trim()
                        if (name.isEmpty()) {
                            input.error = "Playlist name cannot be empty"
                            return@setOnClickListener
                        }

                        if (playlists.any { it.name.equals(name, ignoreCase = true) }) {
                            input.error = "Playlist with this name already exists"
                            return@setOnClickListener
                        }

                        val newPlaylist = Playlist(
                            id = System.currentTimeMillis(),
                            name = name,
                            createdAt = System.currentTimeMillis(),
                            songIds = mutableListOf()
                        )
                        playlists.add(newPlaylist)
                        PreferenceManager.savePlaylists(requireContext(), playlists)
                        loadPlaylists()
                        showToast("Playlist '$name' created")
                        dialog.dismiss()

                        // Automatically show add songs dialog for the new playlist
                        val newPosition = playlists.indexOfFirst { it.id == newPlaylist.id }
                        if (newPosition != -1) {
                            showAddSongsToPlaylistDialog(newPosition, true)
                        }
                    } catch (e: Exception) {
                        Log.e("PlaylistsFragment", "Error creating playlist", e)
                        showToast("Error creating playlist")
                    }
                }

                // Set button colors
                setDialogButtonColors(dialog)
            }

            dialog.show()
        } catch (e: Exception) {
            Log.e("PlaylistsFragment", "Error showing create playlist dialog", e)
            showToast("Error showing dialog")
        }
    }

    private fun renamePlaylist(position: Int) {
        val playlist = playlists[position]
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_create_playlist, null)
        val input = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.et_playlist_name)
        input.setText(playlist.name)
        input.setSelection(playlist.name.length)

        // Set dialog background
        dialogView.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.dialog_background))

        val dialog = AlertDialog.Builder(requireContext(), R.style.AlertDialogTheme)
            .setTitle("Rename Playlist")
            .setView(dialogView)
            .setPositiveButton("RENAME", null)
            .setNegativeButton("CANCEL") { dialog, _ ->
                dialog.dismiss()
            }
            .create()

        // Apply theme fixes
        applyDialogThemeFix(dialog)

        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveButton.setOnClickListener {
                val newName = input.text.toString().trim()
                if (newName.isEmpty()) {
                    input.error = "Playlist name cannot be empty"
                    return@setOnClickListener
                }

                if (playlists.any { it != playlist && it.name.equals(newName, ignoreCase = true) }) {
                    input.error = "Playlist with this name already exists"
                    return@setOnClickListener
                }

                playlist.name = newName
                PreferenceManager.savePlaylists(requireContext(), playlists)
                loadPlaylists()
                showToast("Playlist renamed")
                dialog.dismiss()
            }

            // Set button colors
            setDialogButtonColors(dialog)
        }

        dialog.show()
    }

    private fun deletePlaylist(position: Int) {
        val playlist = playlists[position]

        val dialog = AlertDialog.Builder(requireContext(), R.style.AlertDialogTheme)
            .setTitle("Delete Playlist")
            .setMessage("Are you sure you want to delete '${playlist.name}'?")
            .setPositiveButton("DELETE") { dialog, _ ->
                playlists.removeAt(position)
                PreferenceManager.savePlaylists(requireContext(), playlists)
                loadPlaylists()
                showToast("Playlist deleted")
                dialog.dismiss()
            }
            .setNegativeButton("CANCEL") { dialog, _ ->
                dialog.dismiss()
            }
            .create()

        // Apply theme fixes
        applyDialogThemeFix(dialog)

        dialog.setOnShowListener {
            // Set button colors
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(ContextCompat.getColor(requireContext(), R.color.error_red))
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(ContextCompat.getColor(requireContext(), R.color.button_negative))
        }

        dialog.show()
    }

    private fun applyDialogThemeFix(dialog: AlertDialog) {
        // Ensure proper theming for dark mode
        val titleTextView = dialog.findViewById<TextView>(android.R.id.title)
        val messageTextView = dialog.findViewById<TextView>(android.R.id.message)

        titleTextView?.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
        messageTextView?.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))

        // Set dialog window background
        dialog.window?.setBackgroundDrawableResource(R.color.dialog_background)
    }

    private fun setDialogButtonColors(dialog: AlertDialog) {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(ContextCompat.getColor(requireContext(), R.color.button_positive))
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(ContextCompat.getColor(requireContext(), R.color.button_negative))
    }

    private fun showAddSongsToPlaylistDialog(position: Int, isNewPlaylist: Boolean = false) {
        val playlist = playlists[position]

        val allSongs = SongRepository.getAllSongs(requireContext())
        val currentPlaylistSongIds = playlist.songIds.toSet()

        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_add_songs, null)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.songs_recycler_view)
        val searchBar = dialogView.findViewById<EditText>(R.id.search_bar)
        val tvTitle = dialogView.findViewById<TextView>(R.id.dialog_title)
        val selectedCount = dialogView.findViewById<TextView>(R.id.selected_count)
        val totalSongs = dialogView.findViewById<TextView>(R.id.total_songs)
        val selectAllButton = dialogView.findViewById<Button>(R.id.btn_select_all)
        val submitButton = dialogView.findViewById<Button>(R.id.btn_submit)
        val cancelButton = dialogView.findViewById<Button>(R.id.btn_cancel) // Find new CANCEL button

        tvTitle.text = "Add songs to '${playlist.name}'"
        totalSongs.text = "Total songs: ${allSongs.size}"

        lateinit var songAdapter: SongSelectionAdapter

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        songAdapter = SongSelectionAdapter(
            songs = allSongs,
            selectedSongIds = currentPlaylistSongIds,
            onSongSelected = { songId, isSelected ->
                updateSelectedCount(songAdapter, selectedCount)
            }
        )
        recyclerView.adapter = songAdapter

        updateSelectedCount(songAdapter, selectedCount)

        searchBar.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val query = s.toString().trim()
                songAdapter.filterSongs(query)
                updateSelectedCount(songAdapter, selectedCount)
            }
        })

        selectAllButton.setOnClickListener {
            songAdapter.selectAll()
            updateSelectedCount(songAdapter, selectedCount)
        }

        val dialog = AlertDialog.Builder(requireContext(), R.style.AlertDialogTheme)
            .setView(dialogView)
            // REMOVED .setNegativeButton("CANCEL", ...)
            .create()

        // Apply theme fixes
        applyDialogThemeFix(dialog)

        // Style buttons
        selectAllButton.setTextColor(ContextCompat.getColor(requireContext(), R.color.button_positive))
        submitButton.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white))

        // Handle submit button click
        submitButton.setOnClickListener {
            val selectedSongs = songAdapter.getSelectedSongs()
            if (selectedSongs.isNotEmpty()) {
                addSongsToPlaylist(position, selectedSongs)
                showToast("Added ${selectedSongs.size} songs to '${playlist.name}'")
                dialog.dismiss()

                // Show the dialog again for adding more songs if it's not a new playlist
                if (!isNewPlaylist) {
                    showAddSongsToPlaylistDialog(position, false)
                }
            } else {
                showToast("Please select at least one song")
            }
        }

        // Handle new CANCEL button click
        cancelButton.setOnClickListener {
            dialog.dismiss()
            // If it's a new playlist and user cancels without adding songs, delete the empty playlist
            if (isNewPlaylist && playlist.songIds.isEmpty()) {
                playlists.remove(playlist)
                PreferenceManager.savePlaylists(requireContext(), playlists)
                loadPlaylists()
                showToast("Empty playlist deleted")
            }
        }

        dialog.show()

        // MAKE DIALOG FULLSCREEN
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        // Re-set background after layout change
        dialog.window?.setBackgroundDrawableResource(R.color.dialog_background)
    }

    private fun updateSelectedCount(songAdapter: SongSelectionAdapter, textView: TextView) {
        val selectedCount = songAdapter.getSelectedSongsCount()
        textView.text = "$selectedCount songs selected"
    }

    private fun addSongsToPlaylist(position: Int, songIds: List<Long>) {
        val playlist = playlists[position]
        val updatedSongIds = playlist.songIds.toMutableList()

        val newSongs = songIds.filter { it !in updatedSongIds }
        updatedSongIds.addAll(newSongs)

        playlist.songIds.clear()
        playlist.songIds.addAll(updatedSongIds)
        PreferenceManager.savePlaylists(requireContext(), playlists)
        loadPlaylists()
    }

    private fun playPlaylist(position: Int) {
        val playlist = playlists[position]
        val playlistSongs = getPlaylistSongs(playlist)

        if (playlistSongs.isNotEmpty()) {
            musicService?.startPlayback(ArrayList(playlistSongs), 0)
            (requireActivity() as MainActivity).navigateToNowPlaying()
            showToast("Playing playlist: ${playlist.name}")
        } else {
            showToast("Playlist is empty")
        }
    }

    private fun shuffleAllPlaylists() {
        val allPlaylistSongs = mutableListOf<Song>()
        playlists.forEach { playlist ->
            allPlaylistSongs.addAll(getPlaylistSongs(playlist))
        }

        if (allPlaylistSongs.isNotEmpty()) {
            val shuffledSongs = allPlaylistSongs.shuffled()
            musicService?.startPlayback(ArrayList(shuffledSongs), 0)
            musicService?.toggleShuffle()
            (requireActivity() as MainActivity).navigateToNowPlaying()
            showToast("Shuffling all playlists (${allPlaylistSongs.size} songs)")
        } else {
            showToast("No songs in playlists to shuffle")
        }
    }

    private fun getPlaylistSongs(playlist: Playlist): List<Song> {
        val allSongs = SongRepository.getAllSongs(requireContext())
        return allSongs.filter { song -> playlist.songIds.contains(song.id) }
    }

    private fun showToast(message: String) {
        android.widget.Toast.makeText(requireContext(), message, android.widget.Toast.LENGTH_SHORT).show()
    }

    fun refreshData() {
        loadPlaylists()
    }
}