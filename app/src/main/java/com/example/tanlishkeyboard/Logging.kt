package com.example.tanlishkeyboard

/**
 * The one tag everything in this app logs under, so a single filter shows the whole story of a
 * listening session as it happened:
 *
 * ```bash
 * adb logcat -s Tanlish
 * ```
 *
 * What a press was read as, whether the microphone permission or the AI switch blocked it, whether
 * the foreground service was refused (with the exception), which recogniser error came back by name,
 * and what was heard. Nothing else in the app logs.
 */
const val LOG_TAG = "Tanlish"

/**
 * How much of a recognised phrase is ever written to the log.
 *
 * Enough to tell Tamil script from Latin nonsense — which is often the whole diagnosis — and no more
 * than that, because what is logged is what the user said.
 */
const val SPEECH_PREVIEW_CHARS = 60
