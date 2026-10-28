package dev.ggnotes.store

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Paths
import java.time.OffsetDateTime

/** An overview row's text (the tool window's tooltip), per the examples README. */
class OverviewGroupingTest {

    private fun ov(id: String, commit: String, title: String, updated: String, pr: Int? = null, author: String = "ada", branch: String = "") = Overview(
        id = id, title = title, author = author, commit = commit, branch = branch,
        preview = pr?.let { "main...refs/gg/pr/$it" }, prNumber = pr,
        created = OffsetDateTime.parse(updated), updated = OffsetDateTime.parse(updated),
        document = DocumentResult.Ok(OverviewDocument(title, "x", emptyList())),
        sourceFile = Paths.get("commits.toml"),
    )


    @Test
    fun `row text is title author date short sha and PR number`() {
        val row = OverviewGrouping.rowText(ov("1", "d5efa035df1f3c4c949040bebb924a35d0ea61a0", "How the gate works", "2026-10-13T23:53:56Z", author = "Claude Opus 5.5", branch = "main"))
        assertEquals("How the gate works · Claude Opus 5.5 · 2026-10-13 · d5efa03 · main", row)
        val pr = OverviewGrouping.rowText(ov("2", "e4a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3", "PR 7: the gate, reviewed", "2026-10-14T08:00:00Z", pr = 7))
        assertEquals("PR 7: the gate, reviewed · ada · 2026-10-14 · e4a1b2c · PR #7", pr)
    }
}
