package com.shubhamgupta.nebula_music.fragments

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.imageview.ShapeableImageView
import com.shubhamgupta.nebula_music.R
import com.shubhamgupta.nebula_music.adapters.SongAdapter
import com.shubhamgupta.nebula_music.models.Playlist
import com.shubhamgupta.nebula_music.models.Song
import com.shubhamgupta.nebula_music.repository.SongRepository
import com.shubhamgupta.nebula_music.service.MusicService
import com.shubhamgupta.nebula_music.utils.PreferenceManager
import com.shubhamgupta.nebula_music.utils.SongUtils
import java.util.concurrent.TimeUnit

class PlaylistSongsFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var playlistArt: ShapeableImageView
    private lateinit var playlistName: TextView
    private lateinit var songCount: TextView
    private lateinit var playlistDuration: TextView
    private lateinit var emptyView: TextView
    private lateinit var btnBack: ImageButton
    private lateinit var btnOptions: ImageButton
    private lateinit var btnPlay: com.google.android.material.button.MaterialButton
    private lateinit var btnShuffle: com.google.android.material.button.MaterialButton

    private var currentPlaylist: Playlist? = null
    private var playlistSongs: List<Song> = emptyList()
    private var musicService: MusicService? = null

    private val playbackReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "SONG_CHANGED", "PLAYBACK_STATE_CHANGED" -> {
                    // Refresh the adapter to update play states
                    currentPlaylist?.let { setupPlaylistSongs(it) }
                }
            }
        }
    }

    private fun updateStatusBarColor() {
        val activity = requireActivity() as com.shubhamgupta.nebula_music.MainActivity

        // Get the current theme to determine which color to use
        val currentTheme = com.shubhamgupta.nebula_music.utils.ThemeManager.getCurrentTheme(requireContext())

        // Set status bar color to match colorPrimaryContainer
        when (currentTheme) {
            com.shubhamgupta.nebula_music.utils.ThemeManager.THEME_LIGHT -> {
                // For light theme, use a light color
                activity.window.statusBarColor = ContextCompat.getColor(requireContext(), R.color.colorPrimaryContainer)
                WindowCompat.getInsetsController(activity.window, activity.window.decorView).isAppearanceLightStatusBars = true
            }
            com.shubhamgupta.nebula_music.utils.ThemeManager.THEME_DARK -> {
                // For dark theme, use a dark color
                activity.window.statusBarColor = ContextCompat.getColor(requireContext(), R.color.colorPrimaryContainer)
                WindowCompat.getInsetsController(activity.window, activity.window.decorView).isAppearanceLightStatusBars = false
            }
            else -> {
                // For system theme, follow system
                val isLightTheme = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_NO
                activity.window.statusBarColor = ContextCompat.getColor(requireContext(), R.color.colorPrimaryContainer)
                WindowCompat.getInsetsController(activity.window, activity.window.decorView).isAppearanceLightStatusBars = isLightTheme
            }
        }

        // Make sure the status bar is not transparent
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
    }

    private fun resetStatusBarColor() {
        val activity = requireActivity() as com.shubhamgupta.nebula_music.MainActivity
        val currentTheme = com.shubhamgupta.nebula_music.utils.ThemeManager.getCurrentTheme(requireContext())

        // Reset to default background color
        when (currentTheme) {
            com.shubhamgupta.nebula_music.utils.ThemeManager.THEME_LIGHT -> {
                activity.window.statusBarColor = ContextCompat.getColor(requireContext(), R.color.colorBackground)
                WindowCompat.getInsetsController(activity.window, activity.window.decorView).isAppearanceLightStatusBars = true
            }
            com.shubhamgupta.nebula_music.utils.ThemeManager.THEME_DARK -> {
                activity.window.statusBarColor = ContextCompat.getColor(requireContext(), R.color.colorBackground)
                WindowCompat.getInsetsController(activity.window, activity.window.decorView).isAppearanceLightStatusBars = false
            }
            else -> {
                val isLightTheme = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_NO
                activity.window.statusBarColor = ContextCompat.getColor(requireContext(), R.color.colorBackground)
                WindowCompat.getInsetsController(activity.window, activity.window.decorView).isAppearanceLightStatusBars = isLightTheme
            }
        }
    }

    companion object {
        private const val ARG_PLAYLIST = "playlist"

        fun newInstance(playlist: Playlist): PlaylistSongsFragment {
            val fragment = PlaylistSongsFragment()
            val args = Bundle()
            args.putSerializable(ARG_PLAYLIST, playlist)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_playlist_songs, container, false)
        initializeViews(view)
        return view
    }

    // In onResume method:

    override fun onResume() {
        super.onResume()
        // Lock drawer when fragment is visible
        (requireActivity() as com.shubhamgupta.nebula_music.MainActivity).setDrawerLocked(true)

        // Update status bar color for this fragment
        updateStatusBarColor()

        // Register broadcast receiver for playback state changes with proper flags
        val filter = IntentFilter().apply {
            addAction("SONG_CHANGED")
            addAction("PLAYBACK_STATE_CHANGED")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireActivity().registerReceiver(playbackReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            requireActivity().registerReceiver(playbackReceiver, filter)
        }

        // Refresh data when returning to fragment
        currentPlaylist?.let { setupPlaylistSongs(it) }
    }

    override fun onPause() {
        super.onPause()
        // Unlock drawer when leaving fragment
        (requireActivity() as com.shubhamgupta.nebula_music.MainActivity).setDrawerLocked(false)

        // Reset status bar color when leaving this fragment
        resetStatusBarColor()

        try {
            requireActivity().unregisterReceiver(playbackReceiver)
        } catch (e: Exception) {
            // Ignore if receiver was not registered
        }
    }

    private fun initializeViews(view: View) {
        recyclerView = view.findViewById(R.id.songs_recycler_view)
        playlistArt = view.findViewById(R.id.iv_playlist_art)
        playlistName = view.findViewById(R.id.tv_playlist_name)
        songCount = view.findViewById(R.id.tv_song_count)
        playlistDuration = view.findViewById(R.id.tv_playlist_duration)
        emptyView = view.findViewById(R.id.tv_empty_playlist)
        btnBack = view.findViewById(R.id.btn_back)
        btnOptions = view.findViewById(R.id.btn_playlist_options)
        btnPlay = view.findViewById(R.id.btn_play_playlist)
        btnShuffle = view.findViewById(R.id.btn_shuffle_playlist)

        recyclerView.layoutManager = LinearLayoutManager(context)

        // Fix for Serializable retrieval
        currentPlaylist = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arguments?.getSerializable(ARG_PLAYLIST, Playlist::class.java)
        } else {
            @Suppress("DEPRECATION")
            arguments?.getSerializable(ARG_PLAYLIST) as? Playlist
        }

        currentPlaylist?.let { setupPlaylistSongs(it) }

        btnBack.setOnClickListener {
            requireActivity().supportFragmentManager.popBackStack()
        }

        btnPlay.setOnClickListener {
            playPlaylist()
        }

        btnShuffle.setOnClickListener {
            shufflePlaylist()
        }

        btnOptions.setOnClickListener { view ->
            showPlaylistOptionsMenu(view)
        }

        try {
            musicService = (requireActivity() as com.shubhamgupta.nebula_music.MainActivity).getMusicService()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupPlaylistSongs(playlist: Playlist) {
        playlistName.text = playlist.name
        playlistSongs = getPlaylistSongs(playlist)

        // Update song count
        songCount.text = "${playlistSongs.size} songs"

        // Calculate and display total duration
        val totalDuration = playlistSongs.sumOf { it.duration }
        val minutes = TimeUnit.MILLISECONDS.toMinutes(totalDuration)
        playlistDuration.text = "$minutes min"

        // Load playlist art
        loadPlaylistArt(playlist)

        // Setup adapter
        if (playlistSongs.isNotEmpty()) {
            emptyView.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE

            val adapter = SongAdapter(
                songs = playlistSongs,
                onItemClick = { pos -> openNowPlaying(pos) },
                onMenuClick = { pos, menuItem -> handleMenuAction(pos, menuItem) },
                isSongFavorite = { songId -> PreferenceManager.isFavorite(requireContext(), songId) }
            )
            recyclerView.adapter = adapter

            // Enable buttons
            btnPlay.isEnabled = true
            btnShuffle.isEnabled = true
        } else {
            emptyView.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE

            // Disable buttons when no songs
            btnPlay.isEnabled = false
            btnShuffle.isEnabled = false
        }
    }

    private fun loadPlaylistArt(playlist: Playlist) {
        if (playlist.songIds.isEmpty()) {
            Glide.with(this)
                .load(R.drawable.default_album_art)
                .into(playlistArt)
            return
        }

        // Get the first song in the playlist for album art
        val firstSongId = playlist.songIds.first()
        val allSongs = SongRepository.getAllSongs(requireContext())
        val firstSong = allSongs.firstOrNull { it.id == firstSongId }

        if (firstSong != null) {
            if (firstSong.embeddedArtBytes != null) {
                // Load embedded art bytes
                Glide.with(this)
                    .load(firstSong.embeddedArtBytes)
                    .placeholder(R.drawable.default_album_art)
                    .error(R.drawable.default_album_art)
                    .into(playlistArt)
            } else {
                // Load album art URI
                val albumUri = SongUtils.getAlbumArtUri(firstSong.albumId)
                Glide.with(this)
                    .load(albumUri)
                    .placeholder(R.drawable.default_album_art)
                    .error(R.drawable.default_album_art)
                    .into(playlistArt)
            }
        } else {
            Glide.with(this)
                .load(R.drawable.default_album_art)
                .into(playlistArt)
        }
    }

    private fun getPlaylistSongs(playlist: Playlist): List<Song> {
        val allSongs = SongRepository.getAllSongs(requireContext())
        return allSongs.filter { song -> playlist.songIds.contains(song.id) }
    }

    private fun showPlaylistOptionsMenu(view: View) {
        val popup = PopupMenu(view.context, view)
        popup.menuInflater.inflate(R.menu.playlist_options_menu, popup.menu)

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_play_playlist -> {
                    playPlaylist()
                    true
                }
                R.id.menu_add_songs -> {
                    // Show add songs dialog directly from this fragment
                    showAddSongsToPlaylistDialog()
                    true
                }
                R.id.menu_rename_playlist -> {
                    showRenamePlaylistDialog()
                    true
                }
                R.id.menu_delete_playlist -> {
                    showDeletePlaylistDialog()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showAddSongsToPlaylistDialog() {
        currentPlaylist?.let { playlist ->
            val allSongs = SongRepository.getAllSongs(requireContext())
            val currentPlaylistSongIds = playlist.songIds.toSet()

            val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_add_songs, null)
            val recyclerView = dialogView.findViewById<RecyclerView>(R.id.songs_recycler_view)
            val searchBar = dialogView.findViewById<android.widget.EditText>(R.id.search_bar)
            val tvTitle = dialogView.findViewById<TextView>(R.id.dialog_title)
            val selectedCount = dialogView.findViewById<TextView>(R.id.selected_count)
            val totalSongs = dialogView.findViewById<TextView>(R.id.total_songs)
            val selectAllButton = dialogView.findViewById<android.widget.Button>(R.id.btn_select_all)
            val submitButton = dialogView.findViewById<android.widget.Button>(R.id.btn_submit)

            // Set dialog background
            dialogView.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.dialog_background))

            // Apply text colors
            tvTitle.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
            selectedCount.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            totalSongs.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            searchBar.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
            searchBar.setHintTextColor(ContextCompat.getColor(requireContext(), R.color.text_hint))

            tvTitle.text = "Add songs to '${playlist.name}'"
            totalSongs.text = "Total songs: ${allSongs.size}"

            lateinit var songAdapter: com.shubhamgupta.nebula_music.adapters.SongSelectionAdapter

            recyclerView.layoutManager = LinearLayoutManager(requireContext())
            songAdapter = com.shubhamgupta.nebula_music.adapters.SongSelectionAdapter(
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

            val dialog = android.app.AlertDialog.Builder(requireContext(), R.style.AlertDialogTheme)
                .setView(dialogView)
                .setNegativeButton("CANCEL") { dialog, _ ->
                    dialog.dismiss()
                }
                .create()

            // Apply theme fixes
            applyDialogThemeFix(dialog)

            // Style buttons
            selectAllButton.setTextColor(ContextCompat.getColor(requireContext(), R.color.button_positive))
            submitButton.setTextColor(ContextCompat.getColor(requireContext(), R.color.button_positive))

            // Handle submit button click
            submitButton.setOnClickListener {
                val selectedSongs = songAdapter.getSelectedSongs()
                if (selectedSongs.isNotEmpty()) {
                    addSongsToPlaylist(selectedSongs)
                    showToast("Added ${selectedSongs.size} songs to '${playlist.name}'")
                    dialog.dismiss()
                    // Refresh the current view
                    currentPlaylist?.let { setupPlaylistSongs(it) }
                } else {
                    showToast("Please select at least one song")
                }
            }

            dialog.show()

            dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.setTextColor(ContextCompat.getColor(requireContext(), R.color.button_negative))
        }
    }

    private fun updateSelectedCount(songAdapter: com.shubhamgupta.nebula_music.adapters.SongSelectionAdapter, textView: TextView) {
        val selectedCount = songAdapter.getSelectedSongsCount()
        textView.text = "$selectedCount songs selected"
    }

    private fun addSongsToPlaylist(songIds: List<Long>) {
        currentPlaylist?.let { playlist ->
            val updatedSongIds = playlist.songIds.toMutableList()
            val newSongs = songIds.filter { it !in updatedSongIds }
            updatedSongIds.addAll(newSongs)
            playlist.songIds.clear()
            playlist.songIds.addAll(updatedSongIds)

            // Save to preferences
            val allPlaylists = PreferenceManager.getPlaylists(requireContext()).toMutableList()
            val playlistIndex = allPlaylists.indexOfFirst { it.id == playlist.id }
            if (playlistIndex != -1) {
                allPlaylists[playlistIndex] = playlist
                PreferenceManager.savePlaylists(requireContext(), allPlaylists)
            }
        }
    }

    private fun showRenamePlaylistDialog() {
        currentPlaylist?.let { playlist ->
            val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_create_playlist, null)
            val input = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.et_playlist_name)
            input.setText(playlist.name)
            input.setSelection(playlist.name.length)

            dialogView.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.dialog_background))

            val dialog = android.app.AlertDialog.Builder(requireContext(), R.style.AlertDialogTheme)
                .setTitle("Rename Playlist")
                .setView(dialogView)
                .setPositiveButton("RENAME", null)
                .setNegativeButton("CANCEL") { dialog, _ -> dialog.dismiss() }
                .create()

            applyDialogThemeFix(dialog)

            dialog.setOnShowListener {
                val positiveButton = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                positiveButton.setOnClickListener {
                    val newName = input.text.toString().trim()
                    if (newName.isEmpty()) {
                        input.error = "Playlist name cannot be empty"
                        return@setOnClickListener
                    }

                    // Check if playlist name already exists
                    val allPlaylists = PreferenceManager.getPlaylists(requireContext())
                    if (allPlaylists.any { it.id != playlist.id && it.name.equals(newName, ignoreCase = true) }) {
                        input.error = "Playlist with this name already exists"
                        return@setOnClickListener
                    }

                    playlist.name = newName
                    val updatedPlaylists = allPlaylists.toMutableList()
                    val playlistIndex = updatedPlaylists.indexOfFirst { it.id == playlist.id }
                    if (playlistIndex != -1) {
                        updatedPlaylists[playlistIndex] = playlist
                        PreferenceManager.savePlaylists(requireContext(), updatedPlaylists)
                        setupPlaylistSongs(playlist)
                        showToast("Playlist renamed")
                        dialog.dismiss()
                    }
                }
                setDialogButtonColors(dialog)
            }
            dialog.show()
        }
    }

    private fun showDeletePlaylistDialog() {
        currentPlaylist?.let { playlist ->
            val dialog = android.app.AlertDialog.Builder(requireContext(), R.style.AlertDialogTheme)
                .setTitle("Delete Playlist")
                .setMessage("Are you sure you want to delete '${playlist.name}'?")
                .setPositiveButton("DELETE") { dialog, _ ->
                    val allPlaylists = PreferenceManager.getPlaylists(requireContext()).toMutableList()
                    allPlaylists.removeAll { it.id == playlist.id }
                    PreferenceManager.savePlaylists(requireContext(), allPlaylists)
                    showToast("Playlist deleted")
                    dialog.dismiss()
                    // Navigate back to playlists fragment
                    requireActivity().supportFragmentManager.popBackStack()
                }
                .setNegativeButton("CANCEL") { dialog, _ -> dialog.dismiss() }
                .create()

            applyDialogThemeFix(dialog)

            dialog.setOnShowListener {
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.setTextColor(ContextCompat.getColor(requireContext(), R.color.error_red))
                dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.setTextColor(ContextCompat.getColor(requireContext(), R.color.button_negative))
            }
            dialog.show()
        }
    }

    private fun applyDialogThemeFix(dialog: android.app.AlertDialog) {
        val titleTextView = dialog.findViewById<TextView>(android.R.id.title)
        val messageTextView = dialog.findViewById<TextView>(android.R.id.message)

        titleTextView?.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
        messageTextView?.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))

        dialog.window?.setBackgroundDrawableResource(R.color.dialog_background)
    }

    private fun setDialogButtonColors(dialog: android.app.AlertDialog) {
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.setTextColor(ContextCompat.getColor(requireContext(), R.color.button_positive))
        dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.setTextColor(ContextCompat.getColor(requireContext(), R.color.button_negative))
    }

    private fun handleMenuAction(position: Int, menuItem: String) {
        val song = playlistSongs[position]
        when (menuItem) {
            "play" -> openNowPlaying(position)
            "toggle_favorite" -> toggleFavorite(song)
        }
    }

    private fun toggleFavorite(song: Song) {
        if (PreferenceManager.isFavorite(requireContext(), song.id)) {
            PreferenceManager.removeFavorite(requireContext(), song.id)
        } else {
            PreferenceManager.addFavorite(requireContext(), song.id)
        }
        // Refresh adapter to update favorite states
        currentPlaylist?.let { setupPlaylistSongs(it) }
    }

    private fun playPlaylist() {
        if (playlistSongs.isNotEmpty()) {
            musicService?.startPlayback(ArrayList(playlistSongs), 0)
            (requireActivity() as com.shubhamgupta.nebula_music.MainActivity).navigateToNowPlaying()
        }
    }

    private fun shufflePlaylist() {
        if (playlistSongs.isNotEmpty()) {
            val shuffledSongs = playlistSongs.shuffled()
            musicService?.startPlayback(ArrayList(shuffledSongs), 0)
            musicService?.toggleShuffle()
            (requireActivity() as com.shubhamgupta.nebula_music.MainActivity).navigateToNowPlaying()
        }
    }

    private fun openNowPlaying(position: Int) {
        val songToPlay = playlistSongs[position]
        PreferenceManager.addRecentSong(requireContext(), songToPlay.id)

        musicService?.startPlayback(ArrayList(playlistSongs), position)
        (requireActivity() as com.shubhamgupta.nebula_music.MainActivity).navigateToNowPlaying()
    }

    private fun showToast(message: String) {
        android.widget.Toast.makeText(requireContext(), message, android.widget.Toast.LENGTH_SHORT).show()
    }

    // ADD THIS METHOD
    fun refreshData() {
        currentPlaylist?.let { setupPlaylistSongs(it) }
    }
}