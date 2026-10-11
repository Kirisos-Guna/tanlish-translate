package com.example.tanlishkeyboard.data

import android.content.Context
import com.example.tanlishkeyboard.engine.Translator
import com.example.tanlishkeyboard.net.EasyTamilTypingApi
import com.example.tanlishkeyboard.net.TamilToEnglishApi
import java.io.File

/**
 * Process wide wiring shared by the floating button, the accessibility service and the app screen,
 * so all three use the same cache, quota book keeping and settings.
 */
class AppServices private constructor(context: Context) {

    val cache = TranslationCache(File(context.filesDir, CACHE_FILE))
    val store: KeyValueStore = SharedPreferencesStore.forApp(context)
    val quota = QuotaTracker(store)
    val settings = Settings(store)
    val translator = Translator(cache, quota, settings, EasyTamilTypingApi())

    /**
     * The spoken path: Tamil script from the microphone, translated by the free web translators.
     *
     * It keeps its own budget bookkeeping because the endpoint behind [translator] limits by the hour
     * and the keyless ones do not: a spent typed quota must not stop the user from speaking. Both
     * paths share the cache and the network switch.
     */
    private val spokenQuota = QuotaTracker(store, prefix = "spoken_")
    val spokenTranslator = Translator(cache, spokenQuota, settings, TamilToEnglishApi())

    companion object {
        private const val CACHE_FILE = "ai-translations.tsv"

        @Volatile
        private var instance: AppServices? = null

        fun get(context: Context): AppServices =
            instance ?: synchronized(this) {
                instance ?: AppServices(context.applicationContext).also { instance = it }
            }
    }
}
