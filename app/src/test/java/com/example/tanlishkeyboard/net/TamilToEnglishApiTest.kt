package com.example.tanlishkeyboard.net

import com.example.tanlishkeyboard.engine.ApiResult
import com.example.tanlishkeyboard.engine.RemoteTranslator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TamilToEnglishApiTest {

    private class FakeTranslator(private val result: ApiResult) : RemoteTranslator {
        val calls = mutableListOf<String>()

        override suspend fun translate(sentence: String): ApiResult {
            calls += sentence
            return result
        }
    }

    @Test
    fun `the first translator that answers wins and the second is never called`() = runBlocking {
        val google = FakeTranslator(ApiResult.Success("Hello friend", null, null, false))
        val myMemory = FakeTranslator(ApiResult.Failed("should not be called"))

        val result = TamilToEnglishApi(listOf(google, myMemory)).translate("வணக்கம்") as ApiResult.Success

        assertEquals("Hello friend", result.text)
        assertEquals(listOf("வணக்கம்"), google.calls)
        assertTrue(myMemory.calls.isEmpty())
    }

    @Test
    fun `a failure falls through to the second translator`() = runBlocking {
        val google = FakeTranslator(ApiResult.Failed("Translator error (HTTP 403)"))
        val myMemory = FakeTranslator(ApiResult.Success("Hello friend", null, null, false))

        val result = TamilToEnglishApi(listOf(google, myMemory)).translate("வணக்கம்") as ApiResult.Success

        assertEquals("Hello friend", result.text)
        assertEquals(listOf("வணக்கம்"), myMemory.calls)
    }

    @Test
    fun `a refusal is still worth trying somewhere else`() = runBlocking {
        val google = FakeTranslator(ApiResult.Rejected("Too many requests", null))
        val myMemory = FakeTranslator(ApiResult.Success("Hello friend", null, null, false))

        val result = TamilToEnglishApi(listOf(google, myMemory)).translate("வணக்கம்") as ApiResult.Success

        assertEquals("Hello friend", result.text)
    }

    @Test
    fun `when nothing works the refusal is what the user is told`() = runBlocking {
        val google = FakeTranslator(ApiResult.Failed("Could not reach the translator"))
        val myMemory = FakeTranslator(ApiResult.Rejected("The free daily translation limit is spent", null))

        val result = TamilToEnglishApi(listOf(google, myMemory)).translate("வணக்கம்")

        assertTrue(result is ApiResult.Rejected)
        assertEquals("The free daily translation limit is spent", (result as ApiResult.Rejected).message)
    }

    @Test
    fun `when nothing works at all the failure is reported`() = runBlocking {
        val api = TamilToEnglishApi(
            listOf(
                FakeTranslator(ApiResult.Failed("Could not reach the translator")),
                FakeTranslator(ApiResult.Failed("Translator error (HTTP 500)")),
            ),
        )

        val result = api.translate("வணக்கம்") as ApiResult.Failed

        assertEquals("Translator error (HTTP 500)", result.message)
    }

    @Test
    fun `no translator configured is a failure rather than a crash`() = runBlocking {
        val result = TamilToEnglishApi(emptyList()).translate("வணக்கம்")

        assertTrue(result is ApiResult.Failed)
    }
}
