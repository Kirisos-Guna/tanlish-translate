package com.example.tanlishkeyboard.net

import com.example.tanlishkeyboard.engine.ApiResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.URLDecoder

class GoogleTranslateApiTest {

    /** Captured verbatim from translate.googleapis.com for வணக்கம் நண்பா எப்படி இருக்கிறாய். */
    private val realReply = """
        [[["hello friend how are you","வணக்கம் நண்பா எப்படி இருக்கிறாய்",null,null,3,null,null,[[]],
        [["1eb561d2d816b8957a38cd5018eb164c","tea_AllEn_2022q2.md"]]]],null,"ta",null,null,null,null,[]]
    """.trimIndent()

    @Test
    fun `the query asks for Tamil to English with no key`() {
        val query = GoogleTranslateApi.buildQuery("வணக்கம் நண்பா")

        assertTrue(query.startsWith("client=gtx&dt=t&sl=ta&tl=en&q="))
        assertEquals("வணக்கம் நண்பா", URLDecoder.decode(query.substringAfter("q="), "UTF-8"))
    }

    @Test
    fun `a real reply is parsed`() {
        val result = GoogleTranslateApi.parse(200, realReply) as ApiResult.Success

        assertEquals("hello friend how are you", result.text)
        assertNull("this endpoint reports no hourly budget", result.triesRemaining)
        assertNull(result.resetAtEpochSeconds)
    }

    @Test
    fun `a sentence split across segments is joined back up`() {
        val text = GoogleTranslateApi.translatedText("""[[["Hello ","வணக்கம்"],["friend","நண்பா"]],null,"ta"]""")

        assertEquals("Hello friend", text)
    }

    @Test
    fun `an empty list of segments is not a translation`() {
        val result = GoogleTranslateApi.parse(200, """[[],null,"ta"]""")

        assertTrue(result is ApiResult.Failed)
    }

    @Test
    fun `html instead of json is a failure`() {
        val result = GoogleTranslateApi.parse(503, "<html><body>Sorry, something went wrong</body></html>")
            as ApiResult.Failed

        assertTrue(result.message.contains("503"))
    }

    @Test
    fun `a rate limit is reported as a refusal`() {
        val result = GoogleTranslateApi.parse(429, "Too Many Requests") as ApiResult.Rejected

        assertEquals("Too many requests", result.message)
    }

    @Test
    fun `client gets the translate endpoint and returns the parsed result`() = runBlocking {
        var requestedUrl = ""
        val api = GoogleTranslateApi(
            transport = { url ->
                requestedUrl = url
                HttpResult(200, """[[["Thank you","நன்றி"]],null,"ta"]""")
            },
        )

        val result = api.translate("நன்றி") as ApiResult.Success

        assertEquals("https://translate.googleapis.com/translate_a/single?${GoogleTranslateApi.buildQuery("நன்றி")}", requestedUrl)
        assertEquals("Thank you", result.text)
    }

    @Test
    fun `a network failure does not throw`() = runBlocking {
        val api = GoogleTranslateApi(transport = { throw IOException("no route to host") })

        val result = api.translate("நன்றி") as ApiResult.Failed

        assertTrue(result.message.contains("Could not reach"))
    }
}
