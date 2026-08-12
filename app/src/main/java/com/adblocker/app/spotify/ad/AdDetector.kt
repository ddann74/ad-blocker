package com.adblocker.app.spotify.ad

enum class SkipOutcome { TAPPED, BLOCKED_DISABLED, CONTROL_NOT_FOUND }
enum class DownloadOutcome { TAPPED, CONTROL_NOT_FOUND }

data class AdEvaluation(val isAdPlaying: Boolean, val matchedKeyword: String?)

/**
 * Pure decision logic - no Android dependencies, directly unit-testable.
 * SpotifyAdSkipService turns the current screen into a flat list of on-screen text
 * strings and calls [evaluate]; this only decides whether that looks like an ad.
 * Unlike TikTok's FilterEngine, there's no "preloaded next item" to scope away from -
 * Spotify's ad/now-playing screen is a single, non-scrolling view, so the raw text
 * list is already exactly what's on screen.
 */
object AdDetector {
    fun evaluate(
        screenTexts: List<String>,
        adKeywordsEnabled: Boolean,
        adKeywords: List<String>
    ): AdEvaluation {
        if (!adKeywordsEnabled) return AdEvaluation(false, null)
        val matchedKeyword = adKeywords.firstOrNull { keyword ->
            keyword.isNotBlank() && screenTexts.any { it.contains(keyword, ignoreCase = true) }
        }
        return AdEvaluation(matchedKeyword != null, matchedKeyword)
    }
}
