package com.example.tanlishkeyboard.translate

import com.example.tanlishkeyboard.engine.MAX_SENTENCE_LENGTH
import com.example.tanlishkeyboard.engine.TranslationOutcome
import com.example.tanlishkeyboard.engine.TranslationSource
import com.example.tanlishkeyboard.engine.Translator

/**
 * A text field the button can read and write.
 *
 * The whole button flow is expressed against this, so reading, translating, replacing and undoing
 * are unit tested without any Android in the way.
 */
interface EditableField {

    /** Checked before anything is read, so password fields are never even looked at. */
    val isPassword: Boolean

    /** The text currently in the field, or null when it has gone away. */
    fun read(): String?

    /** Replaces the entire field with [text]; false when the app refused the edit. */
    fun replace(text: String): Boolean
}

/** Where the button looks for the field it should translate. */
fun interface FieldProvider {
    fun focusedField(): EditableField?
}

/** The clipboard. Writing always works; reading only works for the app in the foreground. */
interface Clipboard {
    fun read(): String?
    fun write(text: String)
}

/** Why the button could not translate anything. */
enum class BlockReason { NoField, EmptyField, Password, TooLong, Failed }

sealed interface ButtonOutcome {

    /** The field now holds [text]; [message] says where the translation came from. */
    data class Replaced(
        val text: String,
        val source: TranslationSource,
        val message: String,
    ) : ButtonOutcome

    /** The app refused the edit, so [text] went to the clipboard instead. */
    data class Copied(
        val text: String,
        val source: TranslationSource,
        val message: String,
    ) : ButtonOutcome

    /** Nothing was written anywhere. */
    data class Blocked(val reason: BlockReason, val message: String) : ButtonOutcome
}

/**
 * What happens when the button is tapped: read the focused field, translate it, put the English
 * back, and keep the original around until the user undoes it.
 *
 * Translation itself lives in [Translator], so the button gets the same cache and hourly budget
 * handling, and nothing is written unless the translation actually arrived.
 */
class ButtonTranslator(
    private val fields: FieldProvider,
    private val clipboard: Clipboard,
    private val translator: Translator,
) {

    private class Snapshot(val field: EditableField, val raw: String)

    private var undoSnapshot: Snapshot? = null

    /** Whether the last replacement can still be put back. */
    val canUndo: Boolean get() = undoSnapshot != null

    suspend fun translate(): ButtonOutcome {
        undoSnapshot = null
        val field = fields.focusedField() ?: return translateClipboard()
        return translateField(field)
    }

    /** Puts the original text back. Returns false when there is nothing to undo or the edit failed. */
    fun undo(): Boolean {
        val snapshot = undoSnapshot ?: return false
        val restored = snapshot.field.replace(snapshot.raw)
        if (restored) undoSnapshot = null
        return restored
    }

    private suspend fun translateField(field: EditableField): ButtonOutcome {
        if (field.isPassword) {
            return ButtonOutcome.Blocked(BlockReason.Password, "That is a password field, so nothing was read")
        }

        val raw = field.read()?.trim().orEmpty()
        if (raw.isEmpty()) return ButtonOutcome.Blocked(BlockReason.EmptyField, "Nothing typed there yet")
        if (raw.length > MAX_SENTENCE_LENGTH) {
            return ButtonOutcome.Blocked(
                BlockReason.TooLong,
                "That text is too long — the translator takes up to $MAX_SENTENCE_LENGTH characters",
            )
        }

        return when (val outcome = translator.translateSentence(raw)) {
            is TranslationOutcome.Translated ->
                write(field, raw, outcome.text, outcome.source, outcome.note)

            // No offline fallback: the field keeps its Tanglish and the button says why.
            is TranslationOutcome.Unavailable -> ButtonOutcome.Blocked(BlockReason.Failed, outcome.reason)

            TranslationOutcome.Blank ->
                ButtonOutcome.Blocked(BlockReason.EmptyField, "Nothing typed there yet")
        }
    }

    private fun write(
        field: EditableField,
        raw: String,
        text: String,
        source: TranslationSource,
        note: String?,
    ): ButtonOutcome {
        if (field.replace(text)) {
            undoSnapshot = Snapshot(field, raw)
            return ButtonOutcome.Replaced(text, source, describe(source, note))
        }
        clipboard.write(text)
        return ButtonOutcome.Copied(text, source, "This app blocked the edit — the translation is on the clipboard")
    }

    /** No text field in front: translate the clipboard instead, when Android lets us read it. */
    private suspend fun translateClipboard(): ButtonOutcome {
        val raw = clipboard.read()?.trim().orEmpty()
        if (raw.isEmpty()) {
            return ButtonOutcome.Blocked(BlockReason.NoField, "Tap a text field first, then tap the button")
        }
        if (raw.length > MAX_SENTENCE_LENGTH) {
            return ButtonOutcome.Blocked(
                BlockReason.TooLong,
                "That clipboard text is over $MAX_SENTENCE_LENGTH characters",
            )
        }

        return when (val outcome = translator.translateSentence(raw)) {
            is TranslationOutcome.Translated -> copy(outcome.text, outcome.source, outcome.note)
            is TranslationOutcome.Unavailable -> ButtonOutcome.Blocked(BlockReason.Failed, outcome.reason)

            TranslationOutcome.Blank -> ButtonOutcome.Blocked(BlockReason.EmptyField, "Nothing to translate")
        }
    }

    private fun copy(text: String, source: TranslationSource, note: String?): ButtonOutcome {
        clipboard.write(text)
        return ButtonOutcome.Copied(text, source, "${describe(source, note)} — copied, paste it over")
    }

    private fun describe(source: TranslationSource, note: String?): String = when (source) {
        TranslationSource.Ai -> note ?: "Translated with AI"
        TranslationSource.Cache -> "Reused a saved translation"
    }
}
