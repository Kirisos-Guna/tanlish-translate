package com.example.tanlishkeyboard.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the asset that actually ships, so a malformed row or a stray English string in the Tamil
 * column is caught here instead of on a phone.
 */
class BundledGlossaryTest {

    private fun glossaryFile(): File {
        val candidates = listOf(
            File("src/main/assets/glossary.tsv"),
            File("app/src/main/assets/glossary.tsv"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("Could not locate glossary.tsv from ${File(".").absolutePath}")
    }

    @Test
    fun `bundled glossary parses and is substantial`() {
        val glossary = File(glossaryFile().path).inputStream().use { TanglishGlossary.parse(it) }
        assertTrue("expected a useful vocabulary, found ${glossary.size}", glossary.size > 250)
    }

    @Test
    fun `every tamil column contains tamil script only`() {
        val offenders = mutableListOf<String>()
        glossaryFile().readLines().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val tamil = line.split('\t').getOrNull(2)?.trim().orEmpty()
            if (tamil.isEmpty()) return@forEach
            val wrong = tamil.filter { it.code !in 0x0B80..0x0BFF && !it.isWhitespace() }
            if (wrong.isNotEmpty()) offenders += "$line (found '$wrong')"
        }
        assertTrue("non Tamil characters in the Tamil column: $offenders", offenders.isEmpty())
    }

    @Test
    fun `core conversational words are covered`() {
        val glossary = glossaryFile().inputStream().use { TanglishGlossary.parse(it) }
        val mustHave = listOf("vanakkam", "nandri", "epdi", "enna", "illa", "romba", "sapadu", "naan")
        val missing = mustHave.filter { glossary.englishFor(it) == null }
        assertTrue("missing glossary entries: $missing", missing.isEmpty())
    }
}
