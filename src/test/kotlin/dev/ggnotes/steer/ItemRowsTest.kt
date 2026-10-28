package dev.ggnotes.steer

import dev.ggnotes.items.ItemKind
import dev.ggnotes.items.ItemSource
import dev.ggnotes.items.NoteItem
import dev.ggnotes.items.NoteItems
import dev.ggnotes.items.Owner
import dev.ggnotes.items.Payload
import dev.ggnotes.session.DocIndex
import dev.ggnotes.session.Parsed
import dev.ggnotes.session.SessionDocParser
import dev.ggnotes.session.SessionNoteDoc
import dev.ggnotes.session.SessionOverviewDoc
import dev.ggnotes.store.Overview
import dev.ggnotes.store.RepoModel
import dev.ggnotes.store.StoreModel
import dev.ggnotes.store.StoreReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Paths

/** The `files` rows of note items, lookup, the shown judgement and the focus reply. */
class ItemRowsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val c1 = "1".repeat(40)
    private val n1 = "20261018T143202-1b2c"
    private val o1 = "20261018T143201-9f3a"

    private fun model(): RepoModel {
        val dir = Files.createTempDirectory(tmp.root.toPath(), "store")
        for (f in listOf("commits.toml", "previews.toml", "worktrees/cc36f09eabab0c81.toml")) {
            val target = dir.resolve(f)
            Files.createDirectories(target.parent)
            Files.copy(Paths.get("examples/items/$f"), target)
        }
        return StoreModel.build(StoreReader().read(dir), headSha = c1, subjects = emptyMap())
    }

    private fun sessionOverview(id: String, millis: Long, links: String): DocIndex.Entry<SessionOverviewDoc> {
        val doc = (SessionDocParser.overview(id, "+++\ntitle = 'Walk'\n+++\ntext $links\n".toByteArray()) as Parsed.Ok).doc
        return DocIndex.Entry(doc, size = 0, mtime = 0, time = millis)
    }

    private fun sessionNote(id: String, millis: Long): DocIndex.Entry<SessionNoteDoc> {
        val doc = (SessionDocParser.note(id, "path = 'x.go'\nstart = 2\nend = 3\nsummary = 'sn'\nlines = ['l1', 'l2']\n".toByteArray()) as Parsed.Ok).doc
        return DocIndex.Entry(doc, size = 0, mtime = 0, time = millis)
    }

    private fun items(): List<NoteItem> =
        NoteItems.build(model(), listOf(sessionOverview(o1, 5_000, "[n](note:$n1)")), listOf(sessionNote(n1, 6_000), sessionNote("20261018T143203-2c3d", 7_000)))

    private fun flatten(l: List<NoteItem>): List<NoteItem> = l.flatMap { listOf(it) + flatten(it.children) }

    private fun storeOverviewOf(id: String): Overview = (flatten(items()).single { it.id == id }.payload as Payload.StoreOverview).overview

    @Test
    fun `rows skip session overviews and list children after their parent`() {
        val all = items()
        val rows = ItemRows.rows(all, null)
        val ids = rows.map { it.id }
        assertTrue(o1 !in ids)
        assertTrue("note:$n1" in ids)
        assertEquals(ids.size, ids.toSet().size)
        val expected = flatten(all).filter { it.source != ItemSource.SESSION_OVERVIEW }.map { it.id }
        assertEquals(expected.sorted(), ids.sorted())
        val idx = ids.withIndex().associate { it.value to it.index }
        val tops = all.map { it.id }.toSet()
        for (t in all) for ((k, c) in t.children.withIndex()) {
            if (t.source == ItemSource.SESSION_OVERVIEW) continue
            assertEquals("${c.id} directly after its parent chain", idx[t.id]!! + 1 + k, idx[c.id])
        }
        // a top item is never listed inside another top's run: tops appear in By Date order
        val topOrder = ids.filter { it in tops }
        assertEquals(topOrder, topOrder.sortedBy { idx[it] })
    }

    @Test
    fun `a row carries kind, source, owner, path, line, created`() {
        val rows = ItemRows.rows(items(), null).associateBy { it.id }
        val review = rows.getValue("store:0r000001")
        assertEquals("store-review", review.source)
        assertEquals("commit", review.kind)
        assertEquals(c1, review.owner!!.commit)
        assertEquals("", review.path)
        assertEquals(0, review.line)
        val thread = rows.getValue("store:0n000001")
        assertEquals("store-thread", thread.source)
        assertEquals("file", thread.kind)
        assertEquals("a.go", thread.path)
        assertEquals(3, thread.line)
        assertEquals("a.go", thread.owner!!.path)
        assertEquals("2026-10-05T08:00:00Z", thread.created)
        assertNull(rows.getValue("store:0n000007").created)
        val note = rows.getValue("note:$n1")
        assertEquals("session-note", note.source)
        assertEquals("x.go", note.path)
        assertEquals(2, note.line)
        assertEquals("background", note.state)
        assertNotNull(note.created)
    }

    @Test
    fun `state is shown only for the open record`() {
        val all = items()
        val ov = storeOverviewOf("store:0v000001")
        val shown = ItemRows.rows(all, ov).filter { it.state == "shown" }.map { it.id }
        assertEquals(listOf("store:0v000001"), shown)
        val thread = ov.copy(viewKind = "notes:0n000001:")
        assertEquals(listOf("store:0n000001"), ItemRows.rows(all, thread).filter { it.state == "shown" }.map { it.id })
        val review = ov.copy(viewKind = "review:0r000001:")
        assertEquals(listOf("store:0r000001"), ItemRows.rows(all, review).filter { it.state == "shown" }.map { it.id })
        assertTrue(ItemRows.rows(all, null).all { it.state == "background" })
    }

    @Test
    fun `find finds children`() {
        val all = items()
        val found = ItemRows.find(all, "store:0n000001")
        assertNotNull(found)
        assertEquals(ItemSource.STORE_THREAD, found!!.source)
        assertNull(ItemRows.find(all, "store:nope"))
        assertEquals("note:$n1", ItemRows.find(all, "note:$n1")!!.id)
    }

    @Test
    fun `focused reply quotes the title`() {
        val item = NoteItem("store:x", ItemKind.COMMIT, ItemSource.STORE_REVIEW, "Say \"hi\"\nnow", Owner(), "a", null,
            Payload.SessionNote(sessionNote("n", 1)), emptyList())
        assertEquals("focused store-review store:x \"Say \\\"hi\\\"\\nnow\"", ItemRows.focusedReply(item))
    }
}
