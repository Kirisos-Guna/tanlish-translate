package com.example.tanlishkeyboard.translate

import com.example.tanlishkeyboard.data.MemoryKeyValueStore
import com.example.tanlishkeyboard.data.QuotaTracker
import com.example.tanlishkeyboard.data.Settings
import com.example.tanlishkeyboard.data.TranslationCache
import com.example.tanlishkeyboard.engine.ApiResult
import com.example.tanlishkeyboard.engine.MAX_SENTENCE_LENGTH
import com.example.tanlishkeyboard.engine.RemoteTranslator
import com.example.tanlishkeyboard.engine.TranslationSource
import com.example.tanlishkeyboard.engine.Translator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ButtonTranslatorTest {

    private class FakeField(
        private var text: String?,
        override val isPassword: Boolean = false,
        private val acceptsEdits: Boolean = true,
    ) : EditableField {

        var replacements = 0
            private set

        val current: String? get() = text

        /** Simulates the user editing the field by hand. */
        fun type(newText: String) {
            text = newText
        }

        override fun read(): String? = text

        override fun replace(text: String): Boolean {
            if (!acceptsEdits) return false
            replacements++
            this.text = text
            return true
        }
    }

    private class FakeClipboard(private var text: String? = null) : Clipboard {
        override fun read(): String? = text
        override fun write(text: String) {
            this.text = text
        }

        val current: String? get() = text
    }

    private class FakeRemote(private val result: ApiResult) : RemoteTranslator {
        val calls = mutableListOf<String>()
        override suspend fun translate(sentence: String): ApiResult {
            calls += sentence
            return result
        }
    }

    private val store = MemoryKeyValueStore()
    private val quota = QuotaTracker(store)
    private val settings = Settings(store)
    private val cache = TranslationCache(file = null)
    private val now = 1_800_000_000L

    private fun translator(remote: RemoteTranslator) = Translator(
        cache = cache,
        quota = quota,
        settings = settings,
        remote = remote,
        clock = { now },
    )

    private fun button(
        remote: RemoteTranslator,
        field: EditableField? = null,
        clipboard: FakeClipboard = FakeClipboard(),
    ) = ButtonTranslator(
        fields = FieldProvider { field },
        clipboard = clipboard,
        translator = translator(remote),
    ).let { it to clipboard }

    @Test
    fun `the focused field is replaced in place`() = runBlocking {
        val remote = FakeRemote(ApiResult.Success("Hello friend, how are you?", 12, now + 3600, false))
        val field = FakeField("vanakkam nanba epdi irukka")
        val (subject, clipboard) = button(remote, field)

        val outcome = subject.translate() as ButtonOutcome.Replaced

        assertEquals("Hello friend, how are you?", outcome.text)
        assertEquals(TranslationSource.Ai, outcome.source)
        assertEquals("Hello friend, how are you?", field.current)
        assertNull("nothing should be copied when the field accepted the edit", clipboard.current)
        assertTrue(subject.canUndo)
        assertEquals(listOf("vanakkam nanba epdi irukka"), remote.calls)
    }

    @Test
    fun `password fields are refused before anything is read`() = runBlocking {
        val remote = FakeRemote(ApiResult.Success("nope", null, null, false))
        val field = FakeField("hunter2", isPassword = true)
        val (subject, clipboard) = button(remote, field)

        val outcome = subject.translate() as ButtonOutcome.Blocked

        assertEquals(BlockReason.Password, outcome.reason)
        assertEquals("hunter2", field.current)
        assertNull(clipboard.current)
        assertTrue(remote.calls.isEmpty())
        assertFalse(subject.canUndo)
    }

    @Test
    fun `apps that block the edit get the translation on the clipboard`() = runBlocking {
        val remote = FakeRemote(ApiResult.Success("Hello friend", 11, now + 3600, false))
        val field = FakeField("vanakkam nanba", acceptsEdits = false)
        val (subject, clipboard) = button(remote, field)

        val outcome = subject.translate() as ButtonOutcome.Copied

        assertEquals("Hello friend", outcome.text)
        assertEquals("Hello friend", clipboard.current)
        assertEquals("vanakkam nanba", field.current)
        assertFalse("a blocked edit cannot be undone", subject.canUndo)
    }

    @Test
    fun `an empty field is never sent anywhere`() = runBlocking {
        val remote = FakeRemote(ApiResult.Success("nope", null, null, false))
        val (subject, _) = button(remote, FakeField("   "))

        val outcome = subject.translate() as ButtonOutcome.Blocked

        assertEquals(BlockReason.EmptyField, outcome.reason)
        assertTrue(remote.calls.isEmpty())
    }

    @Test
    fun `no field falls back to the clipboard`() = runBlocking {
        val remote = FakeRemote(ApiResult.Success("Hello friend", 10, now + 3600, false))
        val clipboard = FakeClipboard("vanakkam nanba")
        val (subject, _) = button(remote, field = null, clipboard = clipboard)

        val outcome = subject.translate() as ButtonOutcome.Copied

        assertEquals("Hello friend", outcome.text)
        assertEquals("Hello friend", clipboard.current)
        assertTrue(outcome.message.contains("copied"))
    }

    @Test
    fun `with no field and nothing on the clipboard the user is told what to do`() = runBlocking {
        val remote = FakeRemote(ApiResult.Success("nope", null, null, false))
        val (subject, _) = button(remote, field = null)

        val outcome = subject.translate() as ButtonOutcome.Blocked

        assertEquals(BlockReason.NoField, outcome.reason)
        assertTrue(outcome.message.contains("text field"))
        assertTrue(remote.calls.isEmpty())
    }

    @Test
    fun `text longer than the endpoint allows is refused locally`() = runBlocking {
        val remote = FakeRemote(ApiResult.Success("nope", null, null, false))
        val (subject, _) = button(remote, FakeField("a".repeat(MAX_SENTENCE_LENGTH + 1)))

        val outcome = subject.translate() as ButtonOutcome.Blocked

        assertEquals(BlockReason.TooLong, outcome.reason)
        assertTrue(remote.calls.isEmpty())
    }

    @Test
    fun `an exhausted AI budget blocks and says so, leaving the text alone`() = runBlocking {
        quota.update(0, now + 900)
        val remote = FakeRemote(ApiResult.Success("nope", null, null, false))
        val field = FakeField("nandri nanba")
        val (subject, _) = button(remote, field)

        val outcome = subject.translate() as ButtonOutcome.Blocked

        assertEquals(BlockReason.Failed, outcome.reason)
        assertTrue(outcome.message.contains("limit", ignoreCase = true))
        assertEquals("the Tanglish stays put", "nandri nanba", field.current)
        assertFalse(subject.canUndo)
        assertTrue(remote.calls.isEmpty())
    }

    @Test
    fun `with the AI switched off the field keeps its text`() = runBlocking {
        settings.allowNetwork = false
        val remote = FakeRemote(ApiResult.Success("nope", null, null, false))
        val field = FakeField("vanakkam nanba")
        val (subject, _) = button(remote, field)

        val outcome = subject.translate() as ButtonOutcome.Blocked

        assertEquals(BlockReason.Failed, outcome.reason)
        assertTrue(outcome.message.contains("switched off"))
        assertEquals("vanakkam nanba", field.current)
        assertTrue(remote.calls.isEmpty())
    }

    @Test
    fun `a failure leaves the field alone and reports the reason`() = runBlocking {
        val remote = FakeRemote(ApiResult.Failed("Could not reach the translator"))
        val field = FakeField("zzz qqq")
        val (subject, _) = button(remote, field)

        val outcome = subject.translate() as ButtonOutcome.Blocked

        assertEquals(BlockReason.Failed, outcome.reason)
        assertEquals("Could not reach the translator", outcome.message)
        assertEquals("zzz qqq", field.current)
    }

    @Test
    fun `undo puts the original text back once`() = runBlocking {
        val remote = FakeRemote(ApiResult.Success("Hello friend", 11, now + 3600, false))
        val field = FakeField("vanakkam nanba")
        val (subject, _) = button(remote, field)

        subject.translate() as ButtonOutcome.Replaced
        assertEquals("Hello friend", field.current)

        assertTrue(subject.undo())
        assertEquals("vanakkam nanba", field.current)
        assertFalse(subject.canUndo)
        assertFalse("there is only ever one undo", subject.undo())
    }

    @Test
    fun `only the most recent tap can be undone`() = runBlocking {
        val remote = FakeRemote(ApiResult.Success("Hello friend", 9, now + 3600, false))
        val field = FakeField("vanakkam nanba")
        val (subject, _) = button(remote, field)

        subject.translate() as ButtonOutcome.Replaced
        assertEquals("Hello friend", field.current)

        // The user types the next message and taps again: this replacement replaces the last undo.
        field.type("nandri nanba")
        subject.translate() as ButtonOutcome.Replaced

        assertTrue(subject.undo())
        assertEquals("nandri nanba", field.current)
        assertFalse(subject.canUndo)
    }

    @Test
    fun `a sentence that was translated before is reused from the cache`() = runBlocking {
        cache.put("vanakkam nanba", "Hello friend")
        val remote = FakeRemote(ApiResult.Failed("should not be called"))
        val field = FakeField("vanakkam nanba")
        val (subject, _) = button(remote, field)

        val outcome = subject.translate() as ButtonOutcome.Replaced

        assertEquals("Hello friend", outcome.text)
        assertEquals(TranslationSource.Cache, outcome.source)
        assertTrue(remote.calls.isEmpty())
    }
}
