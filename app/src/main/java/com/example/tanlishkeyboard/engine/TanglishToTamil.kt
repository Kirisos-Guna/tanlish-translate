package com.example.tanlishkeyboard.engine

/**
 * Tanglish (Tamil written in Latin letters) to Tamil script, using phonetic rules.
 *
 * This is the offline fallback for the Tamil mode, not the main feature: romanisation cannot tell
 * the Tamil letter variants apart (ந/ண/ன, த/ட, ர/ற ...), so "vanakkam" comes out as வநக்கம் rather
 * than வணக்கம். The bundled glossary is consulted first for known words; this engine handles
 * everything else.
 */
class TanglishToTamil {

    fun transliterate(input: String): String {
        val text = input.lowercase()
        val out = StringBuilder()
        var pending: String? = null
        var index = 0

        fun flushPending() {
            pending?.let { out.append(it).append(PULLI) }
            pending = null
        }

        while (index < text.length) {
            val char = text[index]
            if (!isAsciiLetter(char)) {
                flushPending()
                out.append(char)
                index++
                continue
            }

            val match = matchUnit(text, index)
            if (match == null) {
                flushPending()
                out.append(char)
                index++
                continue
            }

            when (val unit = match.unit) {
                is Unit.Consonant -> {
                    flushPending()
                    pending = unit.letter
                }

                is Unit.Vowel -> {
                    val held = pending
                    if (held == null) {
                        out.append(unit.independent)
                    } else {
                        out.append(held).append(unit.sign)
                        pending = null
                    }
                }

                is Unit.Syllable -> {
                    flushPending()
                    out.append(unit.text)
                }
            }
            index += match.length
        }
        flushPending()
        return out.toString()
    }

    private fun matchUnit(text: String, start: Int): Match? {
        val longest = minOf(MAX_TOKEN_LENGTH, text.length - start)
        for (length in longest downTo 1) {
            val unit = UNITS[text.substring(start, start + length)]
            if (unit != null) return Match(unit, length)
        }
        return null
    }

    private fun isAsciiLetter(char: Char): Boolean = char in 'a'..'z'

    private class Match(val unit: Unit, val length: Int)

    private sealed interface Unit {
        class Consonant(val letter: String) : Unit
        class Vowel(val independent: String, val sign: String) : Unit

        /** A unit that is already a complete syllable, e.g. ஸ்ரீ. */
        class Syllable(val text: String) : Unit
    }

    private companion object {
        const val PULLI = '\u0BCD'
        const val MAX_TOKEN_LENGTH = 3

        val UNITS: Map<String, Unit> = buildMap {
            // Complete syllables
            put("sri", Unit.Syllable("ஸ்ரீ"))
            put("ksh", Unit.Syllable("க்ஷ"))
            put("x", Unit.Syllable("க்ஸ்"))

            // Consonants: the ligature spellings first, then single letters
            put("ng", Unit.Consonant("ங"))
            put("nj", Unit.Consonant("ஞ"))
            put("zh", Unit.Consonant("ழ"))
            put("sh", Unit.Consonant("ஷ"))
            put("th", Unit.Consonant("த"))
            put("dh", Unit.Consonant("த"))
            put("kh", Unit.Consonant("க"))
            put("gh", Unit.Consonant("க"))
            put("ch", Unit.Consonant("ச"))
            put("ph", Unit.Consonant("ப"))
            put("bh", Unit.Consonant("ப"))
            put("jh", Unit.Consonant("ஜ"))
            put("k", Unit.Consonant("க"))
            put("g", Unit.Consonant("க"))
            put("q", Unit.Consonant("க"))
            put("c", Unit.Consonant("ச"))
            put("s", Unit.Consonant("ச"))
            put("j", Unit.Consonant("ஜ"))
            put("z", Unit.Consonant("ஜ"))
            put("t", Unit.Consonant("த"))
            put("d", Unit.Consonant("த"))
            put("n", Unit.Consonant("ந"))
            put("p", Unit.Consonant("ப"))
            put("b", Unit.Consonant("ப"))
            put("f", Unit.Consonant("ப"))
            put("m", Unit.Consonant("ம"))
            put("y", Unit.Consonant("ய"))
            put("r", Unit.Consonant("ர"))
            put("l", Unit.Consonant("ல"))
            put("v", Unit.Consonant("வ"))
            put("w", Unit.Consonant("வ"))
            put("h", Unit.Consonant("ஹ"))

            // Vowels: the long forms must be registered as whole tokens
            put("aa", Unit.Vowel("ஆ", "\u0BBE"))
            put("ae", Unit.Vowel("ஏ", "\u0BC7"))
            put("ea", Unit.Vowel("ஏ", "\u0BC7"))
            put("ai", Unit.Vowel("ஐ", "\u0BC8"))
            put("au", Unit.Vowel("ஔ", "\u0BCC"))
            put("ou", Unit.Vowel("ஔ", "\u0BCC"))
            put("ow", Unit.Vowel("ஔ", "\u0BCC"))
            put("ee", Unit.Vowel("ஈ", "\u0BC0"))
            put("ii", Unit.Vowel("ஈ", "\u0BC0"))
            // "oo" is the everyday spelling of ஊ/ூ (poo, kooda, thookam); "oa" writes ஓ/ோ.
            put("oo", Unit.Vowel("ஊ", "\u0BC2"))
            put("oa", Unit.Vowel("ஓ", "\u0BCB"))
            put("uu", Unit.Vowel("ஊ", "\u0BC2"))
            put("a", Unit.Vowel("அ", ""))
            put("e", Unit.Vowel("எ", "\u0BC6"))
            put("i", Unit.Vowel("இ", "\u0BBF"))
            put("o", Unit.Vowel("ஒ", "\u0BCA"))
            put("u", Unit.Vowel("உ", "\u0BC1"))
        }
    }
}
