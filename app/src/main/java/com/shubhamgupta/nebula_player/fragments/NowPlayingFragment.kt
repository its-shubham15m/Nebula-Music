package com.shubhamgupta.nebula_player.fragments

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
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
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
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
import com.bumptech.glide.load.engine.DiskCacheStrategy
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

    // Album Art Views
    private lateinit var albumArtContainer: FrameLayout
    private lateinit var ivAlbumArt: ImageView
    private lateinit var ivPrevAlbumArt: ImageView
    private lateinit var ivNextAlbumArt: ImageView

    // Main Song Info Views
    private lateinit var tvSongTitle: TextView
    private lateinit var tvSongArtist: TextView
    private lateinit var tvSongDetails: TextView

    // Lyrics Header Views (Short Hand Details)
    private lateinit var lyricsHeaderInfo: View
    private lateinit var singleLineContainer: View
    private lateinit var tvSingleLineLyric: TextView
    private lateinit var ivSingleLineIcon: ImageView
    private lateinit var ivNowPlayingIcon: ImageView
    private lateinit var tvLyricsSongTitle: TextView
    private lateinit var tvLyricsSongArtist: TextView

    private lateinit var backgroundGradient: ImageView
    private lateinit var backgroundOverlay: View
    private var breathingAnimator: ValueAnimator? = null

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
                    // --- RESET LYRICS STATE IMMEDIATELY ---
                    tvSingleLineLyric.text = ""
                    tvSingleLineLyric.paint.shader = null
                    currentLyricsList = emptyList()
                    lyricsAdapter.submitList(emptyList())
                    showLyricsLoadingState()
                    // --------------------------------------

                    // --- OPTIMIZATION: Seamless Art Handoff ---
                    if (swipeDirection == 1) { // Coming from Prev
                        ivPrevAlbumArt.drawable?.let {
                            ivAlbumArt.setImageDrawable(it)
                        }
                    } else if (swipeDirection == -1) { // Coming from Next
                        ivNextAlbumArt.drawable?.let {
                            ivAlbumArt.setImageDrawable(it)
                        }
                    }
                    // -------------------------------------------

                    musicService?.getCurrentSong()?.let { song ->
                        song.isFavorite = PreferenceManager.isFavorite(requireContext(), song.id)
                        fetchLyrics(song)
                    }

                    // Reset any active animations or translations when the song changes officially
                    resetAlbumArtPositions()
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
                    // Update next/prev art if queue changed
                    musicService?.getCurrentSong()?.let { loadNeighboringArts(it) }
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

        // Initialize multiple album art views
        albumArtContainer = view.findViewById(R.id.album_art_container)
        ivAlbumArt = view.findViewById(R.id.album_art)
        ivPrevAlbumArt = view.findViewById(R.id.album_art_prev)
        ivNextAlbumArt = view.findViewById(R.id.album_art_next)

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

    @SuppressLint("ClickableViewAccessibility")
    private fun setupAlbumArtSwipe() {
        val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                toggleLyricsVisibility()
                return true
            }
            override fun onDown(e: MotionEvent): Boolean { return true }
        })

        ivAlbumArt.setOnTouchListener { v, event ->
            gestureDetector.onTouchEvent(event)
            val width = v.width.toFloat()
            // GAP: Define the gap between images (e.g., 24dp)
            val gap = dpToPx(24).toFloat()
            val totalOffset = width + gap

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().cancel()
                    ivPrevAlbumArt.animate().cancel()
                    ivNextAlbumArt.animate().cancel()

                    initialX = event.rawX
                    dX = 0f
                    isManuallySwiping = false
                    v.parent.requestDisallowInterceptTouchEvent(true)

                    if (ivPrevAlbumArt.visibility != View.VISIBLE) ivPrevAlbumArt.visibility = View.VISIBLE
                    if (ivNextAlbumArt.visibility != View.VISIBLE) ivNextAlbumArt.visibility = View.VISIBLE

                    // Ensure positions are correct before starting drag
                    ivPrevAlbumArt.translationX = -totalOffset
                    ivNextAlbumArt.translationX = totalOffset

                    // Reset Scales
                    ivPrevAlbumArt.scaleX = 0.9f
                    ivPrevAlbumArt.scaleY = 0.9f
                    ivNextAlbumArt.scaleX = 0.9f
                    ivNextAlbumArt.scaleY = 0.9f
                    ivAlbumArt.scaleX = 1.0f
                    ivAlbumArt.scaleY = 1.0f

                    ivPrevAlbumArt.alpha = 1f
                    ivNextAlbumArt.alpha = 1f
                }
                MotionEvent.ACTION_MOVE -> {
                    val newX = event.rawX
                    dX = newX - initialX

                    // Move all three views in unison maintaining the GAP
                    ivAlbumArt.translationX = dX
                    ivPrevAlbumArt.translationX = -totalOffset + dX
                    ivNextAlbumArt.translationX = totalOffset + dX

                    // SCALE ANIMATION
                    val progress = (abs(dX) / width).coerceIn(0f, 1f)

                    // Shrink current
                    val currentScale = 1.0f - (progress * 0.1f)
                    ivAlbumArt.scaleX = currentScale
                    ivAlbumArt.scaleY = currentScale
                    ivAlbumArt.alpha = 1f - (progress * 0.2f)

                    // Grow neighbors
                    val sideScale = 0.9f + (progress * 0.1f)
                    if (dX > 0) { // Dragging right, showing Prev
                        ivPrevAlbumArt.scaleX = sideScale
                        ivPrevAlbumArt.scaleY = sideScale
                    } else { // Dragging left, showing Next
                        ivNextAlbumArt.scaleX = sideScale
                        ivNextAlbumArt.scaleY = sideScale
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handleSwipeRelease(v)
                    v.parent.requestDisallowInterceptTouchEvent(false)
                }
            }
            true
        }
    }

    private var initialX = 0f
    private var dX = 0f

    private fun handleSwipeRelease(view: View) {
        val width = view.width.toFloat()
        val gap = dpToPx(24).toFloat()
        val totalOffset = width + gap
        val threshold = width * 0.25f

        if (dX > threshold) {
            // Swiping RIGHT (Showing Previous)
            isManuallySwiping = true
            swipeDirection = 1
            musicService?.playPrevious()

            ivPrevAlbumArt.animate().translationX(0f).scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(250).setInterpolator(AccelerateDecelerateInterpolator()).start()

            ivAlbumArt.animate().translationX(totalOffset).scaleX(0.9f).scaleY(0.9f).alpha(0.5f)
                .setDuration(250).setInterpolator(AccelerateDecelerateInterpolator()).start()

            ivNextAlbumArt.animate().translationX(totalOffset * 2).setDuration(250).start()

        } else if (dX < -threshold) {
            // Swiping LEFT (Showing Next)
            isManuallySwiping = true
            swipeDirection = -1
            musicService?.playNext()

            ivNextAlbumArt.animate().translationX(0f).scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(250).setInterpolator(AccelerateDecelerateInterpolator()).start()

            ivAlbumArt.animate().translationX(-totalOffset).scaleX(0.9f).scaleY(0.9f).alpha(0.5f)
                .setDuration(250).setInterpolator(AccelerateDecelerateInterpolator()).start()

            ivPrevAlbumArt.animate().translationX(-totalOffset * 2).setDuration(250).start()

        } else {
            // Snap back to center
            isManuallySwiping = false
            swipeDirection = 0

            ivAlbumArt.animate().translationX(0f).scaleX(1f).scaleY(1f).alpha(1f)
                .setInterpolator(OvershootInterpolator()).setDuration(250).start()

            ivPrevAlbumArt.animate().translationX(-totalOffset).scaleX(0.9f).scaleY(0.9f).setDuration(250).start()
            ivNextAlbumArt.animate().translationX(totalOffset).scaleX(0.9f).scaleY(0.9f).setDuration(250).start()
        }
    }

    private fun resetAlbumArtPositions() {
        isManuallySwiping = false
        swipeDirection = 0
        ivAlbumArt.translationX = 0f
        ivAlbumArt.scaleX = 1f
        ivAlbumArt.scaleY = 1f
        ivAlbumArt.alpha = 1f

        ivAlbumArt.post {
            val width = ivAlbumArt.width.toFloat()
            val gap = dpToPx(24).toFloat()
            val totalOffset = width + gap

            if (width > 0) {
                ivPrevAlbumArt.translationX = -totalOffset
                ivNextAlbumArt.translationX = totalOffset
                ivPrevAlbumArt.scaleX = 0.9f
                ivPrevAlbumArt.scaleY = 0.9f
                ivNextAlbumArt.scaleX = 0.9f
                ivNextAlbumArt.scaleY = 0.9f
            }
            ivPrevAlbumArt.visibility = View.VISIBLE
            ivNextAlbumArt.visibility = View.VISIBLE
            ivPrevAlbumArt.alpha = 1f
            ivNextAlbumArt.alpha = 1f
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

        // Make the background transparent so the floating CardView effect works
        bottomSheetDialog.setOnShowListener { dialog ->
            val d = dialog as BottomSheetDialog
            val bottomSheet = d.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.setBackgroundColor(Color.TRANSPARENT)
        }

        bottomSheetDialog.behavior.peekHeight = resources.displayMetrics.heightPixels
        bottomSheetDialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED

        // --- UPDATED: Close button logic (Using bottom button instead of top cross) ---
        bottomSheetView.findViewById<View>(R.id.btn_close_details_bottom).setOnClickListener {
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
            override fun onStartTrackingTouch(seekBar: SeekBar?) { isSeeking = true }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isSeeking = false
                musicService?.seekTo(seekBar?.progress ?: 0)
            }
        })
    }

    private fun setupClickListeners() {
        btnBack.setOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }
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
                        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> 100
                        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> 90
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> 50
                        else -> 0
                    }
                }
                bestDevice?.let { result.add(it) }
            }
        }
        return result.sortedBy { if (it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) 1 else 0 }
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
                    statusBarColor = Color.TRANSPARENT
                }
            }
            dialogView.findViewById<View>(R.id.background_overlay)?.setOnClickListener { audioOutputDialog?.dismiss() }
            btnClose.setOnClickListener { audioOutputDialog?.dismiss() }
            audioOutputDialog?.show()
        } else {
            Toast.makeText(requireContext(), "Audio switching requires Android M+", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getDeviceName(device: AudioDeviceInfo): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) device.productName.toString()
        else getDeviceTypeName(device.type)
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
                tvName.text = getDeviceName(device)
                if (!isSelected) tvStatus.text = getDeviceTypeName(device.type)
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
                itemView.setOnClickListener { onDeviceSelected(device) }
            }
        }
    }

    private fun getDeviceTypeName(type: Int): String {
        return when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone Speaker"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired Headphones"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth Audio"
            else -> "Audio Device"
        }
    }

    private fun toggleLyricsVisibility() {
        isLyricsVisible = !isLyricsVisible
        val duration = 300L
        val scaleSmall = 0.95f
        val scaleNormal = 1f
        val interpolator = AccelerateDecelerateInterpolator()

        if (isLyricsVisible) {
            // SHOW LYRICS
            lyricsContainer.visibility = View.VISIBLE
            lyricsContainer.alpha = 0f
            lyricsContainer.scaleX = scaleSmall
            lyricsContainer.scaleY = scaleSmall

            // Show Header Info (Short hand)
            lyricsHeaderInfo.visibility = View.VISIBLE
            lyricsHeaderInfo.alpha = 0f
            ivNowPlayingIcon.visibility = View.VISIBLE
            ivNowPlayingIcon.alpha = 0f

            lyricsContainer.animate().alpha(1f).scaleX(scaleNormal).scaleY(scaleNormal)
                .setDuration(duration).setInterpolator(interpolator).start()

            lyricsHeaderInfo.animate().alpha(1f).setDuration(duration).start()
            ivNowPlayingIcon.animate().alpha(1f).setDuration(duration).start()

            artInfoContainer.animate().alpha(0f).scaleX(scaleSmall).scaleY(scaleSmall)
                .setDuration(duration).setInterpolator(interpolator)
                .withEndAction { artInfoContainer.visibility = View.INVISIBLE }.start()

            singleLineContainer.animate().alpha(0f).setDuration(duration)
                .withEndAction { singleLineContainer.visibility = View.GONE }.start()

            tvLyricsSongTitle.isSelected = true
            tvLyricsSongArtist.isSelected = true
            val song = musicService?.getCurrentSong()
            if (song != null) {
                // Ensure lyrics info is set for the short hand header
                tvLyricsSongTitle.text = song.title
                tvLyricsSongArtist.text = song.artist ?: "Unknown Artist"
                checkAndDisplayLyrics(song)
            }

        } else {
            // HIDE LYRICS
            artInfoContainer.visibility = View.VISIBLE
            artInfoContainer.alpha = 0f
            artInfoContainer.scaleX = scaleSmall
            artInfoContainer.scaleY = scaleSmall

            singleLineContainer.visibility = View.VISIBLE
            singleLineContainer.alpha = 0f

            artInfoContainer.animate().alpha(1f).scaleX(scaleNormal).scaleY(scaleNormal)
                .setDuration(duration).setInterpolator(interpolator).start()

            singleLineContainer.animate().alpha(1f).setDuration(duration).start()

            lyricsContainer.animate().alpha(0f).scaleX(scaleSmall).scaleY(scaleSmall)
                .setDuration(duration).setInterpolator(interpolator)
                .withEndAction { lyricsContainer.visibility = View.GONE }.start()

            lyricsHeaderInfo.animate().alpha(0f).setDuration(duration)
                .withEndAction { lyricsHeaderInfo.visibility = View.GONE }.start()

            ivNowPlayingIcon.animate().alpha(0f).setDuration(duration)
                .withEndAction { ivNowPlayingIcon.visibility = View.GONE }.start()
        }
    }

    private fun cleanMetaData(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        var cleaned = text!!
        cleaned = cleaned.replace(Regex("(?i)\\.(mp3|m4a|flac|wav|aac|ogg)$"), "")
        cleaned = cleaned.replace(Regex("\\(.*?\\)"), "")
        cleaned = cleaned.replace(Regex("\\[.*?\\]"), "")
        cleaned = cleaned.replace(Regex("\\{.*?\\}"), "")
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

        // Clear previous lyrics to avoid showing old song data
        tvSingleLineLyric.text = ""
        tvSingleLineLyric.paint.shader = null
        currentLyricsList = emptyList()

        if (lyricsCache.containsKey(song.id)) {
            updateLyricsUI(song.id, lyricsCache[song.id])
            return
        }

        tvSingleLineLyric.text = "Loading lyrics..."

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
                        // Fallback search
                        try {
                            val query = "$cleanTitle $cleanArtist"
                            val searchResults = LrcLibApiClient.api.searchLyrics(query)
                            result = searchResults.minByOrNull { abs((it.duration ?: 0) - durationSeconds) }
                        } catch (searchEx: Exception) { Log.e("NowPlayingFragment", "Search failed") }
                    }
                    result
                }
                lyricsCache[song.id] = lyricResult
                updateLyricsUI(song.id, lyricResult)
            } catch (e: Exception) {
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
    }

    private fun toggleRepeat() { musicService?.toggleRepeatMode() }

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

        val artLoader = if (currentSong.embeddedArtBytes != null && currentSong.embeddedArtBytes.isNotEmpty()) {
            Glide.with(this).load(currentSong.embeddedArtBytes)
        } else {
            Glide.with(this).load(SongUtils.getAlbumArtUri(currentSong.albumId))
        }

        val currentDrawable = ivAlbumArt.drawable
        if (currentDrawable != null) {
            artLoader.placeholder(currentDrawable)
        } else {
            artLoader.placeholder(R.drawable.default_album_art)
        }

        artLoader
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
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, dpToPx(4), 0, dpToPx(4)) }
        }
        val labelView = TextView(requireContext()).apply {
            text = label
            setTextAppearance(android.R.style.TextAppearance_Small)
            setTextColor(Color.parseColor("#80FFFFFF")) // Dimmer text
            layoutParams = LinearLayout.LayoutParams(dpToPx(100), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val valueView = TextView(requireContext()).apply {
            text = value
            setTextAppearance(android.R.style.TextAppearance_Small)
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
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
            val mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            val channels = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_NUM_TRACKS)
            bitrate?.let {
                val kbps = (it.toIntOrNull() ?: 0) / 1000
                addMetadataRow(container, "Bitrate", "$kbps kbps")
            }
            mimeType?.let { addMetadataRow(container, "Format", it) }
            channels?.let { addMetadataRow(container, "Channels", it) }
            retriever.release()
        } catch (e: Exception) {}
    }

    private fun showAddToPlaylistDialog() {
        Toast.makeText(requireContext(), "Add to Playlist feature (needs implementation)", Toast.LENGTH_SHORT).show()
    }

    private fun togglePlayPause() { musicService?.togglePlayPause() }

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
            Toast.makeText(requireContext(), "Could not share file.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleFavorite() {
        val currentSong = musicService?.getCurrentSong() ?: return
        currentSong.isFavorite = !currentSong.isFavorite
        if (currentSong.isFavorite) PreferenceManager.addFavorite(requireContext(), currentSong.id)
        else PreferenceManager.removeFavorite(requireContext(), currentSong.id)
        ivFavorite.setImageResource(if (currentSong.isFavorite) R.drawable.ic_favorite_filled else R.drawable.ic_favorite_outline)
        Toast.makeText(requireContext(), if (currentSong.isFavorite) "Added to favorites" else "Removed from favorites", Toast.LENGTH_SHORT).show()
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

        loadMainAlbumArt(currentSong)
        // Also try to load neighbors if possible
        loadNeighboringArts(currentSong)

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

        ivFavorite.setImageResource(if (currentSong.isFavorite) R.drawable.ic_favorite_filled else R.drawable.ic_favorite_outline)
    }

    private fun loadMainAlbumArt(song: Song) {
        val artLoader = if (song.embeddedArtBytes != null && song.embeddedArtBytes.isNotEmpty()) {
            Glide.with(this).load(song.embeddedArtBytes)
        } else {
            Glide.with(this).load(SongUtils.getAlbumArtUri(song.albumId))
        }

        val currentDrawable = ivAlbumArt.drawable
        if (currentDrawable != null) {
            artLoader.placeholder(currentDrawable)
        } else {
            artLoader.placeholder(R.drawable.default_album_art)
        }

        artLoader
            .error(R.drawable.default_album_art)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .dontAnimate()
            .override(800, 800)
            .listener(object : RequestListener<android.graphics.drawable.Drawable> {
                override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<android.graphics.drawable.Drawable>, isFirstResource: Boolean): Boolean { return false }
                override fun onResourceReady(resource: android.graphics.drawable.Drawable, model: Any, target: Target<android.graphics.drawable.Drawable>?, dataSource: DataSource, isFirstResource: Boolean): Boolean {
                    generateGradientBackground(resource)
                    return false
                }
            })
            .into(ivAlbumArt)
    }

    private fun loadNeighboringArts(currentSong: Song) {
        val queue = musicService?.getQueueSongs()
        val pos = musicService?.getCurrentQueuePosition()

        if (queue != null && pos != null && queue.isNotEmpty()) {
            val nextPos = (pos + 1) % queue.size
            val prevPos = if (pos - 1 < 0) queue.size - 1 else pos - 1

            val nextSong = queue[nextPos]
            val prevSong = queue[prevPos]

            loadArtIntoView(nextSong, ivNextAlbumArt)
            loadArtIntoView(prevSong, ivPrevAlbumArt)
        }
    }

    private fun loadArtIntoView(song: Song, targetView: ImageView) {
        val artLoader = if (song.embeddedArtBytes != null && song.embeddedArtBytes.isNotEmpty()) {
            Glide.with(this).load(song.embeddedArtBytes)
        } else {
            Glide.with(this).load(SongUtils.getAlbumArtUri(song.albumId))
        }
        artLoader.placeholder(R.drawable.default_album_art)
            .error(R.drawable.default_album_art)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .dontAnimate()
            .override(800, 800)
            .into(targetView)
    }

    private fun generateGradientBackground(drawable: android.graphics.drawable.Drawable) {
        try {
            val bitmap = if (drawable is BitmapDrawable) drawable.bitmap
            else {
                val bitmap = createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight)
                val canvas = android.graphics.Canvas(bitmap)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                bitmap
            }

            Palette.from(bitmap).generate { palette ->
                val dominantColor = palette?.dominantSwatch?.rgb ?: 0xFF000000.toInt()
                val vibrantColor = palette?.vibrantSwatch?.rgb ?: dominantColor
                val mutedColor = palette?.mutedSwatch?.rgb ?: dominantColor

                val gradientDrawable = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(
                        ColorUtils.setAlphaComponent(vibrantColor, 60),
                        ColorUtils.setAlphaComponent(mutedColor, 40),
                        ColorUtils.setAlphaComponent(dominantColor, 20)
                    )
                )
                backgroundGradient.setImageDrawable(gradientDrawable)

                startBreathingAnimation(vibrantColor, mutedColor, dominantColor)
            }
        } catch (e: Exception) {}
    }

    private fun startBreathingAnimation(color1: Int, color2: Int, color3: Int) {
        breathingAnimator?.cancel()
        breathingAnimator = ValueAnimator.ofObject(ArgbEvaluator(), color1, color2).apply {
            duration = 8000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { animator ->
                val animatedColor = animator.animatedValue as Int
                val newGradient = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(
                        ColorUtils.setAlphaComponent(animatedColor, 100),
                        ColorUtils.setAlphaComponent(color3, 80),
                        ColorUtils.setAlphaComponent(color1, 40)
                    )
                )
                backgroundGradient.setImageDrawable(newGradient)
            }
            start()
        }
    }

    private fun updatePlayButton() {
        val isPlaying = musicService?.isPlaying() ?: false
        btnPlay.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun setSystemBarAppearance(isNowPlaying: Boolean) {
        val window = requireActivity().window
        val windowController = WindowCompat.getInsetsController(window, window.decorView)
        if (isNowPlaying) windowController.isAppearanceLightStatusBars = false
        else (activity as? MainActivity)?.updateSystemUiColors()
    }

    private fun startSeekBarUpdates() { handler.post(updateSeekBar) }
    private fun stopSeekBarUpdates() { handler.removeCallbacks(updateSeekBar) }

    override fun onStart() {
        super.onStart()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) audioManager.registerAudioDeviceCallback(audioDeviceCallback, handler)
        updateAudioOutputUI()
    }

    override fun onStop() {
        super.onStop()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        breathingAnimator?.cancel()
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
            if(isLyricsVisible) checkAndDisplayLyrics(song) else fetchLyrics(song)
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
        if (!isSharing) setSystemBarAppearance(false)
        stopSeekBarUpdates()
        try { requireActivity().unregisterReceiver(callReceiver) } catch (e: Exception) {}
    }

    override fun onDestroyView() {
        super.onDestroyView()
        setSystemBarAppearance(false)
        isFragmentVisible = false
        queueManager.stopScrollMonitoring()
        stopSeekBarUpdates()
        if (bottomSheetDialog.isShowing) bottomSheetDialog.dismiss()
        queueManager.dismissQueueDialog()
    }

    override fun onDestroy() {
        super.onDestroy()
        queueManager.stopScrollMonitoring()
        try { requireActivity().unregisterReceiver(songChangeReceiver) } catch (e: Exception) {}
        queueManager.clearCache()
        fetchLyricsJob?.cancel()
        breathingAnimator?.cancel()
    }

    private fun dpToPx(dp: Int): Int {
        return try {
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics).toInt()
        } catch (e: Exception) { (dp * resources.displayMetrics.density).toInt() }
    }

    fun getMusicService(): MusicService? = musicService
    fun getCurrentQueuePosition(): Int = musicService?.getCurrentQueuePosition() ?: 0
}