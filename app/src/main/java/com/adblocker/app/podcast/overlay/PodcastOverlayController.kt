package com.adblocker.app.podcast.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import com.adblocker.app.R
import com.adblocker.app.diagnostics.DiagnosticLog
import com.adblocker.app.podcast.PodcastSettingsRepository

/** Same TYPE_ACCESSIBILITY_OVERLAY pattern as the TikTok/Spotify modules' overlay
  * controllers - a single "Skip Ns" button whose label reflects the currently
  * configured duration, so it's always clear what a tap is about to do. */
class PodcastOverlayController(
    private val service: AccessibilityService,
    private val settings: PodcastSettingsRepository,
    private val diagnosticLog: DiagnosticLog,
    private val onSkipTapped: () -> Unit
) {
    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null

    val isShowing: Boolean get() = overlayView != null

    fun show() {
        if (overlayView != null) {
            updateLabel()
            return
        }
        val view = LayoutInflater.from(service).inflate(R.layout.overlay_podcast_skip_button, null)
        view.findViewById<Button>(R.id.overlaySkipButton).setOnClickListener { onSkipTapped() }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = 24
            y = 160
        }

        try {
            windowManager.addView(view, params)
            overlayView = view
            updateLabel()
            diagnosticLog.log("PODCAST/OVERLAY", "shown")
        } catch (e: Exception) {
            diagnosticLog.logError("PODCAST/OVERLAY", "failed to show", e)
        }
    }

    private fun updateLabel() {
        overlayView?.findViewById<Button>(R.id.overlaySkipButton)?.text = "Skip ${settings.skipDurationSeconds}s"
    }

    fun hide() {
        val view = overlayView ?: return
        overlayView = null
        try {
            windowManager.removeView(view)
            diagnosticLog.log("PODCAST/OVERLAY", "hidden")
        } catch (e: Exception) {
            diagnosticLog.logError("PODCAST/OVERLAY", "failed to hide (view may already be gone)", e)
        }
    }
}
