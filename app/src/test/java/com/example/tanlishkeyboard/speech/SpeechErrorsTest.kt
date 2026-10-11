package com.example.tanlishkeyboard.speech

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechErrorsTest {

    @Test
    fun `holding the button and saying nothing is not an error, just nothing heard`() {
        assertEquals(
            SpeechFailure.NothingHeard,
            SpeechErrors.failureFor(SpeechRecognizer.ERROR_NO_MATCH),
        )
        assertEquals(
            SpeechFailure.NothingHeard,
            SpeechErrors.failureFor(SpeechRecognizer.ERROR_SPEECH_TIMEOUT),
        )
    }

    @Test
    fun `the failures the user can fix are told apart`() {
        assertEquals(
            SpeechFailure.Permission,
            SpeechErrors.failureFor(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS),
        )
        assertEquals(SpeechFailure.Busy, SpeechErrors.failureFor(SpeechRecognizer.ERROR_RECOGNIZER_BUSY))
        assertEquals(
            SpeechFailure.NoNetwork,
            SpeechErrors.failureFor(SpeechRecognizer.ERROR_NETWORK),
        )
        assertEquals(
            SpeechFailure.Server,
            SpeechErrors.failureFor(SpeechRecognizer.ERROR_SERVER),
        )
    }

    @Test
    fun `a recogniser without Tamil says so instead of listening for English`() {
        assertEquals(
            SpeechFailure.TamilUnavailable,
            SpeechErrors.failureFor(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED),
        )
        assertEquals(
            SpeechFailure.TamilUnavailable,
            SpeechErrors.failureFor(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE),
        )
    }

    @Test
    fun `anything else is an unknown failure`() {
        assertEquals(SpeechFailure.Unknown, SpeechErrors.failureFor(99))
    }

    @Test
    fun `permission, Tamil and silence each have their own sentence`() {
        assertTrue(SpeechErrors.message(SpeechFailure.Permission).contains("microphone"))
        assertTrue(SpeechErrors.message(SpeechFailure.TamilUnavailable).contains("Tamil"))
        assertTrue(SpeechErrors.message(SpeechFailure.NothingHeard).contains("hold"))
        assertTrue(SpeechErrors.message(SpeechFailure.NoRecogniser).contains("recogniser"))
    }

    @Test
    fun `the best of the recogniser's candidates is the first one with words in it`() {
        assertEquals("வணக்கம்", TamilSpeechListener.bestOf(listOf("வணக்கம்", "வணக்கம் நண்பா")))
        assertEquals("நன்றி", TamilSpeechListener.bestOf(listOf("  ", "நன்றி")))
        assertNull(TamilSpeechListener.bestOf(emptyList()))
        assertNull(TamilSpeechListener.bestOf(null))
    }
}
