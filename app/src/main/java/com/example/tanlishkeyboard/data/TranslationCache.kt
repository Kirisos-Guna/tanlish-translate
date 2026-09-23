package com.example.tanlishkeyboard.data

import com.example.tanlishkeyboard.engine.normaliseSentence
import java.io.File

/**
 * Local cache of AI translations, keyed by the normalised Tanglish sentence.
 *
 * This is what makes a 15-calls-per-hour backend usable: a phrase is only ever paid for once.
 * Backed by a small tab separated file so it survives restarts.
 */
class TranslationCache(
    private val file: File? = null,
    private val maxEntries: Int = 500,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private class Entry(val text: String, var lastUsed: Long)

    private val entries = LinkedHashMap<String, Entry>()
    private var loaded = false

    @Synchronized
    fun get(sentence: String): String? {
        ensureLoaded()
        val entry = entries[key(sentence)] ?: return null
        entry.lastUsed = clock()
        return entry.text
    }

    @Synchronized
    fun put(sentence: String, translation: String) {
        ensureLoaded()
        val key = key(sentence)
        if (key.isEmpty() || translation.isBlank()) return
        entries[key] = Entry(translation, clock())
        trim()
        persist()
    }

    @Synchronized
    fun size(): Int {
        ensureLoaded()
        return entries.size
    }

    @Synchronized
    fun clear() {
        ensureLoaded()
        entries.clear()
        persist()
    }

    private fun key(sentence: String): String = normaliseSentence(sentence)

    private fun trim() {
        if (entries.size <= maxEntries) return
        val overflow = entries.entries.sortedBy { it.value.lastUsed }.take(entries.size - maxEntries)
        overflow.forEach { entries.remove(it.key) }
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val target = file ?: return
        if (!target.exists()) return
        runCatching {
            target.readLines().forEach { line ->
                if (line.isBlank()) return@forEach
                val parts = line.split('\t')
                if (parts.size < 3) return@forEach
                val storedKey = unescape(parts[0])
                val text = unescape(parts[1])
                val lastUsed = parts[2].toLongOrNull() ?: clock()
                if (storedKey.isNotEmpty() && text.isNotEmpty()) {
                    entries[storedKey] = Entry(text, lastUsed)
                }
            }
        }
        trim()
    }

    private fun persist() {
        val target = file ?: return
        runCatching {
            target.parentFile?.mkdirs()
            target.writeText(
                buildString {
                    entries.forEach { (storedKey, entry) ->
                        append(escape(storedKey)).append('\t')
                            .append(escape(entry.text)).append('\t')
                            .append(entry.lastUsed).append('\n')
                    }
                },
            )
        }
    }

    private fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\t", "\\t")
        .replace("\n", "\\n")
        .replace("\r", "\\r")

    private fun unescape(value: String): String {
        val out = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == '\\' && index + 1 < value.length) {
                when (value[index + 1]) {
                    't' -> { out.append('\t'); index += 2 }
                    'n' -> { out.append('\n'); index += 2 }
                    'r' -> { out.append('\r'); index += 2 }
                    '\\' -> { out.append('\\'); index += 2 }
                    else -> { out.append(char); index++ }
                }
            } else {
                out.append(char)
                index++
            }
        }
        return out.toString()
    }
}
