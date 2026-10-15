package dev.ggnotes.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Paths
import java.time.OffsetDateTime

/** Tool window order (AGENTS.md §4): groups by commit, HEAD's first, then newest `updated`; rows per the examples README. */
class OverviewGroupingTest {

    private fun ov(id: String, commit: String, title: String, updated: String, pr: Int? = null, author: String = "ada", branch: String = "") = Overview(
        id = id, title = title, author = author, commit = commit, branch = branch,
        preview = pr?.let { "main...refs/gg/pr/$it" }, prNumber = pr,
        created = OffsetDateTime.parse(updated), updated = OffsetDateTime.parse(updated),
        document = DocumentResult.Ok(OverviewDocument(title, "x", emptyList())),
        sourceFile = Paths.get("commits.toml"),
    )

    private val a = "a".repeat(40)
    private val b = "b".repeat(40)
    private val c = "c".repeat(40)

    @Test
    fun `HEAD group comes first then groups by newest update`() {
        val overviews = listOf(
            ov("1", a, "old on a", "2026-10-01T00:00:00Z"),
            ov("2", b, "newest on b", "2026-10-10T00:00:00Z"),
            ov("3", c, "head one", "2026-09-01T00:00:00Z"),
            ov("4", a, "newer on a", "2026-10-05T00:00:00Z"),
        )
        val groups = OverviewGrouping.group(overviews, headSha = c, subjects = mapOf(a to "A subject", c to "C subject"))
        assertEquals(listOf(c, b, a), groups.map { it.commit })
        assertTrue(groups[0].isHead)
        assertEquals("C subject", groups[0].subject)
        assertNull("b is not in the repository", groups[1].subject)
        assertEquals(listOf("newer on a", "old on a"), groups[2].overviews.map { it.title })
    }

    @Test
    fun `row text is title author date short sha and PR number`() {
        val row = OverviewGrouping.rowText(ov("1", "d5efa035df1f3c4c949040bebb924a35d0ea61a0", "How the gate works", "2026-10-13T23:53:56Z", author = "Claude Opus 5.5", branch = "main"))
        assertEquals("How the gate works · Claude Opus 5.5 · 2026-10-13 · d5efa03 · main", row)
        val pr = OverviewGrouping.rowText(ov("2", "e4a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3", "PR 7: the gate, reviewed", "2026-10-14T08:00:00Z", pr = 7))
        assertEquals("PR 7: the gate, reviewed · ada · 2026-10-14 · e4a1b2c · PR #7", pr)
    }

    @Test
    fun `group label shows short sha subject and HEAD marker`() {
        val g = OverviewGrouping.group(listOf(ov("1", a, "t", "2026-10-01T00:00:00Z")), headSha = a, subjects = mapOf(a to "Merge branch 'x'")).single()
        assertEquals("aaaaaaa  Merge branch 'x'  (HEAD)", OverviewGrouping.groupText(g))
        val unknown = OverviewGrouping.group(listOf(ov("1", b, "t", "2026-10-01T00:00:00Z")), headSha = a, subjects = emptyMap()).single()
        assertEquals("bbbbbbb  (commit not in this repository)", OverviewGrouping.groupText(unknown))
    }
}
