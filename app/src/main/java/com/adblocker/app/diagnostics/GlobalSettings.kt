package com.adblocker.app.diagnostics

import android.content.Context
import android.content.SharedPreferences

/** The one diagnostic-logging on/off toggle shared by all three modules (TikTok,
  * Spotify, Podcast) - kept separate from each module's own SettingsRepository since
  * there's only ever one DiagnosticLog file for the whole app, not one per module. */
class GlobalSettings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var isDiagnosticLoggingEnabled: Boolean
        get() = prefs.getBoolean(KEY_DIAGNOSTIC_LOGGING_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_DIAGNOSTIC_LOGGING_ENABLED, value).apply()

    companion object {
        private const val PREFS_NAME = "adblocker_global"
        private const val KEY_DIAGNOSTIC_LOGGING_ENABLED = "diagnostic_logging_enabled"
    }
}
