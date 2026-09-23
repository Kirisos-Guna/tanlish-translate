package com.example.tanlishkeyboard.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rule engine is an offline fallback, so these tests lock in the behaviour we accept rather
 * than perfect Tamil. Where romanisation is ambiguous (ந/ண/ன, த/ட) the engine picks one variant
 * and the bundled glossary is what fixes common words.
 */
class TanglishToTamilTest {

    private val engine = TanglishToTamil()

    @Test
    fun `empty input stays empty`() {
        assertEquals("", engine.transliterate(""))
    }

    @Test
    fun `vowels and consonant plus vowel signs combine`() {
        assertEquals("அம்ம", engine.transliterate("amma"))
        assertEquals("தம்பி", engine.transliterate("thambi"))
        assertEquals("காதல்", engine.transliterate("kaadhal"))
        assertEquals("பு", engine.transliterate("pu"))
    }

    @Test
    fun `oo is read as the long u sound used in everyday spelling`() {
        assertEquals("பூ", engine.transliterate("poo"))
        assertEquals("தூக்கம்", engine.transliterate("thookkam"))
        assertEquals("கோ", engine.transliterate("koa"))
        // The variants stay out of reach of romanisation: d and t both give த.
        assertEquals("கூது", engine.transliterate("koodu"))
    }

    @Test
    fun `word final consonant gets a pulli`() {
        assertEquals("நாந்", engine.transliterate("naan"))
    }

    @Test
    fun `written as documented for ambiguous spellings`() {
        // வணக்கம் would need the glossary; the rules cannot tell ண from ந.
        assertEquals("வநக்கம்", engine.transliterate("vanakkam"))
    }

    @Test
    fun `complete syllables and punctuation pass through`() {
        assertEquals("ஸ்ரீ", engine.transliterate("sri"))
        assertEquals("ஹி!", engine.transliterate("Hi!"))
        assertEquals("வநக்கம்!", engine.transliterate("vanakkam!"))
    }

    @Test
    fun `non latin input is left alone`() {
        assertEquals("வணக்கம்", engine.transliterate("வணக்கம்"))
        assertEquals("12:30", engine.transliterate("12:30"))
    }
}
