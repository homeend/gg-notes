package dev.ggnotes.session

import dev.ggnotes.steer.Lines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** SPEC §3.2 / §3.3 against gg's own `examples/` (what gg writes) and the refusals of §3.4. */
class SessionDocParserTest {

    private val examples = Paths.get("docs/gg-session-docs/examples")

    private fun overview(name: String) = SessionDocParser.overview(name.removeSuffix(".md"), Files.readAllBytes(examples.resolve(name)))
    private fun note(name: String) = SessionDocParser.note(name.removeSuffix(".toml"), Files.readAllBytes(examples.resolve(name)))
    private fun <T> ok(p: Parsed<T>): T = (p as? Parsed.Ok<T>)?.doc ?: throw AssertionError("refused: " + (p as Parsed.Refused).reason)
    private fun refused(p: Parsed<*>): String = (p as? Parsed.Refused)?.reason ?: throw AssertionError("accepted: $p")

    @Test
    fun `the full overview example`() {
        val o = ok(overview("20261018T143201-9f3a.md"))
        assertEquals("20261018T143201-9f3a", o.id)
        assertEquals(1, o.format)
        assertEquals("How scroll coalescing works", o.title)
        assertEquals("claude", o.author)
        assertEquals(OffsetDateTime.of(2026, 10, 18, 14, 32, 1, 0, ZoneOffset.UTC), o.created)
        assertTrue(o.body.startsWith("Scroll events are coalesced"))
        assertEquals(listOf("internal/tui/scroll.go:8-12", "internal/tui/scroll.go:14-21", "note:20261018T143205-c0d1", "internal/tui/missing.go", "README.md"),
            o.anchors.map { it.dest })
        assertEquals("20261018T143205-c0d1", o.anchors[2].note)
    }

    @Test
    fun `the minimal overview reads as agent with no created`() {
        val o = ok(overview("20261018T143301-0001.md"))
        assertEquals("Minimal", o.title)
        assertEquals("agent", o.author)
        assertNull(o.created)
        assertEquals(1, o.anchors.size)
    }

    @Test
    fun `a note with meta and display`() {
        val n = ok(note("20261018T143205-c0d1.toml"))
        assertEquals("internal/tui/scroll.go", n.path)
        assertEquals(15 to 16, n.start to n.end)
        assertEquals("One lock around flush: the wheel handler and the tick both reach delta.", n.summary)
        assertEquals("Without it a tick between two pushes could apply a partial sum.", n.rationale)
        assertEquals("claude", n.author)
        assertEquals(OffsetDateTime.of(2026, 10, 18, 14, 32, 5, 0, ZoneOffset.UTC), n.created)
        assertEquals(listOf("\ts.mu.Lock()", "\tdefer s.mu.Unlock()"), n.lines)
        assertEquals(listOf(dev.ggnotes.store.MetaEntry("kind", "design")), n.meta)
        assertEquals(listOf("badge"), n.display.getValue("kind").show)
        assertEquals("blue", n.display.getValue("kind").values.getValue("design").color)
    }

    @Test
    fun `the example notes place against the example tree as ids json says`() {
        val tree = examples.resolve("tree")
        fun lines(path: String) = Lines.split(String(Files.readAllBytes(tree.resolve(path)), Charsets.UTF_8))
        val readme = Files.readAllBytes(tree.resolve("README.md"))
        assertTrue("the CRLF fixture must stay CRLF", String(readme, Charsets.UTF_8).contains("\r\n"))
        val exact = ok(note("20261018T143205-c0d1.toml"))
        assertEquals(Placement(15, 16, false), ColdAnchor.place(lines(exact.path), exact.start, exact.end, exact.lines))
        val crlf = ok(note("20261018T143210-77aa.toml"))
        assertEquals("claude", crlf.author)
        assertEquals(Placement(3, 4, false), ColdAnchor.place(lines(crlf.path), crlf.start, crlf.end, crlf.lines))
        val stale = ok(note("20261018T143220-beef.toml"))
        assertEquals(Placement(6, 7, true), ColdAnchor.place(lines(stale.path), stale.start, stale.end, stale.lines))
    }

