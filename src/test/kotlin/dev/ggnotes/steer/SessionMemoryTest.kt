package dev.ggnotes.steer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** PLUGIN.md §5: the open-files list — ids f<n> never reused, one entry per file identity, newest shown first. */
class SessionMemoryTest {

    @Test
    fun `files get f ids in order and a second touch keeps the entry`() {
        val m = SessionMemory("/w")
        val a = m.touchFile("worktree", "a.go", null)
        val b = m.touchFile("commit", "a.go", "d5efa035")
        val again = m.touchFile("worktree", "a.go", null)
        assertEquals("f1", a.id)
        assertEquals("f2", b.id)
        assertEquals("f1", again.id)
        assertEquals(listOf("f1", "f2"), m.openFiles().map { it.id })
    }

    @Test
    fun `a background touch does not make the file the most recently shown`() {
        val m = SessionMemory("/w")
        m.touchFile("worktree", "a.go", null)
        m.touchFile("worktree", "b.go", null)
        m.touchFile("worktree", "a.go", null, background = true)
        assertEquals(listOf("b.go", "a.go"), m.openFiles().map { it.path })
        m.touchFile("worktree", "a.go", null)
        assertEquals(listOf("a.go", "b.go"), m.openFiles().map { it.path })
    }

    @Test
    fun `ids are never reused after a file leaves the list`() {
        val m = SessionMemory("/w")
        m.touchFile("worktree", "a.go", null)
        m.removeFile("f1")
        assertNull(m.findFile("f1"))
        assertEquals("f2", m.touchFile("worktree", "a.go", null).id)
        assertEquals("a.go", m.findFileByPath("a.go")?.path)
    }

    @Test
    fun `the wire shape of the list`() {
        val m = SessionMemory("/w")
        val f = m.touchFile("overview", "overview-1.md", null, title = "Tour")
        val wire = m.openFiles().map { m.wire(it, isShown = { it.id == f.id }, caretLine = { 0 }, noteCount = { 0 }) }
        assertEquals(listOf(OpenFileWire("f1", "overview-1.md", "overview", "Tour", null, 0, "shown", 0)), wire)
    }
}
