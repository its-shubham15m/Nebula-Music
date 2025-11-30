package com.shubhamgupta.nebula_player.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.support.v4.media.session.MediaSessionCompat
import androidx.annotation.DrawableRes
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import com.bumptech.glide.Glide
import com.shubhamgupta.nebula_player.MainActivity
import com.shubhamgupta.nebula_player.R
import com.shubhamgupta.nebula_player.models.Song
import com.shubhamgupta.nebula_player.utils.PreferenceManager
import com.shubhamgupta.nebula_player.utils.SongUtils
import java.util.Calendar
import java.util.Random
import java.util.concurrent.TimeUnit

// Renamed from MusicNotificationManager as requested
class NebulaNotificationManager(private val context: Context) {

    companion object {
        const val NOTIFICATION_ID = 101
        const val ENGAGEMENT_NOTIFICATION_ID = 102
        const val CHANNEL_ID = "nebula_player_channel"
        const val CHANNEL_NAME = "Nebula Music Player"
        const val ENGAGEMENT_CHANNEL_ID = "nebula_engagement_channel"
    }

    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val notificationHandler = Handler(Looper.getMainLooper())
    private var notificationUpdateRunnable: Runnable? = null

    // Cache for scaled icons to prevent re-drawing them every second
    private val iconCache = mutableMapOf<Int, IconCompat>()

    // Mutable list for Engagement Pop-up messages
    val engagementMessages = mutableListOf(
        "Time to relax with your favorite tunes!",
        "Discover something new in your library today.",
        "Watch that video you saved for later!",
        "Music is the soundtrack of your life.",
        "Nebula Player: Your media, your way.",
        "Plug in your headphones and drift away.",
        "Check out your recent playlists!"
    )

    fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Media Control Channel
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

