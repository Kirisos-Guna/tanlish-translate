package com.example.tanlishkeyboard.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TranslationCacheTest {

    @get:Rule
    val folder = TemporaryFolder()

    private var tick = 1_000L
    private val clock: () -> Long = { tick++ }

    private fun cache(file: File?, maxEntries: Int = 500) =
        TranslationCache(file = file, maxEntries = maxEntries, clock = clock)

    @Test
    fun `stores and normalises sentences`() {
        val subject = cache(null)
        subject.put("Vanakkam   Nanba", "Hello friend")

        assertEquals("Hello friend", subject.get("vanakkam nanba"))
        assertEquals("Hello friend", subject.get("  VANAKKAM NANBA  "))
        assertNull(subject.get("something else"))
    }

    @Test
    fun `ignores blank input`() {
        val subject = cache(null)
        subject.put("   ", "hello")
        subject.put("vanakkam", "   ")
        assertEquals(0, subject.size())
    }

    @Test
    fun `survives a restart through the cache file`() {
        val file = File(folder.root, "translations.tsv")
        cache(file).put("vanakkam nanba", "Hello friend")

        assertEquals("Hello friend", cache(file).get("vanakkam nanba"))
    }

    @Test
    fun `cache file round trips tabs newlines and tamil`() {
        val file = File(folder.root, "translations.tsv")
        cache(file).put("nandri nanba\ttabbed", "Thank you\tfriend\nநன்றி")

        assertEquals("Thank you\tfriend\nநன்றி", cache(file).get("nandri nanba tabbed"))
    }

    @Test
    fun `oldest entries are evicted past the limit`() {
        val subject = cache(null, maxEntries = 2)
        subject.put("one", "1")
        subject.put("two", "2")
        subject.put("three", "3")

        assertEquals(2, subject.size())
        assertNull(subject.get("one"))
        assertEquals("2", subject.get("two"))
        assertEquals("3", subject.get("three"))
    }

    @Test
    fun `reading refreshes an entry so it is evicted last`() {
        val subject = cache(null, maxEntries = 2)
        subject.put("one", "1")
        subject.put("two", "2")
        subject.get("one")
        subject.put("three", "3")

        assertEquals("1", subject.get("one"))
        assertNull(subject.get("two"))
    }

    @Test
    fun `clear removes memory and file contents`() {
        val file = File(folder.root, "translations.tsv")
        val subject = cache(file)
        subject.put("vanakkam", "hello")
        subject.clear()

        assertEquals(0, subject.size())
        assertEquals(0, cache(file).size())
    }

    @Test
    fun `a corrupt cache file is ignored instead of crashing`() {
        val file = File(folder.root, "translations.tsv")
        file.writeText("garbage\nonly-two\tcolumns\n")

        assertEquals(0, cache(file).size())
    }
}
