package com.example.tanlishkeyboard.speech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * The one permission listening needs.
 *
 * It is asked for from the app's own screen, never from the floating button: Android only shows the
 * dialog to a visible app, and the button spends its life floating over somebody else's.
 */
object Microphone {

    const val PERMISSION = Manifest.permission.RECORD_AUDIO

    /** Whether the user has already allowed listening. */
    fun granted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED
}
