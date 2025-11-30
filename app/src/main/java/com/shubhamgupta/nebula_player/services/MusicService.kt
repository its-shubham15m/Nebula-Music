package com.shubhamgupta.nebula_player.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import com.shubhamgupta.nebula_player.R
import com.shubhamgupta.nebula_player.models.Song
import com.shubhamgupta.nebula_player.repository.SongRepository
import com.shubhamgupta.nebula_player.utils.EqualizerManager
import com.shubhamgupta.nebula_player.utils.PreferenceManager
import com.shubhamgupta.nebula_player.utils.SongCacheManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.Random
import java.util.Stack

class MusicService : Service(), MediaPlayer.OnCompletionListener, MediaPlayer.OnPreparedListener {

    private var player: MediaPlayer? = null
    private var songList: ArrayList<Song> = ArrayList()
    private var originalSongList: ArrayList<Song> = ArrayList()
    private var songPosition: Int = -1
    private var isPrepared = false
    private var isShuffleMode = false
    private var repeatMode = RepeatMode.ALL
    private val random = Random()

    // Shuffle History Stack
    private val shuffleHistory = Stack<Int>()
    private var isNavigatingBack = false

    // Queue management
    private var currentQueue = mutableListOf<Song>()
    private var currentQueuePosition = 0

    // State restoration tracking
    private var isRestoringState = false
    private var restoreSeekPosition = 0

    private lateinit var mediaSession: MediaSessionCompat

    // Renamed Notification Manager
    private lateinit var notificationManager: NebulaNotificationManager

    // State saving
    private val saveStateHandler = Handler(Looper.getMainLooper())
    private val saveStateRunnable = object : Runnable {
        override fun run() {
            savePlaybackState()
            saveStateHandler.postDelayed(this, 5000)
        }
    }

    private companion object {
        const val CUSTOM_ACTION_TOGGLE_REPEAT = "com.shubhamgupta.nebula_player.ACTION_TOGGLE_REPEAT"
        const val CUSTOM_ACTION_TOGGLE_FAVORITE = "com.shubhamgupta.nebula_player.ACTION_TOGGLE_FAVORITE"
        const val ACTION_VIDEO_STARTED = "com.shubhamgupta.nebula_player.ACTION_VIDEO_STARTED"
    }

