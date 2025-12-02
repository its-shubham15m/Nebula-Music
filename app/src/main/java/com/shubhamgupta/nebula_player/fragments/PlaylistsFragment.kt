package com.shubhamgupta.nebula_player.fragments

import android.app.AlertDialog
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.shubhamgupta.nebula_player.MainActivity
import com.shubhamgupta.nebula_player.R
import com.shubhamgupta.nebula_player.adapters.PlaylistAdapter
import com.shubhamgupta.nebula_player.adapters.SongSelectionAdapter
import com.shubhamgupta.nebula_player.models.Playlist
import com.shubhamgupta.nebula_player.models.Song
import com.shubhamgupta.nebula_player.repository.SongRepository
import com.shubhamgupta.nebula_player.service.MusicService
import com.shubhamgupta.nebula_player.utils.PreferenceManager
import com.shubhamgupta.nebula_player.utils.SongUtils

class PlaylistsFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var btnCreatePlaylist: MaterialCardView
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
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Add padding for MiniPlayer and BottomNav
        val bottomPadding = (140 * resources.displayMetrics.density).toInt()
        recyclerView.clipToPadding = false
        recyclerView.setPadding(0, 0, 0, bottomPadding)

        loadPlaylists()
    }

    override fun onResume() {
        super.onResume()
        (requireActivity() as MainActivity).setDrawerLocked(true)
        loadPlaylists()
    }

    override fun onPause() {
        super.onPause()
        (requireActivity() as MainActivity).setDrawerLocked(false)
    }

    private fun initializeViews(view: View) {
        recyclerView = view.findViewById(R.id.recycler_view_playlists)
        tvEmpty = view.findViewById(R.id.tv_empty_playlists)
        btnCreatePlaylist = view.findViewById(R.id.btn_create_playlist)
        btnBack = view.findViewById(R.id.btn_back)

        val shuffleCard = view.findViewById<MaterialCardView>(R.id.shuffle_all_card)

        btnBack.setOnClickListener {
            val parent = parentFragment
            if (parent is HomePageFragment) {
                parent.childFragmentManager.popBackStack()
            } else {
                requireActivity().supportFragmentManager.popBackStack()
            }
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

        setupRecyclerView()
    }

    private fun loadPlaylists() {
        playlists.clear()
        playlists.addAll(PreferenceManager.getPlaylists(requireContext()).sortedByDescending { it.createdAt })

        // Submit to ListAdapter
        if (::adapter.isInitialized) {
            adapter.submitList(ArrayList(playlists))
        }

        if (playlists.isEmpty()) {
            recyclerView.visibility = View.GONE
            tvEmpty.visibility = View.VISIBLE
            view?.findViewById<MaterialCardView>(R.id.shuffle_all_card)?.visibility = View.GONE
        } else {
            recyclerView.visibility = View.VISIBLE
            tvEmpty.visibility = View.GONE
            view?.findViewById<MaterialCardView>(R.id.shuffle_all_card)?.visibility = View.VISIBLE
        }
    }

    private fun setupRecyclerView() {
        recyclerView.layoutManager = GridLayoutManager(context, 2)

        adapter = PlaylistAdapter(
            onItemClick = { position ->
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

    private fun openPlaylistSongs(position: Int) {
        if (position < 0 || position >= adapter.currentList.size) return
        val playlist = adapter.currentList[position]

        val fragment = PlaylistSongsFragment.newInstance(playlist)

        // Check if we are inside HomePageFragment
        val parent = parentFragment
        if (parent is HomePageFragment) {
            parent.childFragmentManager.beginTransaction()
                .setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left, R.anim.slide_in_left, R.anim.slide_out_right)
                .replace(R.id.home_content_container, fragment)
                .addToBackStack("playlist_songs")
                .commit()
            parent.updateMiniPlayerPosition()
        } else {
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .addToBackStack("playlist_songs")
                .commit()
        }
    }

    private fun getPlaylistAlbumArt(playlist: Playlist): Any? {
        if (playlist.songIds.isEmpty()) {
            return R.drawable.default_album_art
        }
        val firstSongId = playlist.songIds.first()
        val allSongs = SongRepository.getAllSongs(requireContext())
        val firstSong = allSongs.firstOrNull { it.id == firstSongId }

        return if (firstSong != null) {
            if (firstSong.embeddedArtBytes != null) {
                firstSong.embeddedArtBytes
            } else {
                SongUtils.getAlbumArtUri(firstSong.albumId)
            }
        } else {
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

    // ==================================================================================
    // NEW PROFESSIONAL DIALOG LOGIC START
    // ==================================================================================

    private fun showCreatePlaylistDialog() {
        showPlaylistInputDialog(
            title = "Create New Playlist",
            positiveButtonText = "Create",
            currentName = "",
            onConfirm = { name, dialog ->
                try {
                    val finalName = name.trim()

                    if (playlists.any { it.name.equals(finalName, ignoreCase = true) }) {
                        return@showPlaylistInputDialog "Playlist with this name already exists"
                    }

                    val newPlaylist = Playlist(
                        id = System.currentTimeMillis(),
                        name = finalName,
                        createdAt = System.currentTimeMillis(),
                        songIds = mutableListOf()
                    )

                    playlists.add(newPlaylist)
                    PreferenceManager.savePlaylists(requireContext(), playlists)
                    loadPlaylists()
                    showToast("Playlist '$finalName' created")
                    dialog.dismiss()

                    // Automatically open add songs dialog for the new playlist
                    val newPosition = playlists.indexOfFirst { it.id == newPlaylist.id }
                    if (newPosition != -1) {
                        showAddSongsToPlaylistDialog(newPosition, true)
                    }
                    return@showPlaylistInputDialog null // Success
                } catch (e: Exception) {
                    Log.e("PlaylistsFragment", "Error creating playlist", e)
                    return@showPlaylistInputDialog "Error creating playlist"
                }
            }
        )
    }

    private fun renamePlaylist(position: Int) {
        if (position < 0 || position >= adapter.currentList.size) return
        val playlist = adapter.currentList[position]

        showPlaylistInputDialog(
            title = "Rename Playlist",
            positiveButtonText = "Rename",
            currentName = playlist.name,
            onConfirm = { name, dialog ->
                val finalName = name.trim()

                if (playlists.any { it != playlist && it.name.equals(finalName, ignoreCase = true) }) {
                    return@showPlaylistInputDialog "Name already taken"
                }

                playlist.name = finalName
                PreferenceManager.savePlaylists(requireContext(), playlists)
                loadPlaylists()
                showToast("Playlist renamed")
                dialog.dismiss()
                return@showPlaylistInputDialog null // Success
            }
        )
    }

    private fun showPlaylistInputDialog(
        title: String,
        positiveButtonText: String,
        currentName: String,
        onConfirm: (String, AlertDialog) -> String?
    ) {
        try {
            // Inflate the new professional layout
            val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_playlist_input, null)

            val tvTitle = dialogView.findViewById<TextView>(R.id.tv_dialog_title)
            val textInputLayout = dialogView.findViewById<TextInputLayout>(R.id.til_playlist_name)
            val input = dialogView.findViewById<TextInputEditText>(R.id.et_playlist_name)
            val btnCancel = dialogView.findViewById<Button>(R.id.btn_dialog_cancel)
            val btnConfirm = dialogView.findViewById<Button>(R.id.btn_dialog_confirm)

            tvTitle.text = title
            btnConfirm.text = positiveButtonText
            input.setText(currentName)
            if (currentName.isNotEmpty()) {
                input.setSelection(currentName.length)
            }
            input.requestFocus()

            val dialog = AlertDialog.Builder(requireContext(), R.style.AlertDialogTheme)
                .setView(dialogView)
                .create()

            // Transparent background to respect XML corners
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

            btnCancel.setOnClickListener { dialog.dismiss() }

            btnConfirm.setOnClickListener {
                val name = input.text.toString()

                if (name.isBlank()) {
                    textInputLayout.error = "Name cannot be empty"
                    return@setOnClickListener
                }

                val error = onConfirm(name, dialog)
                if (error != null) {
                    textInputLayout.error = error
                } else {
                    textInputLayout.error = null
                }
            }

            // Clear errors on type
            input.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    textInputLayout.error = null
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })

            dialog.show()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)

        } catch (e: Exception) {
            Log.e("PlaylistsFragment", "Error showing input dialog", e)
            showToast("Error opening dialog")
        }
    }

    // ==================================================================================
    // NEW PROFESSIONAL DIALOG LOGIC END
    // ==================================================================================

    private fun deletePlaylist(position: Int) {
        if (position < 0 || position >= adapter.currentList.size) return
        val playlist = adapter.currentList[position]

        val dialog = AlertDialog.Builder(requireContext(), R.style.AlertDialogTheme)
            .setTitle("Delete Playlist")
            .setMessage("Are you sure you want to delete '${playlist.name}'?")
            .setPositiveButton("DELETE") { dialog, _ ->
                playlists.remove(playlist)
                PreferenceManager.savePlaylists(requireContext(), playlists)
                loadPlaylists()
                showToast("Playlist deleted")
                dialog.dismiss()
            }
            .setNegativeButton("CANCEL") { dialog, _ -> dialog.dismiss() }
            .create()

        applyDialogThemeFix(dialog)
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(ContextCompat.getColor(requireContext(), R.color.error_red))
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(ContextCompat.getColor(requireContext(), R.color.button_negative))
        }
        dialog.show()
    }

    // Kept for delete dialog and add songs dialog
    private fun applyDialogThemeFix(dialog: AlertDialog) {
        val titleTextView = dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)
        val messageTextView = dialog.findViewById<TextView>(android.R.id.message)
        titleTextView?.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
        messageTextView?.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
        dialog.window?.setBackgroundDrawableResource(R.color.dialog_background)
    }

    private fun setDialogButtonColors(dialog: AlertDialog) {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(ContextCompat.getColor(requireContext(), R.color.button_positive))
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(ContextCompat.getColor(requireContext(), R.color.button_negative))
    }

    private fun showAddSongsToPlaylistDialog(position: Int, isNewPlaylist: Boolean = false) {
        val targetPlaylist = if (position < adapter.currentList.size) adapter.currentList[position] else playlists.lastOrNull()

        if (targetPlaylist == null) return

        val allSongs = SongRepository.getAllSongs(requireContext())
        val currentPlaylistSongIds = targetPlaylist.songIds.toSet()

        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_add_songs, null)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.songs_recycler_view)
        val searchBar = dialogView.findViewById<EditText>(R.id.search_bar)
        val tvTitle = dialogView.findViewById<TextView>(R.id.dialog_title)
        val selectedCount = dialogView.findViewById<TextView>(R.id.selected_count)
        val totalSongs = dialogView.findViewById<TextView>(R.id.total_songs)
        val selectAllButton = dialogView.findViewById<Button>(R.id.btn_select_all)
        val submitButton = dialogView.findViewById<Button>(R.id.btn_submit)
        val cancelButton = dialogView.findViewById<Button>(R.id.btn_cancel)

        tvTitle.text = "Add songs to '${targetPlaylist.name}'"
        totalSongs.text = "Total songs: ${allSongs.size}"

        lateinit var songAdapter: SongSelectionAdapter
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        songAdapter = SongSelectionAdapter(
            songs = allSongs,
            selectedSongIds = currentPlaylistSongIds,
            onSongSelected = { _, _ ->
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
            .create()

        applyDialogThemeFix(dialog)
        selectAllButton.setTextColor(ContextCompat.getColor(requireContext(), R.color.button_positive))
        submitButton.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white))

        submitButton.setOnClickListener {
            val selectedSongs = songAdapter.getSelectedSongs()
            if (selectedSongs.isNotEmpty()) {
                addSongsToPlaylist(targetPlaylist, selectedSongs)
                showToast("Added ${selectedSongs.size} songs to '${targetPlaylist.name}'")
                dialog.dismiss()
            } else {
                showToast("Please select at least one song")
            }
        }

        cancelButton.setOnClickListener {
            dialog.dismiss()
            // If it was a new playlist and user cancelled adding songs, we keep the playlist (it's empty)
            // or you could uncomment the logic below to delete it if empty
            if (isNewPlaylist && targetPlaylist.songIds.isEmpty()) {
                playlists.remove(targetPlaylist)
                PreferenceManager.savePlaylists(requireContext(), playlists)
                loadPlaylists()
                showToast("Empty playlist deleted")
            }
        }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.window?.setBackgroundDrawableResource(R.color.dialog_background)
    }

    private fun updateSelectedCount(songAdapter: SongSelectionAdapter, textView: TextView) {
        val selectedCount = songAdapter.getSelectedSongsCount()
        textView.text = "$selectedCount songs selected"
    }

    private fun addSongsToPlaylist(playlist: Playlist, songIds: List<Long>) {
        val updatedSongIds = playlist.songIds.toMutableList()
        val newSongs = songIds.filter { it !in updatedSongIds }
        updatedSongIds.addAll(newSongs)
        playlist.songIds.clear()
        playlist.songIds.addAll(updatedSongIds)

        // Find playlist in master list and update it
        val index = playlists.indexOfFirst { it.id == playlist.id }
        if (index != -1) {
            playlists[index] = playlist
            PreferenceManager.savePlaylists(requireContext(), playlists)
            loadPlaylists()
        }
    }

    private fun playPlaylist(position: Int) {
        if (position < 0 || position >= adapter.currentList.size) return
        val playlist = adapter.currentList[position]

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