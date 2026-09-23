package com.example.tanlishkeyboard.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TanglishGlossaryTest {

    private val glossary = TanglishGlossary.parseText(
        """
        # a comment line
        vanakkam	hello	வணக்கம்

        nandri	thank you	நன்றி
        epdi irukka	how are you	எப்படி இருக்க
        epdi	how	எப்படி
        romba	very	
        broken-line-without-tab
        """.trimIndent(),
    )

    @Test
    fun `comments blanks and malformed lines are skipped`() {
        assertEquals(5, glossary.size)
        assertNull(glossary.englishFor("broken-line-without-tab"))
    }

    @Test
    fun `lookup ignores case and extra whitespace`() {
        assertEquals("hello", glossary.englishFor("  VANAKKAM "))
        assertEquals("how are you", glossary.englishFor("epdi   irukka"))
    }

    @Test
    fun `tamil column is optional`() {
        assertEquals("வணக்கம்", glossary.tamilFor("vanakkam"))
        assertNull(glossary.tamilFor("romba"))
    }

    @Test
    fun `suggestions match the word being typed`() {
        val suggestions = glossary.suggestions("epdi", 5)
        assertEquals(listOf("epdi", "epdi irukka"), suggestions.map { it.tanglish })
    }

    @Test
    fun `suggestions handle trailing punctuation and empty prefixes`() {
        assertEquals(listOf("vanakkam"), glossary.suggestions("van,", 5).map { it.tanglish })
        assertTrue(glossary.suggestions("", 5).isEmpty())
    }

    @Test
    fun `word by word gloss keeps unknown words and reports no hits`() {
        // nanba is not in this fixture, so it is passed through untouched.
        assertEquals("hello nanba thank you", glossary.glossWordByWord("vanakkam nanba nandri"))
        assertEquals("hello nanba", glossary.glossWordByWord("vanakkam nanba"))
        assertNull(glossary.glossWordByWord("xyz abc"))
        assertNull(glossary.glossWordByWord("   "))
    }

    @Test
    fun `word by word gloss ignores surrounding punctuation`() {
        assertEquals("thank you", glossary.glossWordByWord("nandri."))
    }
}
