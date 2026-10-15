package dev.ggnotes.steer

import org.junit.Assert.assertEquals
import org.junit.Test

/** gg's `agentdocs.Lines`: CRLF and a lone CR break lines, ALL trailing breaks are dropped, empty = no lines. */
class LinesTest {

    @Test
    fun `splits like gg`() {
        assertEquals(listOf("a", "b"), Lines.split("a\nb\n"))
        assertEquals(listOf("a", "b"), Lines.split("a\nb\n\n\n"))
        assertEquals(listOf("a", "", "b"), Lines.split("a\n\nb"))
        assertEquals(listOf("a", "b"), Lines.split("a\r\nb\r\n"))
        assertEquals(listOf("a", "b"), Lines.split("a\rb"))
        assertEquals(emptyList<String>(), Lines.split(""))
        assertEquals(emptyList<String>(), Lines.split("\n\n"))
    }

    @Test
    fun `landed detail follows the TUI's form pinned by the replay corpus`() {
        assertEquals("opened a.go:3-4", Lines.landed("opened a.go:3-4", 3, 10))
        assertEquals("opened a.go:3", Lines.landed("opened a.go:3", 3, 10))
        assertEquals("opened a.go", Lines.landed("opened a.go", 0, 10))
        assertEquals("opened a.go:9; clamped to line 3", Lines.landed("opened a.go:9", 9, 3))
    }
}
