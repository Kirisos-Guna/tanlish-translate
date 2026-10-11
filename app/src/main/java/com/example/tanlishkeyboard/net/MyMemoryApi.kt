package com.example.tanlishkeyboard.net

import com.example.tanlishkeyboard.engine.ApiResult
import com.example.tanlishkeyboard.engine.RemoteTranslator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * MyMemory's documented free translator, the one behind Google's when it will not answer:
 *
 * ```
 * GET /get?q=<tamil>&langpair=ta|en
 * -> {"responseData":{"translatedText":"Hello friend, how are you?"},"responseStatus":200,"quotaFinished":false,...}
 * ```
 *
 * No key and no account, in exchange for a published ceiling: 5,000 characters a day anonymously and
 * 500 bytes per query, which is why a spoken sentence is capped well under that. When the day is
 * spent it says so in the reply rather than in the status code, so both are checked.
 */
class MyMemoryApi(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val transport: (String) -> HttpResult = KeylessHttp::get,
) : RemoteTranslator {

    override suspend fun translate(sentence: String): ApiResult = withContext(Dispatchers.IO) {
        try {
            val response = transport("$baseUrl$ENDPOINT_PATH?${buildQuery(sentence)}")
            parse(response.code, response.body)
        } catch (error: IOException) {
            ApiResult.Failed("Could not reach the translator (${error.javaClass.simpleName})")
        } catch (error: Exception) {
            ApiResult.Failed("Translation failed (${error.javaClass.simpleName})")
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.mymemory.translated.net"
        const val ENDPOINT_PATH = "/get"
        const val LANGUAGES = "ta|en"

        /** Longest query, in bytes, that MyMemory accepts. Tamil script is three bytes a letter. */
        const val MAX_QUERY_BYTES = 500

        fun buildQuery(sentence: String): String =
            "q=${KeylessHttp.encode(sentence)}&langpair=${KeylessHttp.encode(LANGUAGES)}"

        /** Pure so it can be unit tested against the replies MyMemory actually returns. */
        fun parse(httpCode: Int, body: String): ApiResult {
            val reply = JsonScan(body)
            val translated = reply.string("translatedText")?.trim()
            val details = reply.string("responseDetails").orEmpty()
            val spent = reply.rawValue("quotaFinished") == "true" ||
                isRefusal(translated) ||
                isRefusal(details)

            return when {
                spent -> ApiResult.Rejected("The free daily translation limit is spent", null)
                httpCode == 429 -> ApiResult.Rejected("Too many requests", null)
                httpCode !in 200..299 -> ApiResult.Failed("Translator error (HTTP $httpCode)")
                translated.isNullOrBlank() -> ApiResult.Failed("Unexpected reply from the translator")
                else -> ApiResult.Success(
                    text = translated,
                    triesRemaining = null,
                    resetAtEpochSeconds = null,
                    fromCache = false,
                )
            }
        }

        /**
         * MyMemory reports a spent budget or an over-long query as the translation itself, in
         * capitals, so those replies have to be recognised before they are mistaken for English.
         */
        private fun isRefusal(text: String?): Boolean {
            val upper = text?.uppercase() ?: return false
            return upper.contains("MYMEMORY WARNING") ||
                upper.contains("LIMIT EXCEEDED") ||
                upper.contains("QUOTA")
        }
    }
}
