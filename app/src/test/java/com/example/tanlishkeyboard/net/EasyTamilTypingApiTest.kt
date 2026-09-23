package com.example.tanlishkeyboard.net

import com.example.tanlishkeyboard.engine.ApiResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class EasyTamilTypingApiTest {

    @Test
    fun `request body matches what the website posts`() {
        val body = EasyTamilTypingApi.buildRequestBody(
            sentence = "vanakkam nanba epdi irukka",
            slug = EasyTamilTypingApi.TANGLISH_TO_ENGLISH,
        )

        assertEquals(
            "sentenceToTranslate=vanakkam+nanba+epdi+irukka&translationPairSlug=tanglish-to-english-translation",
            body,
        )
    }

    @Test
    fun `request body escapes reserved characters`() {
        val body = EasyTamilTypingApi.buildRequestBody("a&b=c?d e", "slug")

        assertEquals("sentenceToTranslate=a%26b%3Dc%3Fd+e&translationPairSlug=slug", body)
    }

    @Test
    fun `a real success reply is parsed`() {
        val result = EasyTamilTypingApi.parse(
            200,
            """{"translatedText":"Hello friend, how are you?","secondaryText":null,"triesRemaining":13,"fromCache":true,"resetAt":1790142357}""",
        ) as ApiResult.Success

        assertEquals("Hello friend, how are you?", result.text)
        assertEquals(13, result.triesRemaining)
        assertEquals(1790142357L, result.resetAtEpochSeconds)
        assertTrue(result.fromCache)
    }

    @Test
    fun `a null remaining count is accepted`() {
        val result = EasyTamilTypingApi.parse(
            200,
            """{"translatedText":"Hello","triesRemaining":null,"fromCache":true}""",
        ) as ApiResult.Success

        assertEquals("Hello", result.text)
        assertEquals(null, result.triesRemaining)
    }

    @Test
    fun `unicode escapes are decoded`() {
        // The endpoint escapes Tamil output, e.g. வணக்கம் arrives as \u0BB5\u0BA3...
        val result = EasyTamilTypingApi.parse(
            200,
            """{"translatedText":"\u0BB5\u0BA3\u0B95\u0BCD\u0B95\u0BAE\u0BCD","fromCache":false}""",
        ) as ApiResult.Success

        assertEquals("வணக்கம்", result.text)
    }

    @Test
    fun `the unsupported pair error is reported as a rejection`() {
        val result = EasyTamilTypingApi.parse(
            400,
            """{"error":"AI translation not configured for this translation pair"}""",
        ) as ApiResult.Rejected

        assertEquals("AI translation not configured for this translation pair", result.message)
    }

    @Test
    fun `a rate limit reply marks the quota`() {
        val result = EasyTamilTypingApi.parse(
            429,
            """{"error":"Too many requests. Limit reached","resetAt":1790142395}""",
        ) as ApiResult.Rejected

        assertTrue(result.message.contains("Limit"))
        assertEquals(1790142395L, result.resetAtEpochSeconds)
    }

    @Test
    fun `html instead of json is a failure`() {
        val result = EasyTamilTypingApi.parse(503, "<html><body>Just a moment...</body></html>")
            as ApiResult.Failed

        assertTrue(result.message.contains("503"))
    }

    @Test
    fun `an empty translation is not treated as success`() {
        val result = EasyTamilTypingApi.parse(200, """{"translatedText":"","triesRemaining":5}""")

        assertTrue(result is ApiResult.Failed)
    }

    @Test
    fun `client posts to the translator endpoint and returns the parsed result`() = runBlocking {
        var requestedUrl = ""
        var requestedBody = ""
        val api = EasyTamilTypingApi(
            transport = { url, body ->
                requestedUrl = url
                requestedBody = body
                HttpResult(200, """{"translatedText":"Thank you","triesRemaining":9,"fromCache":false}""")
            },
        )

        val result = api.translate("nandri") as ApiResult.Success

        assertEquals("https://www.easytamiltyping.com/ai-translator/translate", requestedUrl)
        assertEquals(
            "sentenceToTranslate=nandri&translationPairSlug=tanglish-to-english-translation",
            requestedBody,
        )
        assertEquals("Thank you", result.text)
        assertEquals(9, result.triesRemaining)
    }

    @Test
    fun `a network failure does not throw`() = runBlocking {
        val api = EasyTamilTypingApi(transport = { _, _ -> throw IOException("no route to host") })

        val result = api.translate("nandri") as ApiResult.Failed

        assertTrue(result.message.contains("Could not reach"))
    }
}
