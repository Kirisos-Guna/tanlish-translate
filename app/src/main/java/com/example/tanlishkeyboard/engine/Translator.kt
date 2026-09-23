package com.example.tanlishkeyboard.engine

import com.example.tanlishkeyboard.data.QuotaTracker
import com.example.tanlishkeyboard.data.Settings
import com.example.tanlishkeyboard.data.TranslationCache

/** Where a translation came from, so the UI can be honest about it. */
enum class TranslationSource { Cache, Ai }

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

    /** The AI could not be used. [reason] says why, and nothing gets written anywhere. */
    data class Unavailable(val reason: String) : TranslationOutcome

    data object Blank : TranslationOutcome
}

/** Longest sentence the easytamiltyping translator accepts. */
const val MAX_SENTENCE_LENGTH = 1000

/**
 * Decides where a sentence gets translated: the cache, or the AI endpoint.
 *
 * There is deliberately no offline fallback. A word-by-word gloss of a sentence the glossary only
 * half knew was more misleading than helpful, so when a request cannot be made this says so
 * plainly, and anything already translated stays free from the cache.
 */
class Translator(
    private val cache: TranslationCache,
    private val quota: QuotaTracker,
    private val settings: Settings,
    private val remote: RemoteTranslator,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000L },
) {

    suspend fun translateSentence(sentence: String): TranslationOutcome {
        val clean = normaliseSentence(sentence)
        if (clean.isEmpty()) return TranslationOutcome.Blank

        cache.get(clean)?.let { cached ->
            return TranslationOutcome.Translated(cached, TranslationSource.Cache, quota.effectiveRemaining(clock()))
        }

        if (clean.length > MAX_SENTENCE_LENGTH) {
            return TranslationOutcome.Unavailable("Too long for AI translation (max $MAX_SENTENCE_LENGTH characters)")
        }
        if (!settings.allowNetwork) {
            return TranslationOutcome.Unavailable("AI translation is switched off in the app")
        }
        if (quota.isExhausted(clock())) {
            return TranslationOutcome.Unavailable("Hourly AI limit reached. ${quota.resetHint(clock())}")
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
                TranslationOutcome.Unavailable("AI translation refused: ${result.message}")
            }

            is ApiResult.Failed -> TranslationOutcome.Unavailable(result.message)
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
