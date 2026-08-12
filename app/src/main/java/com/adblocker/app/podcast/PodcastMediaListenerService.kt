package com.adblocker.app.podcast

import android.content.ComponentName
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.service.notification.NotificationListenerService
import com.adblocker.app.diagnostics.DiagnosticLog
import com.adblocker.app.diagnostics.GlobalSettings

/**
 * The standard public Android API path to read another app's active MediaSession:
 * NotificationListenerService's own component, once the user grants it "Notification
 * access" in Settings, is allowed to call MediaSessionManager.getActiveSessions() -
 * this is not a system/signature permission, just a separate user grant from the
 * accessibility one the other two modules use. This service never reads notification
 * *content* despite the base class name; it only uses the listener-connection status
 * as a key to unlock getActiveSessions(), then hands the matching MediaController to
 * [PodcastPlaybackBridge] for PodcastAddictSkipService's overlay button to use.
 */
class PodcastMediaListenerService : NotificationListenerService() {

    private lateinit var mediaSessionManager: MediaSessionManager
    private lateinit var diagnosticLog: DiagnosticLog
    private lateinit var settings: PodcastSettingsRepository

    private val sessionsChangedListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        updateActiveController(controllers ?: emptyList())
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        val globalSettings = GlobalSettings(this)
        diagnosticLog = DiagnosticLog(this) { globalSettings.isDiagnosticLoggingEnabled }
        settings = PodcastSettingsRepository(this)
        mediaSessionManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        val componentName = ComponentName(this, PodcastMediaListenerService::class.java)
        mediaSessionManager.addOnActiveSessionsChangedListener(sessionsChangedListener, componentName)
        updateActiveController(mediaSessionManager.getActiveSessions(componentName))
        diagnosticLog.log("PODCAST/MEDIA", "listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        mediaSessionManager.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        PodcastPlaybackBridge.clearController()
        diagnosticLog.log("PODCAST/MEDIA", "listener disconnected")
    }

    private fun updateActiveController(controllers: List<MediaController>) {
        val target = settings.targetPackages
        val match = controllers.firstOrNull { it.packageName in target }
        PodcastPlaybackBridge.setController(match)
        diagnosticLog.log(
            "PODCAST/MEDIA",
            if (match != null) "active media session found: ${match.packageName}"
            else "no active media session for target package(s) $target (${controllers.size} other session(s) active)"
        )
    }
}
