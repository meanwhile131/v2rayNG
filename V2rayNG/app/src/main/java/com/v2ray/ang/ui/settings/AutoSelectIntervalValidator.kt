package com.v2ray.ang.ui.settings

/**
 * Normalizes the auto-select health check interval so only a value that can actually schedule a
 * check is persisted.
 */
internal object AutoSelectIntervalValidator {

    /** One day, matching the clamp applied when the interval is read for scheduling. */
    const val MAX_MINUTES = 1440

    /**
     * @param value Raw user input.
     * @return The whole-minute value as a string, or null when the input is not a usable interval.
     */
    fun normalize(value: String): String? {
        val minutes = value.trim().toIntOrNull() ?: return null
        if (minutes !in 1..MAX_MINUTES) return null
        return minutes.toString()
    }
}