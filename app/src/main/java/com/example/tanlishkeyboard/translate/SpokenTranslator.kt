package com.example.tanlishkeyboard.translate

import com.example.tanlishkeyboard.engine.TranslationOutcome
import com.example.tanlishkeyboard.engine.TranslationSource
import com.example.tanlishkeyboard.engine.Translator
import com.example.tanlishkeyboard.engine.normaliseSentence

/**
 * Longest spoken sentence, in characters, that the speech path will send.
 *
 * Shorter than the typed path's limit on purpose: the recogniser hands over one utterance at a time,
 * and the fallback translator behind the spoken path refuses a query longer than 500 bytes, which
 * Tamil script reaches after about 160 characters.
 */
const val MAX_SPEECH_LENGTH = 150

/**
 * What happens when the user lets go of the button: the Tamil they just spoke is translated and
 * written into the field they were in, replacing it, with what was there kept for undo.
 *
 * Nothing is read from the field — the words came from the microphone — and nothing is written unless
 * the translation actually arrived.
 */
class SpokenTranslator(
    private val fields: FieldProvider,
    private val clipboard: Clipboard,
    private val translator: Translator,
) {

    private class Snapshot(val field: EditableField, val raw: String)

    private var undoSnapshot: Snapshot? = null

    /** Whether the last speech translation can still be put back. */
    val canUndo: Boolean get() = undoSnapshot != null

    suspend fun translateSpoken(spoken: String): ButtonOutcome {
        undoSnapshot = null
        val clean = normaliseSentence(spoken)
        if (clean.isEmpty()) {
            return ButtonOutcome.Blocked(BlockReason.EmptyField, HEARD_NOTHING)
        }
        if (clean.length > MAX_SPEECH_LENGTH) {
            return ButtonOutcome.Blocked(
                BlockReason.TooLong,
                "That was a long one — speak up to $MAX_SPEECH_LENGTH characters at a time",
            )
        }

        return when (val outcome = translator.translateSentence(clean)) {
            is TranslationOutcome.Translated -> write(outcome.text, outcome.source, outcome.note)

            // No offline fallback here either: the field keeps what it had and the button says why.
            is TranslationOutcome.Unavailable -> ButtonOutcome.Blocked(BlockReason.Failed, outcome.reason)

            TranslationOutcome.Blank -> ButtonOutcome.Blocked(BlockReason.EmptyField, HEARD_NOTHING)
        }
    }

    /** Puts back whatever the field held before the spoken English replaced it. */
    fun undo(): Boolean {
        val snapshot = undoSnapshot ?: return false
        val restored = snapshot.field.replace(snapshot.raw)
        if (restored) undoSnapshot = null
        return restored
    }

    private fun write(text: String, source: TranslationSource, note: String?): ButtonOutcome {
        val field = fields.focusedField()
            ?: return copy(text, source, "No text field in front — the English is on the clipboard")

        if (field.isPassword) {
            return copy(text, source, "That is a password field — the English is on the clipboard")
        }

        val before = field.read().orEmpty()
        if (field.replace(text)) {
            undoSnapshot = Snapshot(field, before)
            return ButtonOutcome.Replaced(text, source, describe(source, note))
        }
        return copy(text, source, "This app blocked the edit — the English is on the clipboard")
    }

    private fun copy(text: String, source: TranslationSource, message: String): ButtonOutcome {
        clipboard.write(text)
        return ButtonOutcome.Copied(text, source, message)
    }

    private fun describe(source: TranslationSource, note: String?): String = when (source) {
        TranslationSource.Ai -> note ?: "Spoken Tamil, written in English"
        TranslationSource.Cache -> "Reused a saved translation of what you said"
    }

    private companion object {
        const val HEARD_NOTHING = "I did not catch that — hold the button and speak Tamil"
    }
}
