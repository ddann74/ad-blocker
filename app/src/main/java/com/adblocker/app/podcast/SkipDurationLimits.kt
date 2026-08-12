package com.adblocker.app.podcast

/** Bounds on the user-configurable skip duration - keeps a typo (e.g. "3000" meant as
  * "30") from producing a nonsensical multi-hour seek. Pure/no-Android so it's directly
  * unit-testable alongside the parsing logic that uses it. */
object SkipDurationLimits {
    const val MIN_SECONDS = 1
    const val MAX_SECONDS = 600 // 10 minutes - generous beyond any real ad-read length
    const val DEFAULT_SECONDS = 30

    /** Parses free-form EditText input into a valid skip duration, or null if it isn't
      * a usable positive integer within [MIN_SECONDS, MAX_SECONDS]. Never throws. */
    fun parseSeconds(raw: String): Int? {
        val value = raw.trim().toIntOrNull() ?: return null
        if (value < MIN_SECONDS || value > MAX_SECONDS) return null
        return value
    }
}
