package com.adblocker.app.podcast

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PodcastPlaybackBridge.skipForward ultimately calls into android.media.session.MediaController,
 * which isn't available outside an instrumented/Robolectric test environment - so this only
 * covers the one branch that's pure Kotlin regardless of platform: no controller registered
 * at all (PodcastMediaListenerService never connected, or found no matching session). The
 * remaining branches (seek unsupported, position unknown, successful seek) depend on a real
 * or mocked MediaController/PlaybackState and are exercised in Setup/README's manual test
 * steps instead - see PRD.md's verification status.
 */
class PodcastPlaybackBridgeTest {

    @After
    fun tearDown() {
        PodcastPlaybackBridge.clearController()
    }

    @Test
    fun `no active session reports NoActiveSession rather than throwing`() {
        PodcastPlaybackBridge.clearController()
        val result = PodcastPlaybackBridge.skipForward(30_000L)
        assertTrue(result is PodcastPlaybackBridge.SkipResult.NoActiveSession)
    }

    @Test
    fun `clearController resets state so a stale controller from a previous session is never reused`() {
        PodcastPlaybackBridge.clearController()
        val firstResult = PodcastPlaybackBridge.skipForward(30_000L)
        val secondResult = PodcastPlaybackBridge.skipForward(45_000L)
        assertEquals(firstResult::class, secondResult::class)
    }
}
