package com.example.tanlishkeyboard.data

/**
 * User facing switches and remembered window state.
 *
 * [allowNetwork] is the only control over what leaves the phone: the button reads the field you are
 * in, so the switch that stops it talking to the translation endpoint is deliberately explicit.
 */
class Settings(private val store: KeyValueStore) {

    /** Whether the button may call the AI translation endpoint at all. */
    var allowNetwork: Boolean
        get() = store.getString(KEY_ALLOW_NETWORK)?.toBooleanStrictOrNull() ?: true
        set(value) = store.putString(KEY_ALLOW_NETWORK, value.toString())

    /**
     * How opaque the floating button is drawn, 0.2 to 1.0.
     *
     * The default is what the button has always looked like: a dark circle you can see through just
     * enough to read what is underneath it.
     */
    var bubbleOpacity: Float
        get() = store.getString(KEY_BUBBLE_OPACITY)
            ?.toFloatOrNull()
            ?.coerceIn(MIN_BUBBLE_OPACITY, MAX_BUBBLE_OPACITY)
            ?: DEFAULT_BUBBLE_OPACITY
        set(value) = store.putString(
            KEY_BUBBLE_OPACITY,
            value.coerceIn(MIN_BUBBLE_OPACITY, MAX_BUBBLE_OPACITY).toString(),
        )

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
     * reports this itself.
     */
    var accessibilitySeenAtMillis: Long
        get() = store.getString(KEY_ACCESSIBILITY_SEEN)?.toLongOrNull() ?: 0L
        set(value) = store.putString(KEY_ACCESSIBILITY_SEEN, value.toString())

    companion object {
        /** Never quite invisible, or the button could not be found again. */
        const val MIN_BUBBLE_OPACITY = 0.2f
        const val MAX_BUBBLE_OPACITY = 1f

        /** The look the button shipped with: 0xC2 alpha. */
        const val DEFAULT_BUBBLE_OPACITY = 0.76f

        private const val KEY_ALLOW_NETWORK = "allow_network"
        private const val KEY_BUBBLE_OPACITY = "bubble_opacity"
        private const val KEY_BUBBLE_ENABLED = "bubble_enabled"
        private const val KEY_BUBBLE_X = "bubble_x"
        private const val KEY_BUBBLE_Y = "bubble_y"
        private const val KEY_ACCESSIBILITY_SEEN = "accessibility_seen_at"
    }
}
