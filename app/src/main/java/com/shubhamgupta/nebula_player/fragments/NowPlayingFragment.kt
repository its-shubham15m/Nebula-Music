package com.shubhamgupta.nebula_player.fragments

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.transition.TransitionManager
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.palette.graphics.Palette
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.shubhamgupta.nebula_player.MainActivity
import com.shubhamgupta.nebula_player.R
import com.shubhamgupta.nebula_player.adapters.LyricLine
import com.shubhamgupta.nebula_player.adapters.LyricsAdapter
import com.shubhamgupta.nebula_player.api.LrcLibApiClient
import com.shubhamgupta.nebula_player.models.LrcLibLyrics
import com.shubhamgupta.nebula_player.models.Song
import com.shubhamgupta.nebula_player.service.MusicService
import com.shubhamgupta.nebula_player.utils.PreferenceManager
import com.shubhamgupta.nebula_player.utils.SongUtils
import com.shubhamgupta.nebula_player.utils.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class NowPlayingFragment : Fragment() {

    private var musicService: MusicService? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isSeeking = false
    private lateinit var bottomSheetDialog: BottomSheetDialog
    private var audioOutputDialog: BottomSheetDialog? = null
    private var isFragmentVisible = false

    private var isSharing = false

    // Views
    private lateinit var seekBar: SeekBar
    private lateinit var tvCurrent: TextView
    private lateinit var tvTotal: TextView
    private lateinit var btnPlay: ImageButton
    private lateinit var btnPrev: ImageButton
    private lateinit var btnNext: ImageButton
    private lateinit var btnBack: ImageButton
    private lateinit var btnRepeat: ImageButton
    private lateinit var btnShare: ImageButton
    private lateinit var btnDetails: ImageButton
    private lateinit var btnQueue: ImageButton
    private lateinit var btnAudioOutput: ImageButton
    private lateinit var ivFavorite: ImageView
    private lateinit var ivAlbumArt: ImageView

    // Main Song Info Views
    private lateinit var tvSongTitle: TextView
    private lateinit var tvSongArtist: TextView
    private lateinit var tvSongDetails: TextView

    // Lyrics Header Views (The one next to Favorite icon)
    private lateinit var lyricsHeaderInfo: View
    private lateinit var middleControlsSpacer: View
    private lateinit var ivNowPlayingIcon: ImageView
    private lateinit var tvLyricsSongTitle: TextView
    private lateinit var tvLyricsSongArtist: TextView

    private lateinit var backgroundGradient: ImageView
    private lateinit var backgroundOverlay: View

    // Containers for swapping
    private lateinit var artInfoContainer: View
    private lateinit var lyricsContainer: View
    private lateinit var mainContentContainer: ViewGroup

    // Lyrics UI Components
    private lateinit var lyricsLoadingProgress: ProgressBar
    private lateinit var lyricsRecyclerView: RecyclerView
    private lateinit var lyricsPlainScrollView: View
    private lateinit var tvLyricsPlain: TextView

    private lateinit var lyricsAdapter: LyricsAdapter
    private var currentLyricsList: List<LyricLine> = emptyList()
    private var currentLyricsSongId: Long = -1
    private var isLyricsVisible = false

    private lateinit var queueManager: NowPlayingQueueManager

    // Smooth scroller to center items
    private val smoothScroller by lazy {
        object : LinearSmoothScroller(context) {
            override fun getVerticalSnapPreference(): Int {
                return SNAP_TO_ANY
            }
            override fun calculateDtToFit(viewStart: Int, viewEnd: Int, boxStart: Int, boxEnd: Int, snapPreference: Int): Int {
                return (boxStart + (boxEnd - boxStart) / 2) - (viewStart + (viewEnd - viewStart) / 2)
            }
            override fun calculateTimeForScrolling(dx: Int): Int {
                return super.calculateTimeForScrolling(dx) * 2
            }
        }
    }

    private val updateSeekBar = object : Runnable {
        override fun run() {
            musicService?.let { service ->
                if (!isSeeking && service.isPlaying()) {
                    val currentPosition = service.getCurrentPosition()
                    val duration = service.getDuration()

                    if (duration > 0) {
                        seekBar.progress = currentPosition
                        tvCurrent.text = Utils.formatTime(currentPosition.toLong())
                        syncLyrics(currentPosition.toLong())
                    }
                }
                handler.postDelayed(this, 50)
            }
        }
    }

    private fun syncLyrics(currentPosition: Long) {
        if (!isLyricsVisible || currentLyricsList.isEmpty()) return

        lyricsAdapter.updateCurrentTime(currentPosition)

        val activeIndex = currentLyricsList.indexOfLast { it.startTime <= currentPosition }

        if (activeIndex != -1 && activeIndex != lyricsAdapter.activeIndex) {
            lyricsAdapter.updateActiveLine(activeIndex)

            val layoutManager = lyricsRecyclerView.layoutManager as? LinearLayoutManager
            if (layoutManager != null) {
                smoothScroller.targetPosition = activeIndex
                layoutManager.startSmoothScroll(smoothScroller)
            }
        }
    }

    private val songChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "SONG_CHANGED" -> {
                    musicService?.getCurrentSong()?.let { song ->
                        song.isFavorite = PreferenceManager.isFavorite(requireContext(), song.id)
                        if (isLyricsVisible) {
                            fetchLyrics(song)
                        } else {
                            currentLyricsSongId = -1
                        }
                    }
                    updateSongInfo()
                    updatePlaybackControls()
                    queueManager.refreshQueueDialog()
                }
                "PLAYBACK_STATE_CHANGED" -> {
                    updatePlayButton()
                }
                "PLAYBACK_MODE_CHANGED" -> {
                    updateRepeatButton()
                }
                "SEEK_POSITION_CHANGED" -> {
                    val position = intent.getIntExtra("position", 0)
                    if (!isSeeking) {
                        seekBar.progress = position
                        tvCurrent.text = Utils.formatTime(position.toLong())
                        syncLyrics(position.toLong())
                    }
                }
                "QUEUE_CHANGED" -> {
                    queueManager.refreshQueueDialog()
                }
            }
        }
    }

    companion object {
        fun newInstance(): NowPlayingFragment = NowPlayingFragment()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        musicService = (requireActivity() as MainActivity).getMusicService()
        queueManager = NowPlayingQueueManager(this)

        val filter = IntentFilter().apply {
            addAction("SONG_CHANGED")
            addAction("PLAYBACK_STATE_CHANGED")
            addAction("PLAYBACK_MODE_CHANGED")
            addAction("SEEK_POSITION_CHANGED")
            addAction("QUEUE_CHANGED")
        }

        ContextCompat.registerReceiver(
            requireActivity(),
            songChangeReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_now_playing, container, false)
        initializeViews(view)
        setupClickListeners()
        setupBottomSheet()
        updatePlaybackControls()
        startSeekBarUpdates()

        return view
    }

    private fun initializeViews(view: View) {
        mainContentContainer = view.findViewById(R.id.main_content_container)
        seekBar = view.findViewById(R.id.seek_bar)
        tvCurrent = view.findViewById(R.id.tv_current)
        tvTotal = view.findViewById(R.id.tv_total)
        btnPlay = view.findViewById(R.id.btn_play)
        btnPrev = view.findViewById(R.id.btn_prev)
        btnNext = view.findViewById(R.id.btn_next)
        btnBack = view.findViewById(R.id.btn_back)
        btnRepeat = view.findViewById(R.id.btn_repeat)
        btnShare = view.findViewById(R.id.btn_share)
        btnDetails = view.findViewById(R.id.btn_details)
        btnQueue = view.findViewById(R.id.btn_queue)
        btnAudioOutput = view.findViewById(R.id.btn_audio_output) // NEW
        ivFavorite = view.findViewById(R.id.iv_fav)
        ivAlbumArt = view.findViewById(R.id.album_art)

        // Main Info
        tvSongTitle = view.findViewById(R.id.song_title)
        tvSongArtist = view.findViewById(R.id.song_artist)
        tvSongDetails = view.findViewById(R.id.song_details)

        // Lyrics Header Info
        lyricsHeaderInfo = view.findViewById(R.id.lyrics_header_info)
        middleControlsSpacer = view.findViewById(R.id.middle_controls_spacer)
        ivNowPlayingIcon = view.findViewById(R.id.iv_now_playing_icon)
        tvLyricsSongTitle = view.findViewById(R.id.tv_lyrics_song_title)
        tvLyricsSongArtist = view.findViewById(R.id.tv_lyrics_song_artist)

        backgroundGradient = view.findViewById(R.id.background_gradient)
        backgroundOverlay = view.findViewById(R.id.background_overlay)

        // Swappable Containers
        artInfoContainer = view.findViewById(R.id.art_info_container)
        lyricsContainer = view.findViewById(R.id.lyrics_container)

        // Initialize Lyrics Views
        lyricsLoadingProgress = view.findViewById(R.id.lyrics_loading_progress)
        lyricsRecyclerView = view.findViewById(R.id.lyrics_recycler_view)
        lyricsPlainScrollView = view.findViewById(R.id.lyrics_plain_scroll_view)
        tvLyricsPlain = view.findViewById(R.id.tv_lyrics_plain)

        setupLyricsAdapter()
        setupSeekBar()
        applySystemWindowInsets(view)
    }

    private fun setupLyricsAdapter() {
        lyricsAdapter = LyricsAdapter { line ->
            musicService?.seekTo(line.startTime.toInt())
        }
        lyricsRecyclerView.layoutManager = LinearLayoutManager(context)
        lyricsRecyclerView.adapter = lyricsAdapter

        lyricsRecyclerView.post {
            val padding = (lyricsRecyclerView.height * 0.40).toInt()
            lyricsRecyclerView.setPadding(0, padding, 0, padding)
            lyricsRecyclerView.clipToPadding = false
        }
    }

    private fun applySystemWindowInsets(view: View) {
        val topControls = view.findViewById<LinearLayout>(R.id.top_controls)
        val mainPlayControls = view.findViewById<LinearLayout>(R.id.main_play_controls)
        val rootLayout = view as ViewGroup

        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { v, insets ->
            val systemBarsInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            val totalTopPadding = systemBarsInsets.top
            topControls.setPadding(
                topControls.paddingLeft,
                totalTopPadding,
                topControls.paddingRight,
                topControls.paddingBottom
            )

            val mainPlayControlsLayoutParams = mainPlayControls.layoutParams as ViewGroup.MarginLayoutParams
            val totalBottomMargin = systemBarsInsets.bottom + dpToPx(50)

            mainPlayControlsLayoutParams.bottomMargin = totalBottomMargin
            mainPlayControls.layoutParams = mainPlayControlsLayoutParams

            insets
        }
    }

    private fun setupBottomSheet() {
        val bottomSheetView = LayoutInflater.from(requireContext())
            .inflate(R.layout.bottom_sheet_song_details, null)

        bottomSheetDialog = BottomSheetDialog(requireContext())
        bottomSheetDialog.setContentView(bottomSheetView)

        bottomSheetDialog.behavior.peekHeight = resources.displayMetrics.heightPixels
        bottomSheetDialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED

        bottomSheetView.findViewById<ImageButton>(R.id.btn_close_sheet).setOnClickListener {
            bottomSheetDialog.dismiss()
        }

        bottomSheetView.findViewById<View>(R.id.btn_share_song).setOnClickListener {
            shareSongFile()
            bottomSheetDialog.dismiss()
        }

        bottomSheetView.findViewById<View>(R.id.btn_add_to_playlist).setOnClickListener {
            showAddToPlaylistDialog()
            bottomSheetDialog.dismiss()
        }
    }

    private fun setupSeekBar() {
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    tvCurrent.text = Utils.formatTime(progress.toLong())
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isSeeking = false
                musicService?.seekTo(seekBar?.progress ?: 0)
            }
        })
    }

    private fun setupClickListeners() {
        btnBack.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        btnPlay.setOnClickListener { togglePlayPause() }
        btnPrev.setOnClickListener { musicService?.playPrevious() }
        btnNext.setOnClickListener { musicService?.playNext() }
        btnRepeat.setOnClickListener { toggleRepeat() }
        btnShare.setOnClickListener { shareSongFile() }
        btnDetails.setOnClickListener { showSongDetailsSheet() }
        btnQueue.setOnClickListener { queueManager.showQueueDialog() }
        ivFavorite.setOnClickListener { toggleFavorite() }

        // Audio Output Click
        btnAudioOutput.setOnClickListener { showAudioOutputDialog() }

        ivAlbumArt.setOnClickListener { toggleLyricsVisibility() }
        lyricsContainer.setOnClickListener { toggleLyricsVisibility() }

        lyricsRecyclerView.setOnClickListener { toggleLyricsVisibility() }
        lyricsPlainScrollView.setOnClickListener { toggleLyricsVisibility() }
        tvLyricsPlain.setOnClickListener { toggleLyricsVisibility() }
    }

    private fun showAudioOutputDialog() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val audioManager = requireContext().getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

            // Filter devices to show relevant ones (avoid duplicate internals sometimes)
            val filteredDevices = devices.distinctBy { it.id }

            if (filteredDevices.isEmpty()) {
                Toast.makeText(requireContext(), "No output devices found", Toast.LENGTH_SHORT).show()
                return
            }

            // Inflate Custom Layout
            val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_audio_output, null)
            val recyclerView = dialogView.findViewById<RecyclerView>(R.id.recycler_audio_devices)
            val btnClose = dialogView.findViewById<MaterialButton>(R.id.btn_close_audio_output)

            // Setup RecyclerView
            recyclerView.layoutManager = LinearLayoutManager(requireContext())

            // Get currently active device logic
            val currentDeviceId = musicService?.getPreferredAudioDevice() ?: -1

            val adapter = AudioDeviceAdapter(filteredDevices, currentDeviceId) { selectedDevice ->
                val success = musicService?.setPreferredAudioDevice(selectedDevice.id) == true
                if (success) {
                    Toast.makeText(requireContext(), "Switched to ${selectedDevice.productName}", Toast.LENGTH_SHORT).show()
                    audioOutputDialog?.dismiss()
                } else {
                    Toast.makeText(requireContext(), "Could not switch device", Toast.LENGTH_SHORT).show()
                }
            }
            recyclerView.adapter = adapter

            audioOutputDialog = BottomSheetDialog(requireContext())
            audioOutputDialog?.setContentView(dialogView)

            btnClose.setOnClickListener {
                audioOutputDialog?.dismiss()
            }

            audioOutputDialog?.show()

        } else {
            Toast.makeText(requireContext(), "Audio switching requires Android M+", Toast.LENGTH_SHORT).show()
        }
    }

    // Inner Adapter Class for Audio Devices
    inner class AudioDeviceAdapter(
        private val devices: List<AudioDeviceInfo>,
        private val currentDeviceId: Int,
        private val onDeviceSelected: (AudioDeviceInfo) -> Unit
    ) : RecyclerView.Adapter<AudioDeviceAdapter.DeviceViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_audio_device, parent, false)
            return DeviceViewHolder(view)
        }

        override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
            val device = devices[position]
            holder.bind(device, device.id == currentDeviceId)
        }

        override fun getItemCount(): Int = devices.size

        inner class DeviceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val ivIcon: ImageView = itemView.findViewById(R.id.iv_device_icon)
            private val tvName: TextView = itemView.findViewById(R.id.tv_device_name)
            private val tvType: TextView = itemView.findViewById(R.id.tv_device_type)
            private val rbSelected: RadioButton = itemView.findViewById(R.id.rb_device_selected)

            fun bind(device: AudioDeviceInfo, isSelected: Boolean) {
                // Name logic
                val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    device.productName.toString()
                } else {
                    getDeviceTypeName(device.type)
                }
                tvName.text = name
                tvType.text = getDeviceTypeName(device.type)

                // Icon logic
                val iconRes = when(device.type) {
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> R.drawable.ic_bluetooth
                    AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> R.drawable.ic_headset
                    else -> R.drawable.ic_speaker
                }
                ivIcon.setImageResource(iconRes)

                rbSelected.isChecked = isSelected

                itemView.setOnClickListener {
                    onDeviceSelected(device)
                }
                rbSelected.setOnClickListener {
                    onDeviceSelected(device)
                }
            }
        }
    }

    private fun getDeviceTypeName(type: Int): String {
        return when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone Speaker"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired Headphones"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth Audio"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth Headset"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Audio"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset"
            AudioDeviceInfo.TYPE_AUX_LINE -> "AUX Cable"
            else -> "Audio Device"
        }
    }

    private fun toggleLyricsVisibility() {
        isLyricsVisible = !isLyricsVisible

        // Use TransitionManager ONLY on the containers changing to avoid affecting Seekbar
        TransitionManager.beginDelayedTransition(mainContentContainer)

        if (isLyricsVisible) {
            lyricsContainer.visibility = View.VISIBLE
            artInfoContainer.visibility = View.INVISIBLE

            // Show new Details Header & Icon, hide Spacer
            lyricsHeaderInfo.visibility = View.VISIBLE
            ivNowPlayingIcon.visibility = View.VISIBLE
            middleControlsSpacer.visibility = View.GONE

            // Re-trigger marquee for the header
            tvLyricsSongTitle.isSelected = true
            tvLyricsSongArtist.isSelected = true

            val song = musicService?.getCurrentSong()
            if (song != null) {
                fetchLyrics(song)
            }
        } else {
            lyricsContainer.visibility = View.GONE
            artInfoContainer.visibility = View.VISIBLE

            // Hide Details Header & Icon, show Spacer
            lyricsHeaderInfo.visibility = View.GONE
            ivNowPlayingIcon.visibility = View.GONE
            middleControlsSpacer.visibility = View.VISIBLE
        }
    }

    private fun cleanMetaData(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        var cleaned = text
        cleaned = cleaned.replace(Regex("(?i)\\.(mp3|m4a|flac|wav|aac|ogg)$"), "")
        cleaned = cleaned.replace(Regex("\\(.*?\\)"), "")
        cleaned = cleaned.replace(Regex("\\[.*?\\]"), "")
        cleaned = cleaned.replace(Regex("\\{.*?\\}"), "")
        cleaned = cleaned.replace(Regex("(?i)\\s*[-_]?\\s*(?:www\\.|pagal|hindi|mr[-]?jatt|dj|wap|songs\\.pk|\\d+kbps|org|net|com|mobi|info|ru).*"), "")
        return cleaned.trim()
    }

    private fun fetchLyrics(song: Song) {
        if (currentLyricsSongId == song.id && (currentLyricsList.isNotEmpty() || tvLyricsPlain.text.isNotEmpty())) {
            return
        }

        currentLyricsSongId = song.id
        currentLyricsList = emptyList()
        lyricsAdapter.submitList(emptyList())
        tvLyricsPlain.text = ""

        lyricsLoadingProgress.visibility = View.VISIBLE
        lyricsRecyclerView.visibility = View.GONE
        lyricsPlainScrollView.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val cleanTitle = cleanMetaData(song.title)
                val cleanArtist = cleanMetaData(song.artist ?: "")
                val cleanAlbum = cleanMetaData(song.album ?: "")
                val durationSeconds = (song.duration / 1000).toInt()

                val lyricResult = withContext(Dispatchers.IO) {
                    var result: LrcLibLyrics? = null
                    try {
                        result = LrcLibApiClient.api.getLyrics(cleanTitle, cleanArtist, cleanAlbum, durationSeconds)
                    } catch (e: Exception) {
                        try {
                            val query = "$cleanTitle $cleanArtist"
                            val searchResults = LrcLibApiClient.api.searchLyrics(query)
                            result = searchResults.minByOrNull { abs((it.duration ?: 0) - durationSeconds) }
                        } catch (searchEx: Exception) {
                            Log.e("NowPlayingFragment", "Search failed: ${searchEx.message}")
                        }
                    }
                    result
                }

                lyricsLoadingProgress.visibility = View.GONE

                if (lyricResult != null) {
                    if (!lyricResult.syncedLyrics.isNullOrEmpty()) {
                        currentLyricsList = LyricsAdapter.parseLrc(lyricResult.syncedLyrics)
                        lyricsAdapter.submitList(currentLyricsList)
                        lyricsRecyclerView.visibility = View.VISIBLE
                        lyricsPlainScrollView.visibility = View.GONE
                    }
                    else if (!lyricResult.plainLyrics.isNullOrEmpty()) {
                        tvLyricsPlain.text = lyricResult.plainLyrics
                        lyricsPlainScrollView.visibility = View.VISIBLE
                        lyricsRecyclerView.visibility = View.GONE
                    } else {
                        showNoLyricsFound()
                    }
                } else {
                    showNoLyricsFound()
                }

            } catch (e: Exception) {
                Log.e("NowPlayingFragment", "Error fetching lyrics: ${e.message}")
                lyricsLoadingProgress.visibility = View.GONE
                showNoLyricsFound()
            }
        }
    }

    private fun showNoLyricsFound() {
        tvLyricsPlain.text = "No lyrics found"
        lyricsPlainScrollView.visibility = View.VISIBLE
        lyricsRecyclerView.visibility = View.GONE
    }

    private fun toggleRepeat() {
        musicService?.toggleRepeatMode()
    }

    private fun updateRepeatButton() {
        val repeatMode = musicService?.getRepeatMode() ?: MusicService.RepeatMode.ALL

        val iconRes = when (repeatMode) {
            MusicService.RepeatMode.ONE -> R.drawable.repeat_one
            MusicService.RepeatMode.SHUFFLE -> R.drawable.shuffle
            else -> R.drawable.repeat
        }
        btnRepeat.setImageResource(iconRes)
        btnRepeat.setColorFilter(ContextCompat.getColor(requireContext(), R.color.white))
    }

    private fun showSongDetailsSheet() {
        val currentSong = musicService?.getCurrentSong() ?: return
        val sheetView = bottomSheetDialog.findViewById<View>(R.id.bottom_sheet_song_details) ?: return
        sheetView.findViewById<TextView>(R.id.sheet_song_title).text = currentSong.title
        sheetView.findViewById<TextView>(R.id.sheet_song_artist).text = currentSong.artist ?: "Unknown Artist"
        sheetView.findViewById<TextView>(R.id.sheet_song_album).text = currentSong.album ?: "Unknown Album"

        val artLoader = if (currentSong.embeddedArtBytes != null) {
            Glide.with(this).load(currentSong.embeddedArtBytes)
        } else {
            Glide.with(this).load(SongUtils.getAlbumArtUri(currentSong.albumId))
        }

        artLoader.placeholder(R.drawable.default_album_art)
            .error(R.drawable.default_album_art)
            .into(sheetView.findViewById(R.id.sheet_album_art))

        loadSongDetailsIntoSheet(currentSong, sheetView)
        bottomSheetDialog.show()
    }

    private fun loadSongDetailsIntoSheet(song: Song, sheetView: View) {
        val metadataContainer = sheetView.findViewById<LinearLayout>(R.id.sheet_metadata_container)
        metadataContainer.removeAllViews()
        addMetadataRow(metadataContainer, "Title", song.title)
        addMetadataRow(metadataContainer, "Artist", song.artist ?: "Unknown")
        addMetadataRow(metadataContainer, "Album", song.album ?: "Unknown")
        addMetadataRow(metadataContainer, "Genre", song.genre ?: "Unknown")
        addMetadataRow(metadataContainer, "Year", song.year ?: "Unknown")
        val duration = musicService?.getDuration() ?: 0
        addMetadataRow(metadataContainer, "Duration", Utils.formatTime(duration.toLong()))
        loadAudioMetadata(song, metadataContainer)
    }

    private fun addMetadataRow(container: LinearLayout, label: String, value: String) {
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, dpToPx(8), 0, dpToPx(8))
            }
        }
        val labelView = TextView(requireContext()).apply {
            text = "$label:"
            setTextAppearance(android.R.style.TextAppearance_Small)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        val valueView = TextView(requireContext()).apply {
            text = value
            setTextAppearance(android.R.style.TextAppearance_Small)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        layout.addView(labelView)
        layout.addView(valueView)
        container.addView(layout)
    }

    private fun loadAudioMetadata(song: Song, container: LinearLayout) {
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(requireContext(), song.uri)
            val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
            val sampleRate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)
            } else {
                null
            }
            val mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            val channels = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_NUM_TRACKS)
            bitrate?.let {
                val kbps = (it.toIntOrNull() ?: 0) / 1000
                addMetadataRow(container, "Bitrate", "$kbps kbps")
            }
            sampleRate?.let { addMetadataRow(container, "Sample Rate", "$it Hz") }
            mimeType?.let { addMetadataRow(container, "Format", it) }
            channels?.let { addMetadataRow(container, "Channels", it) }
            retriever.release()
        } catch (e: Exception) {
            // Ignore metadata errors
        }
    }

    private fun showAddToPlaylistDialog() {
        Toast.makeText(requireContext(), "Add to Playlist feature (needs implementation)", Toast.LENGTH_SHORT).show()
    }

    private fun togglePlayPause() {
        musicService?.togglePlayPause()
    }

    private fun shareSongFile() {
        val song = musicService?.getCurrentSong() ?: return
        isSharing = true
        try {
            val shareIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_STREAM, song.uri)
                type = requireContext().contentResolver.getType(song.uri) ?: "audio/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Share ${song.title}"))
        } catch (e: Exception) {
            Log.e("NowPlayingFragment", "Error sharing song file", e)
            Toast.makeText(requireContext(), "Could not share file.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleFavorite() {
        val currentSong = musicService?.getCurrentSong() ?: return
        currentSong.isFavorite = !currentSong.isFavorite
        if (currentSong.isFavorite) {
            PreferenceManager.addFavorite(requireContext(), currentSong.id)
        } else {
            PreferenceManager.removeFavorite(requireContext(), currentSong.id)
        }
        ivFavorite.setImageResource(
            if (currentSong.isFavorite) R.drawable.ic_favorite_filled
            else R.drawable.ic_favorite_outline
        )
        Toast.makeText(requireContext(),
            if (currentSong.isFavorite) "Added to favorites" else "Removed from favorites",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun updatePlaybackControls() {
        updateSongInfo()
        updatePlayButton()
        updateRepeatButton()
    }

    private fun updateSongInfo() {
        val currentSong = musicService?.getCurrentSong() ?: return
        currentSong.isFavorite = PreferenceManager.isFavorite(requireContext(), currentSong.id)

        // Update Main Info
        tvSongTitle.text = currentSong.title
        tvSongArtist.text = currentSong.artist ?: "Unknown Artist"

        // IMPORTANT: Trigger marquee for main info
        tvSongTitle.isSelected = true
        tvSongArtist.isSelected = true
        tvSongDetails.isSelected = true

        // Update Lyrics Header Info
        tvLyricsSongTitle.text = currentSong.title
        tvLyricsSongArtist.text = currentSong.artist ?: "Unknown Artist"
        // IMPORTANT: Trigger marquee for lyrics header
        tvLyricsSongTitle.isSelected = true
        tvLyricsSongArtist.isSelected = true

        val detailsText = StringBuilder()
        currentSong.album?.let { detailsText.append(it) }
        currentSong.year?.let {
            if (detailsText.isNotEmpty()) detailsText.append(" | ")
            detailsText.append(it)
        }
        tvSongDetails.text = detailsText

        val artLoader = if (currentSong.embeddedArtBytes != null) {
            Glide.with(this).load(currentSong.embeddedArtBytes)
        } else {
            Glide.with(this).load(SongUtils.getAlbumArtUri(currentSong.albumId))
        }

        artLoader.placeholder(R.drawable.default_album_art)
            .error(R.drawable.default_album_art)
            .listener(object : RequestListener<android.graphics.drawable.Drawable> {
                override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<android.graphics.drawable.Drawable>, isFirstResource: Boolean): Boolean { return false }
                override fun onResourceReady(resource: android.graphics.drawable.Drawable, model: Any, target: Target<android.graphics.drawable.Drawable>?, dataSource: DataSource, isFirstResource: Boolean): Boolean {
                    generateGradientBackground(resource)
                    return false
                }
            })
            .into(ivAlbumArt)

        val duration = musicService?.getDuration() ?: 0
        if (duration > 0) {
            seekBar.max = duration
            tvTotal.text = Utils.formatTime(duration.toLong())

            val savedState = PreferenceManager.loadPlaybackState(requireContext())
            savedState?.let { state ->
                if (state.lastPlayedSongId == currentSong.id && state.lastSeekPosition > 0) {
                    handler.post {
                        seekBar.progress = state.lastSeekPosition
                        tvCurrent.text = Utils.formatTime(state.lastSeekPosition.toLong())
                    }
                }
            }
        }

        ivFavorite.setImageResource(
            if (currentSong.isFavorite) R.drawable.ic_favorite_filled
            else R.drawable.ic_favorite_outline
        )
    }

    private fun generateGradientBackground(drawable: android.graphics.drawable.Drawable) {
        try {
            val bitmap = if (drawable is BitmapDrawable) {
                drawable.bitmap
            } else {
                val bitmap = createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight)
                val canvas = android.graphics.Canvas(bitmap)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                bitmap
            }

            Palette.from(bitmap).generate { palette ->
                val dominantColor = palette?.dominantSwatch?.rgb ?: 0x000000
                val mutedColor = palette?.mutedSwatch?.rgb ?: dominantColor
                val vibrantColor = palette?.vibrantSwatch?.rgb ?: dominantColor

                val gradientDrawable = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(
                        ColorUtils.setAlphaComponent(vibrantColor, 150),
                        ColorUtils.setAlphaComponent(mutedColor, 100),
                        ColorUtils.setAlphaComponent(dominantColor, 50)
                    )
                )
                backgroundGradient.setImageDrawable(gradientDrawable)
            }
        } catch (e: Exception) {
            // Fallback gradient
        }
    }

    private fun updatePlayButton() {
        val isPlaying = musicService?.isPlaying() ?: false
        btnPlay.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun setSystemBarAppearance(isNowPlaying: Boolean) {
        val window = requireActivity().window
        val windowController = WindowCompat.getInsetsController(window, window.decorView)

        if (isNowPlaying) {
            windowController.isAppearanceLightStatusBars = false
        } else {
            (activity as? MainActivity)?.updateSystemUiColors()
        }
    }

    private fun startSeekBarUpdates() {
        handler.post(updateSeekBar)
    }

    private fun stopSeekBarUpdates() {
        handler.removeCallbacks(updateSeekBar)
    }

    override fun onResume() {
        super.onResume()
        isFragmentVisible = true
        isSharing = false
        setSystemBarAppearance(true)

        musicService?.getCurrentSong()?.let { song ->
            song.isFavorite = PreferenceManager.isFavorite(requireContext(), song.id)
        }

        startSeekBarUpdates()
        updatePlaybackControls()

        // Re-enable marquee when fragment resumes
        tvSongTitle.isSelected = true
        tvSongArtist.isSelected = true
        tvSongDetails.isSelected = true
        if (isLyricsVisible) {
            tvLyricsSongTitle.isSelected = true
            tvLyricsSongArtist.isSelected = true
        }
    }

    override fun onPause() {
        super.onPause()
        isFragmentVisible = false
        if (!isSharing) {
            setSystemBarAppearance(false)
        }
        stopSeekBarUpdates()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        setSystemBarAppearance(false)
        isFragmentVisible = false
        queueManager.stopScrollMonitoring()
        stopSeekBarUpdates()
        if (bottomSheetDialog.isShowing) {
            bottomSheetDialog.dismiss()
        }
        queueManager.dismissQueueDialog()
    }

    override fun onDestroy() {
        super.onDestroy()
        queueManager.stopScrollMonitoring()
        try {
            requireActivity().unregisterReceiver(songChangeReceiver)
        } catch (e: Exception) {
            // Ignore if receiver was not registered
        }
        queueManager.clearCache()
    }

    private fun dpToPx(dp: Int): Int {
        return try {
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp.toFloat(),
                resources.displayMetrics
            ).toInt()
        } catch (e: Exception) {
            (dp * resources.displayMetrics.density).toInt()
        }
    }

    fun getMusicService(): MusicService? = musicService
    fun getCurrentQueuePosition(): Int = musicService?.getCurrentQueuePosition() ?: 0
}