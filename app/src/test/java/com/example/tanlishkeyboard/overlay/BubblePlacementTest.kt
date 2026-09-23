package com.example.tanlishkeyboard.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BubblePlacementTest {

    private val screen = BubblePlacement.Screen(
        width = 1080,
        height = 2400,
        topInset = 60,
        bottomInset = 90,
    )
    private val bubble = 150
    private val margin = 24

    @Test
    fun `a button near the right edge snaps right`() {
        val snapped = BubblePlacement.snap(x = 900, y = 1200, bubble = bubble, screen = screen, margin = margin)

        assertEquals(screen.width - bubble - margin, snapped.x)
    }

    @Test
    fun `a button near the left edge snaps left`() {
        val snapped = BubblePlacement.snap(x = 40, y = 1200, bubble = bubble, screen = screen, margin = margin)

        assertEquals(margin, snapped.x)
    }

    @Test
    fun `snapping keeps the button clear of the system bars`() {
        val tooHigh = BubblePlacement.snap(x = 900, y = -500, bubble = bubble, screen = screen, margin = margin)
        assertEquals(screen.topInset + margin, tooHigh.y)

        val tooLow = BubblePlacement.snap(x = 900, y = 9_000, bubble = bubble, screen = screen, margin = margin)
        assertEquals(screen.height - screen.bottomInset - bubble - margin, tooLow.y)
    }

    @Test
    fun `dragging clamps without snapping sideways`() {
        val dragged = BubblePlacement.drag(x = 500, y = 1000, bubble = bubble, screen = screen, margin = margin)

        assertEquals(500, dragged.x)
        assertEquals(1000, dragged.y)
    }

    @Test
    fun `dragging past an edge stops at the margin`() {
        val dragged = BubblePlacement.drag(x = -200, y = -200, bubble = bubble, screen = screen, margin = margin)

        assertEquals(margin, dragged.x)
        assertEquals(screen.topInset + margin, dragged.y)
    }

    @Test
    fun `a new button starts on the right edge and on screen`() {
        val start = BubblePlacement.defaultPosition(bubble = bubble, screen = screen, margin = margin)

        assertEquals(screen.width - bubble - margin, start.x)
        assertTrue(start.y > screen.height / 2)
        assertTrue(start.y + bubble <= screen.height - screen.bottomInset - margin)
    }

    @Test
    fun `the hint sits to the left of a button on the right edge`() {
        val position = BubblePlacement.hintPosition(
            bubble = BubblePlacement.Point(x = screen.width - bubble - margin, y = 1200),
            bubbleSize = bubble,
            hint = BubblePlacement.Size(width = 400, height = 120),
            screen = screen,
            margin = margin,
        )

        assertEquals(screen.width - bubble - margin - 400 - margin, position.x)
        assertEquals(1200 + bubble / 2 - 60, position.y)
    }

    @Test
    fun `the hint sits to the right of a button on the left edge`() {
        val position = BubblePlacement.hintPosition(
            bubble = BubblePlacement.Point(x = margin, y = 1200),
            bubbleSize = bubble,
            hint = BubblePlacement.Size(width = 400, height = 120),
            screen = screen,
            margin = margin,
        )

        assertEquals(margin + bubble + margin, position.x)
    }

    @Test
    fun `a wide hint stays on screen`() {
        val position = BubblePlacement.hintPosition(
            bubble = BubblePlacement.Point(x = margin, y = 1200),
            bubbleSize = bubble,
            hint = BubblePlacement.Size(width = 1_100, height = 120),
            screen = screen,
            margin = margin,
        )

        assertEquals(margin, position.x)
    }

    @Test
    fun `a hint near the top stays below the status bar`() {
        val position = BubblePlacement.hintPosition(
            bubble = BubblePlacement.Point(x = margin, y = screen.topInset + margin),
            bubbleSize = bubble,
            hint = BubblePlacement.Size(width = 300, height = 120),
            screen = screen,
            margin = margin,
        )

        assertTrue(position.y >= screen.topInset + margin)
    }

    @Test
    fun `a hint near the bottom stays above the navigation bar`() {
        val position = BubblePlacement.hintPosition(
            bubble = BubblePlacement.Point(x = margin, y = screen.height - screen.bottomInset - bubble - margin),
            bubbleSize = bubble,
            hint = BubblePlacement.Size(width = 300, height = 140),
            screen = screen,
            margin = margin,
        )

        assertTrue(position.y + 140 <= screen.height - screen.bottomInset - margin)
    }
}
