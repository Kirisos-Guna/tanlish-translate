package com.example.tanlishkeyboard.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Tamil speech, through the recogniser the phone already has.
 *
 * The words come back in Tamil script, and that is the point: a microphone cannot hear romanised
 * Tanglish, so speaking goes Tamil script → English rather than Tanglish → English. The language is
 * asked for as `ta-IN`, and a recogniser without Tamil is reported as
 * [SpeechFailure.TamilUnavailable] rather than quietly expecting English.
 *
 * One recogniser per hold, because that is the documented way to run a single session at a time:
 * `startListening` must not be called again until the previous session has answered, and a session
 * the recogniser never answers is given up on after [STOP_TIMEOUT_MS]. Everything happens on the main
 * thread, which is where the recogniser insists on being driven from.
 */
class TamilSpeechListener(
    private val context: Context,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val buildRecogniser: (Context) -> SpeechRecognizer? = ::newRecogniser,
) : SpeechListener {

    override fun listen(onEvent: (SpeechEvent) -> Unit): SpeechSession {
        val recogniser = buildRecogniser(context)
        if (recogniser == null) {
            handler.post { onEvent(SpeechEvent.Failed(SpeechFailure.NoRecogniser)) }
            return NoSession
        }
        return Session(recogniser, handler, requestIntent(), onEvent).also { it.start() }
    }

    /** What Android is asked to recognise with: Tamil, one answer, and partials for live feedback. */
    private fun requestIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, TAMIL)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, TAMIL)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
    }

    /**
     * One hold of the button.
     *
     * It reports exactly one terminal event — [SpeechEvent.Heard] or [SpeechEvent.Failed] — and never
     * two, whatever order the recogniser's callbacks arrive in. What was heard is kept as it comes in,
     * so a session that recognises words but then times out still hands over the words rather than
     * throwing them away.
     */
    private class Session(
        private val recogniser: SpeechRecognizer,
        private val handler: Handler,
        private val intent: Intent,
        private val onEvent: (SpeechEvent) -> Unit,
    ) : RecognitionListener, SpeechSession {

        private var finished = false
        private var partial = ""

        /** The recogniser cannot be relied on to answer a stop that arrived before any speech. */
        private val giveUp = Runnable { fail(SpeechFailure.NothingHeard) }

        fun start() {
            recogniser.setRecognitionListener(this)
            val started = runCatching { recogniser.startListening(intent) }.isSuccess
            if (!started) fail(SpeechFailure.NoRecogniser)
        }

        override fun stop() {
            if (finished) return
            handler.postDelayed(giveUp, STOP_TIMEOUT_MS)
            runCatching { recogniser.stopListening() }
        }

        override fun cancel() {
            if (finished) return
            finished = true
            handler.removeCallbacks(giveUp)
            runCatching {
                recogniser.stopListening()
                recogniser.cancel()
            }
            release()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (finished) return
            val heard = bestOf(partialResults)
            if (heard == null || heard == partial) return
            partial = heard
            onEvent(SpeechEvent.Partial(heard))
        }

        override fun onResults(results: Bundle?) {
            if (finished) return
            val heard = bestOf(results)
            // A result with no words in it is still a failed session, but a partial is good enough.
            if (heard == null) fail(SpeechFailure.NothingHeard) else finish(heard)
        }

        override fun onError(error: Int) {
            if (finished) return
            val failure = SpeechErrors.failureFor(error)
            if (failure == SpeechFailure.NothingHeard && partial.isNotEmpty()) {
                finish(partial)
            } else {
                fail(failure)
            }
        }

        private fun finish(text: String) {
            if (finished) return
            finished = true
            handler.removeCallbacks(giveUp)
            release()
            onEvent(SpeechEvent.Heard(text))
        }

        private fun fail(failure: SpeechFailure) {
            if (finished) return
            finished = true
            handler.removeCallbacks(giveUp)
            release()
            onEvent(SpeechEvent.Failed(failure))
        }

        private fun release() {
            runCatching { recogniser.destroy() }
        }

        private fun bestOf(bundle: Bundle?): String? =
            TamilSpeechListener.bestOf(bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION))

        // The callbacks this app has nothing to say about, as the listener interface demands them.
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    /** The session handed back when the phone has no recogniser at all: there is nothing to stop. */
    private object NoSession : SpeechSession {
        override fun stop() = Unit
        override fun cancel() = Unit
    }

    companion object {
        /** The language the recogniser is asked for: Tamil as spoken in India. */
        const val TAMIL = "ta-IN"

        /** How long to wait for an answer after the user lets go of the button. */
        const val STOP_TIMEOUT_MS = 6_000L

        private fun newRecogniser(context: Context): SpeechRecognizer? =
            runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()

        /** The first candidate that is not blank, or null when the recogniser offered none. */
        fun bestOf(candidates: List<String>?): String? = candidates?.firstOrNull { it.isNotBlank() }
    }
}
