package com.adblocker.app.spotify.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import com.adblocker.app.R
import com.adblocker.app.diagnostics.DiagnosticLog

/** Near-identical to the TikTok module's OverlayController, but a single Download
  * button only - Spotify's module has nothing equivalent to TikTok's Block action. */
class OverlayController(
    private val service: AccessibilityService,
    private val diagnosticLog: DiagnosticLog,
    private val onDownloadTapped: () -> Unit
) {
    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null

    val isShowing: Boolean get() = overlayView != null

    fun show() {
        if (overlayView != null) return

        val view = LayoutInflater.from(service).inflate(R.layout.overlay_download_button_spotify, null)
        view.findViewById<View>(R.id.overlayDownloadButton).setOnClickListener { onDownloadTapped() }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = 16
        }

        try {
            windowManager.addView(view, params)
            overlayView = view
            diagnosticLog.log("SPOTIFY/OVERLAY", "shown")
        } catch (e: Exception) {
            diagnosticLog.logError("SPOTIFY/OVERLAY", "failed to show", e)
        }
    }

    fun hide() {
        val view = overlayView ?: return
        overlayView = null
        try {
            windowManager.removeView(view)
            diagnosticLog.log("SPOTIFY/OVERLAY", "hidden")
        } catch (e: Exception) {
            diagnosticLog.logError("SPOTIFY/OVERLAY", "failed to hide (view may already be gone)", e)
        }
    }
}
