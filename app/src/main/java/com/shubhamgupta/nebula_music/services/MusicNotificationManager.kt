package com.shubhamgupta.nebula_music.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.support.v4.media.session.MediaSessionCompat
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import com.bumptech.glide.Glide
import com.shubhamgupta.nebula_music.MainActivity
import com.shubhamgupta.nebula_music.R
import com.shubhamgupta.nebula_music.models.Song
import com.shubhamgupta.nebula_music.utils.PreferenceManager
import com.shubhamgupta.nebula_music.utils.SongUtils
import java.util.concurrent.TimeUnit

class MusicNotificationManager(private val context: Context) {

    companion object {
        const val NOTIFICATION_ID = 101
        const val CHANNEL_ID = "nebula_music_channel"
        const val CHANNEL_NAME = "Nebula Music Player"
    }

    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val notificationHandler = Handler(Looper.getMainLooper())
    private var notificationUpdateRunnable: Runnable? = null

    fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Music playback controls"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(null, null) // No sound for music notifications
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    @OptIn(UnstableApi::class)
    @SuppressLint("ForegroundServiceType", "Range")
    fun updateNotification(service: MusicService, currentSong: Song?, isPlaying: Boolean, repeatMode: MusicService.RepeatMode) {
        if (currentSong == null) {
            showMinimalNotification(service)
            return
        }

        val notification = buildNotification(context, currentSong, isPlaying, repeatMode, service)
        notificationManager.notify(NOTIFICATION_ID, notification)

        // Always start foreground service when we have a valid notification
        try {
            service.startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e("MusicNotificationManager", "Error starting foreground service", e)
        }
    }

    @OptIn(UnstableApi::class)
    @SuppressLint("ForegroundServiceType", "Range")
    fun showMinimalNotification(service: MusicService) {
        val notification = buildMinimalNotification(context)
        notificationManager.notify(NOTIFICATION_ID, notification)

        try {
            service.startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e("MusicNotificationManager", "Error starting foreground service with minimal notification", e)
        }
    }

