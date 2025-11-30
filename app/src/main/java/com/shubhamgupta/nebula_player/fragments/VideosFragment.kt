package com.shubhamgupta.nebula_player.fragments

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.shubhamgupta.nebula_player.MainActivity
import com.shubhamgupta.nebula_player.R
import com.shubhamgupta.nebula_player.VideoPlayerActivity
import com.shubhamgupta.nebula_player.adapters.VideoAdapter
import com.shubhamgupta.nebula_player.adapters.VideoUiModel
import com.shubhamgupta.nebula_player.models.Video
import com.shubhamgupta.nebula_player.repository.VideoRepository
import com.shubhamgupta.nebula_player.utils.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

class VideosFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var loadingProgress: ProgressBar
    private var pageTitle: TextView? = null
    private var btnSort: ImageButton? = null

    private lateinit var videoAdapter: VideoAdapter

    private val videoList = mutableListOf<Video>()
    private val currentUiList = mutableListOf<VideoUiModel>()

    // Sort and Grouping State
    private var currentSortType = MainActivity.SortType.DATE_ADDED_DESC
    private var isFolderSort = true // Means "Grouping Enabled"

    // Folder Navigation State
    private var isInFolderView = false
    private var activeFolderName: String? = null

    private val handler = Handler(Looper.getMainLooper())
    private var loadJob: Job? = null
    private var scrollPosition = 0
    private var scrollOffset = 0

    private lateinit var deleteResultLauncher: ActivityResultLauncher<IntentSenderRequest>

    private lateinit var videoContentObserver: VideoContentObserver

    // Broadcast Action for MusicService
    private val ACTION_VIDEO_STARTED = "com.shubhamgupta.nebula_player.ACTION_VIDEO_STARTED"

    private val searchReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "SEARCH_QUERY_CHANGED") {
                val query = intent.getStringExtra("query") ?: ""
                filterVideos(query)
            }
        }
    }

    private val sortReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "SORT_VIDEOS") {
                val sortTypeOrdinal = intent.getIntExtra("sort_type", 0)
                val newSortType = MainActivity.SortType.entries.toTypedArray()[sortTypeOrdinal]

                currentSortType = newSortType
                isInFolderView = false
                PreferenceManager.saveSortPreference(requireContext(), "videos", currentSortType)
                loadVideos()
            }
        }
    }

    inner class VideoContentObserver(handler: Handler) : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            handler.postDelayed({ refreshDataPreserveState() }, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentSortType = PreferenceManager.getSortPreferenceWithDefault(requireContext(), "videos")
        isFolderSort = PreferenceManager.isVideoGroupingEnabled(requireContext())

        deleteResultLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                Toast.makeText(requireContext(), "Video deleted", Toast.LENGTH_SHORT).show()
                loadVideos()
            } else {
                Toast.makeText(requireContext(), "Could not delete video", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_video_page, container, false)

        recyclerView = view.findViewById(R.id.video_recycler_view)
        emptyView = view.findViewById(R.id.tv_empty_videos)
        loadingProgress = view.findViewById(R.id.loading_progress)
        pageTitle = view.findViewById(R.id.page_title)
        btnSort = view.findViewById(R.id.btn_sort)

        recyclerView.layoutManager = GridLayoutManager(context, 2)
        videoAdapter = VideoAdapter(
            requireContext(),
            onItemClick = { item -> handleItemClick(item) },
            onDeleteRequest = { video -> requestDeleteVideo(video) }
        )
        recyclerView.adapter = videoAdapter

        videoContentObserver = VideoContentObserver(handler)

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initializeViews()
        loadVideos()
    }

    private fun initializeViews() {
        btnSort?.setOnClickListener {
            if (isInFolderView) {
                handleBackPress()
            } else {
                showSortDialog()
            }
        }
    }

    private fun loadVideos(preserveState: Boolean = false) {
        if (preserveState) saveScrollState()
        else {
            loadingProgress.visibility = View.VISIBLE
            emptyView.visibility = View.GONE
        }

        loadJob?.cancel()
        loadJob = CoroutineScope(Dispatchers.Main).launch {
            try {
                val videos = VideoRepository.getAllVideos(requireContext())
                videoList.clear()
                videoList.addAll(videos)

                generateUiList()

                loadingProgress.visibility = View.GONE
                if (preserveState) restoreScrollState()
            } catch (e: Exception) {
                Log.e("VideosFragment", "Error loading videos", e)
                loadingProgress.visibility = View.GONE
            }
        }
    }

    private fun generateUiList() {
        currentUiList.clear()

        if (isInFolderView && activeFolderName != null) {
            // --- FOLDER VIEW ---
            pageTitle?.text = activeFolderName
            btnSort?.setImageResource(androidx.appcompat.R.drawable.abc_ic_ab_back_material)

            // Filter videos belonging to this smart group
            val folderVideos = videoList.filter { getSmartGroupKey(it) == activeFolderName }
            val sortedVideos = sortVideosList(folderVideos)
            currentUiList.addAll(sortedVideos.map { VideoUiModel.VideoItem(it) })

        } else if (isFolderSort) {
            // --- HYBRID VIEW (Folders + Unique Videos) ---
            pageTitle?.text = "Videos"
            btnSort?.setImageResource(R.drawable.ic_sort)

            val grouped = videoList.groupBy { getSmartGroupKey(it) }
            val mixedList = mutableListOf<VideoUiModel>()

            grouped.forEach { (key, videos) ->
                if (key.startsWith("UNIQUE_")) {
                    // It's a unique video, show as VideoItem
                    mixedList.add(VideoUiModel.VideoItem(videos[0]))
                } else {
                    // It's a Smart Group (Camera, WhatsApp, etc.), show as FolderItem
                    val sortedGroup = sortVideosList(videos)
                    val representative = sortedGroup.firstOrNull()
                    mixedList.add(VideoUiModel.FolderItem(key, videos.size, representative))
                }
            }
            sortMixedList(mixedList)
            currentUiList.addAll(mixedList)

        } else {
            // --- FLAT LIST (All Videos) ---
            pageTitle?.text = "All Videos"
            btnSort?.setImageResource(R.drawable.ic_sort)

            val sortedVideos = sortVideosList(videoList)
            currentUiList.addAll(sortedVideos.map { VideoUiModel.VideoItem(it) })
        }

        updateAdapter()
    }

    private fun updateAdapter() {
        if (!isAdded) return
        videoAdapter.submitList(ArrayList(currentUiList))

        if (currentUiList.isEmpty()) {
            emptyView.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            emptyView.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
        }
    }

    private fun handleItemClick(item: VideoUiModel) {
        when (item) {
            is VideoUiModel.VideoItem -> {
                requireContext().sendBroadcast(Intent(ACTION_VIDEO_STARTED))
                val intent = Intent(requireContext(), VideoPlayerActivity::class.java).apply {
                    putExtra("VIDEO_ID", item.video.id)
                    putExtra("VIDEO_TITLE", item.video.title)
                }
                startActivity(intent)
            }
            is VideoUiModel.FolderItem -> {
                enterFolder(item.name)
            }
        }
    }

    private fun sortVideosList(videos: List<Video>): List<Video> {
        return when (currentSortType) {
            MainActivity.SortType.NAME_ASC -> videos.sortedBy { it.title.lowercase() }
            MainActivity.SortType.NAME_DESC -> videos.sortedByDescending { it.title.lowercase() }
            MainActivity.SortType.DATE_ADDED_ASC -> videos.sortedBy { it.dateAdded }
            MainActivity.SortType.DATE_ADDED_DESC -> videos.sortedByDescending { it.dateAdded }
            MainActivity.SortType.DURATION -> videos.sortedByDescending { it.duration }
        }
    }

    private fun sortMixedList(list: MutableList<VideoUiModel>) {
        list.sortWith(Comparator { a, b ->
            val vidA = when (a) {
                is VideoUiModel.VideoItem -> a.video
                is VideoUiModel.FolderItem -> a.representative
            }
            val vidB = when (b) {
                is VideoUiModel.VideoItem -> b.video
                is VideoUiModel.FolderItem -> b.representative
            }

            val nameA = if (a is VideoUiModel.FolderItem) a.name else vidA?.title ?: ""
            val nameB = if (b is VideoUiModel.FolderItem) b.name else vidB?.title ?: ""

            when (currentSortType) {
                MainActivity.SortType.NAME_ASC -> nameA.compareTo(nameB, true)
                MainActivity.SortType.NAME_DESC -> nameB.compareTo(nameA, true)
                MainActivity.SortType.DATE_ADDED_ASC -> (vidA?.dateAdded ?: 0).compareTo(vidB?.dateAdded ?: 0)
                MainActivity.SortType.DATE_ADDED_DESC -> (vidB?.dateAdded ?: 0).compareTo(vidA?.dateAdded ?: 0)
                MainActivity.SortType.DURATION -> (vidB?.duration ?: 0).compareTo(vidA?.duration ?: 0)
            }
        })
    }

    /**
     * LOGIC UPDATE:
     * - Returns a Smart Category Name (e.g., "WhatsApp", "Camera") if the file is "Illogical" or generated.
     * - Returns "UNIQUE_ID" if the file name is unique/logical, so it shows as a single item.
     */
    private fun getSmartGroupKey(video: Video): String {
        val title = video.title.trim()
        val path = video.path.lowercase()

        // 1. WhatsApp
        if (title.startsWith("VID-", true) && title.contains("WA", true)) return "WhatsApp"
        if (title.startsWith("WhatsApp", true)) return "WhatsApp"
        if (path.contains("whatsapp")) return "WhatsApp"

        // 2. Camera (Generated Names)
        if (title.startsWith("VID_", true)) return "Camera"
        if (title.startsWith("PXL_", true)) return "Camera" // Pixel
        if (title.startsWith("DSC_", true)) return "Camera" // Standard
        if (path.contains("/dcim/camera")) return "Camera"

        // 3. Instagram / Facebook / Snapchat
        if (title.contains("Instagram", true) || path.contains("instagram")) return "Instagram"
        if (title.startsWith("FB", true) || title.contains("Facebook", true) || path.contains("facebook")) return "Facebook"
        if (title.contains("Snapchat", true) || path.contains("snapchat")) return "Snapchat"

        // 4. Telegram
        if (path.contains("telegram")) return "Telegram"

        // 5. Unique / Logical Names
        // If it doesn't match a "junk/generated" pattern, treat it as Unique.
        // We prepend "UNIQUE_" so the generator knows to unwrap it.
        return "UNIQUE_${video.id}"
    }

    private fun filterVideos(query: String) {
        if (query.isNotBlank()) {
            currentUiList.clear()
            val filtered = videoList.filter { it.title.contains(query, true) }
            currentUiList.addAll(filtered.map { VideoUiModel.VideoItem(it) })
            pageTitle?.text = "Search Results"
            updateAdapter()
        } else {
            generateUiList()
        }
    }

    private fun enterFolder(folderName: String) {
        isInFolderView = true
        activeFolderName = folderName
        generateUiList()
    }

    fun handleBackPress(): Boolean {
        if (isInFolderView) {
            isInFolderView = false
            activeFolderName = null
            generateUiList()
            return true
        }
        return false
    }

    private fun requestDeleteVideo(video: Video) {
        try {
            val intentSender = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val uris = listOf(video.uri)
                MediaStore.createDeleteRequest(requireContext().contentResolver, uris).intentSender
            } else {
                null
            }

            if (intentSender != null) {
                val request = IntentSenderRequest.Builder(intentSender).build()
                deleteResultLauncher.launch(request)
            } else {
                Toast.makeText(requireContext(), "Could not request deletion (Android 10-).", Toast.LENGTH_SHORT).show()
            }
        } catch (e: SecurityException) {
            Toast.makeText(requireContext(), "Permission denied.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSortDialog() {
        val options = arrayOf("Name (A-Z)", "Name (Z-A)", "Date (Newest)", "Date (Oldest)", "Duration")
        AlertDialog.Builder(requireContext())
            .setTitle("Sort Videos By")
            .setItems(options) { _, which ->
                val newSort = when (which) {
                    0 -> MainActivity.SortType.NAME_ASC
                    1 -> MainActivity.SortType.NAME_DESC
                    2 -> MainActivity.SortType.DATE_ADDED_DESC
                    3 -> MainActivity.SortType.DATE_ADDED_ASC
                    4 -> MainActivity.SortType.DURATION
                    else -> MainActivity.SortType.DATE_ADDED_DESC
                }
                currentSortType = newSort
                PreferenceManager.saveSortPreference(requireContext(), "videos", currentSortType)
                loadVideos()
            }
            .show()
    }

    fun refreshData() { if (isAdded) loadVideos() }
    fun refreshDataPreserveState() { if (isAdded) loadVideos(true) }

    override fun onResume() {
        super.onResume()
        try {
            requireContext().contentResolver.registerContentObserver(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, videoContentObserver
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requireActivity().registerReceiver(searchReceiver, IntentFilter("SEARCH_QUERY_CHANGED"), Context.RECEIVER_NOT_EXPORTED)
                requireActivity().registerReceiver(sortReceiver, IntentFilter("SORT_VIDEOS"), Context.RECEIVER_NOT_EXPORTED)
            } else {
                requireActivity().registerReceiver(searchReceiver, IntentFilter("SEARCH_QUERY_CHANGED"))
                requireActivity().registerReceiver(sortReceiver, IntentFilter("SORT_VIDEOS"))
            }
        } catch (e: Exception) {}
        refreshDataPreserveState()
    }

    override fun onPause() {
        super.onPause()
        try { requireContext().contentResolver.unregisterContentObserver(videoContentObserver) } catch (e: Exception) {}
        try {
            requireActivity().unregisterReceiver(searchReceiver)
            requireActivity().unregisterReceiver(sortReceiver)
        } catch (e: Exception) {}
        saveScrollState()
    }

    fun setScrollingEnabled(enabled: Boolean) {
        if (this::recyclerView.isInitialized) {
            recyclerView.isNestedScrollingEnabled = enabled
            recyclerView.isEnabled = enabled
        }
    }

    fun saveScrollState() {
        if (this::recyclerView.isInitialized) {
            val layoutManager = recyclerView.layoutManager as? GridLayoutManager
            layoutManager?.let {
                scrollPosition = it.findFirstVisibleItemPosition()
                val v = it.findViewByPosition(scrollPosition)
                scrollOffset = v?.top ?: 0
            }
        }
    }

    fun restoreScrollState() {
        if (this::recyclerView.isInitialized) {
            val layoutManager = recyclerView.layoutManager as? GridLayoutManager
            layoutManager?.let {
                handler.postDelayed({
                    it.scrollToPositionWithOffset(scrollPosition, scrollOffset)
                }, 100)
            }
        }
    }
}