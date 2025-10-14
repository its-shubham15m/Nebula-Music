package com.shubhamgupta.nebula_music.fragments

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.app.Activity
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.shubhamgupta.nebula_music.MainActivity
import com.shubhamgupta.nebula_music.R
import com.shubhamgupta.nebula_music.models.Playlist
import com.shubhamgupta.nebula_music.models.Song
import com.shubhamgupta.nebula_music.repository.SongRepository
import com.shubhamgupta.nebula_music.utils.PreferenceManager
import com.shubhamgupta.nebula_music.utils.SongUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class HomePageFragment : Fragment() {
    private var currentCategory = "songs"
    private lateinit var searchBar: EditText
    private lateinit var handler: Handler
    private lateinit var drawerLayout: DrawerLayout
    // Cache for child fragments
    private val childFragmentCache = mutableMapOf<String, Fragment>()
    private var isDataLoaded = false

    private lateinit var imgFavoritesOverlay: ImageView
    private lateinit var imgPlaylistsOverlay: ImageView
    private lateinit var imgRecentOverlay: ImageView

    private var isDrawerOpen = false

    // Save scroll state for each tab
    private val scrollStateMap = mutableMapOf<String, Pair<Int, Int>>() // tabName to (firstVisiblePosition, scrollOffset)

    private val voiceRecognitionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val results = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            results?.get(0)?.let { spokenText ->
                searchBar.setText(spokenText)
                handler.postDelayed({
                    performSearch(spokenText)
                }, 50)
            }
        }
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "FORCE_REFRESH_ALL", "FORCE_REFRESH_CURRENT", "QUEUE_CHANGED" -> {
                    Log.d("HomePageFragment", "Refresh broadcast received: ${intent.action}")
                    handler.post {
                        refreshData()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handler = Handler(Looper.getMainLooper())

        // Restore saved state if available
        savedInstanceState?.let {
            currentCategory = it.getString("currentCategory", "songs")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_home_page, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initializeViews(view)
        setupCategoryTabs()
        setupSearchFunctionality()

        // Load data only once or when needed
        if (!isDataLoaded) {
            loadCardAlbumArt()
            loadQuickActionCardData()
            isDataLoaded = true
        }

        // Select the saved category tab
        selectCategoryTab(currentCategory, false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("currentCategory", currentCategory)

        // Save current scroll state
        saveCurrentScrollState()
    }

    // In HomePageFragment.kt, update the onResume method:

    override fun onResume() {
        super.onResume()

        // Register broadcast receiver with proper flags for Android 14+
        val filter = IntentFilter().apply {
            addAction("FORCE_REFRESH_ALL")
            addAction("FORCE_REFRESH_CURRENT")
            addAction("QUEUE_CHANGED")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(refreshReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            requireContext().registerReceiver(refreshReceiver, filter)
        }

        // Refresh data when fragment resumes but preserve scroll position
        handler.postDelayed({
            refreshDataPreserveState()
        }, 300)
    }

    override fun onPause() {
        super.onPause()
        try {
            requireContext().unregisterReceiver(refreshReceiver)
        } catch (e: Exception) {
            // Ignore if not registered
        }

        // Save scroll state when pausing
        saveCurrentScrollState()
    }

    private fun initializeViews(view: View) {
        searchBar = view.findViewById(R.id.search_bar)

        // Get the activity's drawer layout
        val activity = requireActivity() as? MainActivity
        drawerLayout = activity?.findViewById(R.id.drawer_layout) ?: return

        imgFavoritesOverlay = view.findViewById(R.id.img_favorites_overlay)
        imgPlaylistsOverlay = view.findViewById(R.id.img_playlists_overlay)
        imgRecentOverlay = view.findViewById(R.id.img_recent_overlay)

        // Shuffle All Card functionality
        view.findViewById<CardView>(R.id.shuffle_all_card).setOnClickListener {
            shuffleAllSongs()
        }

        // Existing shuffle button functionality
        view.findViewById<ImageView>(R.id.btn_shuffle).setOnClickListener {
            (requireActivity() as? MainActivity)?.getMusicService()?.toggleShuffle()
        }

        view.findViewById<ImageButton>(R.id.btn_sort).setOnClickListener {
            showSortDialog()
        }

        view.findViewById<View>(R.id.card_favorites).setOnClickListener {
            (requireActivity() as? MainActivity)?.showFavoritesPage()
        }

        view.findViewById<View>(R.id.card_playlists).setOnClickListener {
            (requireActivity() as? MainActivity)?.showPlaylistsPage()
        }

        view.findViewById<View>(R.id.card_recent).setOnClickListener {
            (requireActivity() as? MainActivity)?.showRecentPage()
        }

        // Settings icon - Now opens the activity's drawer
        view.findViewById<ImageButton>(R.id.settings_icon).setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        // Voice search button
        view.findViewById<ImageButton>(R.id.voice_search_btn).setOnClickListener {
            startVoiceRecognition()
        }
    }

    // Method to enable/disable scrolling in the fragment
    fun setDrawerOpen(isOpen: Boolean) {
        isDrawerOpen = isOpen
        updateScrollingState()
    }

    private fun updateScrollingState() {
        try {
            // Disable scrolling in all child fragments
            val songsFragment = childFragmentManager.findFragmentByTag("SONGS_FRAGMENT") as? SongsFragment
            val artistsFragment = childFragmentManager.findFragmentByTag("ARTISTS_FRAGMENT") as? ArtistsFragment
            val albumsFragment = childFragmentManager.findFragmentByTag("ALBUMS_FRAGMENT") as? AlbumsFragment
            val genresFragment = childFragmentManager.findFragmentByTag("GENRES_FRAGMENT") as? GenresFragment

            if (isDrawerOpen) {
                // Disable scrolling in all fragments
                disableScrollingInFragment(songsFragment)
                disableScrollingInFragment(artistsFragment)
                disableScrollingInFragment(albumsFragment)
                disableScrollingInFragment(genresFragment)

                // Also disable the main fragment container and its parent
                view?.findViewById<View>(R.id.fragment_container)?.isEnabled = false
                view?.findViewById<View>(R.id.main_content_container)?.isEnabled = false

                // Disable touch events on the entire home page layout
                view?.isEnabled = false
                view?.isClickable = false
            } else {
                // Enable scrolling in all fragments
                enableScrollingInFragment(songsFragment)
                enableScrollingInFragment(artistsFragment)
                enableScrollingInFragment(albumsFragment)
                enableScrollingInFragment(genresFragment)

                // Enable the main fragment container and its parent
                view?.findViewById<View>(R.id.fragment_container)?.isEnabled = true
                view?.findViewById<View>(R.id.main_content_container)?.isEnabled = true

                // Enable touch events on the entire home page layout
                view?.isEnabled = true
                view?.isClickable = true
            }
        } catch (e: Exception) {
            Log.e("HomePageFragment", "Error updating scrolling state: ${e.message}")
        }
    }

    private fun disableScrollingInFragment(fragment: Fragment?) {
        when (fragment) {
            is SongsFragment -> {
                fragment.setScrollingEnabled(false)
                saveFragmentScrollState(fragment, "songs")
            }
            is ArtistsFragment -> {
                fragment.setScrollingEnabled(false)
                saveFragmentScrollState(fragment, "artists")
            }
            is AlbumsFragment -> {
                fragment.setScrollingEnabled(false)
                saveFragmentScrollState(fragment, "albums")
            }
            is GenresFragment -> {
                fragment.setScrollingEnabled(false)
                saveFragmentScrollState(fragment, "genres")
            }
            else -> {
                fragment?.view?.let { fragmentView ->
                    findAndDisableScrollViews(fragmentView)
                    disableViewAndChildren(fragmentView)
                }
            }
        }
    }

    private fun enableScrollingInFragment(fragment: Fragment?) {
        when (fragment) {
            is SongsFragment -> {
                fragment.setScrollingEnabled(true)
                restoreFragmentScrollState(fragment, "songs")
            }
            is ArtistsFragment -> {
                fragment.setScrollingEnabled(true)
                restoreFragmentScrollState(fragment, "artists")
            }
            is AlbumsFragment -> {
                fragment.setScrollingEnabled(true)
                restoreFragmentScrollState(fragment, "albums")
            }
            is GenresFragment -> {
                fragment.setScrollingEnabled(true)
                restoreFragmentScrollState(fragment, "genres")
            }
            else -> {
                fragment?.view?.let { fragmentView ->
                    findAndEnableScrollViews(fragmentView)
                    enableViewAndChildren(fragmentView)
                }
            }
        }
    }

    private fun saveFragmentScrollState(fragment: Fragment, tabName: String) {
        when (fragment) {
            is SongsFragment -> fragment.saveScrollState()
            is ArtistsFragment -> fragment.saveScrollState()
            is AlbumsFragment -> fragment.saveScrollState()
            is GenresFragment -> fragment.saveScrollState()
        }
    }

    private fun restoreFragmentScrollState(fragment: Fragment, tabName: String) {
        when (fragment) {
            is SongsFragment -> fragment.restoreScrollState()
            is ArtistsFragment -> fragment.restoreScrollState()
            is AlbumsFragment -> fragment.restoreScrollState()
            is GenresFragment -> fragment.restoreScrollState()
        }
    }

    private fun findAndDisableScrollViews(view: View) {
        when (view) {
            is RecyclerView -> {
                view.isNestedScrollingEnabled = false
                view.isEnabled = false
                view.setOnTouchListener { _, _ -> true } // Block all touch events
            }
            is androidx.core.widget.NestedScrollView -> {
                view.isNestedScrollingEnabled = false
                view.isEnabled = false
                view.setOnTouchListener { _, _ -> true } // Block all touch events
            }
            is ViewGroup -> {
                for (i in 0 until view.childCount) {
                    findAndDisableScrollViews(view.getChildAt(i))
                }
            }
        }
    }

    private fun findAndEnableScrollViews(view: View) {
        when (view) {
            is RecyclerView -> {
                view.isNestedScrollingEnabled = true
                view.isEnabled = true
                view.setOnTouchListener(null) // Remove touch blocker
            }
            is androidx.core.widget.NestedScrollView -> {
                view.isNestedScrollingEnabled = true
                view.isEnabled = true
                view.setOnTouchListener(null) // Remove touch blocker
            }
            is ViewGroup -> {
                for (i in 0 until view.childCount) {
                    findAndEnableScrollViews(view.getChildAt(i))
                }
            }
        }
    }

    private fun disableViewAndChildren(view: View) {
        view.isEnabled = false
        view.isClickable = false
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                disableViewAndChildren(view.getChildAt(i))
            }
        }
    }

    private fun enableViewAndChildren(view: View) {
        view.isEnabled = true
        view.isClickable = true
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                enableViewAndChildren(view.getChildAt(i))
            }
        }
    }

    private fun setupCategoryTabs() {
        val tabs = mapOf(
            R.id.tab_songs to "songs",
            R.id.tab_artists to "artists",
            R.id.tab_albums to "albums",
            R.id.tab_genres to "genres"
        )

        tabs.forEach { (tabId, category) ->
            view?.findViewById<TextView>(tabId)?.setOnClickListener {
                selectCategoryTab(category)
            }
        }
    }

    private fun selectCategoryTab(category: String, saveState: Boolean = true) {
        if (saveState) {
            // Save current scroll state before switching
            saveCurrentScrollState()
        }

        currentCategory = category
        val selectedColor = ContextCompat.getColor(requireContext(), R.color.purple_500)
        val unselectedColor = ContextCompat.getColor(requireContext(), android.R.color.darker_gray)

        listOf(R.id.tab_songs, R.id.tab_artists, R.id.tab_albums, R.id.tab_genres).forEach { tabId ->
            val tab = view?.findViewById<TextView>(tabId)
            val isSelected = when(category) {
                "songs" -> tabId == R.id.tab_songs
                "artists" -> tabId == R.id.tab_artists
                "albums" -> tabId == R.id.tab_albums
                "genres" -> tabId == R.id.tab_genres
                else -> tabId == R.id.tab_songs
            }

            if (isSelected) {
                tab?.setTextColor(selectedColor)
                tab?.setTypeface(null, android.graphics.Typeface.BOLD)
                tab?.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f)
            } else {
                tab?.setTextColor(unselectedColor)
                tab?.setTypeface(null, android.graphics.Typeface.NORMAL)
                tab?.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
            }
        }

        when (category) {
            "songs" -> showSongsFragment()
            "artists" -> showArtistsFragment()
            "albums" -> showAlbumsFragment()
            "genres" -> showGenresFragment()
        }

        // Force apply the current sort type immediately with delay to ensure fragment is created
        handler.postDelayed({
            val currentSortType = PreferenceManager.getSortPreferenceWithDefault(requireContext(), category)
            Log.d("HomePageFragment", "selectCategoryTab - Applying sort: $currentSortType to category: $category")
            applySortToCurrentFragment(currentSortType)
        }, 500)
    }

    private fun applySortToCurrentFragment(sortType: MainActivity.SortType) {
        val intent = when (currentCategory) {
            "songs" -> Intent("SORT_SONGS")
            "artists" -> Intent("SORT_ARTISTS")
            "albums" -> Intent("SORT_ALBUMS")
            "genres" -> Intent("SORT_GENRES")
            else -> return
        }.apply {
            putExtra("sort_type", sortType.ordinal)
        }
        requireContext().sendBroadcast(intent)
    }

    private fun showSongsFragment() {
        val fragment = childFragmentCache["songs"] as? SongsFragment ?: SongsFragment()
        childFragmentCache["songs"] = fragment

        childFragmentManager.commit {
            replace(R.id.fragment_container, fragment, "SONGS_FRAGMENT")
            setReorderingAllowed(true)
        }

        handler.postDelayed({
            updateScrollingState()
            // Restore scroll state for songs fragment
            fragment.restoreScrollState()
        }, 50)
    }

    private fun showArtistsFragment() {
        val fragment = childFragmentCache["artists"] as? ArtistsFragment ?: ArtistsFragment()
        childFragmentCache["artists"] = fragment

        childFragmentManager.commit {
            replace(R.id.fragment_container, fragment, "ARTISTS_FRAGMENT")
            setReorderingAllowed(true)
        }

        handler.postDelayed({
            updateScrollingState()
            // Restore scroll state for artists fragment
            fragment.restoreScrollState()
        }, 50)
    }

    private fun showAlbumsFragment() {
        val fragment = childFragmentCache["albums"] as? AlbumsFragment ?: AlbumsFragment()
        childFragmentCache["albums"] = fragment

        childFragmentManager.commit {
            replace(R.id.fragment_container, fragment, "ALBUMS_FRAGMENT")
            setReorderingAllowed(true)
        }

        handler.postDelayed({
            updateScrollingState()
            // Restore scroll state for albums fragment
            fragment.restoreScrollState()
        }, 50)
    }

    private fun showGenresFragment() {
        val fragment = childFragmentCache["genres"] as? GenresFragment ?: GenresFragment()
        childFragmentCache["genres"] = fragment

        childFragmentManager.commit {
            replace(R.id.fragment_container, fragment, "GENRES_FRAGMENT")
            setReorderingAllowed(true)
        }

        handler.postDelayed({
            updateScrollingState()
            // Restore scroll state for genres fragment
            fragment.restoreScrollState()
        }, 50)
    }

    private fun setupSearchFunctionality() {
        searchBar.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                performSearch(s.toString())
            }
        })
    }

    private fun performSearch(query: String) {
        val intent = Intent("SEARCH_QUERY_CHANGED").apply {
            putExtra("query", query)
        }
        requireContext().sendBroadcast(intent)
    }

    private fun startVoiceRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to search")
        }

        try {
            voiceRecognitionLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Voice recognition not supported", Toast.LENGTH_SHORT).show()
        }
    }

    // NEW: Shuffle All Songs functionality
    private fun shuffleAllSongs() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val allSongs = SongRepository.getAllSongs(requireContext())

                withContext(Dispatchers.Main) {
                    if (allSongs.isNotEmpty()) {
                        (requireActivity() as? MainActivity)?.getMusicService()?.let { service ->
                            // Shuffle the entire song list
                            val shuffledSongs = allSongs.shuffled()
                            service.startPlayback(shuffledSongs as ArrayList<Song>, 0)

                            // Enable shuffle mode
                            service.toggleShuffle()

                            // Show confirmation
                            Toast.makeText(
                                requireContext(),
                                "Shuffling ${allSongs.size} songs",
                                Toast.LENGTH_SHORT
                            ).show()
                        } ?: run {
                            Toast.makeText(
                                requireContext(),
                                "Music service not available",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    } else {
                        Toast.makeText(
                            requireContext(),
                            "No songs found",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        requireContext(),
                        "Error shuffling songs: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    fun loadCardAlbumArt() {
        CoroutineScope(Dispatchers.IO).launch {
            val context = requireContext()

            val favoritesId = PreferenceManager.getFavorites(context).toList().lastOrNull()
            loadLastAlbumArt(favoritesId, imgFavoritesOverlay)

            val playlists: List<Playlist> = PreferenceManager.getPlaylists(context)
            val playlistId = playlists.firstOrNull()?.songIds?.firstOrNull()
            loadLastAlbumArt(playlistId, imgPlaylistsOverlay)

            val recentId = PreferenceManager.getRecentSongs(context).firstOrNull()
            loadLastAlbumArt(recentId, imgRecentOverlay)
        }
    }

    private suspend fun loadLastAlbumArt(songId: Long?, imageView: ImageView) {
        val context = requireContext()

        val albumArtUri: android.net.Uri? = if (songId != null) {
            val song = SongRepository.getAllSongs(context).firstOrNull { it.id == songId }
            if (song != null) SongUtils.getAlbumArtUri(song.albumId) else null
        } else {
            null
        }

        withContext(Dispatchers.Main) {
            imageView.clearColorFilter()
            if (albumArtUri != null) {
                Glide.with(context)
                    .load(albumArtUri)
                    .centerCrop()
                    .placeholder(R.drawable.default_album_art)
                    .into(imageView)
            } else {
                imageView.setImageResource(R.drawable.default_album_art)
            }
        }
    }

    @SuppressLint("SetTextI18n")
    fun loadQuickActionCardData() {
        CoroutineScope(Dispatchers.IO).launch {
            val context = requireContext()

            // Load favorites count
            val favoritesCount = PreferenceManager.getFavorites(context).size
            withContext(Dispatchers.Main) {
                view?.findViewById<TextView>(R.id.card_fav_count)?.text = "$favoritesCount songs"
            }

            // Load playlists count
            val playlistsCount = PreferenceManager.getPlaylists(context).size
            withContext(Dispatchers.Main) {
                view?.findViewById<TextView>(R.id.card_playlist_count)?.text = "$playlistsCount playlists"
            }

            // Load recent songs count
            val recentCount = PreferenceManager.getRecentSongs(context).size
            withContext(Dispatchers.Main) {
                view?.findViewById<TextView>(R.id.card_recent_count)?.text = "$recentCount songs"
            }
        }
    }

    private fun showSortDialog() {
        val items: Array<String>
        val sortOptions: List<Pair<String, MainActivity.SortType>>

        when (currentCategory) {
            "songs" -> {
                sortOptions = listOf(
                    Pair("Name (A-Z)", MainActivity.SortType.NAME_ASC),
                    Pair("Name (Z-A)", MainActivity.SortType.NAME_DESC),
                    Pair("Date Added (Newest)", MainActivity.SortType.DATE_ADDED_DESC),
                    Pair("Date Added (Oldest)", MainActivity.SortType.DATE_ADDED_ASC),
                    Pair("Duration", MainActivity.SortType.DURATION)
                )
            }
            "artists" -> {
                sortOptions = listOf(
                    Pair("Artist Name (A-Z)", MainActivity.SortType.NAME_ASC),
                    Pair("Artist Name (Z-A)", MainActivity.SortType.NAME_DESC)
                )
            }
            "albums" -> {
                sortOptions = listOf(
                    Pair("Album Name (A-Z)", MainActivity.SortType.NAME_ASC),
                    Pair("Album Name (Z-A)", MainActivity.SortType.NAME_DESC)
                )
            }
            "genres" -> {
                sortOptions = listOf(
                    Pair("Genre Name (A-Z)", MainActivity.SortType.NAME_ASC),
                    Pair("Genre Name (Z-A)", MainActivity.SortType.NAME_DESC)
                )
            }
            else -> return
        }

        items = sortOptions.map { it.first }.toTypedArray()

        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Sort by")
            .setItems(items) { _, which ->
                sortCategory(sortOptions[which].second)
            }
            .show()
    }

    private fun sortCategory(sortType: MainActivity.SortType) {
        Log.d("HomePageFragment", "sortCategory - Setting sort to: $sortType for category: $currentCategory")

        // Save the sort preference immediately when user selects a sort option
        when (currentCategory) {
            "songs" -> {
                PreferenceManager.saveSortPreference(requireContext(), "songs", sortType)
                Log.d("HomePageFragment", "Saved songs sort preference: $sortType")

                // Send broadcast AND directly refresh the fragment
                val intent = Intent("SORT_SONGS").apply {
                    putExtra("sort_type", sortType.ordinal)
                }
                requireContext().sendBroadcast(intent)

                // Force immediate refresh
                handler.postDelayed({
                    refreshCurrentFragment()
                }, 100)
            }
            "artists" -> {
                PreferenceManager.saveSortPreference(requireContext(), "artists", sortType)
                val intent = Intent("SORT_ARTISTS").apply {
                    putExtra("sort_type", sortType.ordinal)
                }
                requireContext().sendBroadcast(intent)
                handler.postDelayed({
                    refreshCurrentFragment()
                }, 100)
            }
            "albums" -> {
                PreferenceManager.saveSortPreference(requireContext(), "albums", sortType)
                val intent = Intent("SORT_ALBUMS").apply {
                    putExtra("sort_type", sortType.ordinal)
                }
                requireContext().sendBroadcast(intent)
                handler.postDelayed({
                    refreshCurrentFragment()
                }, 100)
            }
            "genres" -> {
                PreferenceManager.saveSortPreference(requireContext(), "genres", sortType)
                val intent = Intent("SORT_GENRES").apply {
                    putExtra("sort_type", sortType.ordinal)
                }
                requireContext().sendBroadcast(intent)
                handler.postDelayed({
                    refreshCurrentFragment()
                }, 100)
            }
        }
    }

    fun refreshData() {
        Log.d("HomePageFragment", "refreshData called")
        loadCardAlbumArt()
        loadQuickActionCardData()
        refreshCurrentFragment()
    }

    private fun refreshDataPreserveState() {
        Log.d("HomePageFragment", "refreshDataPreserveState called")
        loadCardAlbumArt()
        loadQuickActionCardData()
        refreshCurrentFragmentPreserveState()
    }

    private fun refreshCurrentFragment() {
        Log.d("HomePageFragment", "refreshCurrentFragment - Refreshing: $currentCategory")

        try {
            when (currentCategory) {
                "songs" -> {
                    val fragment = childFragmentManager.findFragmentByTag("SONGS_FRAGMENT") as? SongsFragment
                    if (fragment != null && fragment.isAdded) {
                        fragment.refreshData()
                    } else {
                        val containerFragment = childFragmentManager.findFragmentById(R.id.fragment_container)
                        if (containerFragment is SongsFragment) {
                            containerFragment.refreshData()
                        }
                    }
                }
                "artists" -> {
                    val fragment = childFragmentManager.findFragmentByTag("ARTISTS_FRAGMENT") as? ArtistsFragment
                    if (fragment != null && fragment.isAdded) {
                        fragment.refreshData()
                    } else {
                        val containerFragment = childFragmentManager.findFragmentById(R.id.fragment_container)
                        if (containerFragment is ArtistsFragment) {
                            containerFragment.refreshData()
                        }
                    }
                }
                "albums" -> {
                    val fragment = childFragmentManager.findFragmentByTag("ALBUMS_FRAGMENT") as? AlbumsFragment
                    if (fragment != null && fragment.isAdded) {
                        fragment.refreshData()
                    } else {
                        val containerFragment = childFragmentManager.findFragmentById(R.id.fragment_container)
                        if (containerFragment is AlbumsFragment) {
                            containerFragment.refreshData()
                        }
                    }
                }
                "genres" -> {
                    val fragment = childFragmentManager.findFragmentByTag("GENRES_FRAGMENT") as? GenresFragment
                    if (fragment != null && fragment.isAdded) {
                        fragment.refreshData()
                    } else {
                        val containerFragment = childFragmentManager.findFragmentById(R.id.fragment_container)
                        if (containerFragment is GenresFragment) {
                            containerFragment.refreshData()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("HomePageFragment", "Error refreshing fragment: ${e.message}")
        }
    }

    private fun refreshCurrentFragmentPreserveState() {
        Log.d("HomePageFragment", "refreshCurrentFragmentPreserveState - Refreshing: $currentCategory")

        try {
            when (currentCategory) {
                "songs" -> {
                    val fragment = childFragmentManager.findFragmentByTag("SONGS_FRAGMENT") as? SongsFragment
                    if (fragment != null && fragment.isAdded) {
                        fragment.refreshDataPreserveState()
                    }
                }
                "artists" -> {
                    val fragment = childFragmentManager.findFragmentByTag("ARTISTS_FRAGMENT") as? ArtistsFragment
                    if (fragment != null && fragment.isAdded) {
                        fragment.refreshDataPreserveState()
                    }
                }
                "albums" -> {
                    val fragment = childFragmentManager.findFragmentByTag("ALBUMS_FRAGMENT") as? AlbumsFragment
                    if (fragment != null && fragment.isAdded) {
                        fragment.refreshDataPreserveState()
                    }
                }
                "genres" -> {
                    val fragment = childFragmentManager.findFragmentByTag("GENRES_FRAGMENT") as? GenresFragment
                    if (fragment != null && fragment.isAdded) {
                        fragment.refreshDataPreserveState()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("HomePageFragment", "Error refreshing fragment: ${e.message}")
        }
    }

    private fun saveCurrentScrollState() {
        when (currentCategory) {
            "songs" -> {
                val fragment = childFragmentManager.findFragmentByTag("SONGS_FRAGMENT") as? SongsFragment
                fragment?.saveScrollState()
            }
            "artists" -> {
                val fragment = childFragmentManager.findFragmentByTag("ARTISTS_FRAGMENT") as? ArtistsFragment
                fragment?.saveScrollState()
            }
            "albums" -> {
                val fragment = childFragmentManager.findFragmentByTag("ALBUMS_FRAGMENT") as? AlbumsFragment
                fragment?.saveScrollState()
            }
            "genres" -> {
                val fragment = childFragmentManager.findFragmentByTag("GENRES_FRAGMENT") as? GenresFragment
                fragment?.saveScrollState()
            }
        }
    }

    companion object {
        fun newInstance(): HomePageFragment {
            return HomePageFragment()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Don't clear cache here to maintain fragment state
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clear cache when fragment is actually destroyed
        childFragmentCache.clear()
    }
}