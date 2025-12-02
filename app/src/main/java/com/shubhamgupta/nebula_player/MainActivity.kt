package com.shubhamgupta.nebula_player

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.textfield.TextInputEditText
import com.shubhamgupta.nebula_player.fragments.AboutFragment
import com.shubhamgupta.nebula_player.fragments.EqualizerFragment
import com.shubhamgupta.nebula_player.fragments.HomePageFragment
import com.shubhamgupta.nebula_player.fragments.MiniPlayerFragment
import com.shubhamgupta.nebula_player.fragments.NowPlayingFragment
import com.shubhamgupta.nebula_player.fragments.SearchFragment
import com.shubhamgupta.nebula_player.fragments.SettingsFragment
import com.shubhamgupta.nebula_player.fragments.UserActivityFragment
import com.shubhamgupta.nebula_player.service.MusicService
import com.shubhamgupta.nebula_player.service.NebulaNotificationManager
import com.shubhamgupta.nebula_player.utils.PreferenceManager
import com.shubhamgupta.nebula_player.utils.SongUtils
import com.shubhamgupta.nebula_player.utils.ThemeManager
import com.shubhamgupta.nebula_player.utils.UserActivityHistory // Added Import
import com.shubhamgupta.nebula_player.utils.UserActivityManager
import com.shubhamgupta.nebula_player.utils.UserProfileManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

class MainActivity : AppCompatActivity() {
    private var musicService: MusicService? = null
    private var bound = false
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var handler: Handler

    // Cache for fragment instances
    private val fragmentCache = mutableMapOf<String, Fragment>()

    // Track current fragment state
    private var currentFragment: String = "home"
    private var isTransitioning = false

    // Flag to control mini player from child fragments
    private var isMiniPlayerAllowed = true

    // Theme related views
    private lateinit var sidebarAppearance: View
    private lateinit var sidebarThemeMode: TextView
    private lateinit var themeModeOptions: View
    private lateinit var themeRadioGroup: RadioGroup
    private lateinit var themeSystem: RadioButton
    private lateinit var themeLight: RadioButton
    private lateinit var themeDark: RadioButton

    // Setup Temp holder for avatar URI during First Run
    private var tempAvatarUri: Uri? = null

    // Permission handling
    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    // Store savedInstanceState for later use
    private var savedInstanceBundle: Bundle? = null

    // Track if we're currently showing permission dialog
    private var isShowingPermissionDialog = false

