package com.example.tanlishkeyboard.speech

import android.os.Handler
import android.os.Looper

/**
 * Delayed work, on whichever thread the caller is already on.
 *
 * A listening run has two timers of its own — the wait for a recogniser that has been asked to stop,
 * and the gap before the next session starts — and the Android recogniser insists on being driven
 * from the main thread. Behind this interface both timers can be run by hand in a test, which is the
 * only way the rules around them can be checked without a phone.
 */
internal interface Scheduler {

    fun after(delayMs: Long, task: Runnable)

    fun cancel(task: Runnable)
}

internal class MainThreadScheduler(
    private val handler: Handler = Handler(Looper.getMainLooper()),
) : Scheduler {

    override fun after(delayMs: Long, task: Runnable) {
        handler.postDelayed(task, delayMs)
    }

    override fun cancel(task: Runnable) {
        handler.removeCallbacks(task)
    }
}
