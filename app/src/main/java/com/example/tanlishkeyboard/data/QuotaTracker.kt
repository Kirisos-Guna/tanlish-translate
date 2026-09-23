package com.example.tanlishkeyboard.data

/**
 * The AI endpoint allows a small number of requests per hour (currently around 15) and reports how
 * many are left in every reply. We remember that so the keyboard can show an honest badge and stop
 * asking once the window is used up.
 */
class QuotaTracker(private val store: KeyValueStore) {

    /** Requests left, or null when the server has not told us yet (or the window has rolled over). */
    fun effectiveRemaining(now: Long): Int? {
        val until = resetAtEpochSeconds()
        if (until != null && until <= now) {
            store.putString(KEY_REMAINING, null)
            store.putString(KEY_RESET_AT, null)
            return null
        }
        return store.getString(KEY_REMAINING)?.toIntOrNull()
    }

    fun update(remaining: Int?, resetAtEpochSeconds: Long?) {
        store.putString(KEY_REMAINING, remaining?.toString())
        store.putString(KEY_RESET_AT, resetAtEpochSeconds?.toString())
    }

    fun isExhausted(now: Long): Boolean {
        val remaining = effectiveRemaining(now) ?: return false
        if (remaining > 0) return false
        val until = resetAtEpochSeconds() ?: return true
        return until > now
    }

    /** Called when the server refuses a request because the hourly budget is gone. */
    fun noteExhausted(now: Long, resetAtEpochSeconds: Long?) {
        update(0, resetAtEpochSeconds ?: (now + DEFAULT_WINDOW_SECONDS))
    }

    fun resetHint(now: Long): String {
        val until = resetAtEpochSeconds() ?: return "Try again in about an hour."
        val minutes = ((until - now + 59) / 60).coerceAtLeast(1)
        return "Resets in about $minutes min."
    }

    /** Label for the AI key badge, or null when the remaining count is unknown. */
    fun badge(now: Long): String? {
        val remaining = effectiveRemaining(now) ?: return null
        return if (isExhausted(now)) "AI off" else "AI $remaining"
    }

    private fun resetAtEpochSeconds(): Long? = store.getString(KEY_RESET_AT)?.toLongOrNull()

    private companion object {
        const val KEY_REMAINING = "quota_remaining"
        const val KEY_RESET_AT = "quota_reset_at"
        const val DEFAULT_WINDOW_SECONDS = 3600L
    }
}
