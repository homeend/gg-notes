package dev.ggnotes.steer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PLUGIN.md §5 / gg's `agentdocs.reanchor`: the eight cases, each diff unambiguous unless the rule is about duplicates. */
class ReanchorTest {

    private fun note(start: Int, end: Int, anchor: List<String>, outdated: Boolean = false) = Reanchor.Note(start, end, outdated, anchor)
    private val base = listOf("package x", "", "func gate(open bool) bool {", "\treturn open", "}", "", "func other() {}")

    @Test
    fun `1 unchanged text stays where it is`() {
        val n = note(3, 4, base.subList(2, 4))
        Reanchor.reanchor(listOf(n), base, base)
        assertEquals(3 to 4, n.start to n.end)
        assertFalse(n.outdated)
    }

    @Test
    fun `2 lines inserted above move the note down`() {
        val n = note(3, 4, base.subList(2, 4))
        val cur = listOf("// one", "// two") + base
        Reanchor.reanchor(listOf(n), base, cur)
        assertEquals(5 to 6, n.start to n.end)
        assertFalse(n.outdated)
    }

    @Test
    fun `3 lines deleted above move the note up`() {
        val n = note(3, 4, base.subList(2, 4))
        val cur = base.drop(2)
        Reanchor.reanchor(listOf(n), base, cur)
        assertEquals(1 to 2, n.start to n.end)
        assertFalse(n.outdated)
    }

    @Test
    fun `4 an edit inside the note makes it outdated and keeps its numbers`() {
        val n = note(3, 4, base.subList(2, 4))
        val cur = base.toMutableList().also { it[3] = "\treturn !open" }
        Reanchor.reanchor(listOf(n), base, cur)
        assertTrue(n.outdated)
        assertEquals(3 to 4, n.start to n.end)
    }

    @Test
    fun `5 lines deleted past the note's range clamp it into the file`() {
        val n = note(6, 7, base.subList(5, 7))
        val cur = base.subList(0, 4)
        Reanchor.reanchor(listOf(n), base, cur)
        assertTrue(n.outdated)
        assertEquals(4 to 4, n.start to n.end)
    }

    @Test
    fun `6 an outdated note comes back when its text is at its old start again`() {
        val n = note(3, 4, base.subList(2, 4), outdated = true)
        val edited = base.toMutableList().also { it[3] = "\treturn !open" }
        Reanchor.reanchor(listOf(n), edited, base)
        assertFalse(n.outdated)
        assertEquals(3 to 4, n.start to n.end)
    }

    @Test
    fun `7 an outdated note comes back at the one place its text now sits`() {
        val n = note(3, 4, base.subList(2, 4), outdated = true)
        val edited = base.toMutableList().also { it[3] = "\treturn !open" }
        val cur = listOf("// a", "// b", "// c") + base
        Reanchor.reanchor(listOf(n), edited, cur)
        assertFalse(n.outdated)
        assertEquals(6 to 7, n.start to n.end)
    }

    @Test
    fun `8 text at two places keeps the note outdated`() {
        val n = note(5, 5, listOf("}"), outdated = true)
        val old = listOf("a", "b", "c", "d", "x")
        val cur = listOf("}", "a", "}", "b")
        Reanchor.reanchor(listOf(n), old, cur)
        assertTrue(n.outdated)
        assertEquals("clamped into the file", 4 to 4, n.start to n.end)
    }

    @Test
    fun `same line map and relocate helpers`() {
        val to = Reanchor.sameLineMap(listOf("a", "b", "c"), listOf("z", "a", "b", "c"))
        assertEquals(listOf(0, 2, 3, 4), to.toList())
        assertEquals(2, Reanchor.mapRange(to, 1, 3))
        assertEquals(null, Reanchor.mapRange(to, 0, 1))
        assertEquals(3, Reanchor.relocate(listOf("x", "y", "a", "b"), listOf("a", "b"), 1))
        assertEquals(0, Reanchor.relocate(listOf("a", "b", "a", "b"), listOf("a", "b"), 9))
        assertEquals(3, Reanchor.relocate(listOf("a", "b", "a", "b"), listOf("a", "b"), 3))
    }
}