    // Track back press behavior
    private var backPressCount = 0
    private val backPressHandler = Handler(Looper.getMainLooper())
    private val backPressRunnable = Runnable { backPressCount = 0 }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        isShowingPermissionDialog = false

        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            Log.d("MainActivity", "All permissions granted")
            initializeAppAfterPermissions()
        } else {
            Log.d("MainActivity", "Some permissions denied: $permissions")
            showPermissionDeniedDialog()
        }
    }

    private val conn = object : ServiceConnection {
        @SuppressLint("UnsafeImplicitIntentLaunch")
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MusicService.MusicBinder
            musicService = binder.getService()
            bound = true

            Log.d("MainActivity", "Service connected, bound: $bound")

            updateMiniPlayerVisibility()

            handler.postDelayed({
                forceRefreshCurrentFragment()
            }, 500)

            handler.postDelayed({
                musicService?.let { service ->
                    Log.d("MainActivity", "Triggering state restoration after service connection")

                    val restoreIntent = Intent(this@MainActivity, MusicService::class.java).apply {
                        action = "RESTORE_PLAYBACK"
                    }
                    startService(restoreIntent)

                    lifecycleScope.launch {
                        delay(800)
                        val savedState = PreferenceManager.loadPlaybackState(this@MainActivity)
                        if (savedState?.lastPlayedSongId != null) {
                            if (service.getCurrentSong() == null) {
                                service.triggerStateRestoration()
                            } else {
                                sendBroadcast(Intent("QUEUE_CHANGED"))
                                sendBroadcast(Intent("SONG_CHANGED"))
                            }
                        }
                    }
                }
            }, 300)

            handleIntent(intent)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d("MainActivity", "Service disconnected")
            bound = false
            musicService = null
        }
    }

    private val queueUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "QUEUE_CHANGED" -> {
                    Log.d("MainActivity", "Queue changed broadcast received")
                    updateMiniPlayerVisibility()
                }
            }
        }
    }

    // Listener to handle back stack changes automatically
    private val backStackListener = FragmentManager.OnBackStackChangedListener {
        handleBackStackChange()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceBundle = savedInstanceState
        ThemeManager.applySavedTheme(this)

        // FIX: Ensure full edge-to-edge configuration
        WindowCompat.setDecorFitsSystemWindows(window, false)

        if (window.attributes.windowAnimations == 0 || window.decorView.background == null) {
            window.setBackgroundDrawableResource(android.R.color.transparent)
        }

        setContentView(R.layout.activity_main)
        updateSystemUiColors()
        handler = Handler(Looper.getMainLooper())
        PreferenceManager.init(this)
        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        if (hasRequiredPermissions()) {
            Log.d("MainActivity", "Permissions already granted, initializing app")
            initializeAppAfterPermissions()
        } else {
            Log.d("MainActivity", "Permissions not granted, requesting permissions")
            if (shouldShowPermissionRationale()) {
                showPermissionExplanationDialog()
            } else {
                requestSystemPermissions()
            }
        }
    }

    private fun shouldShowPermissionRationale(): Boolean {
        return requiredPermissions.any { permission ->
            ActivityCompat.shouldShowRequestPermissionRationale(this, permission)
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val audioGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
            val videoGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
            return audioGranted && videoGranted
        } else {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun showPermissionExplanationDialog() {
        if (isShowingPermissionDialog) return

        isShowingPermissionDialog = true

        val permissionMessage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            "Nebula Music needs access to your audio and video files to play music, create playlists, show videos, and manage your library.\n\nThis permission allows the app to:\n• Browse and play your music and videos\n• Create and manage playlists\n• Display album art and song information\n• Remember your playback preferences"
        } else {
            "Nebula Music needs access to your storage to play music and videos, create playlists, and manage your library.\n\nThis permission allows the app to:\n• Browse and play your music files\n• Create and manage playlists\n• Display album art and song information\n• Remember your playback preferences"
        }

        AlertDialog.Builder(this)
            .setTitle("Allow Access to Media")
            .setMessage(permissionMessage)
            .setPositiveButton("Allow") { dialog, _ ->
                dialog.dismiss()
                isShowingPermissionDialog = false
                requestSystemPermissions()
            }
            .setNegativeButton("Deny") { dialog, _ ->
                dialog.dismiss()
                isShowingPermissionDialog = false
                showPermissionDeniedDialog()
            }
            .setCancelable(false)
            .setOnCancelListener {
                isShowingPermissionDialog = false
                showPermissionDeniedDialog()
            }
            .show()
    }

    private fun requestSystemPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_VIDEO)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            Log.d("MainActivity", "Requesting permissions: $permissionsToRequest")
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            Log.d("MainActivity", "All permissions already granted")
            initializeAppAfterPermissions()
        }
    }

    private fun showPermissionDeniedDialog() {
        val deniedMessage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            "You've denied access to your media files. Without this permission, Nebula Music cannot:\n\n• Play your music or videos\n• Create playlists\n• Display your library\n• Save your preferences\n\nYou can grant permission in Settings or continue with limited functionality."
        } else {
            "You've denied access to your storage. Without this permission, Nebula Music cannot:\n\n• Play your music or videos\n• Create playlists\n• Display your library\n• Save your preferences\n\nYou can grant permission in Settings or continue with limited functionality."
        }

        AlertDialog.Builder(this)
            .setTitle("Permission Required")
            .setMessage(deniedMessage)
            .setPositiveButton("Settings") { dialog, _ ->
                dialog.dismiss()
                openAppSettings()
            }
            .setNegativeButton("Continue Anyway") { dialog, _ ->
                dialog.dismiss()
                initializeAppWithLimitedFunctionality()
            }
            .setCancelable(false)
            .show()
    }

    private fun openAppSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
            finish()
        } catch (e: Exception) {
            Log.e("MainActivity", "Error opening app settings", e)
            Toast.makeText(this, "Error opening settings", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun initializeAppWithLimitedFunctionality() {
        Log.d("MainActivity", "Initializing app with limited functionality")
        Toast.makeText(this, "Running with limited functionality", Toast.LENGTH_LONG).show()
        initializeAppAfterPermissions()
    }

    @SuppressLint("SetTextI18n")
    private fun initializeAppAfterPermissions() {
        Log.d("MainActivity", "Initializing app after permissions check")

        // Register the back stack listener here
        supportFragmentManager.addOnBackStackChangedListener(backStackListener)

        initializeViews()
        setupMiniPlayerInsets()
        setupSidebarInsets()
        setupThemeFunctionality()
        setupBackPressHandler()
        setupDrawerListener()

        // ----------------------------------------------------------------
        // FEATURE: User Setup (Architecture via UserProfileManager)
        // ----------------------------------------------------------------

        // 1. Check if setup is already completed using the Manager
        if (!UserProfileManager.isSetupDone(this)) {
            // Setup NOT done: Show dialog
            showFirstTimeSetupDialog()
        } else {
            // Setup IS done: Initialize UI and Greet
            updateSidebarProfile()

            // Only send greeting notification on fresh app launch (not rotation)
            if (savedInstanceBundle == null) {
                val userName = UserProfileManager.getUserName(this)
                NebulaNotificationManager(this).sendWelcomeNotification(userName)
            }
        }

        try {
            val sidebarVersionTextView = findViewById<TextView>(R.id.sidebar_app_version)
            sidebarVersionTextView?.text = "Version: ${getAppVersionName()}"
        } catch (e: Exception) {
            Log.w("MainActivity", "Could not find R.id.sidebar_app_version.")
        }

        if (savedInstanceBundle == null) {
            showHomePageFragment()
        }

        if (supportFragmentManager.findFragmentById(R.id.mini_player_container) == null) {
            supportFragmentManager.commit {
                replace(R.id.mini_player_container, MiniPlayerFragment.newInstance(), "MINI_PLAYER_TAG")
                setReorderingAllowed(true)
            }
        }

        val savedState = PreferenceManager.loadPlaybackState(this)
        if (savedState?.lastPlayedSongId != null && savedState.lastPlayedSongId != -1L) {
            Intent(this, MusicService::class.java).also { intent ->
                startService(intent)
                bindService(intent, conn, Context.BIND_AUTO_CREATE)
            }
        } else {
            Intent(this, MusicService::class.java).also { intent ->
                startService(intent)
                bindService(intent, conn, Context.BIND_AUTO_CREATE)
            }
        }

        val filter = IntentFilter().apply {
            addAction("QUEUE_CHANGED")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(queueUpdateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(queueUpdateReceiver, filter)
        }
    }

    // New Dialog for First Time User using dialog_user_setup.xml and UserProfileManager
    // Updated: Horizontal Carousel with Snap and Scale effects, using Manager
    private fun showFirstTimeSetupDialog() {
        val builder = AlertDialog.Builder(this)

        // Inflate the custom XML
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_user_setup, null)
        builder.setView(dialogView)
        builder.setCancelable(false) // Force user to complete setup

        val dialog = builder.create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        // 1. Set Title to "Welcome" for First Time Setup
        val tvTitle = dialogView.findViewById<TextView>(R.id.tv_dialog_title)
        if (tvTitle != null) {
            tvTitle.text = "Welcome"
        }

        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.rv_avatar_picker)
        val inputName = dialogView.findViewById<TextInputEditText>(R.id.input_user_name)
        val btnSave = dialogView.findViewById<Button>(R.id.btn_save_setup)

        // Use Manager to get available avatars
        val avatarList = UserProfileManager.getAvailableAvatars(this)
        var selectedAvatarPath: String? = avatarList.firstOrNull()

        // --- Horizontal Carousel Logic ---
        val layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        recyclerView.layoutManager = layoutManager

        val snapHelper = LinearSnapHelper()
        snapHelper.attachToRecyclerView(recyclerView)

        // Padding calculation to center items
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val itemWidthPx = (100 * displayMetrics.density).toInt() // approx width of item_avatar_selection
        val padding = (screenWidth - itemWidthPx) / 2 - (48 * displayMetrics.density).toInt() // Adjustment
        recyclerView.setPadding(padding.coerceAtLeast(0), 0, padding.coerceAtLeast(0), 0)
        recyclerView.clipToPadding = false

        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_avatar_selection, parent, false)
                return object : RecyclerView.ViewHolder(view) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val iv = holder.itemView.findViewById<ImageView>(R.id.iv_avatar_item)
                val path = avatarList[position]

                Glide.with(this@MainActivity)
                    .load(Uri.parse(path))
                    .placeholder(R.drawable.default_album_art)
                    .error(R.drawable.default_album_art)
                    .into(iv)

                holder.itemView.setOnClickListener {
                    recyclerView.smoothScrollToPosition(position)
                    selectedAvatarPath = path
                }
            }

            override fun getItemCount() = avatarList.size
        }
        recyclerView.adapter = adapter

        // Visual Effects Scroll Listener (Scale and Dim)
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(rv, dx, dy)
                val centerX = rv.width / 2f
                for (i in 0 until layoutManager.childCount) {
                    val child = layoutManager.getChildAt(i) ?: continue
                    val childCenterX = (child.left + child.right) / 2f
                    val dist = abs(centerX - childCenterX)

                    // Scale: 1.0 -> 1.15 based on proximity to center
                    val scaleFactor = 1f - (dist / rv.width)
                    val targetScale = 1.0f + (0.15f * scaleFactor.coerceIn(0f, 1f))
                    val finalScale = if (dist < 200) targetScale else 1.0f

                    child.scaleX = finalScale
                    child.scaleY = finalScale

                    // Dimming: Alpha 0.2 at edges, 0.0 at center
                    val overlay = child.findViewById<View>(R.id.view_overlay)
                    if (overlay != null) {
                        val alpha = (dist / 250f).coerceIn(0f, 0.2f)
                        overlay.alpha = alpha
                    }
                }
            }

            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    val centerView = snapHelper.findSnapView(layoutManager)
                    if (centerView != null) {
                        val pos = layoutManager.getPosition(centerView)
                        if (pos != RecyclerView.NO_POSITION) {
                            selectedAvatarPath = avatarList[pos]
                        }
                    }
                }
            }
        })

        // Initial Trigger to apply effects
        recyclerView.post {
            if (avatarList.isNotEmpty()) {
                recyclerView.scrollBy(1, 0)
                recyclerView.scrollBy(-1, 0)
            }
        }

        // --- Save Logic ---
        btnSave.setOnClickListener {
            val rawName = inputName.text.toString()
            // Save via Manager
            UserProfileManager.saveUserProfile(this, rawName, selectedAvatarPath)

            // Dismiss and Resume
            dialog.dismiss()

            // Update UI
            updateSidebarProfile()

            // Show Greeting
            val savedName = UserProfileManager.getUserName(this)
            NebulaNotificationManager(this).sendWelcomeNotification(savedName)
            Toast.makeText(this, "Welcome, $savedName!", Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }

    // Public method to update sidebar from Settings or Init
    fun updateSidebarProfile() {
        // Existing Profile Logic
        val name = UserProfileManager.getUserName(this)
        val avatarUriString = UserProfileManager.getUserAvatarUri(this)
        val sidebarNameView = findViewById<TextView>(R.id.sidebar_user_name)
        val sidebarAvatarView = findViewById<ImageView>(R.id.sidebar_user_avatar)

        if (sidebarNameView != null) sidebarNameView.text = name

        if (sidebarAvatarView != null) {
            if (avatarUriString != null) {
                try {
                    Glide.with(this)
                        .load(Uri.parse(avatarUriString))
                        .placeholder(R.drawable.default_album_art)
                        .error(R.drawable.default_album_art)
                        .circleCrop()
                        .into(sidebarAvatarView)
                } catch (e: Exception) {
                    sidebarAvatarView.setImageResource(R.drawable.default_album_art)
                }
            } else {
                sidebarAvatarView.setImageResource(R.drawable.default_album_art)
            }
        }

        // --- NEW: User Activity Stats Logic ---
        val statsView = findViewById<TextView>(R.id.sidebar_user_stats)
        val streakView = findViewById<TextView>(R.id.sidebar_streak_count)

        if (statsView != null && streakView != null) {
            // FIX: Use formatDuration from UserActivityHistory and raw data from UserActivityManager
            val totalTime = UserActivityManager.getCurrentTotalUsage(this)
            val formattedTime = UserActivityHistory.formatDuration(totalTime)
            val streak = UserActivityManager.getStreak(this)

            // Update UI
            statsView.text = "$formattedTime played"
            streakView.text = streak.toString()
        }

        // NEW: Add Click Listener to open Activity History
        val profileCard = findViewById<View>(R.id.sidebar_user_profile)
        profileCard?.setOnClickListener {
            if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.closeDrawer(GravityCompat.START)
            }
            showUserActivityPage()
        }
    }

    // New centralized method to handle state changes based on back stack
    private fun handleBackStackChange() {
        val entryCount = supportFragmentManager.backStackEntryCount

        if (entryCount == 0) {
            // We are back at the Root (Home)
            currentFragment = "home"
            isMiniPlayerAllowed = true
            setDrawerLocked(false)

            val homeFragment = supportFragmentManager.findFragmentByTag("HOME_PAGE_FRAGMENT") as? HomePageFragment
            homeFragment?.updateMiniPlayerPosition()
        } else {
            // We are deeper in the stack, determine current fragment by tag
            val topEntry = supportFragmentManager.getBackStackEntryAt(entryCount - 1)

            when (topEntry.name) {
                "settings_page" -> {
                    currentFragment = "settings"
                    isMiniPlayerAllowed = false
                }
                "equalizer_page" -> {
                    currentFragment = "equalizer"
                    isMiniPlayerAllowed = false
                }
                "about_page" -> {
                    currentFragment = "about"
                    isMiniPlayerAllowed = false
                }
                "search_page" -> {
                    currentFragment = "search"
                    // Search usually allows mini player, adjust if needed
                    isMiniPlayerAllowed = true
                }
                "now_playing" -> {
                    currentFragment = "now_playing"
                    isMiniPlayerAllowed = false
                    setDrawerLocked(true)
                }
                "user_activity_page" -> {
                    currentFragment = "user_activity"
                    isMiniPlayerAllowed = false
                    setDrawerLocked(false)
                }
            }
        }
        updateMiniPlayerVisibility()
    }

    private fun setupMiniPlayerInsets() {
        // Ensure insets are passed down correctly from the root.
        // Returning 'insets' allows propagation to children (like Fragments).
        ViewCompat.setOnApplyWindowInsetsListener(drawerLayout) { _, insets ->
            insets
        }
    }

    fun setMiniPlayerBottomMargin(bottomMarginPx: Int) {
        val miniPlayerContainer = findViewById<View>(R.id.mini_player_container) ?: return
        val params = miniPlayerContainer.layoutParams as? ViewGroup.MarginLayoutParams
        if (params != null) {
            if (params.bottomMargin != bottomMarginPx) {
                params.bottomMargin = bottomMarginPx
                miniPlayerContainer.layoutParams = params
                Log.d("MainActivity", "MiniPlayer margin updated to: $bottomMarginPx px")
            }
        }
    }

    private fun setupSidebarInsets() {
        val sidebar = findViewById<View>(R.id.sidebar)
        // Adjust footer padding for nav bar
        val sidebarFooterContainer = findViewById<View>(R.id.sidebar_footer_container)

        ViewCompat.setOnApplyWindowInsetsListener(sidebar) { v, insets ->
            val systemBarInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            // Just ensure footer has padding at bottom
            val originalBottomPadding = (16 * resources.displayMetrics.density).toInt()
            sidebarFooterContainer.setPadding(
                sidebarFooterContainer.paddingLeft,
                sidebarFooterContainer.paddingTop,
                sidebarFooterContainer.paddingRight,
                originalBottomPadding + systemBarInsets.bottom
            )
            insets
        }
    }

    fun updateSystemUiColors() {
        val window = window
        val decorView = window.decorView

        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT

        // Ensure we are still in edge-to-edge mode
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val windowController = WindowCompat.getInsetsController(window, decorView)
        val currentTheme = ThemeManager.getCurrentTheme(this)

        when (currentTheme) {
            ThemeManager.THEME_LIGHT -> {
                windowController.isAppearanceLightStatusBars = true
                windowController.isAppearanceLightNavigationBars = true
            }
            ThemeManager.THEME_DARK -> {
                windowController.isAppearanceLightStatusBars = false
                windowController.isAppearanceLightNavigationBars = false
            }
            ThemeManager.THEME_SYSTEM -> {
                @Suppress("DEPRECATION")
                val isLightTheme = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_NO
                windowController.isAppearanceLightStatusBars = isLightTheme
                windowController.isAppearanceLightNavigationBars = isLightTheme
            }
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        @Suppress("DEPRECATION")
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
    }

    @SuppressLint("SetTextI18n")
    private fun setupThemeFunctionality() {
        sidebarAppearance = findViewById(R.id.sidebar_appearance)
        sidebarThemeMode = findViewById(R.id.sidebar_theme_mode)
        themeModeOptions = findViewById(R.id.theme_mode_options)
        themeRadioGroup = findViewById(R.id.theme_radio_group)
        themeSystem = findViewById(R.id.theme_system)
        themeLight = findViewById(R.id.theme_light)
        themeDark = findViewById(R.id.theme_dark)

        // Find arrow icon for animation
        val sidebarThemeArrow = findViewById<ImageView>(R.id.sidebar_theme_arrow)

        val currentTheme = ThemeManager.getCurrentTheme(this)
        updateThemeUI(currentTheme)

        reduceRadioButtonSize(themeSystem)
        reduceRadioButtonSize(themeLight)
        reduceRadioButtonSize(themeDark)

        sidebarAppearance.setOnClickListener {
            val isVisible = themeModeOptions.visibility == View.VISIBLE
            if (isVisible) {
                // Collapse: Hide options and rotate arrow back to closed state (-90 degrees)
                themeModeOptions.visibility = View.GONE
                sidebarThemeArrow?.animate()?.rotation(-90f)?.setDuration(200)?.start()
            } else {
                // Expand: Show options and rotate arrow to open state (0 degrees)
                themeModeOptions.visibility = View.VISIBLE
                sidebarThemeArrow?.animate()?.rotation(90f)?.setDuration(200)?.start()
            }
        }

        themeRadioGroup.setOnCheckedChangeListener { group, checkedId ->
            when (checkedId) {
                R.id.theme_system -> {
                    ThemeManager.setTheme(this, ThemeManager.THEME_SYSTEM)
                    updateThemeUI(ThemeManager.THEME_SYSTEM)
                    // No longer auto-hiding to allow user to see selection change
                    applyThemeAndRecreate()
                }
                R.id.theme_light -> {
                    ThemeManager.setTheme(this, ThemeManager.THEME_LIGHT)
                    updateThemeUI(ThemeManager.THEME_LIGHT)
                    applyThemeAndRecreate()
                }
                R.id.theme_dark -> {
                    ThemeManager.setTheme(this, ThemeManager.THEME_DARK)
                    updateThemeUI(ThemeManager.THEME_DARK)
                    applyThemeAndRecreate()
                }
            }
        }

        findViewById<View>(R.id.sidebar_equalizer).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            // Use handler to wait for drawer close if needed, but safe to call immediately with transition flag
            showEqualizerPage()
        }

        findViewById<View>(R.id.sidebar_settings).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            showSettingsPage()
        }

        findViewById<View>(R.id.sidebar_about).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            showAboutPage()
        }
    }

    private fun reduceRadioButtonSize(radioButton: RadioButton) {
        radioButton.scaleX = 0.7f
        radioButton.scaleY = 0.7f
        radioButton.pivotX = 0f
    }

    private fun applyThemeAndRecreate() {
        ThemeManager.applySavedTheme(this)
        updateSystemUiColors()
        recreate()
    }

    @SuppressLint("SetTextI18n")
    private fun updateThemeUI(theme: Int) {
        sidebarThemeMode.text = "Theme Mode: ${ThemeManager.getThemeName(theme)}"
        when (theme) {
            ThemeManager.THEME_SYSTEM -> themeSystem.isChecked = true
            ThemeManager.THEME_LIGHT -> themeLight.isChecked = true
            ThemeManager.THEME_DARK -> themeDark.isChecked = true
        }
    }

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackPressed()
            }
        })
    }

    private fun initializeViews() {
        drawerLayout = findViewById(R.id.drawer_layout)
    }

    fun openDrawer() {
        drawerLayout.openDrawer(GravityCompat.START)
    }

    fun isDrawerLocked(): Boolean {
        return drawerLayout.getDrawerLockMode(GravityCompat.START) != DrawerLayout.LOCK_MODE_UNLOCKED
    }

    fun setDrawerLocked(locked: Boolean) {
        if (locked) {
            drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
        } else {
            drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
        }
    }

    private fun setupDrawerListener() {
        drawerLayout.addDrawerListener(object : DrawerLayout.DrawerListener {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {}

            override fun onDrawerOpened(drawerView: View) {
                addTouchInterceptor()
                // Update profile every time drawer opens just in case
                updateSidebarProfile()
            }

            override fun onDrawerClosed(drawerView: View) {
                removeTouchInterceptor()
                themeModeOptions.visibility = View.GONE
                // Reset arrow on drawer close
                findViewById<ImageView>(R.id.sidebar_theme_arrow)?.rotation = -90f
            }

            override fun onDrawerStateChanged(newState: Int) {}
        })
    }

    private fun addTouchInterceptor() {
        val mainContentContainer = findViewById<View>(R.id.main_content_container)
        val touchInterceptor = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            id = R.id.touch_interceptor
            setOnTouchListener { _, _ -> true }
            isClickable = true
            isFocusable = true
        }
        if (mainContentContainer is FrameLayout) {
            mainContentContainer.addView(touchInterceptor)
        }
    }

    private fun removeTouchInterceptor() {
        val mainContentContainer = findViewById<View>(R.id.main_content_container)
        if (mainContentContainer is FrameLayout) {
            val touchInterceptor = mainContentContainer.findViewById<View>(R.id.touch_interceptor)
            if (touchInterceptor != null) {
                mainContentContainer.removeView(touchInterceptor)
            }
        }
    }

    fun showSearchPage() {
        val homeFragment = supportFragmentManager.findFragmentByTag("HOME_PAGE_FRAGMENT") as? HomePageFragment
        if (homeFragment != null && homeFragment.isVisible) {
            homeFragment.switchToTab(R.id.nav_search)
        } else {
            if (currentFragment == "search" || isTransitioning) return
            isTransitioning = true
            currentFragment = "search"

            supportFragmentManager.commit {
                setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left, R.anim.slide_in_left, R.anim.slide_out_right)
                replace(R.id.fragment_container, SearchFragment(), "search_page")
                setReorderingAllowed(true)
                addToBackStack("search_page")
            }

            handler.postDelayed({
                updateMiniPlayerVisibility()
                isTransitioning = false
            }, 300)
        }
    }

    fun showSettingsPage() {
        if (currentFragment == "settings") return

        isTransitioning = true
        currentFragment = "settings"
        // Hide mini player for Settings
        isMiniPlayerAllowed = false
        setMiniPlayerBottomMargin(0)

        supportFragmentManager.commit {
            setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left, R.anim.slide_in_left, R.anim.slide_out_right)
            replace(R.id.fragment_container, SettingsFragment(), "settings_page")
            setReorderingAllowed(true)
            addToBackStack("settings_page")
        }
        // No drawer close here, handled in click listener

        handler.postDelayed({
            updateMiniPlayerVisibility()
            isTransitioning = false
        }, 300)
    }

    fun showEqualizerPage() {
        if (currentFragment == "equalizer") return

        isTransitioning = true
        currentFragment = "equalizer"
        // Hide mini player for Equalizer
        isMiniPlayerAllowed = false
        setMiniPlayerBottomMargin(0)

        supportFragmentManager.commit {
            setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left, R.anim.slide_in_left, R.anim.slide_out_right)
            replace(R.id.fragment_container, EqualizerFragment(), "equalizer_page")
            setReorderingAllowed(true)
            addToBackStack("equalizer_page")
        }

        handler.postDelayed({
            updateMiniPlayerVisibility()
            isTransitioning = false
        }, 300)
    }

    fun showAboutPage() {
        if (currentFragment == "about") return

        isTransitioning = true
        currentFragment = "about"
        // Hide mini player for About
        isMiniPlayerAllowed = false
        setMiniPlayerBottomMargin(0)

        supportFragmentManager.commit {
            setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left, R.anim.slide_in_left, R.anim.slide_out_right)
            replace(R.id.fragment_container, AboutFragment(), "about_page")
            setReorderingAllowed(true)
            addToBackStack("about_page")
        }

        handler.postDelayed({
            updateMiniPlayerVisibility()
            isTransitioning = false
        }, 300)
    }

    // NEW: Helper method to navigate to user activity
    fun showUserActivityPage() {
        if (currentFragment == "user_activity") return

        isTransitioning = true
        currentFragment = "user_activity"

        // Hide mini player
        setMiniPlayerVisibility(false)

        supportFragmentManager.commit {
            setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left, R.anim.slide_in_left, R.anim.slide_out_right)
            replace(R.id.fragment_container, UserActivityFragment(), "user_activity_page")
            setReorderingAllowed(true)
            addToBackStack("user_activity_page")
        }

        handler.postDelayed({
            isTransitioning = false
        }, 300)
    }

    private fun showHomePageFragment() {
        if (isTransitioning) return
        isTransitioning = true

        currentFragment = "home"
        isMiniPlayerAllowed = true

        val homeFragment = fragmentCache["home"] as? HomePageFragment ?: HomePageFragment.newInstance()

        supportFragmentManager.commit {
            setReorderingAllowed(true)
            replace(R.id.fragment_container, homeFragment, "HOME_PAGE_FRAGMENT")
        }

        fragmentCache["home"] = homeFragment
        updateMiniPlayerVisibility()

        handler.postDelayed({
            isTransitioning = false
        }, 300)
    }

    fun showFavoritesPage() {
        val homeFragment = supportFragmentManager.findFragmentByTag("HOME_PAGE_FRAGMENT") as? HomePageFragment

        if (homeFragment != null && homeFragment.isVisible) {
            homeFragment.navigateToFavorites()
        } else {
            showHomePageFragment()
            handler.postDelayed({
                (supportFragmentManager.findFragmentByTag("HOME_PAGE_FRAGMENT") as? HomePageFragment)?.navigateToFavorites()
            }, 100)
        }
        drawerLayout.closeDrawer(GravityCompat.START)
    }

    fun showPlaylistsPage() {
        val homeFragment = supportFragmentManager.findFragmentByTag("HOME_PAGE_FRAGMENT") as? HomePageFragment

        if (homeFragment != null && homeFragment.isVisible) {
            homeFragment.navigateToPlaylists()
        } else {
            showHomePageFragment()
            handler.postDelayed({
                (supportFragmentManager.findFragmentByTag("HOME_PAGE_FRAGMENT") as? HomePageFragment)?.navigateToPlaylists()
            }, 100)
        }
        drawerLayout.closeDrawer(GravityCompat.START)
    }

    fun showRecentPage() {
        val homeFragment = supportFragmentManager.findFragmentByTag("HOME_PAGE_FRAGMENT") as? HomePageFragment

        if (homeFragment != null && homeFragment.isVisible) {
            homeFragment.navigateToRecents()
        } else {
            showHomePageFragment()
            handler.postDelayed({
                (supportFragmentManager.findFragmentByTag("HOME_PAGE_FRAGMENT") as? HomePageFragment)?.navigateToRecents()
            }, 100)
        }
        drawerLayout.closeDrawer(GravityCompat.START)
    }

    fun getMusicService(): MusicService? = musicService

    fun setMiniPlayerVisibility(visible: Boolean) {
        isMiniPlayerAllowed = visible
        updateMiniPlayerVisibility()
    }

    fun updateMiniPlayerVisibility() {
        val miniPlayerContainer = findViewById<View>(R.id.mini_player_container) ?: return

        if (!isMiniPlayerAllowed) {
            miniPlayerContainer.visibility = View.GONE
            return
        }

        // Explicitly checking against fragments where we DON'T want it
        val isSettingsOrAboutOrEqualizer = currentFragment == "settings" ||
                currentFragment == "about" ||
                currentFragment == "equalizer" ||
                currentFragment == "user_activity"

        val shouldBeVisible = !isSettingsOrAboutOrEqualizer && currentFragment != "now_playing"

        Log.d("MainActivity", "updateMiniPlayerVisibility: currentFragment=$currentFragment, shouldBeVisible=$shouldBeVisible")

        if (shouldBeVisible) {
            miniPlayerContainer.visibility = View.VISIBLE
            if (supportFragmentManager.findFragmentById(R.id.mini_player_container) == null) {
                supportFragmentManager.commit {
                    replace(R.id.mini_player_container, MiniPlayerFragment.newInstance(), "MINI_PLAYER_TAG")
                    setReorderingAllowed(true)
                }
            }
            // Ensure margin is correct if we are on Home
            val homeFragment = supportFragmentManager.findFragmentByTag("HOME_PAGE_FRAGMENT") as? HomePageFragment
            if (homeFragment != null && homeFragment.isVisible) {
                homeFragment.updateMiniPlayerPosition()
            }
        } else {
            miniPlayerContainer.visibility = View.GONE
        }
    }

    fun showNowPlayingPage() {
        if (currentFragment == "now_playing") return

        isTransitioning = true
        currentFragment = "now_playing"
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)

        // Temporarily hide mini player for full screen player
        setMiniPlayerVisibility(false)

        supportFragmentManager.commit {
            setCustomAnimations(R.anim.slide_up, R.anim.slide_down, R.anim.slide_up, R.anim.slide_down)
            replace(R.id.fragment_container, NowPlayingFragment.newInstance(), "NOW_PLAYING_FRAGMENT")
            setReorderingAllowed(true)
            addToBackStack("now_playing")
        }

        handler.postDelayed({
            isTransitioning = false
        }, 300)
    }

    fun navigateToNowPlaying() {
        val currentSong = musicService?.getCurrentSong()
        if (currentSong != null) {
            showNowPlayingPage()
        } else {
            val miniPlayer = supportFragmentManager.findFragmentByTag("MINI_PLAYER_TAG") as? MiniPlayerFragment
            if (miniPlayer?.isResumableState() == true) {
                showNowPlayingPage()
            } else {
                Toast.makeText(this, "No song is currently playing", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
            return
        }

        val homeFragment = supportFragmentManager.findFragmentByTag("HOME_PAGE_FRAGMENT") as? HomePageFragment
        if (homeFragment != null && homeFragment.isVisible && homeFragment.handleBackPress()) {
            // HomePageFragment handled the back press (popped a child fragment)
            return
        }

        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
            // The OnBackStackChangedListener will handle state updates automatically
        } else {
            if (backPressCount == 1) {
                finish()
            } else {
                backPressCount++
                Toast.makeText(this, "Press back again to exit", Toast.LENGTH_SHORT).show()
                backPressHandler.postDelayed(backPressRunnable, 2000)
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        this.intent = intent
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            val uri: Uri? = intent.data
            if (uri != null && bound && musicService != null) {
                playExternalUri(uri)
                handler.postDelayed({ forceRefreshCurrentFragment() }, 1000)
            }
        }
    }

    private fun playExternalUri(uri: Uri) {
        lifecycleScope.launch {
            val externalSong = SongUtils.createSongFromUri(this@MainActivity, uri)
            if (externalSong != null) {
                val songList = arrayListOf(externalSong)
                musicService?.startPlayback(songList, 0)
                showNowPlayingPage()
                Toast.makeText(this@MainActivity, "Playing: ${externalSong.title}", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this@MainActivity, "Could not load selected audio file.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun forceRefreshCurrentFragment() {
        val homeFragment = supportFragmentManager.findFragmentByTag("HOME_PAGE_FRAGMENT") as? HomePageFragment
        homeFragment?.refreshData()
    }

    @SuppressLint("UnsafeImplicitIntentLaunch")
    override fun onResume() {
        super.onResume()

        // 1. Start Tracking Session & Check Streak
        UserActivityManager.startSession(this)

        backPressCount = 0
        handler.postDelayed({ forceRefreshCurrentFragment() }, 800)

        // 2. Refresh sidebar to show new stats immediately
        updateSidebarProfile()

        lifecycleScope.launch {
            delay(1200)
            musicService?.let { service ->
                val savedState = PreferenceManager.loadPlaybackState(this@MainActivity)
                if (savedState?.lastPlayedSongId != null) {
                    if (service.getCurrentSong() == null) {
                        service.triggerStateRestoration()
                    } else {
                        sendBroadcast(Intent("QUEUE_CHANGED"))
                    }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // 1. End Tracking Session
        UserActivityManager.endSession(this)
        backPressHandler.removeCallbacks(backPressRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        backPressHandler.removeCallbacks(backPressRunnable)
        if (bound) {
            unbindService(conn)
            bound = false
        }
        try {
            unregisterReceiver(queueUpdateReceiver)
            supportFragmentManager.removeOnBackStackChangedListener(backStackListener)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error unregistering receiver", e)
        }
        fragmentCache.clear()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        handler.postDelayed({ updateSystemUiColors() }, 100)
    }

    @SuppressLint("SetTextI18n")
    fun getAppVersionName(): String {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            "v" + packageInfo.versionName
        } catch (e: PackageManager.NameNotFoundException) {
            "v?"
        }
    }

    enum class SortType {
        NAME_ASC, NAME_DESC, DATE_ADDED_ASC, DATE_ADDED_DESC, DURATION
    }
}