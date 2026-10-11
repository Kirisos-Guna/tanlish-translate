package com.example.tanlishkeyboard.net

/**
 * Finds one named field anywhere in a JSON reply.
 *
 * The two free web translators nest their answer differently — MyMemory puts the English one object
 * deep inside eight flat fields — and this is written by hand for the same reason the rest of the
 * network layer is: `org.json` is only a stub outside an Android runtime, so the network layer stays
 * testable on the JVM. Google's reply is a bare array rather than an object, and is read by
 * [GoogleTranslateApi] itself.
 */
internal class JsonScan(private val text: String) {

    /** The decoded string value of the first `"key"`, or null when there is none or it is not a string. */
    fun string(key: String): String? {
        val raw = rawValue(key) ?: return null
        return if (raw.startsWith("\"")) decode(raw) else null
    }

    /** The raw token of the first `"key"`: `true`, `false`, a number, or a quoted string. */
    fun rawValue(key: String): String? {
        val needle = "\"$key\""
        var searchFrom = 0
        while (true) {
            val at = text.indexOf(needle, searchFrom)
            if (at < 0) return null
            val colon = skipWhitespace(at + needle.length)
            if (colon < text.length && text[colon] == ':') {
                readToken(skipWhitespace(colon + 1))?.let { return it }
            }
            searchFrom = at + needle.length
        }
    }

    /** The token starting at [start], exactly as it appears, so `true` stays `true`. */
    private fun readToken(start: Int): String? {
        if (start >= text.length) return null
        if (text[start] == '"') {
            var index = start + 1
            while (index < text.length) {
                when (text[index]) {
                    '\\' -> index += 2
                    '"' -> return text.substring(start, index + 1)
                    else -> index++
                }
            }
            return null
        }
        var end = start
        while (end < text.length && text[end] !in ",}]") end++
        return text.substring(start, end).trim().ifEmpty { null }
    }

    private fun skipWhitespace(start: Int): Int {
        var index = start
        while (index < text.length && text[index].isWhitespace()) index++
        return index
    }

    private fun decode(quoted: String): String {
        val out = StringBuilder(quoted.length)
        var index = 1
        while (index < quoted.length - 1) {
            val char = quoted[index]
            if (char != '\\') {
                out.append(char)
                index++
                continue
            }
            index++
            if (index >= quoted.length - 1) break
            when (val escape = quoted[index]) {
                'b' -> out.append('\b')
                'f' -> out.append('\u000C')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'u' -> {
                    val end = minOf(index + 5, quoted.length - 1)
                    val codePoint = quoted.substring(index + 1, end).toIntOrNull(16)
                    if (codePoint == null) return out.toString()
                    out.append(codePoint.toChar())
                    index += 4
                }
                else -> out.append(escape)
            }
            index++
        }
        return out.toString()
    }
}
