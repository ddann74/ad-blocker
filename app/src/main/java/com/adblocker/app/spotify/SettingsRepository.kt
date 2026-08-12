package com.adblocker.app.spotify

import android.content.Context
import android.content.SharedPreferences

/** Same shape as the TikTok module's SettingsRepository - comma-joined ordered string
  * lists rather than SharedPreferences' StringSet, to preserve insertion order for
  * display/editing in Setup. */
class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var isAdSkipEnabled: Boolean
        get() = prefs.getBoolean(KEY_AD_SKIP_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_AD_SKIP_ENABLED, value).apply()

    var isOverlayEnabled: Boolean
        get() = prefs.getBoolean(KEY_OVERLAY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_OVERLAY_ENABLED, value).apply()

    var targetPackages: List<String>
        get() = parseList(prefs.getString(KEY_TARGET_PACKAGES, null) ?: DEFAULT_TARGET_PACKAGES.joinToString(","))
        set(value) = prefs.edit().putString(KEY_TARGET_PACKAGES, joinList(value)).apply()

    var adKeywords: List<String>
        get() = parseList(prefs.getString(KEY_AD_KEYWORDS, null) ?: DEFAULT_AD_KEYWORDS.joinToString(","))
        set(value) = prefs.edit().putString(KEY_AD_KEYWORDS, joinList(value)).apply()

    /** Candidate labels for the Skip/Next control, tried in order - most-to-least
      * specific, so "Skip Ad" (Spotify's actual ad-screen label, when present) is
      * preferred over the more generic "Skip"/"Next" that also appear during normal
      * playback. */
    var skipControlKeywords: List<String>
        get() = parseList(prefs.getString(KEY_SKIP_CONTROL_KEYWORDS, null) ?: DEFAULT_SKIP_CONTROL_KEYWORDS.joinToString(","))
        set(value) = prefs.edit().putString(KEY_SKIP_CONTROL_KEYWORDS, joinList(value)).apply()

    /** Candidate labels for Spotify's own "Download for offline" toggle - a real
      * Premium feature; this only ever taps that existing control. */
    var downloadControlKeywords: List<String>
        get() = parseList(prefs.getString(KEY_DOWNLOAD_CONTROL_KEYWORDS, null) ?: DEFAULT_DOWNLOAD_CONTROL_KEYWORDS.joinToString(","))
        set(value) = prefs.edit().putString(KEY_DOWNLOAD_CONTROL_KEYWORDS, joinList(value)).apply()

    fun addKeyword(list: kotlin.reflect.KMutableProperty0<List<String>>, keyword: String) {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return
        if (list.get().any { it.equals(trimmed, ignoreCase = true) }) return
        list.set(list.get() + trimmed)
    }

    fun removeKeyword(list: kotlin.reflect.KMutableProperty0<List<String>>, keyword: String) {
        list.set(list.get().filterNot { it.equals(keyword, ignoreCase = true) })
    }

    private fun parseList(raw: String): List<String> =
        raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    private fun joinList(items: List<String>): String = items.joinToString(",")

    companion object {
        private const val PREFS_NAME = "adblocker_spotify_settings"
        private const val KEY_AD_SKIP_ENABLED = "ad_skip_enabled"
        private const val KEY_OVERLAY_ENABLED = "overlay_enabled"
        private const val KEY_TARGET_PACKAGES = "target_packages"
        private const val KEY_AD_KEYWORDS = "ad_keywords"
        private const val KEY_SKIP_CONTROL_KEYWORDS = "skip_control_keywords"
        private const val KEY_DOWNLOAD_CONTROL_KEYWORDS = "download_control_keywords"

        val DEFAULT_TARGET_PACKAGES = listOf("com.spotify.music")
        val DEFAULT_AD_KEYWORDS = listOf("Advertisement")
        val DEFAULT_SKIP_CONTROL_KEYWORDS = listOf("Skip Ad", "Skip", "Next")
        val DEFAULT_DOWNLOAD_CONTROL_KEYWORDS = listOf("Download")
    }
}
