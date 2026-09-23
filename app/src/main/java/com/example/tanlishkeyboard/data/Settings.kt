package com.example.tanlishkeyboard.data

/**
 * User facing switches and remembered window state.
 *
 * Networking is opt-in: nothing is sent anywhere until the user consents, because the button is
 * allowed to read text in other apps.
 */
class Settings(private val store: KeyValueStore) {

    /** Whether the button may call the AI translation endpoint at all. */
    var allowNetwork: Boolean
        get() = store.getString(KEY_ALLOW_NETWORK)?.toBooleanStrictOrNull() ?: true
        set(value) = store.putString(KEY_ALLOW_NETWORK, value.toString())

    /** Set once the user has accepted that the text they translate is sent to easytamiltyping.com. */
    var aiConsentGiven: Boolean
        get() = store.getString(KEY_AI_CONSENT)?.toBooleanStrictOrNull() ?: false
        set(value) = store.putString(KEY_AI_CONSENT, value.toString())

    /** Whether the floating button should be on screen; it is what the service restores from. */
    var bubbleEnabled: Boolean
        get() = store.getString(KEY_BUBBLE_ENABLED)?.toBooleanStrictOrNull() ?: false
        set(value) = store.putString(KEY_BUBBLE_ENABLED, value.toString())

    /** Where the user last left the button, in pixels, or null before it has been dragged. */
    var bubbleX: Int?
        get() = store.getString(KEY_BUBBLE_X)?.toIntOrNull()
        set(value) = store.putString(KEY_BUBBLE_X, value?.toString())

    var bubbleY: Int?
        get() = store.getString(KEY_BUBBLE_Y)?.toIntOrNull()
        set(value) = store.putString(KEY_BUBBLE_Y, value?.toString())

    /**
     * When the accessibility service last connected, or 0 if never.
     *
     * Android's own "is my service enabled?" answer is unreliable across OEMs, so the service
     * reports this itself, the same way the keyboard used to.
     */
    var accessibilitySeenAtMillis: Long
        get() = store.getString(KEY_ACCESSIBILITY_SEEN)?.toLongOrNull() ?: 0L
        set(value) = store.putString(KEY_ACCESSIBILITY_SEEN, value.toString())

    private companion object {
        const val KEY_ALLOW_NETWORK = "allow_network"
        const val KEY_AI_CONSENT = "ai_consent_given"
        const val KEY_BUBBLE_ENABLED = "bubble_enabled"
        const val KEY_BUBBLE_X = "bubble_x"
        const val KEY_BUBBLE_Y = "bubble_y"
        const val KEY_ACCESSIBILITY_SEEN = "accessibility_seen_at"
    }
}
