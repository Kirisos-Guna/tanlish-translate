package com.example.tanlishkeyboard.speech

import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.example.tanlishkeyboard.LOG_TAG
import com.example.tanlishkeyboard.data.ListeningMode

/**
 * Tamil speech, through the recogniser the phone already has.
 *
 * The words come back in Tamil script, and that is the point: a microphone cannot hear romanised
 * Tanglish, so speaking goes Tamil script → English rather than Tanglish → English. The language is
 * asked for as `ta-IN`, and a recogniser without Tamil is reported as
 * [SpeechFailure.TamilUnavailable] rather than quietly expecting English.
 *
 * This class is only the way in: it builds the recogniser the phone has, asks it for Tamil, and hands
 * both to a [ListeningSession], which is where the decisions live. One recogniser per session is the
 * documented way to run one at a time, and a session the recogniser never answers is given up on
 * after [ListeningSession.STOP_TIMEOUT_MS].
 */
internal class TamilSpeechListener(
    private val context: Context,
    private val scheduler: Scheduler = MainThreadScheduler(),
    private val buildRecogniser: (Context) -> SpeechRecognizer? = ::newRecogniser,
) : SpeechListener {

    override fun listen(mode: ListeningMode, onEvent: (SpeechEvent) -> Unit): SpeechSession {
        val recogniser = buildRecogniser(context)
        if (recogniser == null) {
            Log.w(LOG_TAG, "no speech recogniser on this phone")
            scheduler.after(0, Runnable { onEvent(SpeechEvent.Failed(SpeechFailure.NoRecogniser)) })
            return NoSession
        }

        Log.i(LOG_TAG, "session starting: language $TAMIL, mode=$mode")
        return ListeningSession(
            recogniser = AndroidRecogniser(recogniser, requestIntent(mode)),
            scheduler = scheduler,
            mode = mode,
            onEvent = onEvent,
        ).also { it.start() }
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

    /** The session handed back when the phone has no recogniser at all: there is nothing to stop. */
    private object NoSession : SpeechSession {
        override fun stop() = Unit
        override fun cancel() = Unit
    }

    companion object {
        /** The language the recogniser is asked for: Tamil as spoken in India. */
        const val TAMIL = "ta-IN"

        /** How long the recogniser should wait out a pause before calling the sentence finished. */
        private const val COMPLETE_SILENCE_MS = 8_000L
        private const val POSSIBLY_COMPLETE_SILENCE_MS = 4_000L

        /**
         * How long a pause may last before the recogniser decides the sentence is over, or null to
         * leave the recogniser's own default alone.
         *
         * Held: the finger ends the sentence, so there is nothing to wait for. Tapped: the user may
         * well be thinking mid-sentence. These are requests, and a recognition service is free to
         * ignore them — which is exactly why [ListeningSession] does not depend on them being
         * honoured: it starts another session wherever one ends on its own, and the pause costs the
         * user nothing either way.
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
