package dev.ggnotes.overview

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How much of the tool window the notes tree takes: exactly what its rows
 * need, never more than a third of the window (the rest is the document),
 * never less than a small floor, so a long list scrolls inside its share.
 */
class TreeShareTest {

    private fun share(content: Int, total: Int = 900) = TreeShare.proportion(contentHeight = content, totalHeight = total, minHeight = 60)

    @Test
    fun `a short list takes only the height its rows need`() {
        assertEquals(0.1f, share(content = 90), 0.0001f)
    }

    @Test
    fun `a long list is capped at the maximum share and scrolls inside it`() {
        assertEquals(TreeShare.MAX_SHARE, share(content = 2000), 0.0001f)
        assertEquals(TreeShare.MAX_SHARE, share(content = 900), 0.0001f)
    }

    @Test
    fun `an empty list keeps the floor so its placeholder stays readable`() {
        assertEquals(60f / 900f, share(content = 0), 0.0001f)
        assertEquals(60f / 900f, share(content = 20), 0.0001f)
    }

    @Test
    fun `a window too small to measure falls back to the maximum share`() {
        assertEquals(TreeShare.MAX_SHARE, share(content = 90, total = 0), 0.0001f)
    }

    @Test
    fun `the cap wins over the floor in a tiny window`() {
        assertEquals(TreeShare.MAX_SHARE, share(content = 0, total = 100), 0.0001f)
    }
}
