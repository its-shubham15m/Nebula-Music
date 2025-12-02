package com.shubhamgupta.nebula_player

import android.annotation.SuppressLint
import android.app.Dialog
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Rational
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GestureDetectorCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.shubhamgupta.nebula_player.fragments.SubtitleSettingsFragment
import com.shubhamgupta.nebula_player.models.Video
import com.shubhamgupta.nebula_player.repository.VideoRepository
import com.shubhamgupta.nebula_player.utils.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.math.abs

@UnstableApi
class VideoPlayerActivity : AppCompatActivity() {

    // ExoPlayer Components
    private lateinit var playerView: PlayerView
    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null

    // Views
    private lateinit var titleView: TextView
    private lateinit var currentTimeView: TextView
    private lateinit var totalTimeView: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var subtitleView: TextView

    private lateinit var backButton: ImageButton
    private lateinit var playPauseButton: ImageButton
    private lateinit var prevButton: ImageButton
    private lateinit var nextButton: ImageButton

    // Controls
    private lateinit var orientationButton: ImageButton
    private lateinit var aspectRatioButton: ImageButton
    private lateinit var tracksButton: ImageButton
    private lateinit var optionsButton: ImageButton
    private lateinit var lockButton: ImageButton
    private lateinit var unlockButton: ImageButton
    private lateinit var pipButton: ImageButton

    // Overlays
    private lateinit var topControls: LinearLayout
    private lateinit var bottomControls: LinearLayout
    private lateinit var centerControls: LinearLayout
    private lateinit var touchOverlay: View
    private lateinit var rewindOverlay: FrameLayout
    private lateinit var forwardOverlay: FrameLayout
    private lateinit var centerInfoLayout: LinearLayout
    private lateinit var centerInfoIcon: ImageView
    private lateinit var centerInfoText: TextView
    private lateinit var lockOverlay: FrameLayout
    private lateinit var ratioInfoChip: TextView

    // Seek Peek
    private lateinit var seekPeekContainer: View
    private lateinit var seekPeekTime: TextView

    // Logic Variables
    private var videoList: List<Video> = emptyList()
    private var currentVideoIndex = -1
    private var currentVideoUri: Uri? = null

    // Logic State
    private var isLocked = false
    private var hasResumeDialogShown = false
    private var isShowRemainingTime = false

    private lateinit var gestureDetector: GestureDetectorCompat
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private lateinit var audioManager: AudioManager

    // Aspect Ratio Logic
    private enum class AspectRatioMode {
        BEST_FIT, FIT_SCREEN, FILL, CUSTOM
    }
    private var currentAspectRatioMode = AspectRatioMode.BEST_FIT
    private var currentAspectRatioValue = 0f

    private val handler = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable { hideSystemUI() }
    private val hideCenterInfoRunnable = Runnable {
        centerInfoLayout.visibility = View.GONE
    }
    private val hideRatioChipRunnable = Runnable { ratioInfoChip.visibility = View.GONE }

    // Gesture Accumulators
    private var scrollAccumulator = 0f
    private val SCROLL_THRESHOLD = 20f

    // Seek Logic
    private var isSeeking = false
    private var seekDirection = 0
    private val seekRunnable = object : Runnable {
        override fun run() {
            if (isSeeking && player != null) {
                val current = player!!.currentPosition
                val target = current + (seekDirection * 2000)
                player!!.seekTo(target.coerceIn(0, player!!.duration))
                seekBar.progress = target.toInt()
                currentTimeView.text = formatDuration(target)
                handler.postDelayed(this, 100)
            }
        }
    }

