package com.adblocker.app.podcast

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.adblocker.app.diagnostics.DiagnosticLog
import com.adblocker.app.diagnostics.GlobalSettings
import com.adblocker.app.podcast.overlay.PodcastOverlayController

/**
 * Shows a floating "Skip Ns" button while Podcast Addict is in the foreground - unlike
 * the TikTok/Spotify services, this never reads on-screen text or evaluates anything;
 * it only tracks which package is currently in front (to show/hide the button) and,
 * on tap, delegates the actual skip to [PodcastPlaybackBridge], which talks to
 * PodcastAddictSkipService's sibling PodcastMediaListenerService via the real
 * MediaSession API. See PRD.md for why this module works this way instead of trying
 * to detect ads.
 */
class PodcastAddictSkipService : AccessibilityService() {

    private lateinit var settings: PodcastSettingsRepository
    private lateinit var stats: PodcastStatsRepository
    private lateinit var diagnosticLog: DiagnosticLog
    private lateinit var overlay: PodcastOverlayController
    private var overlayVisible = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val hideOverlayRunnable = Runnable {
        overlay.hide()
        overlayVisible = false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        settings = PodcastSettingsRepository(this)
        stats = PodcastStatsRepository(this)
        val globalSettings = GlobalSettings(this)
        diagnosticLog = DiagnosticLog(this) { globalSettings.isDiagnosticLoggingEnabled }
        overlay = PodcastOverlayController(this, settings, diagnosticLog) { onSkipTapped() }
        diagnosticLog.log("PODCAST/SERVICE", "onServiceConnected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
        if (packageName == this.packageName) return

        if (packageName !in settings.targetPackages) {
            // Same debounced-hide pattern as the TikTok/Spotify overlays - avoids
            // flashing the button away on an unrelated event (system UI, keyboard)
            // while Podcast Addict is still genuinely in front.
            mainHandler.removeCallbacks(hideOverlayRunnable)
            mainHandler.postDelayed(hideOverlayRunnable, OVERLAY_HIDE_DELAY_MILLIS)
            return
        }
        mainHandler.removeCallbacks(hideOverlayRunnable)
        if (settings.isSkipButtonEnabled) {
            overlay.show()
            overlayVisible = true
        } else if (overlayVisible) {
            overlay.hide()
            overlayVisible = false
        }
    }

    private fun onSkipTapped() {
        val durationSeconds = settings.skipDurationSeconds
        when (val result = PodcastPlaybackBridge.skipForward(durationSeconds * 1000L)) {
            is PodcastPlaybackBridge.SkipResult.Skipped -> {
                stats.recordSkip(durationSeconds)
                diagnosticLog.log("PODCAST/SKIP", "skipped ${durationSeconds}s: ${result.fromPositionMillis}ms -> ${result.toPositionMillis}ms")
            }
            PodcastPlaybackBridge.SkipResult.NoActiveSession -> {
                stats.recordSkipFailed("no active media session - is Notification access granted (Setup) and Podcast Addict actually playing?")
                diagnosticLog.log("PODCAST/SKIP", "failed: no active media session")
            }
            PodcastPlaybackBridge.SkipResult.SeekNotSupported -> {
                stats.recordSkipFailed("Podcast Addict's media session doesn't support seeking")
                diagnosticLog.log("PODCAST/SKIP", "failed: seek not supported by this session")
            }
            PodcastPlaybackBridge.SkipResult.PositionUnknown -> {
                stats.recordSkipFailed("current playback position unknown")
                diagnosticLog.log("PODCAST/SKIP", "failed: position unknown")
            }
        }
    }

    override fun onInterrupt() {
        diagnosticLog.log("PODCAST/SERVICE", "onInterrupt")
        mainHandler.removeCallbacks(hideOverlayRunnable)
        overlay.hide()
        overlayVisible = false
    }

    companion object {
        private const val OVERLAY_HIDE_DELAY_MILLIS = 800L
    }
}
