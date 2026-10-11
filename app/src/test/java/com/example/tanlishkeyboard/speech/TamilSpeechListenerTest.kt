package com.example.tanlishkeyboard.speech

import com.example.tanlishkeyboard.data.ListeningMode
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TamilSpeechListenerTest {

    @Test
    fun `a session the finger ends leaves the recogniser's own silence timeout alone`() {
        assertNull(TamilSpeechListener.silenceMillis(ListeningMode.WhileHeld))
    }

    @Test
    fun `a session ended by a tap asks the recogniser to wait through a pause`() {
        val (complete, possiblyComplete) = checkNotNull(
            TamilSpeechListener.silenceMillis(ListeningMode.UntilTapped),
        ) { "a session the user has to close by hand should ask for a longer silence" }
        assertTrue("a pause mid-sentence is not a full stop", complete >= 5_000L)
        assertTrue(
            "the tentative silence has to come before the decided one",
            possiblyComplete < complete,
        )
    }
}
