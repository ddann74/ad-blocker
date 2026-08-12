package com.adblocker.app.podcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SkipDurationLimitsTest {

    @Test
    fun `parses a plain valid number`() {
        assertEquals(45, SkipDurationLimits.parseSeconds("45"))
    }

    @Test
    fun `trims surrounding whitespace`() {
        assertEquals(30, SkipDurationLimits.parseSeconds("  30  "))
    }

    @Test
    fun `rejects non-numeric input`() {
        assertNull(SkipDurationLimits.parseSeconds("thirty"))
    }

    @Test
    fun `rejects empty input`() {
        assertNull(SkipDurationLimits.parseSeconds(""))
    }

    @Test
    fun `rejects zero - below the minimum`() {
        assertNull(SkipDurationLimits.parseSeconds("0"))
    }

    @Test
    fun `rejects a negative number`() {
        assertNull(SkipDurationLimits.parseSeconds("-5"))
    }

    @Test
    fun `accepts the minimum boundary`() {
        assertEquals(SkipDurationLimits.MIN_SECONDS, SkipDurationLimits.parseSeconds(SkipDurationLimits.MIN_SECONDS.toString()))
    }

    @Test
    fun `accepts the maximum boundary`() {
        assertEquals(SkipDurationLimits.MAX_SECONDS, SkipDurationLimits.parseSeconds(SkipDurationLimits.MAX_SECONDS.toString()))
    }

    @Test
    fun `rejects a value one above the maximum - guards against a typo like 3000 meant as 30`() {
        assertNull(SkipDurationLimits.parseSeconds((SkipDurationLimits.MAX_SECONDS + 1).toString()))
    }
}
