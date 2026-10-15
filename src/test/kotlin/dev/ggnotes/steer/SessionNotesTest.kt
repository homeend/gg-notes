package dev.ggnotes.steer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PLUGIN.md §5: temporary notes — gg's refusals in order, ids t<n>, text, alignment on edits. */
class SessionNotesTest {

    private val lines = listOf("package x", "", "func gate(open bool) bool {", "\treturn open", "}")

    private fun memoryWithFile(): Pair<SessionMemory, SessionMemory.OpenFile> {
        val m = SessionMemory("/w")
        return m to m.touchFile("worktree", "internal/x/gate.go", null)
    }

    @Test
    fun `a note is placed on the current lines and keeps their text`() {
        val (m, f) = memoryWithFile()
        val r = m.addNote(f.id, lines, 3, 4, " The gate. ", "Rationale", null, null, null)
        val n = (r as NoteResult.Ok).note
        assertEquals("t1", n.id)
        assertEquals("The gate.", n.summary)
        assertEquals("agent", n.author)
        assertEquals(3 to 4, n.start to n.end)
        assertEquals(lines.subList(2, 4), m.noteText(n.id))
        assertEquals("noted internal/x/gate.go:3-4 as t1", m.notedDetail(n))
        val single = (m.addNote(f.id, lines, 1, 1, "one", null, "me", mapOf("k" to "v"), null) as NoteResult.Ok).note
        assertEquals("noted internal/x/gate.go:1 as t2", m.notedDetail(single))
        assertEquals(listOf("t2", "t1"), m.notesOf(f.id).map { it.id })
        assertEquals(2, m.noteCount(f.id))
    }

    @Test
    fun `refusals in gg's order with gg's strings`() {
        val (m, f) = memoryWithFile()
        fun err(ls: List<String>, s: Int, e: Int, summary: String?, rationale: String? = null) =
            (m.addNote(f.id, ls, s, e, summary, rationale, null, null, null) as NoteResult.Refused).error
        assertEquals("internal/x/gate.go has no lines to note", err(emptyList(), 1, 1, "s"))
        assertEquals("a line number is 1-based", err(lines, 0, 1, "s"))
        assertEquals("the range ends before it starts", err(lines, 3, 2, "s"))
        assertEquals("line 9 is past the end of internal/x/gate.go (5 lines)", err(lines, 3, 9, "s"))
        assertEquals("a note needs a summary", err(lines, 3, 3, "   "))
        assertEquals("the summary is longer than 500 characters", err(lines, 3, 3, "é".repeat(501)))
        assertEquals("the rationale is longer than 4000 characters", err(lines, 3, 3, "s", "é".repeat(4001)))
        assertTrue("500 code points are fine", m.addNote(f.id, lines, 3, 3, "é".repeat(500), null, null, null, null) is NoteResult.Ok)
        repeat(49) { m.addNote(f.id, lines, 1, 1, "n$it", null, null, null, null) }
        assertEquals("internal/x/gate.go already carries 50 notes", err(lines, 1, 1, "one more"))
    }

    @Test
    fun `an edit above moves the notes and note_show returns the lines they sit on now`() {
        val (m, f) = memoryWithFile()
        val n = (m.addNote(f.id, lines, 3, 4, "s", null, null, null, null) as NoteResult.Ok).note
        assertTrue(m.alignFile(f.id, listOf("// new") + lines))
        assertEquals(4 to 5, m.findNote(n.id)!!.let { it.start to it.end })
        assertEquals(lines.subList(2, 4), m.noteText(n.id))
        assertTrue("same text again is a no-op", !m.alignFile(f.id, listOf("// new") + lines))
        val edited = (listOf("// new") + lines).toMutableList().also { it[4] = "\treturn !open" }
        m.alignFile(f.id, edited)
        assertTrue(m.findNote(n.id)!!.outdated)
        assertEquals(listOf("func gate(open bool) bool {", "\treturn !open"), m.noteText(n.id))
        m.alignFile(f.id, listOf("// new") + lines)
        assertTrue(!m.findNote(n.id)!!.outdated)
    }

    @Test
    fun `remove and the wire shape`() {
        val (m, f) = memoryWithFile()
        val n = (m.addNote(f.id, lines, 3, 4, "s", "r", "ada", mapOf("severity" to "high"), null) as NoteResult.Ok).note
        val w = m.noteWire(n, withText = true)
        assertEquals(NoteWire("t1", f.id, "internal/x/gate.go", 3, 4, "s", "r", "ada", false, lines.subList(2, 4), mapOf("severity" to "high"), null), w)
        assertTrue(m.removeNote("t1"))
        assertTrue(!m.removeNote("t1"))
        assertNull(m.findNote("t1"))
        assertEquals(0, m.noteCount(f.id))
        assertEquals("t2", (m.addNote(f.id, lines, 1, 1, "again", null, null, null, null) as NoteResult.Ok).note.id)
    }

    @Test
    fun `closing the file drops its notes`() {
        val (m, f) = memoryWithFile()
        m.addNote(f.id, lines, 3, 4, "s", null, null, null, null)
        m.removeFile(f.id)
        assertEquals(emptyList<SessionMemory.Note>(), m.allNotes())
        assertNull(m.findNote("t1"))
    }
}
