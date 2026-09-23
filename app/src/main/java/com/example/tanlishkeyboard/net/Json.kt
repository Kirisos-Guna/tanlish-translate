package com.example.tanlishkeyboard.net

/**
 * Very small reader for flat JSON objects of strings, numbers, booleans and nulls — all the
 * translator endpoint returns. Written by hand so the network layer needs no dependency and stays
 * testable on the JVM (org.json is only a stub outside of an Android runtime).
 */
internal class JsonObject(private val values: Map<String, Any?>) {
    fun string(key: String): String? = values[key] as? String
    fun int(key: String): Int? = (values[key] as? String)?.toIntOrNull()
    fun long(key: String): Long? = (values[key] as? String)?.toLongOrNull()
    fun boolean(key: String): Boolean? = values[key] as? Boolean
}

internal object Json {

    fun parseObject(text: String): JsonObject? {
        val input = text.trim()
        if (input.length < 2 || input[0] != '{') return null

        val values = HashMap<String, Any?>()
        var index = 1
        while (index < input.length) {
            index = skipWhitespace(input, index)
            if (index >= input.length) return null
            when (input[index]) {
                '}' -> return JsonObject(values)
                ',' -> { index++; continue }
                '"' -> Unit
                else -> return null
            }

            val key = readString(input, index) ?: return null
            index = skipWhitespace(input, key.next)
            if (index >= input.length || input[index] != ':') return null
            index = skipWhitespace(input, index + 1)
            if (index >= input.length) return null

            when (val char = input[index]) {
                '"' -> {
                    val value = readString(input, index) ?: return null
                    values[key.value] = value.value
                    index = value.next
                }
                'n' -> {
                    if (!input.startsWith("null", index)) return null
                    values[key.value] = null
                    index += 4
                }
                't' -> {
                    if (!input.startsWith("true", index)) return null
                    values[key.value] = true
                    index += 4
                }
                'f' -> {
                    if (!input.startsWith("false", index)) return null
                    values[key.value] = false
                    index += 5
                }
                else -> {
                    if (char != '-' && !char.isDigit()) return null
                    var end = index
                    while (end < input.length && (input[end].isDigit() || input[end] in "-+.eE")) end++
                    if (end == index) return null
                    values[key.value] = input.substring(index, end)
                    index = end
                }
            }
        }
        return null
    }

    private class ParsedString(val value: String, val next: Int)

    private fun readString(input: String, start: Int): ParsedString? {
        if (start >= input.length || input[start] != '"') return null
        val out = StringBuilder()
        var index = start + 1
        while (index < input.length) {
            val char = input[index]
            when {
                char == '"' -> return ParsedString(out.toString(), index + 1)
                char == '\\' -> {
                    if (index + 1 >= input.length) return null
                    when (input[index + 1]) {
                        '"' -> { out.append('"'); index += 2 }
                        '\\' -> { out.append('\\'); index += 2 }
                        '/' -> { out.append('/'); index += 2 }
                        'b' -> { out.append('\b'); index += 2 }
                        'f' -> { out.append('\u000C'); index += 2 }
                        'n' -> { out.append('\n'); index += 2 }
                        'r' -> { out.append('\r'); index += 2 }
                        't' -> { out.append('\t'); index += 2 }
                        'u' -> {
                            if (index + 5 >= input.length) return null
                            val code = input.substring(index + 2, index + 6).toIntOrNull(16) ?: return null
                            out.append(code.toChar())
                            index += 6
                        }
                        else -> return null
                    }
                }
                else -> { out.append(char); index++ }
            }
        }
        return null
    }

    private fun skipWhitespace(input: String, start: Int): Int {
        var index = start
        while (index < input.length && input[index].isWhitespace()) index++
        return index
    }
}
