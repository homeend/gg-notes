package dev.ggnotes.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

/**
 * SPEC §6–§7: the document's markdown becomes HTML whose links follow the
 * anchor rules. Anchors are numbered in document order and rendered as
 * `<a href="anchor:N">`; the HTML is what the tool window shows.
 */
class MarkdownRendererTest {

    private fun render(md: String) = MarkdownRenderer.render(md)

    @Test
    fun `anchors are numbered in document order and listed`() {
        val r = render("See [`gate`](internal/x/gate.go:3-4) and [cfg](config.toml).\n\n- [the call](./internal/y/call.go:1)")
        assertEquals(
            listOf(
                LinkTarget.Anchor("internal/x/gate.go", 3, 4),
                LinkTarget.Anchor("config.toml", null, null),
                LinkTarget.Anchor("internal/y/call.go", 1, 1),
            ),
            r.anchors.map { it.target })
        assertEquals(listOf("gate", "cfg", "the call"), r.anchors.map { it.label })
        assertTrue(r.html, r.html.contains("""<a href="anchor:1""""))
        assertTrue(r.html, r.html.contains("""<a href="anchor:3""""))
        assertTrue("label markup kept", r.html.contains("<code>gate</code>"))
    }

    @Test
    fun `anchors show their number after the label like gg does`() {
        val r = render("[a](x.go:1) [b](y.go:2)")
        assertTrue(r.html, r.html.contains("""<a href="anchor:1">a<sup>1</sup></a>"""))
        assertTrue(r.html, r.html.contains("""<a href="anchor:2">b<sup>2</sup></a>"""))
        val missing = MarkdownRenderer.render("[a](x.go:1)", missingAnchors = setOf(1))
        assertTrue(missing.html, missing.html.contains("""<span class="missing">a<sup>1</sup></span>"""))
    }

    @Test
    fun `http links stay links and non-anchors become label with dest`() {
        val r = render("[x](https://example.com/p) [y](a.go:9-3) [z](ftp://h/p)")
        assertTrue(r.html, r.html.contains("""<a href="https://example.com/p">x</a>"""))
        assertTrue(r.html, r.html.contains("y (a.go:9-3)"))
        assertTrue(r.html, r.html.contains("z (ftp://h/p)"))
        assertEquals(0, r.anchors.size)
    }

    @Test
    fun `dropped schemes render the label alone`() {
        val r = render("[x](javascript:alert(1)) and [w](note:t12)")
        assertFalse(r.html, r.html.contains("javascript"))
        assertTrue(r.html, r.html.contains("x"))
        assertTrue(r.html, r.html.contains("w (note:t12)"))
    }

    @Test
    fun `images classify like links with alt as label`() {
        val r = render("![alt](ftp://h/i.png) ![pic](docs/a.png)")
        assertTrue(r.html, r.html.contains("alt (ftp://h/i.png)"))
        assertEquals(listOf(LinkTarget.Anchor("docs/a.png", null, null)), r.anchors.map { it.target })
        assertFalse(r.html, r.html.contains("<img"))
    }

    @Test
    fun `a newline inside a paragraph is a hard break also in tight list items`() {
        val r = render("line one\nline two\n\n- item a\n  continued\n- item b")
        // a top-level paragraph's hard break starts a new block (class br: a little air, less than a paragraph's)
        assertTrue(r.html, Regex("line one</p>\\s*<p class=\"br\">line two").containsMatchIn(r.html))
        assertFalse(r.html, Regex("line one<br ?/?>").containsMatchIn(r.html))
        // inside a list item the break stays a <br>
        assertTrue(r.html, Regex("item a<br ?/?>\\s*continued").containsMatchIn(r.html))
        val quoted = render("> q one\n> q two")
        assertTrue(quoted.html, Regex("q one<br ?/?>\\s*q two").containsMatchIn(quoted.html))
    }

    @Test
    fun `raw html is literal text`() {
        val r = render("a <b>bold</b> word\n\n<div>block</div>")
        assertFalse(r.html, r.html.contains("<b>"))
        assertTrue(r.html, r.html.contains("&lt;b&gt;bold&lt;/b&gt;"))
        assertTrue(r.html, r.html.contains("&lt;div&gt;block&lt;/div&gt;"))
    }

    @Test
    fun `block elements render`() {
        val r = render("# H1\n\n## H2\n\n> quote\n\n```kotlin\nval x = 1 < 2\n```\n\n1. one\n2. two\n\n- [ ] todo\n- [x] done\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\n---\n\n**strong** *em* ~~del~~ `code`")
        for (needle in listOf("<h1>H1</h1>", "<h2>H2</h2>", "<blockquote>", "<pre>", "val x = 1 &lt; 2", "<ol>", "<li>", "<table>", "<td>1</td>", "<hr", "<strong>strong</strong>", "<em>em</em>", "del", "<code>code</code>")) {
            assertTrue("missing $needle in\n${r.html}", r.html.contains(needle))
        }
        assertTrue(r.html, r.html.contains("☐") || r.html.contains("[ ]"))
    }

    @Test
    fun `only the first 100 anchors count and later ones are the label alone`() {
        val md = (1..102).joinToString("\n\n") { "[l$it](f$it.go:$it)" }
        val r = render(md)
        assertEquals(100, r.anchors.size)
        assertTrue(r.html, r.html.contains("""<a href="anchor:100""""))
        assertFalse(r.html, r.html.contains("anchor:101"))
        assertTrue(r.html, r.html.contains("l101"))
        assertFalse(r.html, r.html.contains("l101 (f101.go:101)"))
    }

    @Test
    fun `autolinks and bare urls are links`() {
        val r = render("see <https://a.b/c> and https://d.e/f now")
        assertTrue(r.html, r.html.contains("""href="https://a.b/c""""))
        assertTrue(r.html, r.html.contains("""href="https://d.e/f""""))
    }

    @Test
    fun `the real gitfocus document yields 27 anchors into three kotlin files`() {
        val json = Files.readString(Paths.get("examples/gitfocus-real/overview-document.json"))
        val doc = (StoreReader().decodeDocument(json, "") as DocumentResult.Ok).document
        val r = render(doc.overview)
        assertEquals(27, r.anchors.size)
        val first = r.anchors.first()
        assertEquals("rowText", first.label)
        assertEquals(LinkTarget.Anchor("src/main/kotlin/dev/gitfocus/worktrees/WorktreesList.kt", 20, 28), first.target)
        assertTrue(r.html.contains("<h2>"))
    }

    @Test
    fun `current anchor gets a marker class`() {
        val r = MarkdownRenderer.render("[a](x.go:1) [b](y.go:2)", currentAnchor = 2)
        assertTrue(r.html, r.html.contains("""<a href="anchor:2" class="current""""))
        assertFalse(r.html, r.html.contains("""<a href="anchor:1" class="current""""))
    }

    @Test
    fun `missing anchors are drawn greyed and not clickable`() {
        val r = MarkdownRenderer.render("[a](x.go:1) [b](y.go:2)", missingAnchors = setOf(2))
        assertTrue(r.html, r.html.contains("""<a href="anchor:1""""))
        assertFalse(r.html, r.html.contains("""href="anchor:2""""))
        assertTrue(r.html, r.html.contains("""class="missing""""))
    }
}
