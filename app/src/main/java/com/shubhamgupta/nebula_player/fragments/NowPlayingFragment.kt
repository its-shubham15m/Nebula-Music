package com.shubhamgupta.nebula_player.fragments

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.PorterDuff
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.transition.TransitionManager
import android.util.Log
import android.util.TypedValue
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class NowPlayingFragment : Fragment() {

    private var musicService: MusicService? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isSeeking = false
    private lateinit var bottomSheetDialog: BottomSheetDialog

    private var audioOutputDialog: Dialog? = null

    private var isFragmentVisible = false

    private var isSharing = false
    private lateinit var audioManager: AudioManager

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

    // Lyrics Header Views
    private lateinit var lyricsHeaderInfo: View
    private lateinit var singleLineContainer: View
    private lateinit var tvSingleLineLyric: TextView
    private lateinit var ivSingleLineIcon: ImageView
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

    private val lyricsCache = mutableMapOf<Long, LrcLibLyrics?>()
    private var fetchLyricsJob: Job? = null

    private lateinit var queueManager: NowPlayingQueueManager

    // For Swipe Animation logic
    private var swipeDirection = 0 // 0: None, -1: Next (Swipe Left), 1: Prev (Swipe Right)
    private var isManuallySwiping = false

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

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            super.onAudioDevicesAdded(addedDevices)
            updateAudioOutputUI()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            super.onAudioDevicesRemoved(removedDevices)
            updateAudioOutputUI()
        }
    }

    private val callReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
                val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                if (state == TelephonyManager.EXTRA_STATE_RINGING || state == TelephonyManager.EXTRA_STATE_OFFHOOK) {
                    if (musicService?.isPlaying() == true) {
                        musicService?.pause()
                        updatePlayButton()
                    }
                }
            }
        }
    }

    private fun syncLyrics(currentPosition: Long) {
        if (currentLyricsList.isNotEmpty()) {
            val activeIndex = currentLyricsList.indexOfLast { it.startTime <= currentPosition }

            if (activeIndex != -1) {
                val currentLine = currentLyricsList[activeIndex]
                val lineText = currentLine.text
                if (tvSingleLineLyric.text.toString() != lineText) {
                    tvSingleLineLyric.text = lineText
                }

                // --- KARAOKE EFFECT ---
                val nextStartTime = if (activeIndex + 1 < currentLyricsList.size) {
                    currentLyricsList[activeIndex + 1].startTime
                } else {
                    currentLine.startTime + 5000 // Default 5s for last line
                }

                val lineDuration = nextStartTime - currentLine.startTime
                val timePassed = currentPosition - currentLine.startTime
                val progress = (timePassed.toFloat() / lineDuration.toFloat()).coerceIn(0f, 1f)

                if (tvSingleLineLyric.width > 0) {
                    val activeColor = Color.WHITE
                    val inactiveColor = Color.parseColor("#80FFFFFF")

                    val gradient = LinearGradient(
                        0f, 0f, tvSingleLineLyric.width.toFloat(), 0f,
                        intArrayOf(activeColor, activeColor, inactiveColor, inactiveColor),
                        floatArrayOf(0f, progress, progress + 0.01f, 1f),
                        Shader.TileMode.CLAMP
                    )
                    tvSingleLineLyric.paint.shader = gradient
                    tvSingleLineLyric.invalidate()
                }
                // ---------------------

                if (isLyricsVisible) {
                    lyricsAdapter.updateCurrentTime(currentPosition)
                    if (activeIndex != lyricsAdapter.activeIndex) {
                        lyricsAdapter.updateActiveLine(activeIndex)
                        val layoutManager = lyricsRecyclerView.layoutManager as? LinearLayoutManager
                        if (layoutManager != null) {
                            smoothScroller.targetPosition = activeIndex
                            layoutManager.startSmoothScroll(smoothScroller)
                        }
                    }
                }
            }
        }
    }

    private val songChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "SONG_CHANGED" -> {
                    musicService?.getCurrentSong()?.let { song ->
                        song.isFavorite = PreferenceManager.isFavorite(requireContext(), song.id)
                        fetchLyrics(song)
                    }

                    // --- SMOOTH TRANSITION LOGIC ---
                    if (isManuallySwiping) {
                        // User swiped, so we finish that animation
                        completeSwipeAnimation()
                    } else {
                        // Regular auto-play or button press: Default fade/reset
                        ivAlbumArt.animate().cancel()
                        ivAlbumArt.translationX = 0f
                        ivAlbumArt.alpha = 1f
                        updateSongInfo()
                    }
                    // -------------------------------

                    updatePlaybackControls()
                    queueManager.refreshQueueDialog()

                    tvSingleLineLyric.text = "Loading lyrics..."
                    tvSingleLineLyric.paint.shader = null
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
        audioManager = requireContext().getSystemService(Context.AUDIO_SERVICE) as AudioManager

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
        setupAlbumArtSwipe()

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
        btnAudioOutput = view.findViewById(R.id.btn_audio_output)
        ivFavorite = view.findViewById(R.id.iv_fav)
        ivAlbumArt = view.findViewById(R.id.album_art)

        tvSongTitle = view.findViewById(R.id.song_title)
        tvSongArtist = view.findViewById(R.id.song_artist)
        tvSongDetails = view.findViewById(R.id.song_details)

        lyricsHeaderInfo = view.findViewById(R.id.lyrics_header_info)

        singleLineContainer = view.findViewById(R.id.single_line_container)
        tvSingleLineLyric = view.findViewById(R.id.tv_single_line_lyric)
        ivSingleLineIcon = view.findViewById(R.id.iv_single_line_icon)

        ivNowPlayingIcon = view.findViewById(R.id.iv_now_playing_icon)
        tvLyricsSongTitle = view.findViewById(R.id.tv_lyrics_song_title)
        tvLyricsSongArtist = view.findViewById(R.id.tv_lyrics_song_artist)

        backgroundGradient = view.findViewById(R.id.background_gradient)
        backgroundOverlay = view.findViewById(R.id.background_overlay)

        artInfoContainer = view.findViewById(R.id.art_info_container)
        lyricsContainer = view.findViewById(R.id.lyrics_container)

        lyricsLoadingProgress = view.findViewById(R.id.lyrics_loading_progress)
        lyricsRecyclerView = view.findViewById(R.id.lyrics_recycler_view)
        lyricsPlainScrollView = view.findViewById(R.id.lyrics_plain_scroll_view)
        tvLyricsPlain = view.findViewById(R.id.tv_lyrics_plain)

        setupLyricsAdapter()
        setupSeekBar()
        applySystemWindowInsets(view)

        tvSingleLineLyric.isSelected = true
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

    // --- OPTIMIZED SWIPE IMPLEMENTATION ---
    @SuppressLint("ClickableViewAccessibility")
    private fun setupAlbumArtSwipe() {
        val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                toggleLyricsVisibility()
                return true
            }
            override fun onDown(e: MotionEvent): Boolean {
                return true
            }
        })

        ivAlbumArt.setOnTouchListener { v, event ->
            // 1. Process gesture detector for Taps (Lyrics Toggle)
            gestureDetector.onTouchEvent(event)

            // 2. Handle Drag Physics manually (Swipe Songs)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Stop any current animation immediately so we can grab the view
                    v.animate().cancel()

                    initialX = event.rawX
                    dX = 0f
                    isManuallySwiping = false
                    v.parent.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    val newX = event.rawX
                    dX = newX - initialX

                    // Move the view with finger
                    v.translationX = dX

                    // Simple fade out based on distance
                    val progress = (abs(dX) / (v.width.toFloat() * 0.7f)).coerceIn(0f, 1f)
                    v.alpha = 1f - progress
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handleSwipeRelease(v)
                    v.parent.requestDisallowInterceptTouchEvent(false)
                }
            }

            // Consume the event
            true
        }
    }

    private var initialX = 0f
    private var dX = 0f

    // Optimized Threshold: 25% of width
    private fun handleSwipeRelease(view: View) {
        val width = view.width.toFloat()
        val threshold = width * 0.25f

        if (dX > threshold) {
            // Dragged Right -> Previous
            isManuallySwiping = true
            swipeDirection = 1

            // 1. Trigger Service IMMEDIATELY (Don't wait for animation)
            musicService?.playPrevious()

            // 2. Animate visually out (Fast duration)
            view.animate()
                .translationX(width)
                .alpha(0f)
                .setDuration(150)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()

        } else if (dX < -threshold) {
            // Dragged Left -> Next
            isManuallySwiping = true
            swipeDirection = -1

            // 1. Trigger Service IMMEDIATELY
            musicService?.playNext()

            // 2. Animate visually out (Fast duration)
            view.animate()
                .translationX(-width)
                .alpha(0f)
                .setDuration(150)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()

        } else {
            // Snap back to center
            isManuallySwiping = false
            swipeDirection = 0
            view.animate()
                .translationX(0f)
                .alpha(1f)
                .setInterpolator(OvershootInterpolator())
                .setDuration(250)
                .start()
        }
    }

    private fun completeSwipeAnimation() {
        // Update the content (Text, new Image URL)
        updateSongInfo()

        // Prepare the view to slide IN from the opposite side
        val width = ivAlbumArt.width.toFloat()

        // If we swiped Next (Left), new view comes from Right (+width)
        // If we swiped Prev (Right), new view comes from Left (-width)
        val startOffset = if (swipeDirection == -1) width else -width

        // Instantly move view to start position (offscreen)
        ivAlbumArt.translationX = startOffset
        ivAlbumArt.alpha = 0f

        // Animate back to Center (0)
        ivAlbumArt.animate()
            .translationX(0f)
            .alpha(1f)
            .setInterpolator(DecelerateInterpolator()) // Decelerate feels snappier for entry
            .setDuration(250)
            .withEndAction {
                isManuallySwiping = false
                swipeDirection = 0
            }
            .start()
    }
    // ------------------------------------

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

        btnAudioOutput.setOnClickListener { showAudioOutputDialog() }

        lyricsContainer.setOnClickListener { toggleLyricsVisibility() }

        lyricsHeaderInfo.setOnClickListener { toggleLyricsVisibility() }

        singleLineContainer.setOnClickListener { toggleLyricsVisibility() }
        tvSingleLineLyric.setOnClickListener { toggleLyricsVisibility() }

        lyricsRecyclerView.setOnClickListener { toggleLyricsVisibility() }
        lyricsPlainScrollView.setOnClickListener { toggleLyricsVisibility() }
        tvLyricsPlain.setOnClickListener { toggleLyricsVisibility() }
    }

    private fun getActiveDeviceId(devices: List<AudioDeviceInfo>): Int {
        val preferredId = musicService?.getPreferredAudioDevice() ?: -1
        if (preferredId > 0) {
            if (devices.any { it.id == preferredId }) return preferredId
        }

        devices.find {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_USB_DEVICE
        }?.let { return it.id }

        devices.find {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }?.let { return it.id }

        return devices.find { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.id ?: -1
    }

    private fun updateAudioOutputUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            btnAudioOutput.visibility = View.VISIBLE
        } else {
            btnAudioOutput.visibility = View.GONE
        }
    }

    private fun getFilteredAudioDevices(devices: Array<AudioDeviceInfo>): List<AudioDeviceInfo> {
        val rawList = devices.toList()
        val result = mutableListOf<AudioDeviceInfo>()

        val internalSpeaker = rawList.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

        if (internalSpeaker != null) {
            result.add(internalSpeaker)
        } else {
            val earpiece = rawList.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
            if (earpiece != null) result.add(earpiece)
        }

        val externalDevices = rawList.filter {
            it.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER &&
                    it.type != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE &&
                    it.type != AudioDeviceInfo.TYPE_TELEPHONY
        }

        val groupedByName = externalDevices.groupBy { it.productName.toString().trim() }

        for ((_, duplicates) in groupedByName) {
            if (duplicates.size == 1) {
                result.add(duplicates[0])
            } else {
                val bestDevice = duplicates.maxByOrNull { device ->
                    when (device.type) {
                        AudioDeviceInfo.TYPE_WIRED_HEADSET,
                        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                        AudioDeviceInfo.TYPE_USB_HEADSET,
                        AudioDeviceInfo.TYPE_USB_DEVICE -> 100
                        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> 90
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> 50
                        else -> 0
                    }
                }
                bestDevice?.let { result.add(it) }
            }
        }

        return result.sortedBy {
            if (it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) 1 else 0
        }
    }

    private fun showAudioOutputDialog() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val filteredDevices = getFilteredAudioDevices(devices)

            if (filteredDevices.isEmpty()) {
                Toast.makeText(requireContext(), "No output devices found", Toast.LENGTH_SHORT).show()
                return
            }

            val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_audio_output, null)
            val recyclerView = dialogView.findViewById<RecyclerView>(R.id.recycler_audio_devices)
            val btnClose = dialogView.findViewById<MaterialButton>(R.id.btn_close_audio_output)

            recyclerView.layoutManager = LinearLayoutManager(requireContext())

            val currentDeviceId = getActiveDeviceId(devices.toList())

            val adapter = AudioDeviceAdapter(filteredDevices, currentDeviceId) { selectedDevice ->
                val success = musicService?.setPreferredAudioDevice(selectedDevice.id) == true
                if (success) {
                    Toast.makeText(requireContext(), "Switched to ${getDeviceName(selectedDevice)}", Toast.LENGTH_SHORT).show()
                    updateAudioOutputUI()
                    audioOutputDialog?.dismiss()
                } else {
                    Toast.makeText(requireContext(), "Could not switch device", Toast.LENGTH_SHORT).show()
                }
            }
            recyclerView.adapter = adapter

            audioOutputDialog = Dialog(requireContext())
            audioOutputDialog?.requestWindowFeature(Window.FEATURE_NO_TITLE)
            audioOutputDialog?.setContentView(dialogView)

            audioOutputDialog?.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
                    addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        setDecorFitsSystemWindows(false)
                    } else {
                        @Suppress("DEPRECATION")
                        decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
                    }
                    statusBarColor = Color.TRANSPARENT
                }
            }

            dialogView.findViewById<View>(R.id.background_overlay)?.setOnClickListener {
                audioOutputDialog?.dismiss()
            }

            btnClose.setOnClickListener {
                audioOutputDialog?.dismiss()
            }

            audioOutputDialog?.show()

        } else {
            Toast.makeText(requireContext(), "Audio switching requires Android M+", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getDeviceName(device: AudioDeviceInfo): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            device.productName.toString()
        } else {
            getDeviceTypeName(device.type)
        }
    }

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
            val isSelected = device.id == currentDeviceId ||
                    (device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER && isInternalSpeakerActive(currentDeviceId))

            holder.bind(device, isSelected)
        }

        private fun isInternalSpeakerActive(activeId: Int): Boolean {
            val activeDevice = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).find { it.id == activeId }
            return activeDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
                    activeDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        }

        override fun getItemCount(): Int = devices.size

        inner class DeviceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val ivIcon: ImageView = itemView.findViewById(R.id.iv_device_icon)
            private val tvName: TextView = itemView.findViewById(R.id.tv_device_name)
            private val tvStatus: TextView = itemView.findViewById(R.id.tv_device_status)
            private val ivIndicator: ImageView = itemView.findViewById(R.id.iv_active_indicator)

            fun bind(device: AudioDeviceInfo, isSelected: Boolean) {
                val name = getDeviceName(device)
                tvName.text = name

                if (!isSelected) {
                    tvStatus.text = getDeviceTypeName(device.type)
                }

                val iconRes = when (device.type) {
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> R.drawable.ic_bluetooth
                    AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> R.drawable.ic_headset
                    else -> R.drawable.ic_speaker
                }
                ivIcon.setImageResource(iconRes)

                val spotifyGreen = Color.parseColor("#3EA6FF")
                val defaultColor = ContextCompat.getColor(itemView.context, R.color.white)

                if (isSelected) {
                    tvName.setTextColor(spotifyGreen)
                    ivIcon.setColorFilter(spotifyGreen, PorterDuff.Mode.SRC_IN)
                    ivIndicator.setColorFilter(spotifyGreen, PorterDuff.Mode.SRC_IN)
                    ivIndicator.visibility = View.VISIBLE

                    tvStatus.text = "Active"
                    tvStatus.setTextColor(spotifyGreen)
                } else {
                    tvName.setTextColor(defaultColor)
                    ivIcon.clearColorFilter()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        ivIcon.imageTintList = ColorStateList.valueOf(defaultColor)
                    }
                    ivIndicator.visibility = View.GONE
                    tvStatus.setTextColor(Color.parseColor("#B3FFFFFF"))
                }

                itemView.setOnClickListener {
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

        TransitionManager.beginDelayedTransition(mainContentContainer)

        if (isLyricsVisible) {
            lyricsContainer.visibility = View.VISIBLE
            artInfoContainer.visibility = View.INVISIBLE

            lyricsHeaderInfo.visibility = View.VISIBLE
            ivNowPlayingIcon.visibility = View.VISIBLE
            singleLineContainer.visibility = View.GONE

            tvLyricsSongTitle.isSelected = true
            tvLyricsSongArtist.isSelected = true

            val song = musicService?.getCurrentSong()
            if (song != null) {
                checkAndDisplayLyrics(song)
            }
        } else {
            lyricsContainer.visibility = View.GONE
            artInfoContainer.visibility = View.VISIBLE

            lyricsHeaderInfo.visibility = View.GONE
            ivNowPlayingIcon.visibility = View.GONE
            singleLineContainer.visibility = View.VISIBLE
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

    private fun checkAndDisplayLyrics(song: Song) {
        if (lyricsCache.containsKey(song.id)) {
            updateLyricsUI(song.id, lyricsCache[song.id])
        } else {
            if (fetchLyricsJob?.isActive != true) {
                fetchLyrics(song)
            }
            showLyricsLoadingState()
        }
    }

    private fun fetchLyrics(song: Song) {
        fetchLyricsJob?.cancel()
        currentLyricsSongId = song.id

        if (lyricsCache.containsKey(song.id)) {
            updateLyricsUI(song.id, lyricsCache[song.id])
            return
        }

        tvSingleLineLyric.text = "Loading lyrics..."
        tvSingleLineLyric.paint.shader = null

        fetchLyricsJob = lifecycleScope.launch {
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

                lyricsCache[song.id] = lyricResult
                updateLyricsUI(song.id, lyricResult)

            } catch (e: Exception) {
                Log.e("NowPlayingFragment", "Error fetching lyrics: ${e.message}")
                showNoLyricsFound()
            }
        }
    }

    private fun showLyricsLoadingState() {
        lyricsLoadingProgress.visibility = View.VISIBLE
        lyricsRecyclerView.visibility = View.GONE
        lyricsPlainScrollView.visibility = View.GONE
        tvLyricsPlain.text = ""
        tvSingleLineLyric.text = "Loading lyrics..."
        tvSingleLineLyric.paint.shader = null
    }

    private fun updateLyricsUI(songId: Long, lyricResult: LrcLibLyrics?) {
        if (currentLyricsSongId != songId) return

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
                tvSingleLineLyric.text = "No synced lyrics"
                tvSingleLineLyric.paint.shader = null
            } else {
                showNoLyricsFound()
            }
        } else {
            showNoLyricsFound()
        }
    }

    private fun showNoLyricsFound() {
        lyricsLoadingProgress.visibility = View.GONE
        tvLyricsPlain.text = "No lyrics found"
        lyricsPlainScrollView.visibility = View.VISIBLE
        lyricsRecyclerView.visibility = View.GONE
        tvSingleLineLyric.text = "No Lyrics"
        tvSingleLineLyric.paint.shader = null
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
                setMargins(0, dpToPx(4), 0, dpToPx(4))
            }
        }

        val labelView = TextView(requireContext()).apply {
            text = label
            setTextAppearance(android.R.style.TextAppearance_Small)
            setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
            layoutParams = LinearLayout.LayoutParams(
                dpToPx(100),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val valueView = TextView(requireContext()).apply {
            text = value
            setTextAppearance(android.R.style.TextAppearance_Small)
            setTypeface(typeface, android.graphics.Typeface.NORMAL)
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

        tvSongTitle.text = currentSong.title
        tvSongArtist.text = currentSong.artist ?: "Unknown Artist"

        tvSongTitle.isSelected = true
        tvSongArtist.isSelected = true
        tvSongDetails.isSelected = true

        tvLyricsSongTitle.text = currentSong.title
        tvLyricsSongArtist.text = currentSong.artist ?: "Unknown Artist"
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

    override fun onStart() {
        super.onStart()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager.registerAudioDeviceCallback(audioDeviceCallback, handler)
        }
        updateAudioOutputUI()
    }

    override fun onStop() {
        super.onStop()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        }
    }

    override fun onResume() {
        super.onResume()
        isFragmentVisible = true
        isSharing = false
        setSystemBarAppearance(true)

        val callFilter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        requireActivity().registerReceiver(callReceiver, callFilter)

        musicService?.getCurrentSong()?.let { song ->
            song.isFavorite = PreferenceManager.isFavorite(requireContext(), song.id)
            if(isLyricsVisible) {
                checkAndDisplayLyrics(song)
            } else {
                fetchLyrics(song)
            }
        }

        startSeekBarUpdates()
        updatePlaybackControls()
        updateAudioOutputUI()

        tvSongTitle.isSelected = true
        tvSongArtist.isSelected = true
        tvSongDetails.isSelected = true
        tvSingleLineLyric.isSelected = true
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
        try {
            requireActivity().unregisterReceiver(callReceiver)
        } catch (e: Exception) {
            // Ignore
        }
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
        fetchLyricsJob?.cancel()
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