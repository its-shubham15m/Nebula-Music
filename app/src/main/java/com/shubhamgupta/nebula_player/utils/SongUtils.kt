package com.shubhamgupta.nebula_player.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContentUris
import android.content.ContentResolver
import android.net.Uri
import android.media.MediaMetadataRetriever
import android.provider.OpenableColumns
import com.shubhamgupta.nebula_player.R
import com.shubhamgupta.nebula_player.models.Song
import java.util.concurrent.TimeUnit

object SongUtils {

    private val ALBUM_ART_URI = Uri.parse("content://media/external/audio/albumart")

    fun getAlbumArtUri(albumId: Long): Uri {
        return ContentUris.withAppendedId(ALBUM_ART_URI, albumId)
    }

    fun getDefaultAlbumArt(context: Context): Int {
        return R.drawable.default_album_art
    }

    private fun getFileNameFromUri(contentResolver: ContentResolver, uri: Uri): String {
        var fileName: String? = null
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                try {
                    val displayNameIndex = cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
                    fileName = cursor.getString(displayNameIndex)
                } catch (e: IllegalArgumentException) {
                    // Column not found, ignore
                }
            }
        }
        return fileName ?: uri.lastPathSegment ?: "Unknown Song"
    }

    /**
     * Creates a Song object from an external URI, retrieving full text metadata.
     */
    fun createSongFromUri(context: Context, uri: Uri): Song? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)

            val rawTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val rawArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            val rawAlbum = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            val rawYear = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
            val rawGenre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val embeddedPictureBytes = retriever.embeddedPicture

            val durationMs = durationStr?.toLongOrNull() ?: 0L
            // Use a hash of the URI as a temporary unique ID
            val tempId = uri.toString().hashCode().toLong()

            val title = rawTitle ?: getFileNameFromUri(context.contentResolver, uri)
                .substringBeforeLast(".")

            Song(
                id = tempId,
                title = title.trim(),
                artist = rawArtist ?: "Unknown Artist",
                album = rawAlbum ?: "External",
                genre = rawGenre,
                year = rawYear,
                path = uri.toString(),
                duration = durationMs,
                albumId = 0, // Album ID remains 0 for external files
                uri = uri,
                embeddedArtBytes = embeddedPictureBytes
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            retriever.release()
        }
    }

    /**
     * Retrieves the embedded picture bytes (album art) from the audio file specified by the URI.
     */
    fun getEmbeddedPictureBytes(context: Context, uri: Uri): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.embeddedPicture
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            retriever.release()
        }
    }

    // --- ADDED MISSING FUNCTION ---
    @SuppressLint("DefaultLocale")
    fun formatDuration(durationMillis: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMillis)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(durationMillis) % 60
        return String.format("%02d:%02d", minutes, seconds)
    }
}