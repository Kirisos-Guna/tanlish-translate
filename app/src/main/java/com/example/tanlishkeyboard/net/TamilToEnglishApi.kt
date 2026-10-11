package com.example.tanlishkeyboard.net

import com.example.tanlishkeyboard.engine.ApiResult
import com.example.tanlishkeyboard.engine.RemoteTranslator

/**
 * Tamil script to English, from whichever free web translator answers first.
 *
 * [GoogleTranslateApi] is asked first because its Tamil is the better of the two, with
 * [MyMemoryApi] behind it so that one undocumented endpoint is not a single point of failure.
 * Neither needs a key or an account, and neither is told anything but the sentence itself.
 *
 * A refusal is remembered ahead of a plain failure: "the limit is spent" is more useful to the user
 * than "could not reach the translator", and it is the one that arrives when both services are out.
 */
class TamilToEnglishApi(
    private val translators: List<RemoteTranslator> = listOf(GoogleTranslateApi(), MyMemoryApi()),
) : RemoteTranslator {

    override suspend fun translate(sentence: String): ApiResult {
        var refusal: ApiResult.Rejected? = null
        var failure: ApiResult.Failed? = null

        for (translator in translators) {
            when (val result = translator.translate(sentence)) {
                is ApiResult.Success -> return result
                is ApiResult.Rejected -> if (refusal == null) refusal = result
                is ApiResult.Failed -> failure = result
            }
        }

        return refusal ?: failure ?: ApiResult.Failed("No translator is available")
    }
}
