package com.adblocker.app.tiktok

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.adblocker.app.diagnostics.DiagnosticLog
import com.adblocker.app.diagnostics.GlobalSettings
import com.adblocker.app.tiktok.filter.FilterEngine
import com.adblocker.app.tiktok.filter.SkipReason
import com.adblocker.app.tiktok.overlay.OverlayController
import com.adblocker.app.tiktok.overlay.TransientBannerOverlay
import com.adblocker.app.tiktok.tiktokactions.DownloadMode
import com.adblocker.app.tiktok.tiktokactions.TikTokActionCoordinator

/**
 * Reads whatever text TikTok is currently rendering (via the accessibility tree) and, if
 * it looks like an ad or a blocked creator, dispatches a swipe-up gesture to skip past it -
 * the same mechanism a real finger swipe uses, since there's no official API for either
 * "is this an ad" or "skip this video". Also shows the floating Block/Download buttons
 * while TikTok is in front, and drives whichever multi-tap TikTok automation (real Block,
 * Download) is currently in flight via [TikTokActionCoordinator]. This only ever acts on
 * the configured target package(s) and never reads or acts on anything outside them.
 */
class TikTokFilterService : AccessibilityService() {

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var statsRepository: StatsRepository
    private lateinit var diagnosticLog: DiagnosticLog
    private lateinit var actionCoordinator: TikTokActionCoordinator
    private lateinit var overlayController: OverlayController
    private lateinit var bannerOverlay: TransientBannerOverlay
    private var lastSkipMillis: Long = 0L
    // Every video fingerprint ever auto-skipped this session (see performSkipGesture's call
    // site) - not just the most recent one. A single "last skipped" slot meant scrolling
    // BACKWARD past that one video to an earlier ad/blocked-creator post (one already
    // skipped once before, further back in the feed) looked "new" again and got
    // re-skipped, yanking the feed forward out from under a deliberate manual scroll-back.
    // Tracking the whole set means once a video's been skipped, revisiting it - forward or
    // backward - never re-triggers a skip. Bounded so a long scrolling session can't grow
    // this unboundedly.
    //
    // Keyed by FilterEngine.videoFingerprint (the video's own full text block), NOT
    // extractHandle's creator-only identity - a real bug this fixes: using the creator's
    // name alone as the key meant that once ONE video from a blocked creator was skipped,
    // every OTHER video from that same creator produced the identical key and was wrongly
    // treated as "already skipped, leave alone" - silently breaking blocking after the
    // first skip. A fingerprint that includes the video's own caption/stats, not just the
    // creator's name, tells different videos from the same creator apart while still
    // recognizing a genuine revisit to the exact same video.
    private val skippedVideoFingerprints = LinkedHashSet<String>()
    // Same dedup shape as above, but for Subject Boost's auto-like - without it, a video
    // that lingers on screen across multiple accessibility events (normal - nothing here
    // forces it to move on) would get an attempted like on every single one of those
    // events, not just once. Also fixed to key on the video fingerprint rather than just
    // the creator's name, for the identical reason as above - otherwise a second, different
    // subject-matching video from the same creator would be wrongly skipped for auto-like.
    private val autoLikedVideoFingerprints = LinkedHashSet<String>()
    // The fingerprint of whichever video [logFilterOncePerVideo] most recently actually
    // wrote a line for - lets repeated "no match"/"skip suppressed" evaluations of one
    // unchanged video collapse to a single log entry instead of one per accessibility
    // event (TikTok fires typeWindowContentChanged far more often than the visible video
    // actually changes - view counters, etc.). See logFilterOncePerVideo's doc.
    private var lastLoggedFilterFingerprint: String? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    // A single reusable Runnable so scheduling it again (or cancelling it) always
    // targets every currently-pending instance - see the debounce comment below.
    private val hideOverlayRunnable = Runnable { overlayController.hide() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        settingsRepository = SettingsRepository(this)
        statsRepository = StatsRepository(this)
        val globalSettings = GlobalSettings(this)
        diagnosticLog = DiagnosticLog(this) { globalSettings.isDiagnosticLoggingEnabled }
        bannerOverlay = TransientBannerOverlay(this)
        actionCoordinator = TikTokActionCoordinator(this, settingsRepository, statsRepository, diagnosticLog, bannerOverlay)
        overlayController = OverlayController(
            service = this,
            diagnosticLog = diagnosticLog,
            onBlockTapped = { handleOverlayBlockTapped() },
            onDownloadVideoTapped = { handleOverlayDownloadTapped(DownloadMode.VIDEO_ONLY) },
            onDownloadAudioTapped = { handleOverlayDownloadTapped(DownloadMode.AUDIO_ONLY) }
        )
        diagnosticLog.log("TIKTOK/SERVICE", "onServiceConnected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val eventPackageName = event?.packageName?.toString() ?: return

        // The overlay's own buttons are drawn in a window that belongs to this app, so
        // interacting with them (or the window just redrawing) can itself generate an
        // accessibility event tagged with this app's package - reacting to that as "the
        // user left TikTok" would hide the overlay out from under the tap that's still
        // landing on it. Never a real signal either way, so just ignore it.
        if (eventPackageName == this.packageName) return

        if (eventPackageName !in settingsRepository.targetPackages) {
            // Don't hide the instant a single event from some other package shows up -
            // system UI, a keyboard, a notification icon updating, etc. can all fire one
            // while TikTok is still genuinely in front. A real switch away from TikTok is
            // followed by silence from TikTok's own events, so a short delayed hide -
            // cancelled below the moment a TikTok event arrives - tells the two apart
            // without visibly flashing the overlay for every unrelated event in between.
            mainHandler.postDelayed(hideOverlayRunnable, OVERLAY_HIDE_DELAY_MILLIS)
            return
        }

        val root = rootInActiveWindow
        // A real diagnostic log caught this: some OEM launchers' Recents/task-switcher
        // screens report the underlying app's real package on the accessibility EVENT
        // even while the actually-visible, actually-readable content is the launcher's
        // own UI (a live preview/snapshot card of that app's last window) - the log
        // showed dozens of filter evaluations against a full home-screen app-drawer dump
        // (Clock, WhatsApp, ..., "Close all", "5.08 GB available") logged under a
        // TikTok-tagged event, with the floating overlay shown the whole time. The
        // event's own packageName lied; rootInActiveWindow's packageName - which
        // reflects the window whose content is actually about to be read - is the more
        // trustworthy signal here. If they disagree, this isn't really TikTok on screen,
        // so treat it exactly like leaving the app: hide the overlay, evaluate nothing.
        if (root == null || root.packageName?.toString() !in settingsRepository.targetPackages) {
            mainHandler.postDelayed(hideOverlayRunnable, OVERLAY_HIDE_DELAY_MILLIS)
            return
        }

        mainHandler.removeCallbacks(hideOverlayRunnable)
        if (settingsRepository.isOverlayEnabled) overlayController.show() else overlayController.hide()

        // In-flight Block/Download automations advance independent of the skip cooldown
        // below - they're triggered by a deliberate tap, not a per-video reaction, and
        // have their own timeout (see TikTokActionCoordinator).
        actionCoordinator.onScreenUpdated(root)

        // Auto-skip (ad/blocked-creator) must never fire while a Block/Download
        // tap sequence is still working on the current video - swiping away mid-sequence
        // would pull the video out from under it, which can make a multi-tap automation
        // fail outright or, worse, land its next tap on whatever video auto-skip moved to
        // instead. onScreenUpdated above may have just completed the pending sequence on
        // this very event, in which case hasPendingAction is already false again and
        // auto-skip resumes normally starting next event - this only pauses it mid-flight.
        if (actionCoordinator.hasPendingAction) {
            @Suppress("DEPRECATION")
            root.recycle()
            diagnosticLog.log("TIKTOK/FILTER", "auto-skip paused - Block/Download automation in progress")
            return
        }

        // Right after a skip, TikTok is still loading/animating in the next video - reading
        // the screen during that window would evaluate a half-rendered video (or the one we
        // just skipped past) and could trigger a second, unwanted skip.
        val now = System.currentTimeMillis()
        if (now - lastSkipMillis < COOLDOWN_MILLIS) {
            @Suppress("DEPRECATION")
            root.recycle()
            return
        }

        val texts = mutableListOf<String>()
        collectText(root, texts)

        // Computed once, up front, so every log line below about "this video" (no match /
        // suppressed / live-skip-disabled) can be throttled to once per DISTINCT video
        // rather than once per accessibility event. Real diagnostic logs showed why this
        // matters: TikTok fires typeWindowContentChanged repeatedly (view counters, etc.)
        // even while the user sits on one unchanged video reading its comments - at
        // ~300ms/event that filled the 512KB log cap with ~380 duplicate lines of the
        // SAME video in under 2 minutes, twice in a row, trimming away whatever happened
        // right before the log was pulled - which is exactly the moment worth seeing.
        val videoFingerprint = FilterEngine.videoFingerprint(texts) ?: texts.firstOrNull()

        val isLive = FilterEngine.isLiveStream(texts, settingsRepository.liveIndicatorKeywords)

        val decision = FilterEngine.evaluate(
            screenTexts = texts,
            adKeywordsEnabled = settingsRepository.isAdSkipEnabled,
            adKeywords = settingsRepository.adKeywords,
            blockedCreatorsEnabled = settingsRepository.isBlockedCreatorSkipEnabled,
            blockedCreators = settingsRepository.blockedCreators.toSet()
        )
        if (decision == null) {
            logFilterOncePerVideo(videoFingerprint, "no match - live=$isLive - texts=$texts")
            // Subject Boost never skips - it only ever adds a positive signal (auto-like)
            // on top of otherwise-normal browsing, so it's only relevant once we already
            // know this video isn't being skipped for an unrelated reason (ad/blocked
            // creator) above. root is still alive here (not yet recycled) since the
            // auto-like tap needs it, unlike the skip path below which never touches root
            // again once it's decided to skip.
            attemptSubjectBoost(root, texts, isLive)
            @Suppress("DEPRECATION")
            root.recycle()
            return
        }
        @Suppress("DEPRECATION")
        root.recycle()
        // A Live room is a different kind of screen than a normal video - swiping away
        // from one is gated by its own toggle (default on) rather than assuming the
        // same behavior as skipping a video is always wanted here too.
        if (isLive && !settingsRepository.isLiveStreamSkipEnabled) {
            logFilterOncePerVideo(videoFingerprint, "${decision.reason} matched \"${decision.detail}\" on a Live stream but live-skip is disabled - texts=$texts")
            return
        }
        // videoFingerprint (computed above) identifies the specific video on screen, not
        // just its creator - see FilterEngine.videoFingerprint's doc for why that
        // distinction matters here. Covers two cases: TikTok is still transitioning out
        // the video we just skipped (would otherwise read as a "new" duplicate decision),
        // AND the user has manually scrolled back to a video that was already skipped
        // earlier in this session - either way, it's already been acted on once and
        // shouldn't be yanked away again out from under a deliberate scroll-back.
        // Critically, a DIFFERENT video from the same blocked creator produces a different
        // fingerprint, so it still gets skipped - this is what makes blocking an account
        // actually stay in effect past the first hit.
        if (videoFingerprint != null && videoFingerprint in skippedVideoFingerprints) {
            logFilterOncePerVideo(videoFingerprint, "skip suppressed - this video was already skipped earlier (still transitioning, or you scrolled back to it) - texts=$texts")
            return
        }
        // A genuinely new skip decision - always logged (never throttled), since by
        // definition this only happens once per video anyway (it's added to
        // skippedVideoFingerprints immediately below, so every later evaluation of this
        // same video hits the suppressed branch above instead).
        diagnosticLog.log("TIKTOK/FILTER", "${decision.reason} matched \"${decision.detail}\" - live=$isLive - texts=$texts")

        lastSkipMillis = now
        if (videoFingerprint != null) {
            skippedVideoFingerprints.add(videoFingerprint)
            if (skippedVideoFingerprints.size > MAX_TRACKED_SKIPPED_VIDEOS) {
                skippedVideoFingerprints.remove(skippedVideoFingerprints.first())
            }
        }
        statsRepository.recordSkip(decision)
        // Fires at the exact moment this app dispatches a swipe - the point of this is
        // attribution, not notification: without it, an unexpected scroll gives no way to
        // tell "that was this app" from "that was TikTok's own autoplay, another
        // accessibility-capable app (Tasker/AutoInput/MacroDroid etc. are common), or a
        // stray touch" without digging through the Activity/Diagnostic logs afterward. A
        // toast right as it happens removes that guesswork entirely.
        val skipLabel = when (decision.reason) {
            SkipReason.AD -> "Skipped ad (\"${decision.detail}\")"
            SkipReason.BLOCKED_CREATOR -> "Skipped blocked creator (${decision.detail})"
        }
        bannerOverlay.show(skipLabel)
        performSkipGesture()
    }

    /** Writes [message] only if [fingerprint] differs from whichever video this last
      * actually logged for - collapses "no match"/"skip suppressed" spam on one
      * unchanged video down to a single entry instead of one per accessibility event.
      * Real diagnostic logs showed why this matters: TikTok fires typeWindowContentChanged
      * far more often than the visible video actually changes, which filled the 512KB
      * log cap with ~380 duplicate lines of the same video in under two minutes, twice in
      * a row - trimming away whatever happened right before the log was pulled, which is
      * exactly the moment worth seeing. A null fingerprint (couldn't build one at all)
      * always logs, since there's no video identity to dedupe against. */
    private fun logFilterOncePerVideo(fingerprint: String?, message: String) {
        if (fingerprint != null && fingerprint == lastLoggedFilterFingerprint) return
        lastLoggedFilterFingerprint = fingerprint
        diagnosticLog.log("TIKTOK/FILTER", message)
    }

    /** Subject Boost: if enabled and the current video's on-screen text matches a
      * configured subject, auto-likes it as a positive engagement signal - see
      * SettingsRepository.isSubjectBoostEnabled's doc for why this replaced the old
      * force-skip Subject Filter. Skipped entirely on a Live room: its layout differs
      * enough from a normal video that the same Like-button keyword search is more
      * likely to mis-tap something else, and Live rooms don't have the same caption/
      * hashtag text a subject match would normally be based on anyway. */
    private fun attemptSubjectBoost(root: AccessibilityNodeInfo, texts: List<String>, isLive: Boolean) {
        if (isLive || !settingsRepository.isSubjectBoostEnabled) return
        if (!FilterEngine.matchesSubject(texts, settingsRepository.subjectKeywords)) return
        val videoFingerprint = FilterEngine.videoFingerprint(texts) ?: texts.firstOrNull()
        if (videoFingerprint != null && videoFingerprint in autoLikedVideoFingerprints) return
        if (videoFingerprint != null) {
            autoLikedVideoFingerprints.add(videoFingerprint)
            if (autoLikedVideoFingerprints.size > MAX_TRACKED_SKIPPED_VIDEOS) {
                autoLikedVideoFingerprints.remove(autoLikedVideoFingerprints.first())
            }
        }
        diagnosticLog.log("TIKTOK/SUBJECT_BOOST", "subject match - attempting auto-like - texts=$texts")
        actionCoordinator.attemptLikeCurrentVideo(root)
    }

    override fun onInterrupt() {
        diagnosticLog.log("TIKTOK/SERVICE", "onInterrupt")
        mainHandler.removeCallbacks(hideOverlayRunnable)
        overlayController.hide()
    }

    /** The overlay button click arrives outside the normal event flow, so this reads a
      * fresh snapshot of the current screen itself rather than relying on state left
      * over from the last onAccessibilityEvent call. */
    private fun handleOverlayBlockTapped() {
        val root = rootInActiveWindow
        if (root == null) {
            statsRepository.recordEvent("Block tapped but no screen content was available")
            diagnosticLog.log("TIKTOK/OVERLAY", "Block tapped, rootInActiveWindow was null")
            bannerOverlay.show("Couldn't read the screen - try tapping Block again")
            return
        }
        val texts = mutableListOf<String>()
        collectText(root, texts)
        @Suppress("DEPRECATION")
        root.recycle()

        val handle = FilterEngine.extractHandle(texts)
        if (handle == null) {
            statsRepository.recordEvent("Block tapped but couldn't identify the current creator's handle")
            diagnosticLog.log("TIKTOK/OVERLAY", "Block tapped, no handle found - texts=$texts")
            bannerOverlay.show("Couldn't identify this video's creator - try again from directly on the video")
            return
        }
        val isLive = FilterEngine.isLiveStream(texts, settingsRepository.liveIndicatorKeywords)
        diagnosticLog.log("TIKTOK/OVERLAY", "Block tapped for $handle (live=$isLive)")
        actionCoordinator.startBlockCurrentCreator(handle, isLive)
    }

    /** Same fresh-snapshot approach as [handleOverlayBlockTapped] - Download needs to know
      * whether the current screen is a Live room before starting the tap sequence, since
      * there's no video file to save from a live broadcast (see
      * TikTokActionCoordinator.startDownloadCurrentVideo). [mode] reflects which of the
      * overlay's Video/Audio choice buttons was tapped. */
    private fun handleOverlayDownloadTapped(mode: DownloadMode) {
        val root = rootInActiveWindow
        if (root == null) {
            statsRepository.recordEvent("Download tapped but no screen content was available")
            diagnosticLog.log("TIKTOK/OVERLAY", "Download tapped, rootInActiveWindow was null")
            bannerOverlay.show("Couldn't read the screen - try tapping Download again")
            return
        }
        val texts = mutableListOf<String>()
        collectText(root, texts)
        @Suppress("DEPRECATION")
        root.recycle()

        val isLive = FilterEngine.isLiveStream(texts, settingsRepository.liveIndicatorKeywords)
        diagnosticLog.log("TIKTOK/OVERLAY", "Download tapped, mode=$mode (live=$isLive)")
        actionCoordinator.startDownloadCurrentVideo(mode, isLive)
    }

    /** Depth-first collection of every text/contentDescription string in the current
      * window - the closest available substitute for "what does this screen say",
      * since accessibility nodes don't expose anything richer than that. */
    private fun collectText(node: AccessibilityNodeInfo?, out: MutableList<String>, depth: Int = 0) {
        if (node == null || depth > MAX_TREE_DEPTH) return
        node.text?.toString()?.let { if (it.isNotBlank()) out.add(it) }
        node.contentDescription?.toString()?.let { if (it.isNotBlank()) out.add(it) }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            collectText(child, out, depth + 1)
            @Suppress("DEPRECATION")
            child?.recycle()
        }
    }

    /** A swipe from just below center to just above it, matching how TikTok itself expects
      * a "next video" gesture - vertical, roughly half the screen's height, quick. */
    private fun performSkipGesture() {
        val metrics = resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val startY = metrics.heightPixels * 0.75f
        val endY = metrics.heightPixels * 0.25f

        val path = Path().apply {
            moveTo(x, startY)
            lineTo(x, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, SWIPE_DURATION_MILLIS))
            .build()
        dispatchGesture(gesture, null, null)
    }

    companion object {
        private const val COOLDOWN_MILLIS = 900L
        private const val SWIPE_DURATION_MILLIS = 250L
        private const val MAX_TREE_DEPTH = 60
        private const val OVERLAY_HIDE_DELAY_MILLIS = 800L
        // Generous cap on how many skipped-video identities are remembered per session -
        // large enough that no normal scrolling session would hit it, just a backstop
        // against unbounded growth if TikTok is left open for a very long time.
        private const val MAX_TRACKED_SKIPPED_VIDEOS = 500
    }
}
