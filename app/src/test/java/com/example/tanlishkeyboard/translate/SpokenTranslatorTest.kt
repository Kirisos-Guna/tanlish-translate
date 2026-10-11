package com.example.tanlishkeyboard.translate

import com.example.tanlishkeyboard.data.MemoryKeyValueStore
import com.example.tanlishkeyboard.data.QuotaTracker
import com.example.tanlishkeyboard.data.Settings
import com.example.tanlishkeyboard.data.TranslationCache
import com.example.tanlishkeyboard.engine.ApiResult
import com.example.tanlishkeyboard.engine.RemoteTranslator
import com.example.tanlishkeyboard.engine.TranslationSource
import com.example.tanlishkeyboard.engine.Translator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpokenTranslatorTest {

    private class FakeField(
        private var text: String?,
        override val isPassword: Boolean = false,
        private val acceptsEdits: Boolean = true,
    ) : EditableField {

        val current: String? get() = text

        override fun read(): String? = text

        override fun replace(text: String): Boolean {
            if (!acceptsEdits) return false
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

    private fun spoken(
        remote: RemoteTranslator,
        field: EditableField? = null,
        clipboard: FakeClipboard = FakeClipboard(),
    ) = SpokenTranslator(
        fields = FieldProvider { field },
        clipboard = clipboard,
        translator = Translator(cache, quota, settings, remote, clock = { now }),
    ).let { it to clipboard }

    @Test
    fun `the English lands in the field the user was in`() = runBlocking {
        val remote = FakeRemote(ApiResult.Success("Hello friend, how are you?", null, null, false))
        val field = FakeField("")
        val (subject, clipboard) = spoken(remote, field)

        val outcome = subject.translateSpoken("வணக்கம் நண்பா எப்படி இருக்கிறாய்") as ButtonOutcome.Replaced

        assertEquals("Hello friend, how are you?", outcome.text)
        assertEquals(TranslationSource.Ai, outcome.source)
        assertEquals("Hello friend, how are you?", field.current)
        assertNull("nothing should be copied when the field took the words", clipboard.current)
        assertTrue(subject.canUndo)
        assertEquals(listOf("வணக்கம் நண்பா எப்படி இருக்கிறாய்"), remote.calls)
    }

    @Test
    fun `speaking again replaces what the last hold wrote`() = runBlocking {
        val remote = FakeRemote(ApiResult.Success("Hello friend", null, null, false))
        val field = FakeField("half a message")
        val (subject, _) = spoken(remote, field)

        subject.translateSpoken("வணக்கம் நண்பா") as ButtonOutcome.Replaced

        assertEquals("Hello friend", field.current)
    }

    @Test
    fun `undo puts back whatever the field held before`() = runBlocking {
        val remote = FakeRemote(ApiResult.Success("Hello friend", null, null, false))
        val field = FakeField("half a message")
        val (subject, _) = spoken(remote, field)

        subject.translateSpoken("வணக்கம் நண்பா") as ButtonOutcome.Replaced
        assertTrue(subject.undo())

        assertEquals("half a message", field.current)
        assertFalse("there is only ever one undo", subject.undo())
    }

    @Test
    fun `a silent hold is never sent anywhere`() = runBlocking {
        val remote = FakeRemote(ApiResult.Success("nope", null, null, false))
        val field = FakeField("")
        val (subject, _) = spoken(remote, field)

        val outcome = subject.translateSpoken("   ") as ButtonOutcome.Blocked

        assertEquals(BlockReason.EmptyField, outcome.reason)
        assertTrue(remote.calls.isEmpty())
        assertEquals("", field.current)
    }

    @Test
    fun `a very long utterance is refused locally`() = runBlocking {
        val remote = FakeRemote(ApiResult.Success("nope", null, null, false))
        val (subject, _) = spoken(remote, FakeField(""))

        val outcome = subject.translateSpoken("க".repeat(MAX_SPEECH_LENGTH + 1)) as ButtonOutcome.Blocked

        assertEquals(BlockReason.TooLong, outcome.reason)
        assertTrue(remote.calls.isEmpty())
    }

    @Test
    fun `nothing is written when the translation could not be made`() = runBlocking {
        val remote = FakeRemote(ApiResult.Failed("Could not reach the translator"))
        val field = FakeField("half a message")
        val (subject, clipboard) = spoken(remote, field)

        val outcome = subject.translateSpoken("வணக்கம்") as ButtonOutcome.Blocked

        assertEquals(BlockReason.Failed, outcome.reason)
        assertEquals("half a message", field.current)
        assertNull(clipboard.current)
        assertFalse(subject.canUndo)
    }

    @Test
    fun `an app that blocks the edit gets the English on the clipboard`() = runBlocking {
        val remote = FakeRemote(ApiResult.Success("Hello friend", null, null, false))
        val field = FakeField("half a message", acceptsEdits = false)
        val (subject, clipboard) = spoken(remote, field)

        val outcome = subject.translateSpoken("வணக்கம் நண்பா") as ButtonOutcome.Copied

        assertEquals("Hello friend", clipboard.current)
        assertEquals("half a message", field.current)
        assertFalse(subject.canUndo)
    }

    @Test
    fun `with no text field in front the English is copied instead`() = runBlocking {
        val remote = FakeRemote(ApiResult.Success("Hello friend", null, null, false))
        val (subject, clipboard) = spoken(remote, field = null)

        val outcome = subject.translateSpoken("வணக்கம் நண்பா") as ButtonOutcome.Copied

        assertEquals("Hello friend", clipboard.current)
        assertTrue(outcome.message.contains("clipboard"))
    }

    @Test
    fun `a password field is never written into`() = runBlocking {
        val remote = FakeRemote(ApiResult.Success("Hello friend", null, null, false))
        val field = FakeField("hunter2", isPassword = true)
        val (subject, clipboard) = spoken(remote, field)

        val outcome = subject.translateSpoken("வணக்கம் நண்பா") as ButtonOutcome.Copied

        assertEquals("hunter2", field.current)
        assertEquals("Hello friend", clipboard.current)
    }

    @Test
    fun `something said a second time costs nothing`() = runBlocking {
        cache.put("வணக்கம் நண்பா", "Hello friend")
        val remote = FakeRemote(ApiResult.Failed("should not be called"))
        val field = FakeField("")
        val (subject, _) = spoken(remote, field)

        val outcome = subject.translateSpoken("வணக்கம் நண்பா") as ButtonOutcome.Replaced

        assertEquals("Hello friend", outcome.text)
        assertEquals(TranslationSource.Cache, outcome.source)
        assertTrue(remote.calls.isEmpty())
    }
}
