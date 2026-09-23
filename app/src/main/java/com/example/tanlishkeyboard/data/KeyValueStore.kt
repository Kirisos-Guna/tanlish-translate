package com.example.tanlishkeyboard.data

/**
 * Tiny key/value abstraction so the settings and quota code can be unit tested without Android.
 */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
}

/** In-memory store for tests. */
class MemoryKeyValueStore : KeyValueStore {
    private val values = HashMap<String, String>()

    override fun getString(key: String): String? = values[key]

    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}
