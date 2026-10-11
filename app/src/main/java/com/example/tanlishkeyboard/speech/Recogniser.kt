package com.example.tanlishkeyboard.speech

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.util.Log
import com.example.tanlishkeyboard.LOG_TAG
import com.example.tanlishkeyboard.SPEECH_PREVIEW_CHARS

/**
 * How one recogniser session ended.
 *
 * A session that heard nothing is [Finished] with no words rather than a failure, and that
 * distinction is most of what tap to stop needs: a recogniser calling a pause the end of the sentence
 * is normal, and the run it belongs to simply carries on from there.
 */
internal sealed interface SpeechEnding {

    /** The session stopped, by itself or because it was asked to. */
    data class Finished(val words: String?) : SpeechEnding

    /** The session failed, and starting another one would not help. */
    data class Failed(val failure: SpeechFailure) : SpeechEnding
}

/** How a session that reported a recogniser error ends. */
internal fun endingForError(errorCode: Int, partial: String?): SpeechEnding = when (
    val failure = SpeechErrors.failureFor(errorCode)
) {
    // It heard no words, which is a pause, not a problem: whatever partial came in before the pause
    // is the words this session has to offer.
    SpeechFailure.NothingHeard -> SpeechEnding.Finished(partial?.ifBlank { null })

    else -> SpeechEnding.Failed(failure)
}

/** How a session that returned results ends. */
internal fun endingForResults(candidates: List<String>?, partial: String?): SpeechEnding =
    SpeechEnding.Finished(TamilSpeechListener.bestOf(candidates) ?: partial?.ifBlank { null })

/**
 * The recogniser as this app needs it: one session at a time, feeding back text.
 *
 * [AndroidRecogniser] is what runs on a phone. Behind this interface the rules that decide when
 * listening stops can be exercised without one, and those rules are worth exercising: they decide
 * whether a spoken sentence is written down or thrown away.
 */
internal interface Recogniser {

    /** Starts one session, which ends in exactly one [onEnded]; [onPartial] arrives while it runs. */
    fun listen(onPartial: (String) -> Unit, onEnded: (SpeechEnding) -> Unit)

    /** Asks the session to finish now; it still answers, through [listen]'s `onEnded`. */
    fun stop()

    /** Abandons the session: nothing further is ever reported from it. */
    fun cancel()

    /** Lets the recogniser go. It is never used again afterwards. */
    fun release()
}

/**
 * The recogniser the phone ships with, wrapped so the rest of the app sees text rather than `Bundle`s
 * and error codes.
 *
 * Everything here is Android's business: reading candidates out of a `Bundle`, and knowing that a
 * session which heard nothing is still a session that may have words in it. The decisions about what
 * to do with an ending are behind [endingForError] and [endingForResults], where they can be tested.
 */
internal class AndroidRecogniser(
    private val recogniser: SpeechRecognizer,
    private val intent: Intent,
) : Recogniser, RecognitionListener {

    private var onPartial: (String) -> Unit = {}
    private var onEnded: (SpeechEnding) -> Unit = {}
    private var partial = ""
    private var ended = true

    override fun listen(onPartial: (String) -> Unit, onEnded: (SpeechEnding) -> Unit) {
        this.onPartial = onPartial
        this.onEnded = onEnded
        partial = ""
        ended = false
        recogniser.setRecognitionListener(this)
        runCatching { recogniser.startListening(intent) }
            .onSuccess { Log.i(LOG_TAG, "the recogniser is listening") }
            .onFailure {
                Log.w(LOG_TAG, "startListening was refused", it)
                end(SpeechEnding.Failed(SpeechFailure.NoRecogniser))
            }
    }

    override fun stop() {
        Log.i(LOG_TAG, "asked the recogniser to stop")
        runCatching { recogniser.stopListening() }
    }

    override fun cancel() {
        ended = true
        runCatching {
            recogniser.stopListening()
            recogniser.cancel()
        }
    }

    override fun release() {
        ended = true
        runCatching { recogniser.destroy() }
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val heard = TamilSpeechListener.bestOf(candidates(partialResults)) ?: return
        if (heard == partial) return
        partial = heard
        Log.d(LOG_TAG, "partial: ${heard.length} chars, “${heard.take(SPEECH_PREVIEW_CHARS)}”")
        onPartial(heard)
    }

    override fun onResults(results: Bundle?) {
        val ending = endingForResults(candidates(results), partial)
        Log.i(LOG_TAG, "result: ${ending.words?.length ?: 0} chars")
        end(ending)
    }

    override fun onError(error: Int) {
        Log.w(LOG_TAG, "recogniser error $error (${SpeechErrors.nameOf(error)})")
        end(endingForError(error, partial))
    }

    private fun end(ending: SpeechEnding) {
        if (ended) return
        ended = true
        onEnded(ending)
    }

    private fun candidates(results: Bundle?): List<String>? =
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)

    // The two callbacks that say the microphone is actually open are worth a line: without them, a
    // session that never speaks is indistinguishable from one that never started.
    override fun onReadyForSpeech(params: Bundle?) {
        Log.i(LOG_TAG, "the microphone is open")
    }

    override fun onBeginningOfSpeech() {
        Log.i(LOG_TAG, "speech has begun")
    }

    override fun onEndOfSpeech() {
        Log.i(LOG_TAG, "the recogniser heard the end of the sentence")
    }

    // The callbacks this app has nothing to say about, as the listener interface demands them.
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit
}

/** The words of a [Finished] ending, for logging it without unwrapping it everywhere. */
private val SpeechEnding.words: String?
    get() = (this as? SpeechEnding.Finished)?.words