    @Test
    fun `overview refusals`() {
        fun ov(text: String) = SessionDocParser.overview("x", text.toByteArray())
        assertEquals("an overview file starts with a +++ front matter block", refused(ov("title = 'x'\nbody")))
        assertEquals("an overview file starts with a +++ front matter block", refused(ov("+++\ntitle = 'x'\nbody")))
        assertEquals("an overview needs a title", refused(ov("+++\nauthor = 'a'\n+++\nbody")))
        assertEquals("an overview needs a title", refused(ov("+++\ntitle = '  '\n+++\nbody")))
        assertEquals("an overview needs text", refused(ov("+++\ntitle = 'x'\n+++\n  \n")))
        assertEquals("the title must be one line of at most 200 characters", refused(ov("+++\ntitle = '" + "t".repeat(201) + "'\n+++\nbody")))
        assertEquals("the text is over 64 KiB", refused(ov("+++\ntitle = 'x'\n+++\n" + "x".repeat(64 * 1024 + 1))))
        assertEquals("created must be an RFC 3339 date-time", refused(ov("+++\ntitle = 'x'\ncreated = 'yesterday'\n+++\nbody")))
        assertTrue(refused(ov("+++\ntitle = \n+++\nbody")).startsWith("toml: "))
        // CRLF files, a format a reader does not know, unknown keys: still read
        val crlf = ok(ov("+++\r\nformat = 7\r\ntitle = ' Spaced '\r\nextra = 1\r\n+++\r\nbody [a](x.go:1)\r\n"))
        assertEquals("Spaced", crlf.title)
        assertEquals(7, crlf.format)
        assertEquals("body [a](x.go:1)\n", crlf.body)
    }

    @Test
    fun `note refusals in gg's order`() {
        fun n(text: String) = SessionDocParser.note("x", text.toByteArray())
        val good = "path = 'a/b.go'\nstart = 2\nend = 3\nsummary = 's'\nlines = ['x', 'y']\n"
        assertEquals("a/b.go", ok(n(good)).path)
        assertEquals("agent", ok(n(good)).author)
        assertNull(ok(n(good)).rationale)
        assertEquals("a note needs a path", refused(n(good.replace("path = 'a/b.go'", "path = ''"))))
        assertEquals("a note needs a path", refused(n(good.replace("path = 'a/b.go'\n", ""))))
        for (bad in listOf("/a.go", "a\\b.go", "c:/a.go", "a/../b.go", "./a.go", "a//b.go", "a/")) {
            assertEquals(bad, "path must be a relative slash path inside the worktree", refused(n(good.replace("a/b.go", bad))))
        }
        assertEquals("a line number is 1-based", refused(n(good.replace("start = 2", "start = 0"))))
        assertEquals("the range ends before it starts", refused(n(good.replace("end = 3", "end = 1"))))
        assertEquals("a note needs a summary", refused(n(good.replace("summary = 's'", "summary = ' '"))))
        assertEquals("a note needs a summary", refused(n(good.replace("summary = 's'\n", ""))))
        assertEquals("the summary is longer than 500 characters", refused(n(good.replace("summary = 's'", "summary = '" + "é".repeat(501) + "'"))))
        assertEquals("the rationale is longer than 4000 characters", refused(n(good + "rationale = '" + "é".repeat(4001) + "'\n")))
        assertEquals("lines must hold end-start+1 entries (got 1, want 2)", refused(n(good.replace("['x', 'y']", "['x']"))))
        assertEquals("lines must hold end-start+1 entries (got 0, want 2)", refused(n(good.replace("lines = ['x', 'y']\n", ""))))
        assertEquals("created must be an RFC 3339 date-time", refused(n(good + "created = 'now'\n")))
        assertTrue(refused(n("path = \n")).startsWith("toml: "))
        // a writer that left line breaks on its anchor lines, a summary with blanks around it
        val sloppy = ok(n(good.replace("['x', 'y']", "[\"x\\r\\n\", \"y\\n\"]").replace("summary = 's'", "summary = ' s '")))
        assertEquals(listOf("x", "y"), sloppy.lines)
        assertEquals("s", sloppy.summary)
        // meta: the part-2 limits — a repeated key keeps the first, an over-long key is dropped
        val meta = ok(n(good + "[[meta]]\nkey = 'k'\nvalue = 'one'\n[[meta]]\nkey = 'k'\nvalue = 'two'\n[[meta]]\nkey = '" + "x".repeat(65) + "'\nvalue = 'v'\n"))
        assertEquals(listOf(dev.ggnotes.store.MetaEntry("k", "one")), meta.meta)
    }
}
