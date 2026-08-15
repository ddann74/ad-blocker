package com.adblocker.app.tiktok.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * A transient, auto-dismissing text banner shown via the same accessibility-overlay
 * window mechanism as the Block/Download buttons (TYPE_ACCESSIBILITY_OVERLAY) - built
 * as a more reliable replacement for Android's system Toast after real-device evidence:
 * a real diagnostic log showed 72 genuine skip decisions in one session, but the user
 * never saw a single toast for any of them. Toast.makeText() calls from a background
 * AccessibilityService (not a foreground Activity) are commonly, silently suppressed by
 * some OEMs - Xiaomi/MIUI in particular requires its own separate "Display pop-up
 * windows while running in the background" permission, off by default, unrelated to the
 * accessibility permission already granted. This app's OWN overlay windows are already
 * confirmed working on the same device (the Block/Download buttons render and are
 * interactive), so routing through that same mechanism instead of Toast sidesteps the
 * whole class of OEM restriction rather than asking the user to hunt down a setting.
 */
class TransientBannerOverlay(private val service: AccessibilityService) {
    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentView: View? = null
    private val removeRunnable = Runnable { removeCurrent() }

    fun show(message: String, durationMillis: Long = DEFAULT_DURATION_MILLIS) {
        removeCurrent()
        val view = TextView(service).apply {
            text = message
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#E6000000"))
            setPadding(36, 22, 36, 22)
            textSize = 13f
            maxWidth = (service.resources.displayMetrics.widthPixels * 0.82f).toInt()
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 140
        }
        try {
            windowManager.addView(view, params)
            currentView = view
            mainHandler.postDelayed(removeRunnable, durationMillis)
        } catch (e: Exception) {
            // Same best-effort convention as OverlayController - some OEMs restrict
            // accessibility overlays further than stock Android; a failed banner should
            // never crash the whole service over a UI extra.
        }
    }

    private fun removeCurrent() {
        mainHandler.removeCallbacks(removeRunnable)
        val view = currentView ?: return
        currentView = null
        try {
            windowManager.removeView(view)
        } catch (e: Exception) {
            // View may already be gone - nothing to clean up.
        }
    }

    companion object {
        private const val DEFAULT_DURATION_MILLIS = 2800L
    }
}
