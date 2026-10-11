package com.example.tanlishkeyboard.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.example.tanlishkeyboard.data.ListeningMode

/**
 * Everything this file has to say goes out under one tag, so the gesture logs in the overlay and
 * the recogniser logs here can be read as a single story: `adb logcat -s Tanlish`.
 */
private const val TAG = "Tanlish"

/** How much of a recognised phrase is ever written to the log. */
private const val PREVIEW_CHARS = 60

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

    override fun listen(mode: ListeningMode, onEvent: (SpeechEvent) -> Unit): SpeechSession {
        val recogniser = buildRecogniser(context)
        if (recogniser == null) {
            Log.w(TAG, "no speech recogniser on this phone")
            handler.post { onEvent(SpeechEvent.Failed(SpeechFailure.NoRecogniser)) }
            return NoSession
        }
        Log.i(TAG, "session starting: language $TAMIL, mode=$mode")
        return Session(recogniser, handler, requestIntent(mode), onEvent).also { it.start() }
    }

    /** What Android is asked to recognise with: Tamil, one answer, and partials for live feedback. */
    private fun requestIntent(mode: ListeningMode): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, TAMIL)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, TAMIL)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)

        silenceMillis(mode)?.let { (complete, possiblyComplete) ->
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, complete)
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                possiblyComplete,
            )
        }
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
        private val giveUp = Runnable {
            Log.w(TAG, "the recogniser never answered within ${STOP_TIMEOUT_MS}ms; giving up")
            fail(SpeechFailure.NothingHeard)
        }

        fun start() {
            recogniser.setRecognitionListener(this)
            runCatching { recogniser.startListening(intent) }
                .onSuccess { Log.i(TAG, "the recogniser is listening") }
                .onFailure {
                    Log.w(TAG, "startListening was refused", it)
                    fail(SpeechFailure.NoRecogniser)
                }
        }

        override fun stop() {
            if (finished) return
            Log.i(TAG, "asked the recogniser to stop")
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
            Log.d(TAG, "partial: ${heard.length} chars, “${heard.take(PREVIEW_CHARS)}”")
            partial = heard
            onEvent(SpeechEvent.Partial(heard))
        }

        override fun onResults(results: Bundle?) {
            if (finished) return
            val heard = bestOf(results)
            Log.i(TAG, "result: ${heard?.length ?: 0} chars, “${heard?.take(PREVIEW_CHARS) ?: ""}”")
            // A result with no words in it is still a failed session, but a partial is good enough.
            if (heard == null) fail(SpeechFailure.NothingHeard) else finish(heard)
        }

        override fun onError(error: Int) {
            if (finished) return
            val failure = SpeechErrors.failureFor(error)
            Log.w(TAG, "recogniser error $error (${SpeechErrors.nameOf(error)}) -> $failure")
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
        // The two that say the microphone is actually open are worth a line: without them, a session
        // that never speaks is indistinguishable from one that never started.
        override fun onReadyForSpeech(params: Bundle?) {
            Log.i(TAG, "the microphone is open")
        }

        override fun onBeginningOfSpeech() {
            Log.i(TAG, "speech has begun")
        }

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

        /** How long the recogniser should wait out a pause before calling the sentence finished. */
        private const val COMPLETE_SILENCE_MS = 8_000L
        private const val POSSIBLY_COMPLETE_SILENCE_MS = 4_000L

        /**
         * How long a pause may last before the recogniser decides the sentence is over, or null to
         * leave the recogniser's own default alone.
         *
         * Held: the finger ends the sentence, so there is nothing to wait for. Tapped: the user may
         * well be thinking mid-sentence, and a recogniser that gave up on the first pause would end
         * the session before they ever tapped it. These are requests, and a recognition service is
         * free to ignore them — in which case the session ends on its own silence timeout and the
         * English is written early, rather than not at all.
         */
        fun silenceMillis(mode: ListeningMode): Pair<Long, Long>? = when (mode) {
            ListeningMode.WhileHeld -> null
            ListeningMode.UntilTapped -> COMPLETE_SILENCE_MS to POSSIBLY_COMPLETE_SILENCE_MS
        }

        private fun newRecogniser(context: Context): SpeechRecognizer? =
            runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()

        /** The first candidate that is not blank, or null when the recogniser offered none. */
        fun bestOf(candidates: List<String>?): String? = candidates?.firstOrNull { it.isNotBlank() }
    }
}
