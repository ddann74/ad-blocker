package com.adblocker.app.podcast

import android.media.session.MediaController
import android.media.session.PlaybackState

/**
 * Holds whichever MediaController currently belongs to a target podcast app (updated by
 * PodcastMediaListenerService as active sessions change) and turns "skip forward N
 * seconds" into a real MediaController.getTransportControls().seekTo() call - the same
 * official control surface Android Auto / Bluetooth media buttons use, not a guessed UI
 * tap. A plain object (not a bound service) since PodcastMediaListenerService and
 * PodcastAddictSkipService are two independent services that both need access to the
 * same current controller without binding to each other.
 */
object PodcastPlaybackBridge {
    @Volatile
    private var controller: MediaController? = null

    fun setController(newController: MediaController?) {
        controller = newController
    }

    fun clearController() {
        controller = null
    }

    sealed class SkipResult {
        data class Skipped(val fromPositionMillis: Long, val toPositionMillis: Long) : SkipResult()
        object NoActiveSession : SkipResult()
        object SeekNotSupported : SkipResult()
        object PositionUnknown : SkipResult()
    }

    /** Attempts to seek the current target-app session forward by [durationMillis].
      * Every failure mode is reported explicitly rather than silently doing nothing -
      * see PRD.md's "Honest limit" for why this matters here specifically: unlike
      * TikTok/Spotify's keyword matching, there's no fallback heuristic if this doesn't
      * work, so the caller needs to know exactly which of "no session", "can't seek",
      * or "don't know where we are" actually happened. */
    fun skipForward(durationMillis: Long): SkipResult {
        val activeController = controller ?: return SkipResult.NoActiveSession
        val playbackState = activeController.playbackState ?: return SkipResult.SeekNotSupported
        if (playbackState.actions and PlaybackState.ACTION_SEEK_TO == 0L) return SkipResult.SeekNotSupported
        val currentPosition = playbackState.position
        if (currentPosition < 0) return SkipResult.PositionUnknown
        val targetPosition = currentPosition + durationMillis
        activeController.transportControls.seekTo(targetPosition)
        return SkipResult.Skipped(currentPosition, targetPosition)
    }
}
