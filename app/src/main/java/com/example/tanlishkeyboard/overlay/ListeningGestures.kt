package com.example.tanlishkeyboard.overlay

import com.example.tanlishkeyboard.data.ListeningMode

/** What a gesture on the floating button means right now. */
enum class BubbleGesture { TranslateField, StartListening, StopListening, Nothing }

/**
 * The button's press handling, as arithmetic rather than Android.
 *
 * The same press means different things depending on whether a session is already running and on
 * which [ListeningMode] the user chose, and getting it wrong either leaves the microphone open or
 * writes half a sentence. So the decision lives here, pure and unit tested, and [BubbleService] only
 * carries out what it is told.
 */
object ListeningGestures {

    /** A finger going down and coming straight back up. */
    fun tap(listening: Boolean, translatingSpeech: Boolean, mode: ListeningMode): BubbleGesture = when {
        // Tapped rather than held: mid-session, the only reason to touch the button is to finish.
        listening && mode == ListeningMode.UntilTapped -> BubbleGesture.StopListening

        // Held: the release is what ends the session, so a tap under the finger is part of that hold.
        listening -> BubbleGesture.Nothing

        // The words just spoken are still being translated; nothing may interrupt that.
        translatingSpeech -> BubbleGesture.Nothing

        else -> BubbleGesture.TranslateField
    }

    /** A press that outlasted the long press threshold. */
    fun hold(listening: Boolean): BubbleGesture =
        if (listening) BubbleGesture.Nothing else BubbleGesture.StartListening

    /** The finger coming up, whether it was a tap or the end of a hold. */
    fun release(listening: Boolean, mode: ListeningMode): BubbleGesture =
        if (listening && mode == ListeningMode.WhileHeld) {
            BubbleGesture.StopListening
        } else {
            // Tapped mode keeps listening after the finger leaves, which is the whole point of it.
            BubbleGesture.Nothing
        }

    /**
     * What the pill says while a session runs. The listening notice says this too: with the keyboard
     * gone, that notice is often the only thing telling the user how to stop.
     */
    fun listeningHint(mode: ListeningMode): String = when (mode) {
        ListeningMode.WhileHeld -> "Listening… speak Tamil, then let go"
        ListeningMode.UntilTapped -> "Listening… speak Tamil, then tap the button"
    }

    /**
     * The pill once words start arriving, which is where the reading-back wins over the instruction.
     * A session closed by a tap keeps one reminder of how to close it, because the finger is already
     * away from the button by then.
     */
    fun hearingHint(text: String, mode: ListeningMode): String = when (mode) {
        ListeningMode.WhileHeld -> "Hearing “$text”"
        ListeningMode.UntilTapped -> "Hearing “$text” · tap to stop"
    }
}
