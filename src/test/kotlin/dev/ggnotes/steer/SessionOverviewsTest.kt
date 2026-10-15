package dev.ggnotes.steer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PLUGIN.md §5 / SPEC §5: in-memory overviews — limits, refusals, anchors with note:t<n>, unresolved, set/rm, the viewer markdown. */
class SessionOverviewsTest {

    private val lines = listOf("package x", "", "func gate(open bool) bool {", "\treturn open", "}")

    /** A resolver that knows one file of five lines. */
    private val resolver = SessionAnchors.Resolver { path, end -> path == "internal/x/gate.go" && (end == null || end <= 5) }

    @Test
    fun `anchor destinations follow gg's grammar plus note ids`() {
        assertEquals(SessionAnchor("internal/x/gate.go", 3, 4, null), SessionAnchors.parse("./internal/x/gate.go:3-4"))
        assertEquals(SessionAnchor("internal/x/gate.go", null, null, null), SessionAnchors.parse("internal/x/gate.go"))
        assertEquals(SessionAnchor(null, null, null, "t12"), SessionAnchors.parse("note:t12"))
        assertNull(SessionAnchors.parse("note:x1"))
        assertNull(SessionAnchors.parse("note:t"))
        assertNull(SessionAnchors.parse("../a.go:1"))
        assertNull(SessionAnchors.parse("C:/a.go"))
        assertNull(SessionAnchors.parse("https://x/y"))
        assertNull(SessionAnchors.parse("a.go:9-3"))
    }

    @Test
    fun `an overview is an open file with an f id, its anchors in document order, unresolved ones listed`() {
        val m = SessionMemory("/w")
        val f = m.touchFile("worktree", "internal/x/gate.go", null)
        val note = (m.addNote(f.id, lines, 3, 4, "s", null, null, null, null) as NoteResult.Ok).note
        val r = m.addOverview("Tour", "See [the gate](internal/x/gate.go:3-4), [its note](note:t1), [gone](internal/nope.go:1), [far](internal/x/gate.go:99) and [t9](note:t9).", resolver)
        val o = (r as OverviewResult.Ok).overview
        assertEquals("f2", o.id)
        assertEquals("Tour", o.title)
        assertEquals(5, o.anchors.size)
        assertEquals(listOf("internal/nope.go:1", "internal/x/gate.go:99", "note:t9"), o.unresolved)
        assertEquals(listOf("f2", "f1"), m.openFiles().map { it.id })
        assertEquals("overview", m.findFile("f2")!!.source)
        assertEquals("Tour", m.findFile("f2")!!.title)
        assertEquals(OverviewWire("f2", "Tour", "shown", 5, listOf("internal/nope.go:1", "internal/x/gate.go:99", "note:t9"), null), m.overviewWire(o, shown = true, withText = false))
        // the viewer markdown: note anchors point at the note's lines now; unresolved ones are plain text
        val md = m.viewerMarkdown(o)
        assertTrue(md, md.contains("[its note](internal/x/gate.go:${note.start}-${note.end})"))
        assertTrue(md, md.contains("gone (internal/nope.go:1)"))
        assertTrue(md, md.contains("t9 (note:t9)"))
        assertTrue(md, md.contains("[the gate](internal/x/gate.go:3-4)"))
    }

    @Test
    fun `refusals in gg's order and the 20 per root limit`() {
        val m = SessionMemory("/w")
        fun err(title: String?, text: String?) = (m.addOverview(title, text, resolver) as OverviewResult.Refused).error
        assertEquals("an overview needs text", err("T", "  "))
        assertEquals("the text is over 64 KiB", err("T", "x".repeat(64 * 1024 + 1)))
        assertEquals("an overview needs a title", err(" ", "text"))
        assertEquals("the title must be one line of at most 200 characters", err("a\nb", "text"))
        assertEquals("the title must be one line of at most 200 characters", err("t".repeat(201), "text"))
        repeat(20) { assertTrue(m.addOverview("T$it", "text", resolver) is OverviewResult.Ok) }
        assertEquals("20 overviews are open; remove one first", err("T21", "text"))
    }

    @Test
    fun `set replaces in place, show returns the text, rm closes`() {
        val m = SessionMemory("/w")
        val o = (m.addOverview("Tour", "one [a](x.go:1)", resolver) as OverviewResult.Ok).overview
        val set = (m.setOverview(o.id, "Tour 2", "two", resolver) as OverviewResult.Ok).overview
        assertEquals(o.id, set.id)
        assertEquals("Tour 2", set.title)
        assertEquals("two", set.text)
        assertEquals(0, set.anchors.size)
        assertEquals("Tour 2", m.findFile(o.id)!!.title)
        assertEquals("two", m.overviewWire(set, shown = false, withText = true).text)
        assertEquals("background", m.overviewWire(set, shown = false, withText = true).state)
        assertEquals("no overview f9", (m.setOverview("f9", "x", "y", resolver) as OverviewResult.Refused).error)
        assertEquals("an overview needs text", (m.setOverview(o.id, null, "", resolver) as OverviewResult.Refused).error)
        assertTrue(m.removeOverview(o.id))
        assertTrue(!m.removeOverview(o.id))
        assertNull(m.findFile(o.id))
        assertEquals(emptyList<SessionMemory.Overview>(), m.overviews())
    }

    @Test
    fun `showing an overview again keeps one entry and makes it the most recently shown`() {
        val m = SessionMemory("/w")
        val o = (m.addOverview("Tour", "text", resolver) as OverviewResult.Ok).overview
        m.touchFile("worktree", "a.go", null)
        m.markShown(o.id)
        assertEquals(listOf(o.id, "f2"), m.openFiles().map { it.id })
        assertEquals(1, m.openFiles().count { it.source == "overview" })
    }

    @Test
    fun `only the first 100 anchors count`() {
        val m = SessionMemory("/w")
        val text = (1..102).joinToString(" ") { "[l$it](f$it.go:$it)" }
        val o = (m.addOverview("Many", text, SessionAnchors.Resolver { _, _ -> true }) as OverviewResult.Ok).overview
        assertEquals(100, o.anchors.size)
    }
}
