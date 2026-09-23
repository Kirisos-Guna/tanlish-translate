package com.example.tanlishkeyboard.engine

import com.example.tanlishkeyboard.data.QuotaTracker
import com.example.tanlishkeyboard.data.Settings
import com.example.tanlishkeyboard.data.TranslationCache

/** Where a translation came from, so the UI can be honest about it. */
enum class TranslationSource { Cache, Local, Ai }

/** Result of a remote AI translation attempt. */
sealed interface ApiResult {
    data class Success(
        val text: String,
        val triesRemaining: Int?,
        val resetAtEpochSeconds: Long?,
        val fromCache: Boolean,
    ) : ApiResult

    /** The server understood the request but refused it (bad pair, quota, ...). */
    data class Rejected(val message: String, val resetAtEpochSeconds: Long?) : ApiResult

    /** Network or server failure. */
    data class Failed(val message: String) : ApiResult
}

/** Anything that can translate a Tanglish sentence to English. */
interface RemoteTranslator {
    suspend fun translate(sentence: String): ApiResult
}

sealed interface TranslationOutcome {
    data class Translated(
        val text: String,
        val source: TranslationSource,
        val quotaRemaining: Int?,
        val note: String? = null,
    ) : TranslationOutcome

    /** AI could not be used; [offlineFallback] is a word-by-word gloss when one exists. */
    data class Unavailable(val reason: String, val offlineFallback: String? = null) : TranslationOutcome

    data object Blank : TranslationOutcome
}

/** Longest sentence the easytamiltyping translator accepts. */
const val MAX_SENTENCE_LENGTH = 1000

/**
 * Decides where a sentence gets translated: cache, bundled glossary, or the AI endpoint.
 *
 * Every caller goes through here, so swapping the backend for our own proxy is a one-class change.
 */
class Translator(
    private val glossary: TanglishGlossary,
    private val tamilEngine: TanglishToTamil,
    private val cache: TranslationCache,
    private val quota: QuotaTracker,
    private val settings: Settings,
    private val remote: RemoteTranslator,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000L },
) {

    /** Tamil script for a Tanglish word: glossary spelling first, phonetic rules otherwise. */
    fun toTamil(tanglish: String): String =
        glossary.tamilFor(tanglish) ?: tamilEngine.transliterate(tanglish)

    suspend fun translateSentence(sentence: String): TranslationOutcome {
        val clean = TanglishGlossary.normalise(sentence)
        if (clean.isEmpty()) return TranslationOutcome.Blank

        cache.get(clean)?.let { cached ->
            return TranslationOutcome.Translated(cached, TranslationSource.Cache, quota.effectiveRemaining(clock()))
        }

        glossary.englishFor(clean)?.let { known ->
            return TranslationOutcome.Translated(known, TranslationSource.Local, quota.effectiveRemaining(clock()))
        }

        val offline = glossary.glossWordByWord(clean)

        if (clean.length > MAX_SENTENCE_LENGTH) {
            return TranslationOutcome.Unavailable("Too long for AI translation (max $MAX_SENTENCE_LENGTH characters)", offline)
        }
        if (!settings.allowNetwork) {
            return TranslationOutcome.Unavailable("AI translation is turned off in the app", offline)
        }
        if (!settings.aiConsentGiven) {
            return TranslationOutcome.Unavailable("Open the app and allow AI translation to use it", offline)
        }
        if (quota.isExhausted(clock())) {
            return TranslationOutcome.Unavailable("Hourly AI limit reached. ${quota.resetHint(clock())}", offline)
        }

        return when (val result = remote.translate(clean)) {
            is ApiResult.Success -> {
                cache.put(clean, result.text)
                quota.update(result.triesRemaining, result.resetAtEpochSeconds)
                TranslationOutcome.Translated(
                    text = result.text,
                    source = TranslationSource.Ai,
                    quotaRemaining = quota.effectiveRemaining(clock()),
                    note = if (result.fromCache) "from server cache" else null,
                )
            }

            is ApiResult.Rejected -> {
                if (looksLikeLimit(result.message)) {
                    quota.noteExhausted(clock(), result.resetAtEpochSeconds)
                }
                TranslationOutcome.Unavailable("AI translation refused: ${result.message}", offline)
            }

            is ApiResult.Failed -> TranslationOutcome.Unavailable(result.message, offline)
        }
    }

    private fun looksLikeLimit(message: String): Boolean {
        val lower = message.lowercase()
        return LIMIT_HINTS.any { lower.contains(it) }
    }

    private companion object {
        val LIMIT_HINTS = listOf("limit", "tries", "quota", "too many", "429")
    }
}
