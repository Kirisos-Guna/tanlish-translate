package com.example.tanlishkeyboard.engine

import java.io.InputStream
import java.io.Reader

/** One glossary row: the Tanglish spelling, its English gloss and an optional Tamil spelling. */
data class GlossaryEntry(
    val tanglish: String,
    val english: String,
    val tamil: String?,
) {
    val hasTamil: Boolean get() = !tamil.isNullOrEmpty()
}

/**
 * The bundled Tanglish vocabulary.
 *
 * Two things read this: the keyboard's suggestion strip (instant, offline, no network) and the
 * offline fallbacks used when the AI translation is unavailable.
 */
class TanglishGlossary private constructor(private val entries: Map<String, GlossaryEntry>) {

    val size: Int get() = entries.size

    fun entry(text: String): GlossaryEntry? = entries[normalise(text)]

    fun englishFor(text: String): String? = entry(text)?.english

    fun tamilFor(text: String): String? = entry(text)?.tamil?.takeIf { it.isNotEmpty() }

    /** Prefix suggestions for the word currently being typed, shortest match first. */
    fun suggestions(prefix: String, limit: Int = 4): List<GlossaryEntry> {
        val key = normalise(stripEdgePunctuation(prefix))
        if (key.isEmpty()) return emptyList()
        return entries.values
            .asSequence()
            .filter { it.tanglish.startsWith(key) }
            .sortedWith(compareBy({ it.tanglish.length }, { it.tanglish }))
            .take(limit)
            .toList()
    }

    /**
     * Word-by-word offline gloss, used when the AI is unreachable or out of quota.
     * Words that are not in the glossary are passed through untouched.
     * Returns null when nothing at all was recognised.
     */
    fun glossWordByWord(text: String): String? {
        val tokens = text.split(WHITESPACE).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null
        var hits = 0
        val glossed = tokens.map { token ->
            val found = entries[stripEdgePunctuation(token).lowercase()]
            if (found == null) {
                token
            } else {
                hits++
                found.english
            }
        }
        return if (hits == 0) null else glossed.joinToString(" ")
    }

    companion object {
        private val WHITESPACE = Regex("\\s+")

        /** Lowercase, collapse runs of whitespace and trim — the canonical lookup key. */
        fun normalise(raw: String): String = WHITESPACE.replace(raw.lowercase(), " ").trim()

        fun stripEdgePunctuation(raw: String): String = raw.trim { !it.isLetterOrDigit() }

        fun parse(input: InputStream): TanglishGlossary = input.bufferedReader().use { parseReader(it) }

        /** Text-based entry point so tests do not need Android assets. */
        fun parseText(text: String): TanglishGlossary = parseReader(text.reader())

        private fun parseReader(reader: Reader): TanglishGlossary {
            val map = LinkedHashMap<String, GlossaryEntry>()
            reader.forEachLine { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachLine
                val parts = line.split('\t')
                if (parts.size < 2) return@forEachLine
                val tanglish = parts[0].trim().lowercase()
                val english = parts[1].trim()
                if (tanglish.isEmpty() || english.isEmpty()) return@forEachLine
                val tamil = parts.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() }
                map[tanglish] = GlossaryEntry(tanglish, english, tamil)
            }
            return TanglishGlossary(map)
        }
    }
}
