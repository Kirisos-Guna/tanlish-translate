package com.example.tanlishkeyboard.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.tanlishkeyboard.data.SharedPreferencesStore
import com.example.tanlishkeyboard.data.Settings

/**
 * Puts the button back on screen after a restart, but only when the user left it switched on.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val settings = Settings(SharedPreferencesStore.forApp(context))
        if (!settings.bubbleEnabled) return
        BubbleService.start(context)
    }
}