    // --- FIX: Playback Concurrency Receiver ---
    private val videoStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_VIDEO_STARTED) {
                Log.d("MusicService", "Video started, pausing music")
                if (isPlaying()) {
                    pause()
                    // Optional: If you want notification to disappear completely when video starts:
                    // notificationManager.cancelNotification()
                }
            }
        }
    }

    fun getAudioSessionId(): Int {
        return player?.audioSessionId ?: 0
    }

    private fun savePlaybackState() {
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val currentSong = getCurrentSong()
                val currentPosition = getCurrentPosition()

                if (currentSong != null || currentQueue.isNotEmpty()) {
                    PreferenceManager.savePlaybackStateWithQueueValidation(
                        context = applicationContext,
                        currentSongId = currentSong?.id,
                        seekPosition = currentPosition,
                        repeatMode = repeatMode,
                        isShuffleMode = isShuffleMode,
                        queueSongs = currentQueue,
                        currentQueuePosition = currentQueuePosition,
                        originalQueueSongs = originalSongList
                    )
                }
            } catch (e: Exception) {
                Log.e("MusicService", "Error saving playback state", e)
            }
        }
    }

    private val binder = MusicBinder()

    inner class MusicBinder : Binder() {
        fun getService(): MusicService = this@MusicService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()

        // Use new NebulaNotificationManager
        notificationManager = NebulaNotificationManager(this)
        notificationManager.createNotificationChannel()

        // Register Video State Receiver
        val filter = IntentFilter(ACTION_VIDEO_STARTED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(videoStateReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(videoStateReceiver, filter)
        }

        mediaSession = MediaSessionCompat(this, "NebulaMusic").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onSeekTo(pos: Long) {
                    this@MusicService.seekTo(pos.toInt())
                }

                override fun onPlay() {
                    play()
                }

                override fun onPause() {
                    pause()
                }

                override fun onSkipToNext() {
                    playNext("NONE")
                }

                override fun onSkipToPrevious() {
                    playPrevious("NONE")
                }

                override fun onCustomAction(action: String, extras: Bundle?) {
                    when (action) {
                        CUSTOM_ACTION_TOGGLE_REPEAT -> toggleRepeatMode()
                        CUSTOM_ACTION_TOGGLE_FAVORITE -> toggleFavorite()
                    }
                }
            })
            isActive = true
        }

        SongCacheManager.initializeCache(applicationContext)
        startBackgroundCacheUpdates()

        kotlinx.coroutines.GlobalScope.launch {
            loadSavedPlaybackState()
        }

        // Send a welcome notification/pop-up
        notificationManager.sendEngagementNotification()

        ensureForegroundService()
        startSaveStateUpdates()
    }

    private fun startBackgroundCacheUpdates() {
        val cacheUpdateHandler = Handler(Looper.getMainLooper())
        val cacheUpdateRunnable = object : Runnable {
            override fun run() {
                if (SongCacheManager.shouldUpdateCache()) {
                    SongRepository.refreshSongs(applicationContext)
                }
                cacheUpdateHandler.postDelayed(this, 60000)
            }
        }
        cacheUpdateHandler.postDelayed(cacheUpdateRunnable, 60000)
    }

    fun refreshQueueState() {
        sendBroadcast(Intent("QUEUE_CHANGED"))
        sendBroadcast(Intent("SONG_CHANGED"))
    }

    private fun startSaveStateUpdates() {
        saveStateHandler.post(saveStateRunnable)
    }

    private fun stopSaveStateUpdates() {
        saveStateHandler.removeCallbacks(saveStateRunnable)
    }

    override fun onPrepared(mp: MediaPlayer?) {
        isPrepared = true

        val audioSessionId = mp?.audioSessionId ?: 0
        if (audioSessionId != 0) {
            EqualizerManager.initialize(audioSessionId)
            EqualizerManager.reapplySettings()
        }

        if (isRestoringState && restoreSeekPosition > 0) {
            mp?.seekTo(restoreSeekPosition)
            restoreSeekPosition = 0
        } else {
            mp?.start()
        }

        updateMediaSessionMetadata()
        notificationManager.updateNotification(this, getCurrentSong(), isPlaying(), repeatMode)
        updateMediaSessionState()

        if (!isRestoringState) {
            notificationManager.startNotificationUpdates(this, getCurrentSong(), isPlaying())
        }

        ensureForegroundService()
        savePlaybackState()

        if (isRestoringState) {
            sendSongChangedBroadcast("RESTORE")
            isRestoringState = false
        } else {
            sendSongChangedBroadcast("NONE")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForegroundService()

        when (intent?.action) {
            "PREVIOUS" -> playPrevious("NONE")
            "TOGGLE_PLAYBACK" -> togglePlayPause()
            "NEXT" -> playNext("NONE")
            "TOGGLE_REPEAT" -> toggleRepeatMode()
            "TOGGLE_FAVORITE" -> toggleFavorite()
            "SEEK_TO" -> {
                val position = intent.getIntExtra("position", 0)
                seekTo(position)
            }
            "CLOSE" -> {
                // User swiped away notification or closed app
                stopForeground(true)
                stopSelf()
            }
            "ENSURE_FOREGROUND" -> {
                ensureForegroundService()
            }
            "QUEUE" -> {
                val queueIntent = Intent("SHOW_QUEUE")
                sendBroadcast(queueIntent)
            }
            "RESTORE_PLAYBACK" -> {
                kotlinx.coroutines.GlobalScope.launch {
                    loadSavedPlaybackState()
                }
            }
        }
        return START_STICKY
    }

    override fun onCompletion(mp: MediaPlayer?) {
        notificationManager.stopNotificationUpdates()
        when (repeatMode) {
            RepeatMode.ONE -> playCurrentSong()
            RepeatMode.ALL, RepeatMode.SHUFFLE -> playNext("NONE")
        }
    }

    fun startPlayback(songs: ArrayList<Song>, position: Int) {
        if (songs.isEmpty()) return

        val safePosition = position.coerceIn(0, songs.size - 1)

        this.originalSongList = ArrayList(songs)
        this.songList = ArrayList(songs)
        this.songPosition = safePosition
        this.currentQueue = ArrayList(songs)
        this.currentQueuePosition = safePosition

        if (isShuffleMode) {
            val clickedSong = songList[safePosition]
            val tempList = ArrayList(songList)
            tempList.remove(clickedSong)
            Collections.shuffle(tempList)
            tempList.add(0, clickedSong)

            songList = tempList
            songPosition = 0
            currentQueuePosition = 0
            currentQueue = ArrayList(songList)
        }

        verifyQueueSync()
        playSong()
        ensureForegroundService()
    }

    fun getQueueSongs(): List<Song> = currentQueue

    fun getCurrentQueuePosition(): Int = currentQueuePosition

    fun playFromQueue(position: Int) {
        if (position in currentQueue.indices) {
            currentQueuePosition = position
            songPosition = position

            playSong()
            verifyQueueSync()

            sendBroadcast(Intent("QUEUE_CHANGED"))
            sendBroadcast(Intent("SONG_CHANGED"))
        }
    }

    private fun verifyQueueSync(): Boolean {
        if (songList.isEmpty() || currentQueue.isEmpty()) return true

        val currentSong = getCurrentSong() ?: return false
        val songInQueue = currentQueue.getOrNull(currentQueuePosition)
        val syncOk = songInQueue?.id == currentSong.id

        if (!syncOk) {
            val correctQueuePos = currentQueue.indexOfFirst { it.id == currentSong.id }
            if (correctQueuePos != -1) {
                currentQueuePosition = correctQueuePos
                return true
            }
        }
        return syncOk
    }

    private suspend fun loadSavedPlaybackState() {
        try {
            val savedState = PreferenceManager.loadPlaybackState(applicationContext)
            savedState?.let { state ->
                isRestoringState = true
                restoreSeekPosition = state.lastSeekPosition

                repeatMode = MusicService.RepeatMode.entries.getOrNull(state.repeatMode) ?: MusicService.RepeatMode.ALL
                isShuffleMode = state.isShuffleMode

                if (state.queueSongIds.isNotEmpty() || state.originalQueueSongIds.isNotEmpty()) {
                    originalSongList = if (state.originalQueueSongIds.isNotEmpty()) {
                        state.originalQueueSongIds.mapNotNull { id ->
                            SongCacheManager.getSongById(id)
                        }.toMutableList() as ArrayList<Song>
                    } else {
                        ArrayList()
                    }

                    currentQueue = if (state.queueSongIds.isNotEmpty()) {
                        state.queueSongIds.mapNotNull { id ->
                            SongCacheManager.getSongById(id)
                        }.toMutableList()
                    } else {
                        ArrayList(originalSongList)
                    }

                    songList = ArrayList(currentQueue)

                    currentQueuePosition = if (currentQueue.isNotEmpty()) {
                        state.currentQueuePosition.coerceIn(0, currentQueue.size - 1)
                    } else {
                        0
                    }

                    songPosition = currentQueuePosition

                    if (songList.isNotEmpty() && songPosition in songList.indices) {
                        prepareSongForRestoration(state.lastSeekPosition)
                    } else {
                        isRestoringState = false
                    }
                } else if (state.lastPlayedSongId != null && state.lastPlayedSongId != -1L) {
                    val song = SongCacheManager.getSongById(state.lastPlayedSongId)
                    if (song != null) {
                        currentQueue = mutableListOf(song)
                        originalSongList = arrayListOf(song)
                        songList = arrayListOf(song)
                        currentQueuePosition = 0
                        songPosition = 0
                        prepareSongForRestoration(state.lastSeekPosition)
                    } else {
                        isRestoringState = false
                    }
                } else {
                    isRestoringState = false
                }

                sendBroadcast(Intent("PLAYBACK_MODE_CHANGED"))
                sendBroadcast(Intent("SONG_CHANGED"))
                sendBroadcast(Intent("PLAYBACK_STATE_CHANGED"))
                sendBroadcast(Intent("QUEUE_CHANGED"))
                verifyQueueSync()
            } ?: run {
                isRestoringState = false
            }
        } catch (e: Exception) {
            isRestoringState = false
        }
    }

    private fun prepareSongForRestoration(seekPosition: Int = 0) {
        if (songPosition == -1 || songList.isEmpty()) {
            isRestoringState = false
            return
        }

        val currentSong = songList[songPosition]

        try {
            player?.release()
            player = MediaPlayer().apply {
                setOnCompletionListener(this@MusicService)
                setOnPreparedListener { mp ->
                    isPrepared = true
                    val audioSessionId = mp.audioSessionId
                    if (audioSessionId != 0) {
                        EqualizerManager.initialize(audioSessionId)
                    }

                    if (seekPosition > 0) {
                        val safeSeekPosition = seekPosition.coerceAtMost(mp.duration)
                        mp.seekTo(safeSeekPosition)
                    }

                    updateMediaSessionMetadata()
                    notificationManager.updateNotification(this@MusicService, getCurrentSong(), isPlaying(), repeatMode)
                    updateMediaSessionState()
                    ensureForegroundService()

                    sendSongChangedBroadcast("RESTORE")
                    sendBroadcast(Intent("QUEUE_CHANGED"))

                    isRestoringState = false
                }
                setOnErrorListener { _, _, _ ->
                    isRestoringState = false
                    false
                }
                setDataSource(applicationContext, currentSong.uri)
                prepareAsync()
            }
            isPrepared = false
            notificationManager.stopNotificationUpdates()

        } catch (e: Exception) {
            Log.e("MusicService", "Error preparing song for restoration", e)
            isRestoringState = false
        }
    }

    private fun playSong() {
        if (songPosition == -1 || songList.isEmpty()) return

        val currentSong = songList[songPosition]

        if (isShuffleMode && !isNavigatingBack) {
            if (shuffleHistory.isEmpty() || shuffleHistory.peek() != songPosition) {
                shuffleHistory.push(songPosition)
            }
        }
        isNavigatingBack = false

        val queueIndex = currentQueue.indexOfFirst { it.id == currentSong.id }
        if (queueIndex != -1 && queueIndex != currentQueuePosition) {
            currentQueuePosition = queueIndex
        }

        PreferenceManager.addRecentSong(applicationContext, currentSong.id)

        try {
            player?.release()
            player = MediaPlayer().apply {
                setOnCompletionListener(this@MusicService)
                setOnPreparedListener(this@MusicService)
                setOnErrorListener { _, _, _ ->
                    playNext("NONE")
                    true
                }
                setDataSource(applicationContext, currentSong.uri)
                prepareAsync()
            }
            isPrepared = false
            notificationManager.stopNotificationUpdates()
            updateMediaSessionMetadata()
            notificationManager.updateNotification(this, getCurrentSong(), isPlaying(), repeatMode)
            updateMediaSessionState()
            ensureForegroundService()

            sendBroadcast(Intent("QUEUE_CHANGED"))
            sendBroadcast(Intent("SONG_CHANGED"))

        } catch (e: Exception) {
            Log.e("MusicService", "Error playing song", e)
            playNext("NONE")
        }
    }

    private fun playCurrentSong() {
        playSong()
    }

    fun play() {
        if (player?.isPlaying == false && isPrepared) {
            player?.start()
            notificationManager.updateNotification(this, getCurrentSong(), isPlaying(), repeatMode)
            updateMediaSessionState()
            notificationManager.startNotificationUpdates(this, getCurrentSong(), isPlaying())
            ensureForegroundService()
            sendBroadcast(Intent("PLAYBACK_STATE_CHANGED"))
        } else if (!isPrepared && songPosition != -1) {
            playSong()
        }
    }

    fun pause() {
        if (player?.isPlaying == true) {
            player?.pause()
            // Update notification to remove "Foreground" status so it can be dismissed
            notificationManager.updateNotification(this, getCurrentSong(), isPlaying(), repeatMode)
            updateMediaSessionState()
            notificationManager.stopNotificationUpdates()
            sendBroadcast(Intent("PLAYBACK_STATE_CHANGED"))
        }
        // Ensure we don't force foreground if paused
        if (isPlaying()) ensureForegroundService()
    }

    fun togglePlayPause() {
        player?.let {
            if (it.isPlaying && isPrepared) {
                it.pause()
                notificationManager.stopNotificationUpdates()
            } else if (isPrepared) {
                it.start()
                notificationManager.startNotificationUpdates(this, getCurrentSong(), isPlaying())
                ensureForegroundService()
            } else if (songPosition != -1) {
                playSong()
            }
            notificationManager.updateNotification(this, getCurrentSong(), isPlaying(), repeatMode)
            updateMediaSessionState()
            savePlaybackState()
            sendBroadcast(Intent("PLAYBACK_STATE_CHANGED"))
        }
    }

    fun toggleRepeatMode() {
        repeatMode = when (repeatMode) {
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.SHUFFLE
            RepeatMode.SHUFFLE -> RepeatMode.ALL
        }

        isShuffleMode = (repeatMode == RepeatMode.SHUFFLE)

        if (!isShuffleMode) {
            shuffleHistory.clear()
        }

        val currentSong = getCurrentSong()
        if (isShuffleMode) {
            val tempList = ArrayList(originalSongList)
            currentSong?.let { song ->
                tempList.remove(song)
                Collections.shuffle(tempList)
                tempList.add(0, song)
                songList = tempList
                songPosition = 0
                shuffleHistory.clear()
                shuffleHistory.push(0)
            } ?: Collections.shuffle(tempList)

        } else {
            songList = ArrayList(originalSongList)
            currentSong?.let { song ->
                val newPosition = songList.indexOfFirst { it.id == song.id }
                if (newPosition != -1) songPosition = newPosition
            }
        }

        currentQueue = ArrayList(songList)
        currentQueuePosition = songPosition

        verifyQueueSync()
        updateMediaSessionState()
        notificationManager.updateNotification(this, getCurrentSong(), isPlaying(), repeatMode)
        sendBroadcast(Intent("PLAYBACK_MODE_CHANGED"))
        sendBroadcast(Intent("QUEUE_CHANGED"))
    }

    private fun toggleFavorite() {
        val currentSong = getCurrentSong() ?: return

        currentSong.isFavorite = !currentSong.isFavorite
        if (currentSong.isFavorite) {
            PreferenceManager.addFavorite(applicationContext, currentSong.id)
        } else {
            PreferenceManager.removeFavorite(applicationContext, currentSong.id)
        }

        updateMediaSessionState()
        sendBroadcast(Intent("SONG_CHANGED"))
        notificationManager.updateNotification(this, getCurrentSong(), isPlaying(), repeatMode)
    }

    fun toggleShuffle() {
        toggleRepeatMode()
    }

    fun setRepeatMode(mode: RepeatMode) {
        repeatMode = mode
        isShuffleMode = (repeatMode == RepeatMode.SHUFFLE)

        if (repeatMode == RepeatMode.SHUFFLE && !isShuffleMode) {
            toggleShuffle()
        } else if (repeatMode != RepeatMode.SHUFFLE && isShuffleMode) {
            toggleShuffle()
        }

        sendBroadcast(Intent("PLAYBACK_MODE_CHANGED"))
    }

    fun isShuffleMode(): Boolean = isShuffleMode
    fun getRepeatMode(): RepeatMode = repeatMode

    fun isPlaying(): Boolean = player?.isPlaying ?: false

    fun getCurrentSong(): Song? =
        if (songPosition >= 0 && songPosition < songList.size) songList[songPosition] else null

    fun getDuration(): Int = if (isPrepared) player?.duration ?: 0 else 0

    fun getCurrentPosition(): Int = if (isPrepared) player?.currentPosition ?: 0 else 0

    fun seekTo(position: Int) {
        if (isPrepared) {
            player?.seekTo(position)
            updateMediaSessionState()
            sendBroadcast(Intent("SEEK_POSITION_CHANGED").apply {
                putExtra("position", position)
            })
        }
    }

    fun setPreferredAudioDevice(deviceId: Int): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && player != null) {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val targetDevice = devices.find { it.id == deviceId }

            if (targetDevice != null) {
                val success = player?.setPreferredDevice(targetDevice) ?: false
                if (success) {
                    Log.d("MusicService", "Switched audio output to: ${targetDevice.productName} (ID: $deviceId)")
                }
                return success
            }
        }
        return false
    }

    fun getPreferredAudioDevice(): Int? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && player != null) {
            return player?.preferredDevice?.id
        }
        return null
    }

    private fun updateMediaSessionMetadata() {
        val currentSong = getCurrentSong() ?: return

        val albumArt = notificationManager.loadAlbumArtBitmap(currentSong)

        val metadataBuilder = android.support.v4.media.MediaMetadataCompat.Builder()
            .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_TITLE, currentSong.title)
            .putString(
                android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ARTIST,
                currentSong.artist ?: "Unknown Artist"
            )
            .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ALBUM, currentSong.album ?: "Unknown Album")
            .putBitmap(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ALBUM_ART, albumArt)
            .putLong(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_DURATION, getDuration().toLong())
            .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_GENRE, currentSong.genre)
            .putLong(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_YEAR, currentSong.year?.toLongOrNull() ?: 0L)

        mediaSession.setMetadata(metadataBuilder.build())
    }

    private fun updateMediaSessionState() {
        val state = when {
            !isPrepared -> PlaybackStateCompat.STATE_NONE
            isPlaying() -> PlaybackStateCompat.STATE_PLAYING
            else -> PlaybackStateCompat.STATE_PAUSED
        }

        val position = getCurrentPosition().toLong()

        val playbackStateBuilder = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SEEK_TO
            )
            .setState(state, position, 1.0f)

        val (repeatIcon, repeatTitle) = when (repeatMode) {
            RepeatMode.ONE -> Pair(R.drawable.repeat_one, "Repeat One")
            RepeatMode.SHUFFLE -> Pair(R.drawable.shuffle, "Shuffle")
            else -> Pair(R.drawable.repeat, "Repeat All")
        }
        playbackStateBuilder.addCustomAction(
            PlaybackStateCompat.CustomAction.Builder(
                CUSTOM_ACTION_TOGGLE_REPEAT,
                repeatTitle,
                repeatIcon
            ).build()
        )

        getCurrentSong()?.let {
            val isFavorite = PreferenceManager.isFavorite(applicationContext, it.id)
            val favoriteIcon = if (isFavorite) R.drawable.ic_favorite_filled else R.drawable.ic_favorite_outline
            val favoriteTitle = if (isFavorite) "Unfavorite" else "Favorite"
            playbackStateBuilder.addCustomAction(
                PlaybackStateCompat.CustomAction.Builder(
                    CUSTOM_ACTION_TOGGLE_FAVORITE,
                    favoriteTitle,
                    favoriteIcon
                ).build()
            )
        }

        mediaSession.setPlaybackState(playbackStateBuilder.build())
    }

    private fun ensureForegroundService() {
        try {
            val currentSong = getCurrentSong()
            if (currentSong != null) {
                notificationManager.updateNotification(this, currentSong, isPlaying(), repeatMode)
            } else {
                notificationManager.showMinimalNotification(this)
            }
        } catch (e: Exception) {
            Log.e("MusicService", "Error ensuring foreground service", e)
            notificationManager.showMinimalNotification(this)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(videoStateReceiver) // Unregister logic
        stopSaveStateUpdates()
        notificationManager.stopNotificationUpdates()
        mediaSession.release()
        player?.release()
        player = null
        EqualizerManager.release()
        stopForeground(true)
    }

    enum class RepeatMode {
        ALL, ONE, SHUFFLE
    }

    fun playNext(animationType: String = "NONE") {
        if (songList.isEmpty()) return

        when (repeatMode) {
            RepeatMode.ONE -> {
                playCurrentSong()
            }
            RepeatMode.SHUFFLE, RepeatMode.ALL -> {
                songPosition = (songPosition + 1) % songList.size
                currentQueuePosition = songPosition
                playSong()
            }
        }
        verifyQueueSync()
        sendSongChangedBroadcast(animationType)
    }

    fun playPrevious(animationType: String = "NONE") {
        if (songList.isEmpty()) return

        when (repeatMode) {
            RepeatMode.ONE -> {
                playCurrentSong()
            }
            RepeatMode.SHUFFLE, RepeatMode.ALL -> {
                songPosition = if (songPosition - 1 < 0) songList.size - 1 else songPosition - 1
                currentQueuePosition = songPosition
                playSong()
            }
        }
        verifyQueueSync()
        sendSongChangedBroadcast(animationType)
    }

    private fun sendSongChangedBroadcast(animationType: String) {
        val intent = Intent("SONG_CHANGED").apply {
            putExtra("animationType", animationType)
        }
        sendBroadcast(intent)
    }

    fun triggerStateRestoration() {
        kotlinx.coroutines.GlobalScope.launch {
            loadSavedPlaybackState()
        }
    }

    fun restorePlaybackStateIfNeeded() {
        if (getCurrentSong() == null) {
            triggerStateRestoration()
        }
    }
}