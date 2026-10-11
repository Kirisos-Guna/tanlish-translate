package com.example.tanlishkeyboard.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTest {

    private val store = MemoryKeyValueStore()
    private val settings = Settings(store)

    @Test
    fun `the button starts as see-through as it always was`() {
        assertEquals(Settings.DEFAULT_BUBBLE_OPACITY, settings.bubbleOpacity, 0.001f)
    }

    @Test
    fun `opacity survives a restart`() {
        settings.bubbleOpacity = 0.4f

        assertEquals(0.4f, Settings(store).bubbleOpacity, 0.001f)
    }

    @Test
    fun `opacity is kept inside a usable range`() {
        settings.bubbleOpacity = 0.01f
        assertEquals(Settings.MIN_BUBBLE_OPACITY, settings.bubbleOpacity, 0.001f)

        settings.bubbleOpacity = 5f
        assertEquals(Settings.MAX_BUBBLE_OPACITY, settings.bubbleOpacity, 0.001f)
    }

    @Test
    fun `a nonsense stored value falls back to the default`() {
        store.putString("bubble_opacity", "not a number")

        assertEquals(Settings.DEFAULT_BUBBLE_OPACITY, settings.bubbleOpacity, 0.001f)
    }

    @Test
    fun `listening ends when the button is let go, until the user says otherwise`() {
        assertEquals(ListeningMode.WhileHeld, settings.listeningMode)

        settings.listeningMode = ListeningMode.UntilTapped

        assertEquals(ListeningMode.UntilTapped, Settings(store).listeningMode)
    }

    @Test
    fun `a listening mode written by a newer version falls back to holding`() {
        store.putString("listening_mode", "SomeFutureMode")

        assertEquals(ListeningMode.WhileHeld, settings.listeningMode)
    }

    @Test
    fun `ai translation is on until it is switched off`() {
        assertTrue(settings.allowNetwork)

        settings.allowNetwork = false
        assertFalse(Settings(store).allowNetwork)
    }
}
