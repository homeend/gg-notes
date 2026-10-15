package dev.ggnotes.store

import org.junit.Assert.assertEquals
import org.junit.Test

/** SPEC §6 grammar and §7 destination rules, table-tested with the spec's own examples. */
class LinkClassifierTest {

    private fun anchor(path: String, line: Int? = null, end: Int? = null) = LinkTarget.Anchor(path, line, end ?: line)

    @Test
    fun `spec table of destinations`() {
        val table = listOf(
            "internal/x/parse.go" to anchor("internal/x/parse.go"),
            "internal/x/parse.go:40" to anchor("internal/x/parse.go", 40),
            "./internal/x/parse.go:40-60" to anchor("internal/x/parse.go", 40, 60),
            "  ./x.go:3 " to anchor("x.go", 3),
            "config.toml:0" to LinkTarget.Text("config.toml:0"),
            "a.go:9-3" to LinkTarget.Text("a.go:9-3"),
            "../other/a.go:3" to LinkTarget.Text("../other/a.go:3"),
            "C:/src/a.go:3" to LinkTarget.Text("C:/src/a.go:3"),
            "C:" to LinkTarget.Text("C:"),
            "/abs/a.go" to LinkTarget.Text("/abs/a.go"),
            "dir\\a.go" to LinkTarget.Text("dir\\a.go"),
            "note:t12" to LinkTarget.Text("note:t12"),
            "https://example.com/x:3" to LinkTarget.Url("https://example.com/x:3"),
            "http://example.com/p" to LinkTarget.Url("http://example.com/p"),
            "javascript:alert(1)" to LinkTarget.LabelOnly,
            "data:a.go:3" to LinkTarget.LabelOnly,
            "VBScript:x" to LinkTarget.LabelOnly,
            "java\tscript:alert(1)" to LinkTarget.LabelOnly,
            "mailto:a@b" to anchor("mailto:a@b"),
            "file:x" to anchor("file:x"),
            "a:b" to anchor("a:b"),
            "a:b:3" to anchor("a:b", 3),
            "ftp://h/p" to LinkTarget.Text("ftp://h/p"),
            "" to LinkTarget.Text(""),
            "a b.go:3" to LinkTarget.Text("a b.go:3"),
            "x/../a.go" to LinkTarget.Text("x/../a.go"),
            "a.go:3-" to anchor("a.go:3-"),
            "a.go:" to anchor("a.go:"),
            ":3" to LinkTarget.Text(":3"),
        )
        for ((dest, expected) in table) {
            assertEquals("dest <$dest>", expected, LinkClassifier.classify(dest))
        }
    }

    @Test
    fun `dropped-scheme sniffing ignores embedded whitespace and control bytes`() {
        assertEquals(LinkTarget.LabelOnly, LinkClassifier.classify("ja va\u0001script:alert(1)"))
        assertEquals(LinkTarget.LabelOnly, LinkClassifier.classify("DATA:text/html,x"))
    }

    @Test
    fun `an http url with a control byte inside is neither a url nor an anchor`() {
        assertEquals(LinkTarget.Text("ht\ntps://x.y/z"), LinkClassifier.classify("ht\ntps://x.y/z"))
        assertEquals(LinkTarget.Text("https://x.y/a b"), LinkClassifier.classify("https://x.y/a b"))
    }
}
