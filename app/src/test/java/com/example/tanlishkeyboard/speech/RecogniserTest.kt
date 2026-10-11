package com.example.tanlishkeyboard.speech

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a recogniser session's ending is read, which is what tap to stop depends on. */
class RecogniserTest {

    @Test
    fun `a pause the recogniser calls the end of a sentence is words, not a failure`() {
        assertEquals(
            SpeechEnding.Finished("வணக்கம்"),
            endingForError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT, "வணக்கம்"),
        )
        assertEquals(SpeechEnding.Finished(null), endingForError(SpeechRecognizer.ERROR_NO_MATCH, null))
    }

    @Test
    fun `an error another session could not fix is a failure`() {
        assertEquals(
            SpeechEnding.Failed(SpeechFailure.Permission),
            endingForError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, null),
        )
        assertEquals(
            SpeechEnding.Failed(SpeechFailure.TamilUnavailable),
            endingForError(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, null),
        )
    }

    @Test
    fun `a result with no words in it still hands over the partial that was heard`() {
        assertEquals(SpeechEnding.Finished("வணக்கம்"), endingForResults(null, "வணக்கம்"))
        assertEquals(SpeechEnding.Finished("வணக்கம்"), endingForResults(emptyList(), "வணக்கம்"))
    }

    @Test
    fun `the first candidate with words in it is the one that counts`() {
        assertEquals(SpeechEnding.Finished("நண்பா"), endingForResults(listOf("  ", "நண்பா"), "வணக்கம்"))
        assertEquals(SpeechEnding.Finished(null), endingForResults(null, ""))
    }

    @Test
    fun `an ending with no words in it is still an ending`() {
        val ending = endingForError(SpeechRecognizer.ERROR_NO_MATCH, "   ")

        assertTrue(ending is SpeechEnding.Finished)
        assertEquals(null, (ending as SpeechEnding.Finished).words)
    }
}
