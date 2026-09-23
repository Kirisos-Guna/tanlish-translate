package com.example.tanlishkeyboard.overlay

/**
 * Where the button and its hint pill sit on screen.
 *
 * Pure arithmetic on purpose: window maths is the easiest thing in an overlay to get subtly wrong,
 * and this way it is all unit tested in pixels, with no Android in the way.
 */
object BubblePlacement {

    /** The screen rectangle to keep the button inside, in pixels, including the system bar insets. */
    data class Screen(
        val width: Int,
        val height: Int,
        val topInset: Int = 0,
        val bottomInset: Int = 0,
    )

    data class Point(val x: Int, val y: Int)

    data class Size(val width: Int, val height: Int)

    /** Clamps the button onto the screen and snaps it to whichever side edge it is nearest. */
    fun snap(x: Int, y: Int, bubble: Int, screen: Screen, margin: Int): Point = Point(
        x = if (x + bubble / 2 < screen.width / 2) margin else rightEdge(bubble, screen, margin),
        y = clampY(y, bubble, screen, margin),
    )

    /** Keeps a button that is being dragged on screen without snapping it anywhere yet. */
    fun drag(x: Int, y: Int, bubble: Int, screen: Screen, margin: Int): Point = Point(
        x = x.coerceIn(margin, rightEdge(bubble, screen, margin)),
        y = clampY(y, bubble, screen, margin),
    )

    /** Where a button that has never been moved starts: the right edge, just below the middle. */
    fun defaultPosition(bubble: Int, screen: Screen, margin: Int): Point = Point(
        x = rightEdge(bubble, screen, margin),
        y = clampY((screen.height * 0.62f).toInt(), bubble, screen, margin),
    )

    /** The hint pill sits beside the button, on whichever side has room for it. */
    fun hintPosition(bubble: Point, bubbleSize: Int, hint: Size, screen: Screen, margin: Int): Point {
        val wantsLeft = bubble.x + bubbleSize / 2 > screen.width / 2
        val x = if (wantsLeft) bubble.x - hint.width - margin else bubble.x + bubbleSize + margin
        return Point(
            x = x.coerceIn(margin, (screen.width - hint.width - margin).coerceAtLeast(margin)),
            y = (bubble.y + bubbleSize / 2 - hint.height / 2).coerceIn(
                screen.topInset + margin,
                (screen.height - screen.bottomInset - hint.height - margin)
                    .coerceAtLeast(screen.topInset + margin),
            ),
        )
    }

    private fun rightEdge(bubble: Int, screen: Screen, margin: Int): Int =
        (screen.width - bubble - margin).coerceAtLeast(margin)

    private fun clampY(y: Int, bubble: Int, screen: Screen, margin: Int): Int {
        val top = screen.topInset + margin
        val bottom = (screen.height - screen.bottomInset - bubble - margin).coerceAtLeast(top)
        return y.coerceIn(top, bottom)
    }
}
