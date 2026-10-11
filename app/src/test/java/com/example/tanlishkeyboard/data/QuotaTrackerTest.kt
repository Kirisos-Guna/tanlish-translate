package com.example.tanlishkeyboard.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuotaTrackerTest {

    private val store = MemoryKeyValueStore()
    private val quota = QuotaTracker(store)
    private val now = 1_800_000_000L

    @Test
    fun `unknown remaining means requests are allowed`() {
        assertNull(quota.effectiveRemaining(now))
        assertFalse(quota.isExhausted(now))
        assertNull(quota.badge(now))
    }

    @Test
    fun `server reported remaining is remembered across instances`() {
        quota.update(7, now + 3600)

        assertEquals(7, QuotaTracker(store).effectiveRemaining(now))
        assertEquals("AI 7", quota.badge(now))
        assertFalse(quota.isExhausted(now))
    }

    @Test
    fun `zero remaining blocks until the window resets`() {
        quota.update(0, now + 120)

        assertTrue(quota.isExhausted(now))
        assertEquals("AI off", quota.badge(now))
        assertTrue(quota.resetHint(now).contains("2 min"))
    }

    @Test
    fun `an expired window forgets the old count`() {
        quota.update(0, now - 1)

        assertNull(quota.effectiveRemaining(now))
        assertFalse(quota.isExhausted(now))
        assertNull(quota.effectiveRemaining(now))
    }

    @Test
    fun `exhaustion without a reset time still blocks`() {
        store.putString("quota_remaining", "0")
        store.putString("quota_reset_at", null)

        assertTrue(quota.isExhausted(now))
    }

    @Test
    fun `a spent budget in one account leaves the other one alone`() {
        val typed = QuotaTracker(store)
        val spoken = QuotaTracker(store, prefix = "spoken_")

        typed.update(0, now + 3600)

        assertTrue(typed.isExhausted(now))
        assertEquals("speaking must not be blocked by a spent typed quota", false, spoken.isExhausted(now))
    }

    @Test
    fun `noteExhausted falls back to a one hour window`() {
        quota.noteExhausted(now, null)

        assertTrue(quota.isExhausted(now))
        assertEquals(0, quota.effectiveRemaining(now))
        assertTrue(quota.resetHint(now).contains("60 min"))
    }
}
