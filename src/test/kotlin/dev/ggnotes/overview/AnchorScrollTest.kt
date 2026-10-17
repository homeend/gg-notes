package dev.ggnotes.overview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where the overview pane scrolls when an anchor becomes current: the anchor
 * keeps a few lines of context above it instead of sitting on the top edge,
 * and a pane that already shows the whole anchor does not move at all.
 */
class AnchorScrollTest {

    // one anchor line is 20 px high; the viewport shows 300 px of a 2000 px document
    private fun target(anchorY: Int, viewTop: Int) =
        AnchorScroll.target(anchorY = anchorY, anchorHeight = 20, viewTop = viewTop, viewHeight = 300, docHeight = 2000)

    @Test
    fun `an anchor below the viewport lands three lines under the top edge`() {
        assertEquals(1000 - 3 * 20, target(anchorY = 1000, viewTop = 0))
    }

    @Test
    fun `an anchor above the viewport lands three lines under the top edge too`() {
        assertEquals(500 - 3 * 20, target(anchorY = 500, viewTop = 1200))
    }

    @Test
    fun `an anchor already fully visible leaves the pane where it is`() {
        assertNull(target(anchorY = 150, viewTop = 100))
        assertNull(target(anchorY = 100, viewTop = 100))
        assertNull(target(anchorY = 380, viewTop = 100))
    }

    @Test
    fun `an anchor cut by the bottom edge counts as hidden`() {
        assertEquals(390 - 60, target(anchorY = 390, viewTop = 100))
    }

    @Test
    fun `the context never scrolls past the document start`() {
        assertEquals(0, target(anchorY = 40, viewTop = 800))
    }

    @Test
    fun `the context never scrolls past the document end`() {
        // the last anchor: the pane stops where the document ends
        assertEquals(2000 - 300, target(anchorY = 1990, viewTop = 0))
    }

    @Test
    fun `a document shorter than the viewport never scrolls`() {
        assertNull(AnchorScroll.target(anchorY = 250, anchorHeight = 20, viewTop = 0, viewHeight = 300, docHeight = 280))
    }
}
