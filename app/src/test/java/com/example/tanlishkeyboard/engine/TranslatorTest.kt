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

    private val cache = TranslationCache(file = null)
    private val store = MemoryKeyValueStore()
    private val quota = QuotaTracker(store)
    private val settings = Settings(store)
    private val now = 1_800_000_000L

    private fun translator(remote: RemoteTranslator) = Translator(
        cache = cache,
        quota = quota,
        settings = settings,
        remote = remote,
        clock = { now },
    )

    @Test
    fun `ai results are cached and the quota counter is remembered`() = runBlocking {
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
    fun `cached sentences never hit the network`() = runBlocking {
        cache.put("vanakkam nanba", "Hello friend")
        val remote = FakeRemote(ApiResult.Failed("should not be called"))

        val outcome = translator(remote).translateSentence("vanakkam nanba") as TranslationOutcome.Translated

        assertEquals("Hello friend", outcome.text)
        assertEquals(TranslationSource.Cache, outcome.source)
        assertTrue(remote.calls.isEmpty())
    }

    @Test
    fun `sentences are normalised before they are sent and cached`() = runBlocking {
        val remote = FakeRemote(ApiResult.Success("Hello friend", 12, now + 3600, false))
        val subject = translator(remote)

        subject.translateSentence("  Vanakkam   Nanba ")

        assertEquals(listOf("vanakkam nanba"), remote.calls)
        assertEquals("Hello friend", cache.get("vanakkam nanba"))
    }

    @Test
    fun `a server side cache hit says so`() = runBlocking {
        val remote = FakeRemote(ApiResult.Success("Hello", 5, now + 60, fromCache = true))

        val outcome = translator(remote).translateSentence("vanakkam") as TranslationOutcome.Translated

        assertEquals("from server cache", outcome.note)
    }

    @Test
    fun `with the network switch off nothing is sent and the reason says so`() = runBlocking {
        settings.allowNetwork = false
        val remote = FakeRemote(ApiResult.Success("nope", null, null, false))

        val outcome = translator(remote).translateSentence("vanakkam nanba") as TranslationOutcome.Unavailable

        assertTrue(outcome.reason.contains("switched off"))
        assertTrue(remote.calls.isEmpty())
    }

    @Test
    fun `only the cache can answer while the ai is switched off`() = runBlocking {
        settings.allowNetwork = false
        val remote = FakeRemote(ApiResult.Success("nope", null, null, false))
        val subject = translator(remote)

        assertTrue(subject.translateSentence("nandri") is TranslationOutcome.Unavailable)

        cache.put("nandri", "thank you")
        val cached = subject.translateSentence("nandri") as TranslationOutcome.Translated
        assertEquals("thank you", cached.text)
        assertEquals(TranslationSource.Cache, cached.source)
        assertTrue(remote.calls.isEmpty())
    }

    @Test
    fun `an exhausted quota stops requests until the window resets`() = runBlocking {
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
        val remote = FakeRemote(ApiResult.Rejected("Too many requests, limit reached", now + 900))

        val outcome = translator(remote).translateSentence("some sentence here")
            as TranslationOutcome.Unavailable

        assertTrue(outcome.reason.contains("refused"))
        assertTrue(quota.isExhausted(now))
    }

    @Test
    fun `a failure is reported with the reason it failed`() = runBlocking {
        val remote = FakeRemote(ApiResult.Failed("Could not reach the translator"))

        val outcome = translator(remote).translateSentence("nandri nanba") as TranslationOutcome.Unavailable

        assertEquals("Could not reach the translator", outcome.reason)
    }

    @Test
    fun `blank input is a no-op`() = runBlocking {
        val remote = FakeRemote(ApiResult.Success("nope", null, null, false))

        assertEquals(TranslationOutcome.Blank, translator(remote).translateSentence("   "))
        assertTrue(remote.calls.isEmpty())
    }

    @Test
    fun `sentences over the endpoint limit are refused locally`() = runBlocking {
        val remote = FakeRemote(ApiResult.Success("nope", null, null, false))

        val outcome = translator(remote).translateSentence("a".repeat(MAX_SENTENCE_LENGTH + 1))
            as TranslationOutcome.Unavailable

        assertTrue(outcome.reason.contains("Too long"))
        assertTrue(remote.calls.isEmpty())
    }
}
