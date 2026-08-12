package com.adblocker.app.podcast

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Locale

/** Counters plus a capped, newest-first activity log - same auditability pattern as
  * the TikTok and Spotify modules' StatsRepository. */
class PodcastStatsRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    val skipsPerformed: Int get() = prefs.getInt(KEY_SKIPS_PERFORMED, 0)
    val skipsFailed: Int get() = prefs.getInt(KEY_SKIPS_FAILED, 0)

    fun recentLog(): List<String> {
        val raw = prefs.getString(KEY_LOG, null) ?: return emptyList()
        return raw.split("\n").filter { it.isNotBlank() }
    }

    fun recordSkip(durationSeconds: Int) {
        prefs.edit().putInt(KEY_SKIPS_PERFORMED, skipsPerformed + 1).apply()
        appendLogEntry("Skipped forward ${durationSeconds}s")
    }

    fun recordSkipFailed(reason: String) {
        prefs.edit().putInt(KEY_SKIPS_FAILED, skipsFailed + 1).apply()
        appendLogEntry("Skip failed - $reason")
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun appendLogEntry(message: String) {
        val entry = timeFormat.format(java.util.Date()) + " - " + message
        val updatedLog = (listOf(entry) + recentLog()).take(MAX_LOG_ENTRIES)
        prefs.edit().putString(KEY_LOG, updatedLog.joinToString("\n")).apply()
    }

    companion object {
        private const val PREFS_NAME = "adblocker_podcast_stats"
        private const val KEY_SKIPS_PERFORMED = "skips_performed"
        private const val KEY_SKIPS_FAILED = "skips_failed"
        private const val KEY_LOG = "recent_log"
        private const val MAX_LOG_ENTRIES = 50
    }
}
