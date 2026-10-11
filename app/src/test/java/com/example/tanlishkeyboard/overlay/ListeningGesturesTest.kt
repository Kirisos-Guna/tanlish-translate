package com.example.tanlishkeyboard.overlay

import com.example.tanlishkeyboard.data.ListeningMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListeningGesturesTest {

    private val held = ListeningMode.WhileHeld
    private val tapped = ListeningMode.UntilTapped

    @Test
    fun `a tap with nothing going on translates the field`() {
        assertEquals(
            BubbleGesture.TranslateField,
            ListeningGestures.tap(listening = false, translatingSpeech = false, mode = held),
        )
        assertEquals(
            BubbleGesture.TranslateField,
            ListeningGestures.tap(listening = false, translatingSpeech = false, mode = tapped),
        )
    }

    @Test
    fun `a hold starts listening, whichever way it will end`() {
        assertEquals(BubbleGesture.StartListening, ListeningGestures.hold(listening = false))
    }

    @Test
    fun `holding an already listening button changes nothing`() {
        assertEquals(BubbleGesture.Nothing, ListeningGestures.hold(listening = true))
    }

    @Test
    fun `while held, letting go is what ends the session`() {
        assertEquals(BubbleGesture.StopListening, ListeningGestures.release(listening = true, mode = held))
    }

    @Test
    fun `while held, a tap during the hold is not a new instruction`() {
        assertEquals(
            BubbleGesture.Nothing,
            ListeningGestures.tap(listening = true, translatingSpeech = false, mode = held),
        )
    }

    @Test
    fun `while tapped, letting go leaves the session running`() {
        assertEquals(BubbleGesture.Nothing, ListeningGestures.release(listening = true, mode = tapped))
    }

    @Test
    fun `while tapped, tapping again is what ends the session`() {
        assertEquals(
            BubbleGesture.StopListening,
            ListeningGestures.tap(listening = true, translatingSpeech = false, mode = tapped),
        )
    }

    @Test
    fun `letting go of a button that is not listening does nothing`() {
        assertEquals(BubbleGesture.Nothing, ListeningGestures.release(listening = false, mode = held))
        assertEquals(BubbleGesture.Nothing, ListeningGestures.release(listening = false, mode = tapped))
    }

    @Test
    fun `nothing interrupts the translation of words just spoken`() {
        assertEquals(
            BubbleGesture.Nothing,
            ListeningGestures.tap(listening = false, translatingSpeech = true, mode = held),
        )
        assertEquals(
            BubbleGesture.Nothing,
            ListeningGestures.tap(listening = false, translatingSpeech = true, mode = tapped),
        )
    }

    @Test
    fun `the pill says how to stop, in both modes`() {
        assertTrue(ListeningGestures.listeningHint(held).contains("let go"))
        assertTrue(ListeningGestures.listeningHint(tapped).contains("tap the button"))
    }
}
