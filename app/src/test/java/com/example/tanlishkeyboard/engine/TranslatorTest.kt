package com.example.tanlishkeyboard.engine

import com.example.tanlishkeyboard.data.MemoryKeyValueStore
import com.example.tanlishkeyboard.data.QuotaTracker
import com.example.tanlishkeyboard.data.Settings
import com.example.tanlishkeyboard.data.TranslationCache
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslatorTest {

    private class FakeRemote(private val result: ApiResult) : RemoteTranslator {
        val calls = mutableListOf<String>()
        override suspend fun translate(sentence: String): ApiResult {
            calls += sentence
            return result
        }
    }

    private val glossary = TanglishGlossary.parseText(
        """
        vanakkam	hello	வணக்கம்
        nandri	thank you	நன்றி
        nanba	friend	நண்பா
        epdi irukka	how are you	எப்படி இருக்க
        """.trimIndent(),
    )

    private val cache = TranslationCache(file = null)
    private val store = MemoryKeyValueStore()
    private val quota = QuotaTracker(store)
    private val settings = Settings(store)
    private val now = 1_800_000_000L

    private fun translator(remote: RemoteTranslator) = Translator(
        glossary = glossary,
        tamilEngine = TanglishToTamil(),
        cache = cache,
        quota = quota,
        settings = settings,
        remote = remote,
        clock = { now },
    )

    private fun granted() {
        settings.aiConsentGiven = true
        settings.allowNetwork = true
    }

    @Test
    fun `glossary answers without using the network`() = runBlocking {
        val remote = FakeRemote(ApiResult.Failed("should not be called"))
        granted()

        val outcome = translator(remote).translateSentence("  Vanakkam ") as TranslationOutcome.Translated

        assertEquals("hello", outcome.text)
        assertEquals(TranslationSource.Local, outcome.source)
        assertTrue(remote.calls.isEmpty())
    }

    @Test
    fun `multi word phrases come from the glossary`() = runBlocking {
        granted()
        val remote = FakeRemote(ApiResult.Failed("should not be called"))

        val outcome = translator(remote).translateSentence("Epdi  Irukka") as TranslationOutcome.Translated

        assertEquals("how are you", outcome.text)
        assertEquals(TranslationSource.Local, outcome.source)
        assertTrue(remote.calls.isEmpty())
    }

    @Test
    fun `cached sentences never hit the network`() = runBlocking {
        granted()
        cache.put("vanakkam nanba", "Hello friend")
        val remote = FakeRemote(ApiResult.Failed("should not be called"))

        val outcome = translator(remote).translateSentence("vanakkam nanba") as TranslationOutcome.Translated

        assertEquals("Hello friend", outcome.text)
        assertEquals(TranslationSource.Cache, outcome.source)
        assertTrue(remote.calls.isEmpty())
    }

    @Test
    fun `ai results are cached and the quota counter is remembered`() = runBlocking {
        granted()
        val remote = FakeRemote(ApiResult.Success("Hello friend, how are you?", 12, now + 3600, false))
        val subject = translator(remote)

        val first = subject.translateSentence("vanakkam nanba epdi irukka") as TranslationOutcome.Translated
        assertEquals(TranslationSource.Ai, first.source)
        assertEquals("Hello friend, how are you?", first.text)
        assertEquals(12, quota.effectiveRemaining(now))
        assertEquals(listOf("vanakkam nanba epdi irukka"), remote.calls)

        val second = subject.translateSentence("vanakkam nanba epdi irukka") as TranslationOutcome.Translated
        assertEquals(TranslationSource.Cache, second.source)
        assertEquals(1, remote.calls.size)
    }

    @Test
    fun `network disabled falls back to the offline gloss`() = runBlocking {
        granted()
        settings.allowNetwork = false
        val remote = FakeRemote(ApiResult.Success("nope", null, null, false))

        val outcome = translator(remote).translateSentence("vanakkam nanba nandri")
            as TranslationOutcome.Unavailable

        assertEquals("hello friend thank you", outcome.offlineFallback)
        assertTrue(remote.calls.isEmpty())
    }

    @Test
    fun `without consent nothing is sent and the app is mentioned`() = runBlocking {
        settings.aiConsentGiven = false
        val remote = FakeRemote(ApiResult.Success("nope", null, null, false))

        val outcome = translator(remote).translateSentence("some sentence here")
            as TranslationOutcome.Unavailable

        assertTrue(remote.calls.isEmpty())
        assertTrue(outcome.reason.contains("app", ignoreCase = true))
    }

    @Test
    fun `an exhausted quota stops requests until the window resets`() = runBlocking {
        granted()
        quota.update(0, now + 1200)
        val remote = FakeRemote(ApiResult.Success("nope", null, null, false))

        val outcome = translator(remote).translateSentence("some sentence here")
            as TranslationOutcome.Unavailable

        assertTrue(outcome.reason.contains("limit", ignoreCase = true))
        assertTrue(outcome.reason.contains("20 min"))
        assertTrue(remote.calls.isEmpty())

        quota.update(0, now - 1)
        assertNull(quota.effectiveRemaining(now))
    }

    @Test
    fun `a rejected request that mentions limits marks the quota exhausted`() = runBlocking {
        granted()
        val remote = FakeRemote(ApiResult.Rejected("Too many requests, limit reached", now + 900))
        val subject = translator(remote)

        val outcome = subject.translateSentence("some sentence here") as TranslationOutcome.Unavailable

        assertTrue(outcome.reason.contains("refused"))
        assertTrue(quota.isExhausted(now))
    }

    @Test
    fun `failures expose the offline gloss instead of throwing`() = runBlocking {
        granted()
        val remote = FakeRemote(ApiResult.Failed("Could not reach the translator"))

        val outcome = translator(remote).translateSentence("nandri nanba") as TranslationOutcome.Unavailable

        assertEquals("Could not reach the translator", outcome.reason)
        assertEquals("thank you friend", outcome.offlineFallback)
    }

    @Test
    fun `blank input is a no-op`() = runBlocking {
        granted()
        val remote = FakeRemote(ApiResult.Success("nope", null, null, false))

        assertEquals(TranslationOutcome.Blank, translator(remote).translateSentence("   "))
        assertTrue(remote.calls.isEmpty())
    }

    @Test
    fun `sentences over the endpoint limit are refused locally`() = runBlocking {
        granted()
        val remote = FakeRemote(ApiResult.Success("nope", null, null, false))

        val outcome = translator(remote).translateSentence("a".repeat(MAX_SENTENCE_LENGTH + 1))
            as TranslationOutcome.Unavailable

        assertTrue(outcome.reason.contains("Too long"))
        assertTrue(remote.calls.isEmpty())
    }

    @Test
    fun `tamil uses the glossary spelling first and the rules otherwise`() {
        val subject = translator(FakeRemote(ApiResult.Failed("no")))

        assertEquals("வணக்கம்", subject.toTamil("vanakkam"))
        assertEquals("அம்ம", subject.toTamil("amma"))
        assertEquals("ஸ்ரீ", subject.toTamil("sri"))
    }
}