    private val updateProgressAction = object : Runnable {
        override fun run() {
            if (player != null && player!!.isPlaying && !isSeeking) {
                val current = player!!.currentPosition
                val duration = player!!.duration
                if (duration > 0) {
                    seekBar.max = duration.toInt()
                    seekBar.progress = current.toInt()
                    currentTimeView.text = formatDuration(current)

                    if (isShowRemainingTime) {
                        totalTimeView.text = "-" + formatDuration(duration - current)
                    } else {
                        totalTimeView.text = formatDuration(duration)
                    }
                }
            }
            handler.postDelayed(this, 1000)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_video_player)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        initViews()
        applySavedPreferences()

        playerView.subtitleView?.visibility = View.GONE

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish()
            }
        })

        val initialVideoId = intent.getLongExtra("VIDEO_ID", -1L)
        loadVideoList(initialVideoId)

        setupClickListeners()
        setupHoldToSeek()
        setupSeekBar()
        setupGestures()

        totalTimeView.setOnClickListener {
            isShowRemainingTime = !isShowRemainingTime
            handler.post(updateProgressAction)
        }

        hideSystemUI()
    }

    private fun initViews() {
        playerView = findViewById(R.id.player_view)

        titleView = findViewById(R.id.player_title)
        currentTimeView = findViewById(R.id.tv_current_time)
        totalTimeView = findViewById(R.id.tv_total_time)
        seekBar = findViewById(R.id.player_seekbar)
        subtitleView = findViewById(R.id.subtitle_view)

        backButton = findViewById(R.id.player_back_btn)
        playPauseButton = findViewById(R.id.player_play_pause_btn)
        prevButton = findViewById(R.id.player_prev_btn)
        nextButton = findViewById(R.id.player_next_btn)

        orientationButton = findViewById(R.id.player_orientation_btn)
        aspectRatioButton = findViewById(R.id.player_aspect_ratio_btn)
        tracksButton = findViewById(R.id.player_tracks_btn)
        optionsButton = findViewById(R.id.player_options_btn)
        lockButton = findViewById(R.id.player_lock_btn)
        unlockButton = findViewById(R.id.unlock_btn)
        pipButton = findViewById(R.id.player_pip_btn)
        lockOverlay = findViewById(R.id.lock_overlay)

        topControls = findViewById(R.id.top_controls)
        bottomControls = findViewById(R.id.bottom_controls)
        centerControls = findViewById(R.id.center_controls)

        touchOverlay = findViewById(R.id.touch_overlay)
        rewindOverlay = findViewById(R.id.rewind_overlay)
        forwardOverlay = findViewById(R.id.forward_overlay)
        centerInfoLayout = findViewById(R.id.center_info_layout)
        centerInfoIcon = findViewById(R.id.center_info_icon)
        centerInfoText = findViewById(R.id.center_info_text)
        ratioInfoChip = findViewById(R.id.ratio_info_chip)

        seekPeekContainer = findViewById(R.id.seek_peek_container)
        seekPeekTime = findViewById(R.id.seek_peek_time)
    }

    private fun applySavedPreferences() {
        val savedBrightness = PreferenceManager.getLastVideoBrightness(this)
        if (savedBrightness != -1f) {
            val lp = window.attributes
            lp.screenBrightness = savedBrightness
            window.attributes = lp
        }

        val savedVolume = PreferenceManager.getLastVideoVolume(this)
        if (savedVolume != -1) {
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val targetVol = (maxVol * (savedVolume / 100f)).toInt()
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
        }

        val savedRatioIndex = PreferenceManager.getLastVideoAspectRatioMode(this)
        if (savedRatioIndex < AspectRatioMode.values().size) {
            currentAspectRatioMode = AspectRatioMode.values()[savedRatioIndex]
        }

        applySubtitleSettings()
    }

    private fun applySubtitleSettings() {
        val settings = PreferenceManager.getSubtitleSettings(this)

        subtitleView.textSize = settings.fontSize.toFloat()
        subtitleView.setTextColor(settings.textColor)

        val alpha = (255 * (settings.bgOpacity / 100f)).toInt()
        val baseColor = settings.bgColor
        val finalBgColor = Color.argb(alpha, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))

        subtitleView.setBackgroundColor(finalBgColor)
        subtitleView.setTypeface(null, Typeface.BOLD)
        subtitleView.setShadowLayer(4f, 2f, 2f, Color.BLACK)

        val lp = subtitleView.layoutParams as RelativeLayout.LayoutParams
        val screenHeight = resources.displayMetrics.heightPixels
        lp.bottomMargin = (screenHeight * settings.bottomPadding).toInt()
        subtitleView.layoutParams = lp
    }

    private fun initializePlayer() {
        if (player == null) {
            trackSelector = DefaultTrackSelector(this)
            player = ExoPlayer.Builder(this)
                .setTrackSelector(trackSelector!!)
                .build()
                .apply {
                    playerView.player = this
                    playerView.useController = false

                    addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_READY) {
                                if (duration > 0) {
                                    totalTimeView.text = formatDuration(duration)
                                    seekBar.max = duration.toInt()
                                }
                                handler.post(updateProgressAction)
                                applyAspectRatio()
                                checkResumeStatus()
                            } else if (state == Player.STATE_ENDED) {
                                playNextVideo()
                            }
                        }

                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            playPauseButton.setImageResource(if (isPlaying) R.drawable.ic_pausen else R.drawable.ic_playn)
                            if (isPlaying) resetAutoHideTimer()
                            else {
                                handler.removeCallbacks(hideControlsRunnable)
                                showSystemUI()
                            }
                        }

                        override fun onCues(cueGroup: CueGroup) {
                            if (cueGroup.cues.isNotEmpty()) {
                                subtitleView.text = cueGroup.cues.joinToString("\n") { it.text ?: "" }
                                subtitleView.visibility = View.VISIBLE
                            } else {
                                subtitleView.visibility = View.GONE
                            }
                        }
                    })
                }
        }
    }

    // ===============================================
    // DIALOG HELPER (With Click Outside Fix)
    // ===============================================
    private fun createPlayerDialog(layoutId: Int): Pair<Dialog, View> {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val view = LayoutInflater.from(this).inflate(layoutId, null)
        dialog.setContentView(view)

        // Make window transparent
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        // Ensure dialog fills screen so FrameLayout center/bottom gravity works
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        // CLICK OUTSIDE TO DISMISS LOGIC
        // We look for the root layout ID 'background_overlay' added to all XMLs
        view.findViewById<View>(R.id.background_overlay)?.setOnClickListener {
            dialog.dismiss()
        }

        return Pair(dialog, view)
    }

    // ===============================================
    // FRAGMENT DISMISSAL HELPER
    // ===============================================
    // This is called via android:onClick in fragment_subtitle_settings.xml
    fun dismissSubtitleFragment(view: View) {
        val fragment = supportFragmentManager.findFragmentByTag("SubtitleSettings") as? androidx.fragment.app.DialogFragment
        fragment?.dismiss()
    }

    private fun checkResumeStatus() {
        if (hasResumeDialogShown) return

        val p = player ?: return
        val videoId = videoList.getOrNull(currentVideoIndex)?.id ?: return
        val savedPos = PreferenceManager.getVideoResumePosition(this, videoId)

        if (savedPos > 5000 && savedPos < p.duration - 5000) {
            hasResumeDialogShown = true
            p.pause()

            val (dialog, view) = createPlayerDialog(R.layout.dialog_resume_video)

            view.findViewById<TextView>(R.id.tv_resume_message).text = "Resume from ${formatDuration(savedPos)}?"

            view.findViewById<View>(R.id.btn_resume).setOnClickListener {
                p.seekTo(savedPos)
                p.play()
                resetAutoHideTimer()
                dialog.dismiss()
            }

            view.findViewById<View>(R.id.btn_start_over).setOnClickListener {
                p.seekTo(0)
                p.play()
                resetAutoHideTimer()
                dialog.dismiss()
            }

            dialog.setOnCancelListener {
                p.seekTo(0)
                p.play()
                resetAutoHideTimer()
            }

            dialog.show()

        } else {
            hasResumeDialogShown = true
        }
    }

    private fun loadVideoList(startId: Long) {
        CoroutineScope(Dispatchers.IO).launch {
            val videos = VideoRepository.getAllVideos(applicationContext)
            withContext(Dispatchers.Main) {
                videoList = videos
                currentVideoIndex = videos.indexOfFirst { it.id == startId }

                initializePlayer()

                if (currentVideoIndex != -1) {
                    playVideo(videoList[currentVideoIndex])
                } else if (videos.isNotEmpty()) {
                    currentVideoIndex = 0
                    playVideo(videos[0])
                }
            }
        }
    }

    private fun playVideo(video: Video) {
        player?.let {
            if (it.currentPosition > 0 && currentVideoUri != null) {
                val prevId = videoList.find { v -> v.uri == currentVideoUri }?.id
                if (prevId != null) {
                    PreferenceManager.saveVideoResumePosition(this, prevId, it.currentPosition)
                }
            }
        }

        hasResumeDialogShown = false

        titleView.text = cleanTitle(video.title)
        currentVideoUri = video.uri

        val mediaItem = MediaItem.fromUri(video.uri)
        player?.setMediaItem(mediaItem)
        player?.prepare()
        player?.play()

        subtitleView.visibility = View.GONE
        applyAspectRatio()
    }

    private fun cleanTitle(title: String): String {
        var clean = title
        clean = clean.replace(Regex("\\.mp4|\\.mkv|\\.avi|\\.3gp", RegexOption.IGNORE_CASE), "")
        clean = clean.replace(Regex("\\[.*?\\]|\\(.*?\\)|\\{.*?\\}"), "")
        clean = clean.replace(Regex("www\\..*?\\.(com|org|net|in)", RegexOption.IGNORE_CASE), "")
        clean = clean.replace(Regex("[-_.]"), " ")
        clean = clean.trim()
        return clean.ifEmpty { title }
    }

    private fun setupClickListeners() {
        backButton.setOnClickListener { finish() }

        playPauseButton.setOnClickListener {
            player?.let { if (it.isPlaying) it.pause() else it.play() }
        }

        orientationButton.setOnClickListener { showOrientationDialog() }
        aspectRatioButton.setOnClickListener { showAspectRatioDialog() }
        tracksButton.setOnClickListener { showTrackSelectionDialog() }
        optionsButton.setOnClickListener { showOptionsDialog() }

        lockButton.setOnClickListener { lockScreen() }
        unlockButton.setOnClickListener { unlockScreen() }

        pipButton.setOnClickListener {
            tryEnterPiP()
        }
    }

    private fun tryEnterPiP() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
                Toast.makeText(this, "Device does not support PiP", Toast.LENGTH_SHORT).show()
                return
            }

            try {
                var width = 16
                var height = 9

                val videoFormat = player?.videoFormat
                if (videoFormat != null && videoFormat.width > 0 && videoFormat.height > 0) {
                    width = videoFormat.width
                    height = videoFormat.height
                } else if (playerView.width > 0 && playerView.height > 0) {
                    width = playerView.width
                    height = playerView.height
                }

                if (width <= 0) width = 16
                if (height <= 0) height = 9

                var ratio = width.toFloat() / height.toFloat()

                // Clamp Ratio for Android PiP
                if (ratio < 0.418410f) {
                    width = 9
                    height = 21
                    ratio = width.toFloat() / height.toFloat()
                } else if (ratio > 2.39f) {
                    width = 24
                    height = 10
                    ratio = width.toFloat() / height.toFloat()
                }

                val aspectRatio = Rational(width, height)
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(aspectRatio)
                    .build()

                enterPictureInPictureMode(params)
            } catch (e: Exception) {
                Log.e("PiP", "Error entering PiP with custom ratio. Retrying with default.", e)
                try {
                    val builder = PictureInPictureParams.Builder()
                    enterPictureInPictureMode(builder.build())
                } catch (e2: Exception) {
                    Log.e("PiP", "Fatal PiP Error", e2)
                    Toast.makeText(this, "Cannot enter PiP mode", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            Toast.makeText(this, "PiP requires Android 8.0+", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onUserLeaveHint() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (player?.isPlaying == true && !isLocked) {
                tryEnterPiP()
            }
        }
        super.onUserLeaveHint()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            topControls.visibility = View.GONE
            bottomControls.visibility = View.GONE
            centerControls.visibility = View.GONE
            seekPeekContainer.visibility = View.GONE
            ratioInfoChip.visibility = View.GONE
            lockOverlay.visibility = View.GONE
            centerInfoLayout.visibility = View.GONE
            touchOverlay.visibility = View.GONE
            playerView.useController = false
        } else {
            showSystemUI()
            touchOverlay.visibility = View.VISIBLE
            playerView.useController = false
        }
    }

    // ===============================================
    // DIALOG IMPLEMENTATIONS (Using createPlayerDialog)
    // ===============================================

    private fun showOrientationDialog() {
        val (dialog, view) = createPlayerDialog(R.layout.dialog_orientation)

        view.findViewById<View>(R.id.btn_orient_auto).setOnClickListener {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
            resetAutoHideTimer()
            dialog.dismiss()
        }
        view.findViewById<View>(R.id.btn_orient_landscape).setOnClickListener {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            resetAutoHideTimer()
            dialog.dismiss()
        }
        view.findViewById<View>(R.id.btn_orient_portrait).setOnClickListener {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            resetAutoHideTimer()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showAspectRatioDialog() {
        val (dialog, view) = createPlayerDialog(R.layout.dialog_aspect_ratio)

        // Helper to setup clicks
        fun setMode(id: Int, mode: AspectRatioMode, value: Float = 0f) {
            view.findViewById<View>(id).setOnClickListener {
                currentAspectRatioMode = mode
                currentAspectRatioValue = value
                PreferenceManager.saveVideoAspectRatioMode(this, AspectRatioMode.values().indexOf(mode))
                applyAspectRatio()
                resetAutoHideTimer()
                dialog.dismiss()
            }
        }

        setMode(R.id.btn_ar_best_fit, AspectRatioMode.BEST_FIT)
        setMode(R.id.btn_ar_fit_screen, AspectRatioMode.FIT_SCREEN)
        setMode(R.id.btn_ar_fill, AspectRatioMode.FILL)
        setMode(R.id.btn_ar_16_9, AspectRatioMode.CUSTOM, 16f/9f)
        setMode(R.id.btn_ar_4_3, AspectRatioMode.CUSTOM, 4f/3f)
        setMode(R.id.btn_ar_16_10, AspectRatioMode.CUSTOM, 16f/10f)
        setMode(R.id.btn_ar_cinema, AspectRatioMode.CUSTOM, 2.35f)
        setMode(R.id.btn_ar_18_9, AspectRatioMode.CUSTOM, 2f) // Approx 18:9

        dialog.show()
    }

    private fun applyAspectRatio() {
        val p = player ?: return
        val format = p.videoFormat ?: return
        if (format.width == 0 || format.height == 0) return

        val viewWidth = findViewById<View>(R.id.root_layout).width
        val viewHeight = findViewById<View>(R.id.root_layout).height
        val params = playerView.layoutParams

        if (viewHeight == 0) return

        val screenRatio = viewWidth.toFloat() / viewHeight.toFloat()
        playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT

        when (currentAspectRatioMode) {
            AspectRatioMode.BEST_FIT -> {
                params.width = FrameLayout.LayoutParams.MATCH_PARENT
                params.height = FrameLayout.LayoutParams.MATCH_PARENT
                playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            AspectRatioMode.FIT_SCREEN -> {
                params.width = FrameLayout.LayoutParams.MATCH_PARENT
                params.height = FrameLayout.LayoutParams.MATCH_PARENT
                playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
            }
            AspectRatioMode.FILL -> {
                params.width = FrameLayout.LayoutParams.MATCH_PARENT
                params.height = FrameLayout.LayoutParams.MATCH_PARENT
                playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            }
            AspectRatioMode.CUSTOM -> {
                val targetRatio = currentAspectRatioValue
                if (targetRatio > screenRatio) {
                    params.width = viewWidth
                    params.height = (viewWidth / targetRatio).toInt()
                } else {
                    params.width = (viewHeight * targetRatio).toInt()
                    params.height = viewHeight
                }
                playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
            }
        }
        playerView.layoutParams = params
    }

    private fun showTrackSelectionDialog() {
        val p = player ?: return
        val tracks = p.currentTracks

        val (dialog, view) = createPlayerDialog(R.layout.dialog_audio_subtitle)

        val audioContainer = view.findViewById<LinearLayout>(R.id.container_audio_tracks)
        val subtitleContainer = view.findViewById<LinearLayout>(R.id.container_subtitle_tracks)
        val settingsBtn = view.findViewById<View>(R.id.btn_open_sub_settings)

        // Open Subtitle Settings
        settingsBtn.setOnClickListener {
            val sheet = SubtitleSettingsFragment()
            sheet.setOnSettingsChangedListener { applySubtitleSettings() }
            sheet.show(supportFragmentManager, "SubtitleSettings")
            dialog.dismiss()
        }

        // Logic to generate view items for tracks
        fun createTrackItem(label: String, isSelected: Boolean, onClick: () -> Unit): View {
            val textView = TextView(this)
            textView.text = label
            textView.textSize = 16f
            textView.setPadding(32, 24, 32, 24)
            textView.setTextColor(if (isSelected) Color.parseColor("#3EA6FF") else Color.WHITE)
            textView.setBackgroundResource(TypedValue().let {
                theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
                it.resourceId
            })
            textView.setOnClickListener { onClick() }
            return textView
        }

        // --- POPULATE AUDIO ---
        var hasAudio = false
        for (groupIndex in 0 until tracks.groups.size) {
            val group = tracks.groups[groupIndex]
            if (group.type == C.TRACK_TYPE_AUDIO) {
                for (trackIndex in 0 until group.length) {
                    if (group.isTrackSupported(trackIndex)) {
                        hasAudio = true
                        val format = group.getTrackFormat(trackIndex)
                        val lang = format.language?.uppercase() ?: "UND"
                        val labelStr = "${lang} - ${format.label ?: "Track ${trackIndex + 1}"}"
                        val isSelected = group.isSelected

                        audioContainer.addView(createTrackItem(labelStr, isSelected) {
                            p.trackSelectionParameters = p.trackSelectionParameters
                                .buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
                                .build()
                            dialog.dismiss()
                        })
                    }
                }
            }
        }
        if (!hasAudio) {
            val emptyInfo = TextView(this)
            emptyInfo.text = "No audio tracks found"
            emptyInfo.setTextColor(Color.GRAY)
            emptyInfo.setPadding(32,16,32,16)
            audioContainer.addView(emptyInfo)
        }

        // --- POPULATE SUBTITLES ---
        val isSubsDisabled = p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)

        subtitleContainer.addView(createTrackItem("Off", isSubsDisabled) {
            p.trackSelectionParameters = p.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            subtitleView.visibility = View.GONE
            dialog.dismiss()
        })

        for (groupIndex in 0 until tracks.groups.size) {
            val group = tracks.groups[groupIndex]
            if (group.type == C.TRACK_TYPE_TEXT) {
                for (trackIndex in 0 until group.length) {
                    if (group.isTrackSupported(trackIndex)) {
                        val format = group.getTrackFormat(trackIndex)
                        val lang = format.language?.uppercase() ?: "UND"
                        val labelStr = "${lang} - ${format.label ?: "Subtitle ${trackIndex + 1}"}"
                        val isSelected = group.isSelected && !isSubsDisabled

                        subtitleContainer.addView(createTrackItem(labelStr, isSelected) {
                            p.trackSelectionParameters = p.trackSelectionParameters
                                .buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
                                .build()
                            subtitleView.visibility = View.VISIBLE
                            dialog.dismiss()
                        })
                    }
                }
            }
        }

        dialog.show()
    }

    private fun showOptionsDialog() {
        val (dialog, view) = createPlayerDialog(R.layout.dialog_player_options)

        // Show Current Speed
        val currentSpeed = player?.playbackParameters?.speed ?: 1.0f
        view.findViewById<TextView>(R.id.txt_current_speed).text = "${currentSpeed}x"

        view.findViewById<View>(R.id.btn_opt_speed).setOnClickListener {
            dialog.dismiss()
            showPlaybackSpeedDialog()
        }

        view.findViewById<View>(R.id.btn_opt_info).setOnClickListener {
            dialog.dismiss()
            showVideoDetails()
        }

        dialog.show()
    }

    private fun showPlaybackSpeedDialog() {
        val (dialog, view) = createPlayerDialog(R.layout.dialog_playback_speed)

        val container = view.findViewById<LinearLayout>(R.id.container_speed_options)
        val speeds = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

        speeds.forEach { speed ->
            val btn = TextView(this)
            val text = "${speed}x"
            btn.text = if (speed == 1.0f) "Normal" else text
            btn.textSize = 16f
            btn.setTextColor(Color.WHITE)
            btn.setPadding(32, 32, 32, 32)
            btn.gravity = Gravity.CENTER
            btn.setBackgroundResource(TypedValue().let {
                theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
                it.resourceId
            })

            if (player?.playbackParameters?.speed == speed) {
                btn.setTextColor(Color.parseColor("#3EA6FF"))
                btn.typeface = Typeface.DEFAULT_BOLD
            }

            btn.setOnClickListener {
                player?.setPlaybackSpeed(speed)
                dialog.dismiss()
            }
            container.addView(btn)
        }
        dialog.show()
    }

    private fun showVideoDetails() {
        val uri = currentVideoUri ?: return
        CoroutineScope(Dispatchers.IO).launch {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(this@VideoPlayerActivity, uri)
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L

                withContext(Dispatchers.Main) {
                    val info = StringBuilder()
                    info.append("File: ${cleanTitle(titleView.text.toString())}\n\n")
                    info.append("Resolution: ${width} x ${height}\n\n")
                    info.append("Duration: ${formatDuration(duration)}\n\n")
                    if (bitrate > 0) info.append("Bitrate: ${bitrate / 1000} kbps")

                    val (dialog, view) = createPlayerDialog(R.layout.dialog_video_info)
                    view.findViewById<TextView>(R.id.tv_video_info_content).text = info.toString()
                    view.findViewById<View>(R.id.btn_info_close).setOnClickListener {
                        dialog.dismiss()
                    }
                    dialog.show()
                }
                retriever.release()
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        // Scale Gesture for Aspect Ratio
        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (isLocked) return false
                val factor = detector.scaleFactor
                if (factor > 1.1f && currentAspectRatioMode != AspectRatioMode.FILL) {
                    // Zoom In -> Fill
                    currentAspectRatioMode = AspectRatioMode.FILL
                    applyAspectRatio()
                    showRatioChip("Fill Screen")
                    return true
                } else if (factor < 0.9f && currentAspectRatioMode != AspectRatioMode.BEST_FIT) {
                    // Zoom Out -> Best Fit
                    currentAspectRatioMode = AspectRatioMode.BEST_FIT
                    applyAspectRatio()
                    showRatioChip("Original")
                    return true
                }
                return false
            }
        })

        gestureDetector = GestureDetectorCompat(this, object : GestureDetector.SimpleOnGestureListener() {
            private var isVolume = false
            private var isBrightness = false

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (isLocked || player == null) return false

                val width = playerView.width
                val x = e.x

                if (x < width * 0.35) {
                    seekRelative(-10000) // Double tap left: Rewind 10s
                } else if (x > width * 0.65) {
                    seekRelative(10000) // Double tap right: Forward 10s
                } else {
                    if (player!!.isPlaying) player!!.pause() else player!!.play()
                }
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (isLocked) {
                    lockOverlay.visibility = View.VISIBLE
                    handler.postDelayed({ lockOverlay.visibility = View.GONE }, 3000)
                    return true
                }
                toggleControls()
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (e1 == null || isLocked) return false
                if (!isVolume && !isBrightness) {
                    if (e1.x > playerView.width / 2) isVolume = true else isBrightness = true
                }

                scrollAccumulator += distanceY
                if (abs(scrollAccumulator) > SCROLL_THRESHOLD) {
                    val steps = (scrollAccumulator / SCROLL_THRESHOLD).toInt()
                    if (isVolume) adjustVolume(steps) else adjustBrightness(steps)
                    scrollAccumulator %= SCROLL_THRESHOLD
                }
                return true
            }

            override fun onDown(e: MotionEvent): Boolean {
                isVolume = false
                isBrightness = false
                scrollAccumulator = 0f
                return true
            }
        })

        touchOverlay.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event) // Handle Scale First
            if (scaleGestureDetector.isInProgress) return@setOnTouchListener true

            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                handler.postDelayed(hideCenterInfoRunnable, 500)
                scrollAccumulator = 0f
            }
            gestureDetector.onTouchEvent(event)
        }
    }

    private fun showRatioChip(text: String) {
        ratioInfoChip.text = text
        ratioInfoChip.visibility = View.VISIBLE
        handler.removeCallbacks(hideRatioChipRunnable)
        handler.postDelayed(hideRatioChipRunnable, 1000)
    }

    private fun adjustVolume(steps: Int) {
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        var newVolume = currentVolume + steps
        newVolume = newVolume.coerceIn(0, maxVolume)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)

        val percentage = ((newVolume.toFloat() / maxVolume.toFloat()) * 100).toInt()
        showCenterInfo(R.drawable.ic_volume, "$percentage%")
    }

    private fun adjustBrightness(steps: Int) {
        val lp = window.attributes
        var brightness = if (lp.screenBrightness == -1f) 0.5f else lp.screenBrightness
        brightness = (brightness + (steps * 0.02f)).coerceIn(0.01f, 1.0f)
        lp.screenBrightness = brightness
        window.attributes = lp
        showCenterInfo(R.drawable.ic_brightness, "${(brightness * 100).toInt()}%")
    }

    private fun showCenterInfo(iconRes: Int, text: String) {
        handler.removeCallbacks(hideCenterInfoRunnable)
        centerInfoLayout.visibility = View.VISIBLE
        centerInfoIcon.setImageResource(iconRes)
        centerInfoText.text = text
    }

    private fun lockScreen() {
        isLocked = true
        hideSystemUI()
        lockOverlay.visibility = View.VISIBLE
        handler.postDelayed({ lockOverlay.visibility = View.GONE }, 2000)
        Toast.makeText(this, "Screen Locked", Toast.LENGTH_SHORT).show()
    }

    private fun unlockScreen() {
        isLocked = false
        lockOverlay.visibility = View.GONE
        showSystemUI()
        resetAutoHideTimer()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupHoldToSeek() {
        prevButton.setOnTouchListener { _, event ->
            if (isLocked) return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isSeeking = false
                    handler.postDelayed({
                        if (prevButton.isPressed) {
                            isSeeking = true; seekDirection = -1; showRewindOverlay(); handler.post(seekRunnable)
                        }
                    }, 400)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isSeeking) { isSeeking = false; rewindOverlay.visibility = View.GONE; resetAutoHideTimer() }
                    else handlePrevious()
                    true
                }
                else -> false
            }
        }

        nextButton.setOnTouchListener { _, event ->
            if (isLocked) return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isSeeking = false
                    handler.postDelayed({
                        if (nextButton.isPressed) {
                            isSeeking = true; seekDirection = 1; showForwardOverlay(); handler.post(seekRunnable)
                        }
                    }, 400)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isSeeking) { isSeeking = false; forwardOverlay.visibility = View.GONE; resetAutoHideTimer() }
                    else playNextVideo()
                    true
                }
                else -> false
            }
        }
    }

    private fun handlePrevious() {
        if (player != null && player!!.currentPosition > 3000) player!!.seekTo(0)
        else playPreviousVideo()
    }

    private fun playPreviousVideo() {
        if (videoList.isEmpty()) return
        currentVideoIndex = if (currentVideoIndex - 1 < 0) videoList.size - 1 else currentVideoIndex - 1
        playVideo(videoList[currentVideoIndex])
    }

    private fun playNextVideo() {
        if (videoList.isEmpty()) return
        currentVideoIndex = (currentVideoIndex + 1) % videoList.size
        playVideo(videoList[currentVideoIndex])
    }

    private fun seekRelative(offsetMs: Int) {
        player?.let {
            val target = (it.currentPosition + offsetMs).coerceIn(0, it.duration)
            it.seekTo(target)
            if (offsetMs < 0) showRewindOverlay() else showForwardOverlay()
            seekBar.progress = target.toInt()
            currentTimeView.text = formatDuration(target)
            resetAutoHideTimer()
            handler.postDelayed({ rewindOverlay.visibility = View.GONE; forwardOverlay.visibility = View.GONE }, 600)
        }
    }

    private fun showRewindOverlay() { rewindOverlay.visibility = View.VISIBLE }
    private fun showForwardOverlay() { forwardOverlay.visibility = View.VISIBLE }

    private fun setupSeekBar() {
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val timeString = formatDuration(progress.toLong())
                    currentTimeView.text = timeString
                    seekPeekTime.text = timeString

                    val width = seekBar!!.width - seekBar!!.paddingLeft - seekBar!!.paddingRight
                    val thumbPos = seekBar!!.paddingLeft + (width * seekBar!!.progress / seekBar!!.max)
                    seekPeekContainer.x = seekBar!!.x + thumbPos - (seekPeekContainer.width / 2)

                    if (seekPeekContainer.visibility != View.VISIBLE) {
                        seekPeekContainer.visibility = View.VISIBLE
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                handler.removeCallbacks(updateProgressAction)
                resetAutoHideTimer()
                seekPeekContainer.visibility = View.VISIBLE
                isSeeking = true
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                player?.seekTo(seekBar?.progress?.toLong() ?: 0)
                player?.play()
                handler.post(updateProgressAction)
                resetAutoHideTimer()
                seekPeekContainer.visibility = View.GONE
                isSeeking = false
            }
        })
    }

    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        topControls.visibility = View.GONE
        bottomControls.visibility = View.GONE
        centerControls.visibility = View.GONE

        seekPeekContainer.visibility = View.GONE
        ratioInfoChip.visibility = View.GONE
    }

    private fun showSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode) return

        topControls.visibility = View.VISIBLE
        bottomControls.visibility = View.VISIBLE
        centerControls.visibility = View.VISIBLE
    }

    private fun toggleControls() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode) return

        if (topControls.visibility == View.VISIBLE) {
            hideSystemUI()
            handler.removeCallbacks(hideControlsRunnable)
        } else {
            showSystemUI()
            resetAutoHideTimer()
        }
    }

    private fun resetAutoHideTimer() {
        handler.removeCallbacks(hideControlsRunnable)
        if (player?.isPlaying == true && !isSeeking && !isLocked) handler.postDelayed(hideControlsRunnable, 3000)
    }

    @SuppressLint("DefaultLocale")
    private fun formatDuration(millis: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(millis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
        return if (hours > 0) String.format("%02d:%02d:%02d", hours, minutes, seconds)
        else String.format("%02d:%02d", minutes, seconds)
    }

    override fun onPause() {
        super.onPause()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode) {
            return
        }

        player?.let {
            if (currentVideoUri != null) {
                val id = videoList.getOrNull(currentVideoIndex)?.id
                if (id != null) PreferenceManager.saveVideoResumePosition(this, id, it.currentPosition)
            }
            if (!isFinishing && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Do nothing
            } else {
                it.pause()
            }
        }

        val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        PreferenceManager.saveVideoVolume(this, ((currentVol.toFloat()/maxVol.toFloat())*100).toInt())

        val brightness = window.attributes.screenBrightness
        if (brightness != -1f) PreferenceManager.saveVideoBrightness(this, brightness)
    }

    override fun onStop() {
        super.onStop()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode) {
            // Keep playing
        } else {
            player?.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
        handler.removeCallbacksAndMessages(null)
    }
}