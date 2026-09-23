package com.example.tanlishkeyboard.data

import android.content.Context
import android.content.SharedPreferences

class SharedPreferencesStore(private val preferences: SharedPreferences) : KeyValueStore {

    override fun getString(key: String): String? = preferences.getString(key, null)

    override fun putString(key: String, value: String?) {
        val editor = preferences.edit()
        if (value == null) editor.remove(key) else editor.putString(key, value)
        editor.apply()
    }

    companion object {
        const val PREFS_NAME = "tanlish_keyboard"

        fun forApp(context: Context): SharedPreferencesStore =
            SharedPreferencesStore(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    }
}
