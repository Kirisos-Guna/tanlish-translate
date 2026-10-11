package com.example.tanlishkeyboard.net

import com.example.tanlishkeyboard.engine.ApiResult
import com.example.tanlishkeyboard.engine.RemoteTranslator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Google's own keyless translator, asked for Tamil script to English:
 *
 * ```
 * GET /translate_a/single?client=gtx&dt=t&sl=ta&tl=en&q=<tamil>
 * -> [[["hello friend how are you","வணக்கம் நண்பா எப்படி இருக்கிறாய்",null,null,3,...]],null,"ta",...]
 * ```
 *
 * `client=gtx` is the endpoint Google's own web page calls: no key, no account, and the best Tamil
 * output of the free options. It is also undocumented and rate limited per IP, which is why
 * [TamilToEnglishApi] keeps a second translator behind it, and why a sentence that has been
 * translated once is never paid for again.
 *
 * The reply is a bare array rather than an object, and a long sentence comes back split across
 * several segments, so the first string of each segment is joined back up.
 *
 * Speech goes Tamil script → English rather than Tanglish → English because Android's recogniser
 * writes what it hears in Tamil script; romanised Tanglish is not something a microphone can hear.
 */
class GoogleTranslateApi(
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
        const val DEFAULT_BASE_URL = "https://translate.googleapis.com"
        const val ENDPOINT_PATH = "/translate_a/single"
        const val SOURCE_LANGUAGE = "ta"
        const val TARGET_LANGUAGE = "en"

        fun buildQuery(sentence: String): String =
            "client=gtx&dt=t&sl=$SOURCE_LANGUAGE&tl=$TARGET_LANGUAGE&q=${KeylessHttp.encode(sentence)}"

        /** Pure so it can be unit tested against the replies Google actually returns. */
        fun parse(httpCode: Int, body: String): ApiResult {
            if (httpCode == 429) return ApiResult.Rejected("Too many requests", null)
            if (httpCode !in 200..299) return ApiResult.Failed("Translator error (HTTP $httpCode)")

            val text = translatedText(body)
            return if (text.isNullOrBlank()) {
                ApiResult.Failed("Unexpected reply from the translator")
            } else {
                ApiResult.Success(
                    text = text,
                    // This endpoint reports nothing about its own limits and keeps no server cache.
                    triesRemaining = null,
                    resetAtEpochSeconds = null,
                    fromCache = false,
                )
            }
        }

        /** The English out of a `translate_a/single` reply, or null when the reply is not one. */
        fun translatedText(body: String): String? {
            val reply = ReplyCursor(body)
            if (!reply.take('[')) return null
            if (!reply.take('[')) return null

            val out = StringBuilder()
            var first = true
            while (true) {
                if (!first && !reply.take(',')) break
                first = false
                // Past the segments the outer array carries the detected language and more: stop there.
                if (!reply.take('[')) break
                val piece = reply.readString() ?: break
                out.append(piece)
                while (reply.take(',')) reply.skipValue()
                reply.take(']')
            }
            return out.toString().trim().ifEmpty { null }
        }
    }
}

/**
 * Walks a JSON reply a token at a time, which is all the array-shaped Google response needs.
 * Deliberately not a general parser: it knows how to read a string, skip a value and step over
 * punctuation.
 */
private class ReplyCursor(private val text: String) {

    private var index = 0

    fun take(char: Char): Boolean {
        skipWhitespace()
        if (index >= text.length || text[index] != char) return false
        index++
        return true
    }

    fun readString(): String? {
        skipWhitespace()
        if (index >= text.length || text[index] != '"') return null
        index++
        val out = StringBuilder()
        while (index < text.length) {
            when (val char = text[index]) {
                '"' -> {
                    index++
                    return out.toString()
                }
                '\\' -> {
                    if (index + 1 >= text.length) return null
                    when (val escape = text[index + 1]) {
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            if (index + 5 >= text.length) return null
                            val codePoint = text.substring(index + 2, index + 6).toIntOrNull(16) ?: return null
                            out.append(codePoint.toChar())
                            index += 4
                        }
                        else -> out.append(escape)
                    }
                    index += 2
                }
                else -> {
                    out.append(char)
                    index++
                }
            }
        }
        return null
    }

    /** Steps over whatever value sits at the cursor: a string, a number, an object or a list. */
    fun skipValue() {
        skipWhitespace()
        if (index >= text.length) return
        when (text[index]) {
            '"' -> {
                readString()
            }

            '{', '[' -> {
                val open = text[index]
                val close = if (open == '{') '}' else ']'
                var depth = 0
                while (index < text.length) {
                    when (text[index]) {
                        '"' -> {
                            readString() ?: return
                        }

                        open -> {
                            depth++
                            index++
                        }

                        close -> {
                            depth--
                            index++
                            if (depth <= 0) return
                        }

                        else -> index++
                    }
                }
            }

            else -> {
                while (index < text.length && text[index] !in ",]}" && !text[index].isWhitespace()) index++
            }
        }
    }

    private fun skipWhitespace() {
        while (index < text.length && text[index].isWhitespace()) index++
    }
}
