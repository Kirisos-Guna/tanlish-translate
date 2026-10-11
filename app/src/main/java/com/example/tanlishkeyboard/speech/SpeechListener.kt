package com.example.tanlishkeyboard.speech

/** One listening session: a press and hold of the button, from the press to the release. */
interface SpeechSession {

    /** Stops capturing and delivers what was heard, as [SpeechEvent.Heard] or a failure. */
    fun stop()

    /** Gives up on the session; no further event ever arrives from it. */
    fun cancel()
}

/** What the microphone reports while the button is held down, always on the main thread. */
sealed interface SpeechEvent {

    /** The words the recogniser has heard so far, while the user is still talking. */
    data class Partial(val text: String) : SpeechEvent

    /** What was said, once the words stop. */
    data class Heard(val text: String) : SpeechEvent

    /** Nothing came back, and here is why. */
    data class Failed(val failure: SpeechFailure) : SpeechEvent
}

/** Why a listening session did not produce any Tamil. */
enum class SpeechFailure {
    Permission,
    NoRecogniser,
    Busy,
    TamilUnavailable,
    NothingHeard,
    NoNetwork,
    Server,
    Unknown,
}

/**
 * Anything the button can listen with.
 *
 * The button only ever sees this, so the hold-translate-write flow can be exercised without a
 * microphone and the Android recogniser stays behind one class.
 */
interface SpeechListener {

    /** Starts one session; every event for it is delivered back through [onEvent]. */
    fun listen(onEvent: (SpeechEvent) -> Unit): SpeechSession
}