    fun buildNotification(
        context: Context,
        song: Song,
        isPlaying: Boolean,
        repeatMode: MusicService.RepeatMode,
        service: MusicService? = null
    ): Notification {
        // Create all actions in the correct order: [Repeat, Previous, Play/Pause, Next, Favorite]
        val repeatAction = createRepeatAction(repeatMode)
        val previousAction = createPreviousAction()
        val playPauseAction = createPlayPauseAction(isPlaying)
        val nextAction = createNextAction()
        val favoriteAction = createFavoriteAction(song)

        // Build notification
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.default_album_art)
            .setContentTitle(song.title)
            .setContentText(song.artist ?: "Unknown Artist")
            .setLargeIcon(loadAlbumArtBitmap(song))
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    // Show Previous, Play/Pause, Next in compact view (indices 1, 2, 3)
                    .setShowActionsInCompactView(1, 2, 3)
                    .setMediaSession(service?.let { getMediaSessionToken(it) })
            )
            .setColor(context.getColor(R.color.purple_500))
            .setColorized(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(isPlaying)
            .setAutoCancel(false)
            // Add actions in the correct order: [0:Repeat, 1:Previous, 2:Play/Pause, 3:Next, 4:Favorite]
            .addAction(repeatAction)
            .addAction(previousAction)
            .addAction(playPauseAction)
            .addAction(nextAction)
            .addAction(favoriteAction)

        // Set content intent
        val contentIntent = createContentIntent()
        builder.setContentIntent(contentIntent)

        return builder.build()
    }

    fun buildMinimalNotification(context: Context): Notification {
        val contentIntent = createContentIntent()

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.default_album_art)
            .setContentTitle("Nebula Music")
            .setContentText("Music service is running")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun createPlayPauseAction(isPlaying: Boolean): NotificationCompat.Action {
        val icon = if (isPlaying) R.drawable.ic_pausen else R.drawable.ic_playn
        val title = if (isPlaying) "Pause" else "Play"

        val intent = Intent(context, MusicService::class.java).apply {
            action = "TOGGLE_PLAYBACK"
        }

        val pendingIntent = PendingIntent.getService(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Action.Builder(icon, title, pendingIntent).build()
    }

    private fun createPreviousAction(): NotificationCompat.Action {
        val intent = Intent(context, MusicService::class.java).apply {
            action = "PREVIOUS"
        }

        val pendingIntent = PendingIntent.getService(
            context,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Action.Builder(R.drawable.ic_previous, "Previous", pendingIntent).build()
    }

    private fun createNextAction(): NotificationCompat.Action {
        val intent = Intent(context, MusicService::class.java).apply {
            action = "NEXT"
        }

        val pendingIntent = PendingIntent.getService(
            context,
            2,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Action.Builder(R.drawable.ic_next, "Next", pendingIntent).build()
    }

    private fun createRepeatAction(repeatMode: MusicService.RepeatMode): NotificationCompat.Action {
        val (icon, title) = when (repeatMode) {
            MusicService.RepeatMode.ONE -> Pair(R.drawable.repeat_one, "Repeat One")
            MusicService.RepeatMode.SHUFFLE -> Pair(R.drawable.shuffle, "Shuffle")
            else -> Pair(R.drawable.repeat, "Repeat All")
        }

        val intent = Intent(context, MusicService::class.java).apply {
            action = "TOGGLE_REPEAT"
        }

        val pendingIntent = PendingIntent.getService(
            context,
            3,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Action.Builder(icon, title, pendingIntent).build()
    }

    private fun createFavoriteAction(song: Song): NotificationCompat.Action {
        val isFavorite = PreferenceManager.isFavorite(context, song.id)
        val icon = if (isFavorite) R.drawable.ic_favorite_filled else R.drawable.ic_favorite_outline
        val title = if (isFavorite) "Unfavorite" else "Favorite"

        val intent = Intent(context, MusicService::class.java).apply {
            action = "TOGGLE_FAVORITE"
        }

        val pendingIntent = PendingIntent.getService(
            context,
            4,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Action.Builder(icon, title, pendingIntent).build()
    }

    private fun createContentIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("fragment", "now_playing")
        }

        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun loadAlbumArtBitmap(song: Song): Bitmap {
        return try {
            val albumArtUri = SongUtils.getAlbumArtUri(song.albumId)
            Glide.with(context)
                .asBitmap()
                .load(albumArtUri)
                .submit(256, 256)
                .get(2, TimeUnit.SECONDS) // Add timeout to prevent blocking
        } catch (e: Exception) {
            // Fallback to default album art
            BitmapFactory.decodeResource(context.resources, R.drawable.default_album_art)
        }
    }

    private fun getMediaSessionToken(service: MusicService): MediaSessionCompat.Token? {
        return try {
            // Use reflection to access the mediaSession field
            val field = service::class.java.getDeclaredField("mediaSession")
            field.isAccessible = true
            val mediaSession = field.get(service) as? MediaSessionCompat
            mediaSession?.sessionToken
        } catch (e: Exception) {
            null
        }
    }

    fun startNotificationUpdates(service: MusicService, currentSong: Song?, isPlaying: Boolean) {
        stopNotificationUpdates()

        if (isPlaying && currentSong != null) {
            notificationUpdateRunnable = object : Runnable {
                @SuppressLint("Range")
                @OptIn(UnstableApi::class)
                override fun run() {
                    try {
                        updateNotification(service, currentSong, isPlaying, service.getRepeatMode())
                        notificationHandler.postDelayed(this, 1000)
                    } catch (e: Exception) {
                        Log.e("MusicNotificationManager", "Error in notification update", e)
                    }
                }
            }
            notificationHandler.post(notificationUpdateRunnable!!)
        }
    }

    fun stopNotificationUpdates() {
        notificationUpdateRunnable?.let {
            notificationHandler.removeCallbacks(it)
        }
        notificationUpdateRunnable = null
    }

    // Helper method to remove notification when service stops
    @SuppressLint("Range")
    @OptIn(UnstableApi::class)
    fun cancelNotification() {
        try {
            notificationManager.cancel(NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.e("MusicNotificationManager", "Error canceling notification", e)
        }
    }
}