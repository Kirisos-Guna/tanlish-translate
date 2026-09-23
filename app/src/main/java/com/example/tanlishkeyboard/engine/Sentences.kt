package com.example.tanlishkeyboard.engine

private val WHITESPACE = Regex("\\s+")

/**
 * The canonical form of a sentence: lowercased, runs of whitespace collapsed, trimmed.
 *
 * Used both as the cache key and as the text handed to the AI, so the same phrase typed with stray
 * spaces or capitals is the same phrase.
 */
fun normaliseSentence(raw: String): String = WHITESPACE.replace(raw.lowercase(), " ").trim()
