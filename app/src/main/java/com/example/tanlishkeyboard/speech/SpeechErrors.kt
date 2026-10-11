package com.example.tanlishkeyboard.speech

import android.speech.SpeechRecognizer

/**
 * Turns the recogniser's numeric errors into something the button can say, in one place.
 *
 * Every failure the user can do something about gets its own sentence: a missing permission, a phone
 * with no recogniser, a recogniser with no Tamil, and the ordinary case of holding the button and
 * saying nothing at all.
 */
object SpeechErrors {

    /**
     * The failure behind a `RecognitionListener` error code.
     *
     * The two language codes only exist from Android 12 on, where a recogniser that cannot do Tamil
     * says so instead of quietly listening for English.
     */
    fun failureFor(errorCode: Int): SpeechFailure = when (errorCode) {
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        -> SpeechFailure.NothingHeard

        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechFailure.Permission
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> SpeechFailure.Busy
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        -> SpeechFailure.NoNetwork

        SpeechRecognizer.ERROR_SERVER,
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
        -> SpeechFailure.Server

        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
        -> SpeechFailure.TamilUnavailable

        else -> SpeechFailure.Unknown
    }

    /**
     * The recogniser's own name for an error code, for the log.
     *
     * The numbers on their own are unreadable, and which one came back is often the whole diagnosis:
     * 12 and 13 mean this phone's recogniser has no Tamil, 9 means the microphone was never allowed.
     */
    fun nameOf(errorCode: Int): String = when (errorCode) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
        SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
        SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
        SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "ERROR_TOO_MANY_REQUESTS"
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "ERROR_SERVER_DISCONNECTED"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "ERROR_LANGUAGE_NOT_SUPPORTED"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "ERROR_LANGUAGE_UNAVAILABLE"
        else -> "ERROR_CODE_$errorCode"
    }

    /** What the button says when a session fails. */
    fun message(failure: SpeechFailure): String = when (failure) {
        SpeechFailure.Permission -> "Allow the microphone in the app, then hold the button again"
        SpeechFailure.NoRecogniser ->
            "This phone has no speech recogniser — install the Google app's speech service"
        SpeechFailure.Busy -> "The recogniser is busy — hold the button again in a moment"
        SpeechFailure.TamilUnavailable ->
            "This phone's recogniser has no Tamil — download Tamil in its voice typing settings"
        SpeechFailure.NothingHeard -> "I did not catch that — hold the button and speak Tamil"
        SpeechFailure.NoNetwork -> "Speech recognition needs the internet — check your connection"
        SpeechFailure.Server -> "The speech service is not answering — try again"
        SpeechFailure.Unknown -> "Speech recognition failed — try again"
    }
}
