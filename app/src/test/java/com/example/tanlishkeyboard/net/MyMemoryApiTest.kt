package com.example.tanlishkeyboard.net

import com.example.tanlishkeyboard.engine.ApiResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.URLDecoder

class MyMemoryApiTest {

    /** Captured from api.mymemory.translated.net, trimmed to the fields the app reads. */
    private val realReply = """
        {"responseData":{"translatedText":"Hi friend, how are you?","match":0.85},"quotaFinished":false,
        "responseDetails":"","responseStatus":200,
        "matches":[{"segment":"ஹாய் எப்படி இருக்கிறாய்","translation":"i also miss our office life",
        "source":"ta-LK","target":"en-GB","model":"neural"}]}
    """.trimIndent()

    @Test
    fun `the query asks for the Tamil to English pair`() {
        val query = MyMemoryApi.buildQuery("வணக்கம்")

        assertTrue(query.startsWith("q="))
        assertTrue(query.endsWith("&langpair=ta%7Cen"))
        assertEquals("வணக்கம்", URLDecoder.decode(query.removePrefix("q=").substringBefore("&"), "UTF-8"))
    }

    @Test
    fun `the nested translation is read out of a real reply`() {
        val result = MyMemoryApi.parse(200, realReply) as ApiResult.Success

        assertEquals("Hi friend, how are you?", result.text)
        assertNull(result.triesRemaining)
    }

    @Test
    fun `a spent daily budget is reported as a refusal`() {
        val result = MyMemoryApi.parse(200, """{"responseData":{"translatedText":"Hello"},"quotaFinished":true}""")
            as ApiResult.Rejected

        assertTrue(result.message.contains("limit", ignoreCase = true))
    }

    @Test
    fun `the warning MyMemory sends as the translation is not mistaken for English`() {
        val result = MyMemoryApi.parse(
            200,
            """{"responseData":{"translatedText":"MYMEMORY WARNING: YOU USED ALL AVAILABLE FREE TRANSLATIONS FOR TODAY. NEXT AVAILABLE IN 14 HOURS 57 MINUTES 30 SECONDS"},"responseDetails":"","responseStatus":200}""",
        ) as ApiResult.Rejected

        assertTrue(result.message.contains("limit", ignoreCase = true))
    }

    @Test
    fun `the query length warning is not mistaken for English either`() {
        val result = MyMemoryApi.parse(
            200,
            """{"responseData":{"translatedText":"QUERY LENGTH LIMIT EXCEEDED. MAX ALLOWED QUERY : 500 CHARS"},"responseStatus":200}""",
        ) as ApiResult.Rejected

        assertTrue(result.message.contains("limit", ignoreCase = true))
    }

    @Test
    fun `a server error is a failure`() {
        val result = MyMemoryApi.parse(500, "<html>500 Internal Server Error</html>") as ApiResult.Failed

        assertTrue(result.message.contains("500"))
    }

    @Test
    fun `client gets the free endpoint and returns the parsed result`() = runBlocking {
        var requestedUrl = ""
        val api = MyMemoryApi(
            transport = { url ->
                requestedUrl = url
                HttpResult(200, """{"responseData":{"translatedText":"Thank you"},"responseStatus":200}""")
            },
        )

        val result = api.translate("நன்றி") as ApiResult.Success

        assertEquals("https://api.mymemory.translated.net/get?q=%E0%AE%A8%E0%AE%A9%E0%AF%8D%E0%AE%B1%E0%AE%BF&langpair=ta%7Cen", requestedUrl)
        assertEquals("Thank you", result.text)
    }

    @Test
    fun `a network failure does not throw`() = runBlocking {
        val api = MyMemoryApi(transport = { throw IOException("no route to host") })

        val result = api.translate("நன்றி") as ApiResult.Failed

        assertTrue(result.message.contains("Could not reach"))
    }
}
