package dev.ggnotes.items

import dev.ggnotes.session.DocIndex
import dev.ggnotes.session.Parsed
import dev.ggnotes.session.SessionDocParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class ItemViewsTest {
    private val payload: Payload by lazy {
        val text = "+++\ntitle = 'Walk'\n+++\ntext\n"
        val doc = (SessionDocParser.overview("x", text.toByteArray()) as Parsed.Ok).doc
        Payload.SessionOverview(DocIndex.Entry(doc, size = 0, mtime = 0, time = 0))
    }

    private fun item(
        id: String, kind: ItemKind = ItemKind.OTHER, time: String? = null,
        owner: Owner = Owner(), source: ItemSource = ItemSource.SESSION_OVERVIEW,
    ) = NoteItem(id, kind, source, id, owner, "a", time?.let { Instant.parse(it) }, payload, emptyList())

    private val utc = ZoneId.of("UTC")

    @Test fun `days are local days, newest first`() {
        val items = listOf(item("a", time = "2026-10-27T23:30:00Z"), item("b", time = "2026-10-26T10:00:00Z"))
        assertEquals(listOf(LocalDate.of(2026, 10, 27), LocalDate.of(2026, 10, 26)), ItemViews.byDate(items, utc).map { it.day })
        assertEquals(LocalDate.of(2026, 10, 28), ItemViews.byDate(items, ZoneId.of("+02:00")).first().day)
    }

    @Test fun `items in a day are newest first, then id`() {
        val items = listOf(
            item("b", time = "2026-10-27T10:00:00Z"), item("a", time = "2026-10-27T10:00:00Z"),
            item("c", time = "2026-10-27T12:00:00Z"), item("d", time = "2026-10-27T08:00:00Z"),
        )
        assertEquals(listOf("c", "a", "b", "d"), ItemViews.byDate(items, utc).single().items.map { it.id })
    }

    @Test fun `undated items come last under a null day`() {
        val groups = ItemViews.byDate(listOf(item("n"), item("a", time = "2026-10-27T10:00:00Z"), item("m")), utc)
        assertEquals(2, groups.size)
        assertNull(groups.last().day)
        assertEquals(listOf("m", "n"), groups.last().items.map { it.id })
    }

    @Test fun `by type - kinds in fixed order, empty ones omitted, days inside`() {
        val items = listOf(
            item("f", ItemKind.FILE, "2026-10-27T10:00:00Z"), item("b1", ItemKind.BRANCH, "2026-10-26T10:00:00Z"),
            item("b2", ItemKind.BRANCH, "2026-10-27T10:00:00Z"), item("o", ItemKind.OTHER),
        )
        val groups = ItemViews.byType(items, utc)
        assertEquals(listOf(ItemKind.BRANCH, ItemKind.FILE, ItemKind.OTHER), groups.map { it.kind })
        assertEquals(listOf(LocalDate.of(2026, 10, 27), LocalDate.of(2026, 10, 26)), groups[0].days.map { it.day })
        assertNull(groups[2].days.single().day)
    }

    @Test fun `owner labels`() {
        val sha = "0123456789abcdef"
        assertEquals("PR #7", ItemViews.ownerLabel(item("p", ItemKind.PULL_REQUEST, owner = Owner(pr = 7, preview = "pv"))))
        assertEquals("pv", ItemViews.ownerLabel(item("p", ItemKind.PULL_REQUEST, owner = Owner(preview = "pv"))))
        assertEquals("feat/x", ItemViews.ownerLabel(item("b", ItemKind.BRANCH, owner = Owner(branch = "feat/x"))))
        assertEquals("0123456", ItemViews.ownerLabel(item("c", ItemKind.COMMIT, owner = Owner(commit = sha))))
        assertEquals("a/b.kt:3", ItemViews.ownerLabel(item("f", ItemKind.FILE, owner = Owner(path = "a/b.kt", first = 3))))
        assertEquals("a/b.kt:3", ItemViews.ownerLabel(item("f", ItemKind.FILE, owner = Owner(path = "a/b.kt", first = 3, last = 3))))
        assertEquals("a/b.kt:3-5 · 0123456", ItemViews.ownerLabel(item("f", ItemKind.FILE, owner = Owner(commit = sha, path = "a/b.kt", first = 3, last = 5))))
        assertEquals("a/b.kt", ItemViews.ownerLabel(item("f", ItemKind.FILE, owner = Owner(path = "a/b.kt"))))
        assertEquals("working", ItemViews.ownerLabel(item("o", source = ItemSource.STORE_THREAD, owner = Owner(worktree = "/w"))))
        assertEquals("session", ItemViews.ownerLabel(item("o")))
        assertEquals("session", ItemViews.ownerLabel(item("o", owner = Owner(worktree = "/w"))))
    }
}
