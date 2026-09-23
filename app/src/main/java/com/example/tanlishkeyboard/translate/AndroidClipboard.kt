package com.example.tanlishkeyboard.translate

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * The Android clipboard behind the [Clipboard] port.
 *
 * Writing always works. Reading is only allowed for the app the user is looking at, so in the
 * background [read] returns null and the button asks the user to tap a field instead of guessing.
 */
class AndroidClipboard(context: Context) : Clipboard {

    private val manager: ClipboardManager? = context.getSystemService(ClipboardManager::class.java)

    override fun read(): String? = runCatching {
        val clip = manager?.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        clip.getItemAt(0).text?.toString()
    }.getOrNull()

    override fun write(text: String) {
        runCatching { manager?.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text)) }
    }

    private companion object {
        const val CLIP_LABEL = "Tanlish translation"
    }
}
