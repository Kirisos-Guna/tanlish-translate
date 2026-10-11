package com.example.tanlishkeyboard.speech

import android.os.SystemClock
import android.util.Log
import com.example.tanlishkeyboard.LOG_TAG
import com.example.tanlishkeyboard.data.ListeningMode

/**
 * One press of the floating button, from the moment listening starts to the moment what was heard is
 * handed over.
 *
 * In *hold to talk* that is a single recogniser session, and the finger coming up ends it. In *tap to
 * stop* it is a run of them, because a recogniser decides for itself that a pause means the sentence
 * is over — and in this mode the user is the one who decides that, with a tap. So a session that ends
 * on its own hands its words to the run and another starts in its place; the run ends at the tap, or
 * when nothing at all has been said for [MAX_SILENCE_MS], and everything it heard goes back as one
 * sentence.
 *
 * That is what keeps a mid-sentence pause from writing half a sentence into the field: the
 * recogniser's silence timeouts and its habit of ignoring the ones it is asked for stop mattering,
 * because another session simply takes over where the last one stopped.
 */
internal class ListeningSession(
    private val recogniser: Recogniser,
    private val scheduler: Scheduler,
    private val mode: ListeningMode,
    private val onEvent: (SpeechEvent) -> Unit,
    private val now: () -> Long = SystemClock::uptimeMillis,
) : SpeechSession {

    private var finished = false
    private var userStopped = false
    private var waitingToRestart = false

    /** What each finished session of this run heard, in the order it was said. */
    private val chunks = mutableListOf<String>()

    /** What the session in flight has heard so far. */
    private var partial = ""

    /** When words last arrived, which is what the silence budget is measured against. */
    private var heardAt = 0L

    private val restart = Runnable {
        if (finished) return@Runnable
        waitingToRestart = false
        listen()
    }

    private val giveUp = Runnable {
        Log.w(LOG_TAG, "the recogniser never answered within ${STOP_TIMEOUT_MS}ms; giving up")
        handOver(SpeechFailure.NothingHeard)
    }

    fun start() {
        heardAt = now()
        listen()
    }

    private fun listen() {
        recogniser.listen(onPartial = ::heardPartial, onEnded = ::sessionEnded)
    }

    private fun heardPartial(heard: String) {
        if (finished) return
        partial = heard
        heardAt = now()
        // The words already behind this run are shown with the new ones, so the pill reads as the
        // sentence the user is building rather than as whatever the current session happens to hold.
        onEvent(SpeechEvent.Partial(joined(chunks, partial)))
    }

    private fun sessionEnded(ending: SpeechEnding) {
        if (finished) return
        when (ending) {
            is SpeechEnding.Failed -> handOver(ending.failure)
            is SpeechEnding.Finished -> chunkEnded(ending.words)
        }
    }

    /** A recogniser session is over: keep its words, and carry on listening if the run is not done. */
    private fun chunkEnded(words: String?) {
        if (!words.isNullOrBlank()) {
            chunks += words
            heardAt = now()
        }
        // This session's partial belongs to the session that has just finished, and its result
        // supersedes it: carrying it on would put the same phrase in the sentence twice.
        partial = ""

        if (!keepsListening(mode, userStopped, now() - heardAt)) {
            return handOver(SpeechFailure.NothingHeard)
        }

        Log.i(LOG_TAG, "the recogniser finished on its own; listening on for the rest of the sentence")
        waitingToRestart = true
        scheduler.after(RESTART_GAP_MS, restart)
    }

    override fun stop() {
        if (finished) return
        userStopped = true

        if (waitingToRestart) {
            // Between sessions there is nothing to stop and nothing to wait for: the words are
            // already in, so the run ends here instead of after a timeout.
            return handOver(SpeechFailure.NothingHeard)
        }

        Log.i(LOG_TAG, "stopping: the user is done talking")
        recogniser.stop()
        scheduler.after(STOP_TIMEOUT_MS, giveUp)
    }

    override fun cancel() {
        if (finished) return
        finished = true
        scheduler.cancel(giveUp)
        scheduler.cancel(restart)
        recogniser.cancel()
        recogniser.release()
    }

    /**
     * The words of the run go back, or the reason there are none do.
     *
     * [failure] is only ever said aloud when nothing was heard at all: a run that gathered words and
     * then went wrong still hands those words over, because half a sentence the user spoke is worth
     * more to them than a sentence about what went wrong.
     */
    private fun handOver(failure: SpeechFailure) {
        if (finished) return
        finished = true
        scheduler.cancel(giveUp)
        scheduler.cancel(restart)
        recogniser.release()

        val spoken = joined(chunks, partial)
        if (spoken.isNotBlank()) {
            Log.i(LOG_TAG, "handing over ${spoken.length} chars from ${chunks.size} session(s)")
            onEvent(SpeechEvent.Heard(spoken))
        } else {
            onEvent(SpeechEvent.Failed(failure))
        }
    }

    companion object {

        /**
         * How long a tap-to-stop run stays open while nothing at all is being said.
         *
         * Each recogniser session that times out starts another, so this is what stops the run from
         * becoming an open microphone once the user has walked away: a minute of complete silence and
         * the run hands over whatever it has. Words reset it, so a long sentence said slowly is never
         * cut off by it.
         */
        const val MAX_SILENCE_MS = 60_000L

        /** How long to wait after a session ends on its own before starting the next one. */
        const val RESTART_GAP_MS = 300L

        /** How long to wait for an answer once the user has ended the run. */
        const val STOP_TIMEOUT_MS = 6_000L

        /**
         * Whether a finished session should be followed by another one.
         *
         * Only tap to stop, and only while the user has not ended the run: it is the one mode where
         * the user, not the recogniser, decides the sentence is over.
         */
        fun keepsListening(mode: ListeningMode, userStopped: Boolean, silenceMillis: Long): Boolean =
            mode == ListeningMode.UntilTapped && !userStopped && silenceMillis < MAX_SILENCE_MS

        /** What a run has heard, as one sentence. */
        fun joined(chunks: List<String>, inFlight: String?): String {
            val words = chunks.filter { it.isNotBlank() } +
                listOfNotNull(inFlight?.takeIf { it.isNotBlank() })
            return words.joinToString(" ")
        }
    }
}
