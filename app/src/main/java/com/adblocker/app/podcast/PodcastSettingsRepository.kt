package com.adblocker.app.podcast

import android.content.Context
import android.content.SharedPreferences

/**
 * Settings for the Podcast Addict module. Unlike TikTok/Spotify, there's no ad
 * *detection* here at all (see PRD.md's "Non-goals") - just a user-configurable
 * skip-forward duration for a manual button, since per-podcast sponsor-read lengths
 * tend to be consistent enough that a fixed skip amount is genuinely useful.
 */
class PodcastSettingsRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Whether the floating "Skip Ns" button is shown while Podcast Addict is
      * foregrounded. On by default - same reasoning as the other two modules'
      * overlay toggles. */
    var isSkipButtonEnabled: Boolean
        get() = prefs.getBoolean(KEY_SKIP_BUTTON_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_SKIP_BUTTON_ENABLED, value).apply()

    /** How far the button skips forward, in seconds - tune this to match how long your
      * podcast's sponsor reads actually run. Clamped to [SkipDurationLimits] on write. */
    var skipDurationSeconds: Int
        get() = prefs.getInt(KEY_SKIP_DURATION_SECONDS, SkipDurationLimits.DEFAULT_SECONDS)
        set(value) = prefs.edit().putInt(
            KEY_SKIP_DURATION_SECONDS,
            value.coerceIn(SkipDurationLimits.MIN_SECONDS, SkipDurationLimits.MAX_SECONDS)
        ).apply()

    var targetPackages: List<String>
        get() = parseList(prefs.getString(KEY_TARGET_PACKAGES, null) ?: DEFAULT_TARGET_PACKAGES.joinToString(","))
        set(value) = prefs.edit().putString(KEY_TARGET_PACKAGES, joinList(value)).apply()

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
        private const val PREFS_NAME = "adblocker_podcast_settings"
        private const val KEY_SKIP_BUTTON_ENABLED = "skip_button_enabled"
        private const val KEY_SKIP_DURATION_SECONDS = "skip_duration_seconds"
        private const val KEY_TARGET_PACKAGES = "target_packages"

        val DEFAULT_TARGET_PACKAGES = listOf("com.bambuna.podcastaddict")
    }
}