            // Engagement / Pop-up Channel
            val engagementChannel = NotificationChannel(
                ENGAGEMENT_CHANNEL_ID,
                "Nebula Updates",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "App updates and suggestions"
                setShowBadge(true)
            }
            notificationManager.createNotificationChannel(engagementChannel)
        }
    }

    /**
     * Sends a welcome notification with a greeting and graphical abstract.
     * Starts the app like a professional service.
     */
    fun sendWelcomeNotification(userName: String) {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val greeting = when (hour) {
            in 5..11 -> "Good Morning"
            in 12..16 -> "Good Afternoon"
            in 17..20 -> "Good Evening"
            else -> "Good Night"
        }

        val title = "$greeting, $userName"
        val message = "Ready to dive into your music world?"

        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Use default album art as the graphical abstract base if no specific image is available
        val largeImage = BitmapFactory.decodeResource(context.resources, R.drawable.default_album_art)

        val notification = NotificationCompat.Builder(context, ENGAGEMENT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            // Graphical Abstract / Big Picture Style
            .setStyle(NotificationCompat.BigPictureStyle()
                .bigPicture(largeImage)
                .setBigContentTitle(title)
                .setSummaryText("Nebula Player is ready for you."))
            .build()

        notificationManager.notify(ENGAGEMENT_NOTIFICATION_ID, notification)
    }

    /**
     * Sends a random pop-up notification from the mutable list
     */
    fun sendEngagementNotification() {
        if (engagementMessages.isEmpty()) return

        val random = Random()
        val message = engagementMessages[random.nextInt(engagementMessages.size)]

        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, ENGAGEMENT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note) // Ensure you have a generic icon or use R.drawable.default_album_art
            .setContentTitle("Nebula Player")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(ENGAGEMENT_NOTIFICATION_ID, notification)
    }

    @OptIn(UnstableApi::class)
    @SuppressLint("ForegroundServiceType", "Range")
    fun updateNotification(service: MusicService, currentSong: Song?, isPlaying: Boolean, repeatMode: MusicService.RepeatMode) {
        if (currentSong == null) {
            // If stopped/null, we allow swipe dismissal
            cancelNotification()
            return
        }

        val notification = buildNotification(context, currentSong, isPlaying, repeatMode, service)
        notificationManager.notify(NOTIFICATION_ID, notification)

        // FIX: Notification Locking
        // If playing -> startForeground (Locked/Persistent)
        // If paused -> stopForeground(false) (Notification stays but is dismissible/unlocked)
        try {
            if (isPlaying) {
                service.startForeground(NOTIFICATION_ID, notification)
            } else {
                // This keeps the notification but removes the "Foreground" status, allowing swipe away
                service.stopForeground(false)
            }
        } catch (e: Exception) {
            Log.e("NebulaNotificationManager", "Error managing foreground state", e)
        }
    }

    /**
     * New Method to show Video Details in Notification
     * Call this from VideoPlayerActivity
     */
    fun showVideoNotification(title: String, description: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.default_album_art) // Replace with video icon
            .setContentTitle(title)
            .setContentText(description)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true) // Video is usually ongoing
            .setAutoCancel(false)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    @OptIn(UnstableApi::class)
    @SuppressLint("ForegroundServiceType", "Range")
    fun showMinimalNotification(service: MusicService) {
        val notification = buildMinimalNotification(context)
        notificationManager.notify(NOTIFICATION_ID, notification)

        try {
            service.startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e("NebulaNotificationManager", "Error starting foreground service with minimal notification", e)
        }
    }

    fun buildNotification(
        context: Context,
        song: Song,
        isPlaying: Boolean,
        repeatMode: MusicService.RepeatMode,
        service: MusicService? = null
    ): Notification {
        val repeatAction = createRepeatAction(repeatMode)
        val previousAction = createPreviousAction()
        val playPauseAction = createPlayPauseAction(isPlaying)
        val nextAction = createNextAction()
        val favoriteAction = createFavoriteAction(song)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.default_album_art)
            .setContentTitle(song.title)
            .setContentText(song.artist ?: "Unknown Artist")
            .setLargeIcon(loadAlbumArtBitmap(song))
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setShowActionsInCompactView(1, 2, 3)
                    .setMediaSession(service?.let { getMediaSessionToken(it) })
            )
            .setColor(context.getColor(R.color.purple_500))
            .setColorized(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            // FIX: Only ongoing if playing
            .setOngoing(isPlaying)
            .setAutoCancel(false)
            .addAction(repeatAction)
            .addAction(previousAction)
            .addAction(playPauseAction)
            .addAction(nextAction)
            .addAction(favoriteAction)

        val contentIntent = createContentIntent()
        builder.setContentIntent(contentIntent)

        // When paused, make the notification deletable (swipable)
        builder.setDeleteIntent(createDeleteIntent(context))

        return builder.build()
    }

    fun buildMinimalNotification(context: Context): Notification {
        val contentIntent = createContentIntent()

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.default_album_art)
            .setContentTitle("Nebula Music")
            .setContentText("Ready to play")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(false) // Not locked
            .setAutoCancel(false)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun getScaledIcon(@DrawableRes resourceId: Int): IconCompat {
        if (iconCache.containsKey(resourceId)) {
            return iconCache[resourceId]!!
        }

        try {
            val drawable = ContextCompat.getDrawable(context, resourceId)
                ?: return IconCompat.createWithResource(context, resourceId)

            val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 96
            val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 96

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            val scale = 0.75f
            val cx = width / 2f
            val cy = height / 2f

            canvas.scale(scale, scale, cx, cy)
            drawable.setBounds(0, 0, width, height)
            drawable.draw(canvas)

            val icon = IconCompat.createWithBitmap(bitmap)
            iconCache[resourceId] = icon
            return icon
        } catch (e: Exception) {
            return IconCompat.createWithResource(context, resourceId)
        }
    }

    private fun createPlayPauseAction(isPlaying: Boolean): NotificationCompat.Action {
        val iconRes = if (isPlaying) R.drawable.ic_pausen else R.drawable.ic_playn
        val title = if (isPlaying) "Pause" else "Play"

        val intent = Intent(context, MusicService::class.java).apply {
            action = "TOGGLE_PLAYBACK"
        }

        val pendingIntent = PendingIntent.getService(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Action.Builder(getScaledIcon(iconRes), title, pendingIntent).build()
    }

    private fun createPreviousAction(): NotificationCompat.Action {
        val intent = Intent(context, MusicService::class.java).apply {
            action = "PREVIOUS"
        }
        val pendingIntent = PendingIntent.getService(
            context, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Action.Builder(getScaledIcon(R.drawable.ic_previous), "Previous", pendingIntent).build()
    }

    private fun createNextAction(): NotificationCompat.Action {
        val intent = Intent(context, MusicService::class.java).apply {
            action = "NEXT"
        }
        val pendingIntent = PendingIntent.getService(
            context, 2, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Action.Builder(getScaledIcon(R.drawable.ic_next), "Next", pendingIntent).build()
    }

    private fun createRepeatAction(repeatMode: MusicService.RepeatMode): NotificationCompat.Action {
        val (iconRes, title) = when (repeatMode) {
            MusicService.RepeatMode.ONE -> Pair(R.drawable.repeat_one, "Repeat One")
            MusicService.RepeatMode.SHUFFLE -> Pair(R.drawable.shuffle, "Shuffle")
            else -> Pair(R.drawable.repeat, "Repeat All")
        }
        val intent = Intent(context, MusicService::class.java).apply {
            action = "TOGGLE_REPEAT"
        }
        val pendingIntent = PendingIntent.getService(
            context, 3, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Action.Builder(getScaledIcon(iconRes), title, pendingIntent).build()
    }

    private fun createFavoriteAction(song: Song): NotificationCompat.Action {
        val isFavorite = PreferenceManager.isFavorite(context, song.id)
        val iconRes = if (isFavorite) R.drawable.ic_favorite_filled else R.drawable.ic_favorite_outline
        val title = if (isFavorite) "Unfavorite" else "Favorite"
        val intent = Intent(context, MusicService::class.java).apply {
            action = "TOGGLE_FAVORITE"
        }
        val pendingIntent = PendingIntent.getService(
            context, 4, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Action.Builder(getScaledIcon(iconRes), title, pendingIntent).build()
    }

    private fun createContentIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("fragment", "now_playing")
        }
        return PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // Intent to stop service when notification is swiped away
    private fun createDeleteIntent(context: Context): PendingIntent {
        val intent = Intent(context, MusicService::class.java).apply {
            action = "CLOSE"
        }
        return PendingIntent.getService(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun loadAlbumArtBitmap(song: Song): Bitmap {
        return try {
            val albumArtUri = SongUtils.getAlbumArtUri(song.albumId)
            Glide.with(context)
                .asBitmap()
                .load(albumArtUri)
                .submit(256, 256)
                .get(2, TimeUnit.SECONDS)
        } catch (e: Exception) {
            BitmapFactory.decodeResource(context.resources, R.drawable.default_album_art)
        }
    }

    private fun getMediaSessionToken(service: MusicService): MediaSessionCompat.Token? {
        return try {
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
                        Log.e("NebulaNotificationManager", "Error in notification update", e)
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

    @SuppressLint("Range")
    @OptIn(UnstableApi::class)
    fun cancelNotification() {
        try {
            notificationManager.cancel(NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.e("NebulaNotificationManager", "Error canceling notification", e)
        }
    }
}