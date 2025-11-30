package com.shubhamgupta.nebula_player.utils

import android.content.Context
import android.util.Log
import com.shubhamgupta.nebula_player.R

object UserProfileManager {
    private const val PREFS_NAME = "nebula_player_prefs"
    private const val KEY_USER_NAME = "user_name"
    private const val KEY_AVATAR_URI = "user_avatar_uri"
    private const val KEY_SETUP_DONE = "is_setup_done"

    fun getUserName(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_USER_NAME, "User") ?: "User"
    }

    fun getUserAvatarUri(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_AVATAR_URI, null)
    }

    fun isSetupDone(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_SETUP_DONE, false)
    }

    fun saveUserProfile(context: Context, name: String, avatarUri: String?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val finalName = if (name.isBlank()) "User" else name.trim()

        val editor = prefs.edit()
        editor.putString(KEY_USER_NAME, finalName)
        if (avatarUri != null) {
            editor.putString(KEY_AVATAR_URI, avatarUri)
        }
        editor.putBoolean(KEY_SETUP_DONE, true)
        editor.apply()
    }

    fun getAvailableAvatars(context: Context): List<String> {
        val avatarList = mutableListOf<String>()
        try {
            // Note: Folder must be at src/main/assets/avatars
            val files = context.assets.list("avatars")
            if (!files.isNullOrEmpty()) {
                for (file in files) {
                    if (file.endsWith(".png", true) ||
                        file.endsWith(".jpg", true) ||
                        file.endsWith(".jpeg", true)) {
                        // Correct URI format for Glide to read from assets
                        avatarList.add("file:///android_asset/avatars/$file")
                    }
                }
            } else {
                Log.w("UserProfileManager", "No avatars found in assets/avatars. Check src/main/assets folder.")
            }
        } catch (e: Exception) {
            Log.e("UserProfileManager", "Error listing assets", e)
        }

        // Always add default option at the end
        val packageName = context.packageName
        avatarList.add("android.resource://$packageName/${R.drawable.default_album_art}")

        return avatarList
    }
}