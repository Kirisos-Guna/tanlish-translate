package com.example.tanlishkeyboard.net

import com.example.tanlishkeyboard.engine.ApiResult
import com.example.tanlishkeyboard.engine.RemoteTranslator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class HttpResult(val code: Int, val body: String)

/**
 * Talks to easytamiltyping.com's translator, the same endpoint its own Tanglish page posts to:
 *
 * ```
 * POST /ai-translator/translate
 * sentenceToTranslate=<tanglish>&translationPairSlug=tanglish-to-english-translation
 * -> {"translatedText":"...","triesRemaining":13,"fromCache":false,"resetAt":1790142357}
 * ```
 *
 * It is an undocumented endpoint: it needs no key, is rate limited to roughly 15 requests an hour
 * per IP, and can change or disappear. Everything funnels through [RemoteTranslator], and the local
 * cache means a sentence is never paid for twice.
 */
class EasyTamilTypingApi(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val slug: String = TANGLISH_TO_ENGLISH,
    private val transport: (String, String) -> HttpResult = ::httpPost,
) : RemoteTranslator {

    override suspend fun translate(sentence: String): ApiResult = withContext(Dispatchers.IO) {
        try {
            val response = transport("$baseUrl$ENDPOINT_PATH", buildRequestBody(sentence, slug))
            parse(response.code, response.body)
        } catch (error: IOException) {
            ApiResult.Failed("Could not reach the translator (${error.javaClass.simpleName})")
        } catch (error: Exception) {
            ApiResult.Failed("Translation failed (${error.javaClass.simpleName})")
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://www.easytamiltyping.com"
        const val ENDPOINT_PATH = "/ai-translator/translate"
        const val TANGLISH_TO_ENGLISH = "tanglish-to-english-translation"

        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000

        private const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/130.0.0.0 Mobile Safari/537.36"

        fun buildRequestBody(sentence: String, slug: String): String =
            "sentenceToTranslate=${encode(sentence)}&translationPairSlug=${encode(slug)}"

        fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

        /** Pure so it can be unit tested against captured server replies. */
        fun parse(httpCode: Int, body: String): ApiResult {
            val json = Json.parseObject(body)
                ?: return ApiResult.Failed(
                    if (httpCode in 200..299) {
                        "Unexpected reply from the translator"
                    } else {
                        "Translator error (HTTP $httpCode)"
                    },
                )

            val translated = json.string("translatedText")
            val error = json.string("error")

            return when {
                !translated.isNullOrBlank() -> ApiResult.Success(
                    text = translated,
                    triesRemaining = json.int("triesRemaining"),
                    resetAtEpochSeconds = json.long("resetAt"),
                    fromCache = json.boolean("fromCache") ?: false,
                )

                error != null -> ApiResult.Rejected(error, json.long("resetAt"))
                httpCode == 429 -> ApiResult.Rejected("Too many requests", json.long("resetAt"))
                else -> ApiResult.Failed("Translator error (HTTP $httpCode)")
            }
        }

        private fun httpPost(url: String, body: String): HttpResult {
            val connection = URL(url).openConnection() as HttpURLConnection
            return try {
                connection.requestMethod = "POST"
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                connection.setRequestProperty("Accept", "*/*")
                connection.setRequestProperty("X-Requested-With", "XMLHttpRequest")
                connection.setRequestProperty("User-Agent", BROWSER_USER_AGENT)
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                HttpResult(code, text)
            } finally {
                connection.disconnect()
            }
        }
    }
}
