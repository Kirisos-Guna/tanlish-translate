package com.example.tanlishkeyboard.overlay

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.tanlishkeyboard.R
import com.example.tanlishkeyboard.data.Settings
import kotlinx.coroutines.withTimeoutOrNull

/** Diameter of the floating button, in dp. */
val BubbleSize = 56.dp

private val BubbleInk = Color(0xFF141A1F)
private val GlyphTint = Color(0xFFF2F5F7)
private val DoneTint = Color(0xFF6EE7A8)
private val FailedTint = Color(0xFFFFB4A2)
private val ListeningTint = Color(0xFF8AB4F8)

enum class BubblePhase { Idle, Listening, Working, Done, Failed }

/** Everything the button needs to draw itself. */
data class BubbleState(
    val phase: BubblePhase = BubblePhase.Idle,
    val hint: String? = null,
    val undoOffered: Boolean = false,
)

interface BubbleCallbacks {
    fun onTap()

    /** The user has pressed and held the button: start listening. */
    fun onHold()

    /** The press is over, whether it turned into a hold or was just a tap. */
    fun onRelease()

    fun onDrag(dx: Float, dy: Float)
    fun onDragEnd()
    fun onUndo()
}

/**
 * The small translucent circle: tap to translate, press and hold to speak Tamil, drag to move.
 *
 * Its mark is the same த the app icon is built from, and a microphone with a pulsing ring while it
 * is listening.
 *
 * [opacity] is how see-through the user wants it, and applies to the circle only: the hint pill has
 * to stay readable whatever this is set to.
 *
 * This one really is a window of its own, so it is added to the window manager instead of being
 * measured first — that is what lets Compose find its window recomposer here.
 */
@Composable
fun BubbleRoot(
    state: BubbleState,
    callbacks: BubbleCallbacks,
    opacity: Float = Settings.DEFAULT_BUBBLE_OPACITY,
) {
    val surface = BubbleInk.copy(
        alpha = opacity.coerceIn(Settings.MIN_BUBBLE_OPACITY, Settings.MAX_BUBBLE_OPACITY),
    )
    Box(
        modifier = Modifier
            .size(BubbleSize)
            .shadow(6.dp, CircleShape)
            .clip(CircleShape)
            .background(surface)
            .pointerInput(Unit) {
                // One detector owns the whole press: tap, hold and drag together.
                //
                // Splitting them, as this button used to, sets two detectors racing for the same
                // pointer, and the drag one consumes movement the moment a finger drifts past the
                // touch slop — which cancels the press. On a real hand, where a finger always drifts
                // a little during a half-second press, that silently eats the hold that starts
                // listening. So the rule is stated here instead, and only here:
                //
                //   travelled further than [DRAG_SLOP_FACTOR] touch slops  -> a drag, the button moves
                //   lasted past the long press threshold first            -> a hold, listening starts
                //   lifted before either                                  -> a tap
                //
                // The drag threshold is deliberately generous: overshooting it costs the user nothing
                // but a small nudge of the button, while undershooting it costs them the whole gesture.
                val holdAfterMillis = viewConfiguration.longPressTimeoutMillis
                val dragAfterPixels = viewConfiguration.touchSlop * DRAG_SLOP_FACTOR

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var position = down.position
                    var travelled = 0f
                    var lifted = false

                    // The undecided stretch of the press: whichever of the three happens first wins.
                    // If this returns without a verdict, the timeout cut it short, which means the
                    // finger is still down and still on the button: a hold.
                    withTimeoutOrNull(holdAfterMillis) {
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                                ?: break
                            if (!change.pressed) {
                                lifted = true
                                break
                            }
                            travelled += (change.position - position).getDistance()
                            position = change.position
                            if (travelled > dragAfterPixels) break
                        }
                    }

                    if (lifted) {
                        callbacks.onTap()
                        return@awaitEachGesture
                    }

                    val holding = travelled <= dragAfterPixels
                    if (holding) callbacks.onHold()

                    // Whichever way the press was read, the finger is still down: let the button
                    // follow it, and take the lift as the end of the gesture.
                    try {
                        var last = position
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                                ?: break
                            if (!change.pressed) break
                            change.consume()
                            callbacks.onDrag(change.position.x - last.x, change.position.y - last.y)
                            last = change.position
                        }
                    } finally {
                        // A gesture the framework cancels — the window being re-added, say — must not
                        // leave a session listening with no finger on the button, so the release is
                        // reported even then. It is also what ends listening before the button settles.
                        if (holding) callbacks.onRelease()
                    }
                    callbacks.onDragEnd()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when (state.phase) {
            BubblePhase.Listening -> ListeningMark()

            BubblePhase.Working -> CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                color = GlyphTint,
                strokeWidth = 2.dp,
            )

            BubblePhase.Done -> Icon(
                painter = painterResource(R.drawable.ic_bubble_done),
                contentDescription = null,
                tint = DoneTint,
                modifier = Modifier.size(22.dp),
            )

            BubblePhase.Failed -> Icon(
                painter = painterResource(R.drawable.ic_bubble_failed),
                contentDescription = null,
                tint = FailedTint,
                modifier = Modifier.size(22.dp),
            )

            BubblePhase.Idle -> Icon(
                painter = painterResource(R.drawable.ic_bubble_mark),
                contentDescription = null,
                tint = GlyphTint,
                modifier = Modifier.size(30.dp),
            )
        }
    }
}

/**
 * The mark for a listening session: a microphone over a ring that breathes, so that a hold which
 * has not caught a word yet still looks like it is working.
 */
@Composable
private fun ListeningMark() {
    val breathe by rememberInfiniteTransition(label = "listening").animateFloat(
        initialValue = 0.12f,
        targetValue = 0.42f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = LISTENING_PULSE_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "listening pulse",
    )

    Box(contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(BubbleSize)
                .background(ListeningTint.copy(alpha = breathe)),
        )
        Icon(
            painter = painterResource(R.drawable.ic_bubble_mic),
            contentDescription = null,
            tint = GlyphTint,
            modifier = Modifier.size(26.dp),
        )
    }
}

private const val LISTENING_PULSE_MS = 900

/**
 * How far a press may travel, in touch slops, before it counts as a drag instead of a hold.
 *
 * A finger resting on glass for half a second moves a few pixels, and treating that as a drag would
 * make the hold unreliable on real hands while costing a deliberate drag nothing but a moment of lag.
 */
private const val DRAG_SLOP_FACTOR = 1.5f
