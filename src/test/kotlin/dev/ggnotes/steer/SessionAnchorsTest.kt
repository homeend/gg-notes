package dev.ggnotes.steer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** gg's anchor grammar plus `note:<id>` with the session-docs id rule (SPEC §3.2). */
class SessionAnchorsTest {

    @Test
    fun `anchor destinations follow gg's grammar plus note ids`() {
        assertEquals(SessionAnchor("internal/x/gate.go", 3, 4, null), SessionAnchors.parse("./internal/x/gate.go:3-4"))
        assertEquals(SessionAnchor("internal/x/gate.go", null, null, null), SessionAnchors.parse("internal/x/gate.go"))
        assertEquals(SessionAnchor(null, null, null, "20261018T143205-c0d1"), SessionAnchors.parse("note:20261018T143205-c0d1"))
        assertEquals(SessionAnchor(null, null, null, "tour-report-gg-12345-7"), SessionAnchors.parse("note:tour-report-gg-12345-7"))
        assertNull(SessionAnchors.parse("note:"))
        assertNull(SessionAnchors.parse("note:a.b"))
        assertNull(SessionAnchors.parse("note:-x"))
        assertNull(SessionAnchors.parse("../a.go:1"))
        assertNull(SessionAnchors.parse("C:/a.go"))
        assertNull(SessionAnchors.parse("https://x/y"))
        assertNull(SessionAnchors.parse("a.go:9-3"))
    }

    @Test
    fun `only the first 100 anchors count and the viewer rewrite keeps note links that resolve`() {
        val text = (1..102).joinToString(" ") { "[l$it](f$it.go:$it)" }
        assertEquals(100, SessionAnchors.extract(text).size)
        val md = SessionAnchors.rewrite("[n](note:abc) [gone](note:zzz) [f](x.go:1) [u](y.go:2)", setOf("y.go:2")) { id -> if (id == "abc") "a.go:3-4" else null }
        assertEquals("[n](a.go:3-4) gone (note:zzz) [f](x.go:1) u (y.go:2)", md)
    }
}
