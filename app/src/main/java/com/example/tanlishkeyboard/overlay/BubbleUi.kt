package com.example.tanlishkeyboard.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
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

/** Diameter of the floating button, in dp. */
val BubbleSize = 56.dp

private val BubbleInk = Color(0xFF141A1F)
private val GlyphTint = Color(0xFFF2F5F7)
private val DoneTint = Color(0xFF6EE7A8)
private val FailedTint = Color(0xFFFFB4A2)

enum class BubblePhase { Idle, Working, Done, Failed }

/** Everything the button needs to draw itself. */
data class BubbleState(
    val phase: BubblePhase = BubblePhase.Idle,
    val hint: String? = null,
    val undoOffered: Boolean = false,
)

interface BubbleCallbacks {
    fun onTap()
    fun onLongPress()
    fun onDrag(dx: Float, dy: Float)
    fun onDragEnd()
    fun onUndo()
}

/**
 * The small translucent circle: tap to translate, drag to move, long press to hide.
 *
 * Its mark is the same த the app icon is built from.
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
                detectTapGestures(
                    onTap = { callbacks.onTap() },
                    onLongPress = { callbacks.onLongPress() },
                )
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDrag = { change, drag ->
                        change.consume()
                        callbacks.onDrag(drag.x, drag.y)
                    },
                    onDragEnd = { callbacks.onDragEnd() },
                    onDragCancel = { callbacks.onDragEnd() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        when (state.phase) {
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
