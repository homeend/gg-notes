package dev.ggnotes.items

import dev.ggnotes.session.DocIndex
import dev.ggnotes.session.Parsed
import dev.ggnotes.session.SessionDocParser
import dev.ggnotes.session.SessionNoteDoc
import dev.ggnotes.session.SessionOverviewDoc
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
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant

/** The item model over a store fixture (`examples/items/`) and session docs: kinds, ids, owners, dates. */
class NoteItemsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val c1 = "1".repeat(40)
    private val c2 = "2".repeat(40)

    private fun copyStore(vararg parts: Pair<String, String>): Path {
        val dir = Files.createTempDirectory(tmp.root.toPath(), "store")
        for ((from, to) in parts) {
            val target = dir.resolve(to)
            Files.createDirectories(target.parent)
            Files.copy(Paths.get(from), target)
        }
        return dir
    }

    private fun itemsModel(): RepoModel {
        val dir = copyStore(
            "examples/items/commits.toml" to "commits.toml",
            "examples/items/previews.toml" to "previews.toml",
            "examples/items/worktrees/cc36f09eabab0c81.toml" to "worktrees/cc36f09eabab0c81.toml",
        )
        return StoreModel.build(StoreReader().read(dir), headSha = c1, subjects = emptyMap())
    }

    private fun sessionOverview(id: String, millis: Long): DocIndex.Entry<SessionOverviewDoc> {
        val text = "+++\ntitle = 'Walk'\n+++\ntext [a](x.go:1)\n"
        val doc = (SessionDocParser.overview(id, text.toByteArray()) as Parsed.Ok).doc
        return DocIndex.Entry(doc, size = 0, mtime = 0, time = millis)
    }

    private fun sessionNote(id: String, millis: Long): DocIndex.Entry<SessionNoteDoc> {
        val text = "path = 'x.go'\nstart = 2\nend = 3\nsummary = 'sn'\nlines = ['l1', 'l2']\n"
        val doc = (SessionDocParser.note(id, text.toByteArray()) as Parsed.Ok).doc
        return DocIndex.Entry(doc, size = 0, mtime = 0, time = millis)
    }

    private fun items(): List<NoteItem> = NoteItems.build(itemsModel(), emptyList(), emptyList())

    private fun byId(id: String): NoteItem = items().single { it.id == id }

    @Test
    fun `kinds follow gg's address`() {
        val kinds = items().associate { it.id to it.kind }
        assertEquals(ItemKind.COMMIT, kinds["store:0v000001"])
        assertEquals(ItemKind.COMMIT, kinds["store:0r000001"])
        assertEquals(ItemKind.COMMIT, kinds["store:0r000003"])
        assertEquals(ItemKind.BRANCH, kinds["store:0r000002"])
        assertEquals(ItemKind.PULL_REQUEST, kinds["store:0v000002"])
        assertEquals(ItemKind.OTHER, kinds["store:0w000001"])
        val threads = items().filter { it.source == ItemSource.STORE_THREAD }
        assertEquals(8, threads.size)
        assertTrue(threads.all { it.kind == ItemKind.FILE })
    }

    @Test
    fun `ids are namespaced`() {
        val all = NoteItems.build(itemsModel(), listOf(sessionOverview("20261018T143201-9f3a", 5)), listOf(sessionNote("20261018T143202-1b2c", 6)))
        val store = all.filter { it.source.name.startsWith("STORE") }
        assertTrue(store.isNotEmpty() && store.all { it.id.startsWith("store:") })
        assertEquals("note:20261018T143202-1b2c", all.single { it.source == ItemSource.SESSION_NOTE }.id)
        assertEquals("20261018T143201-9f3a", all.single { it.source == ItemSource.SESSION_OVERVIEW }.id)
        assertEquals(all.size, all.map { it.id }.toSet().size)
    }

    @Test
    fun `session items carry the entry time, the note its path and lines`() {
        val all = NoteItems.build(itemsModel(), listOf(sessionOverview("o1", 5_000)), listOf(sessionNote("n1", 6_000)))
        val o = all.single { it.source == ItemSource.SESSION_OVERVIEW }
        assertEquals(ItemKind.OTHER, o.kind)
        assertEquals("Walk", o.title)
        assertEquals(Owner(), o.owner)
        assertEquals(Instant.ofEpochMilli(5_000), o.time)
        val n = all.single { it.source == ItemSource.SESSION_NOTE }
        assertEquals(ItemKind.FILE, n.kind)
        assertEquals("sn", n.title)
        assertEquals(Owner(path = "x.go", first = 2, last = 3), n.owner)
        assertEquals(Instant.ofEpochMilli(6_000), n.time)
    }

    @Test
    fun `owners`() {
        val branch = byId("store:0r000002").owner
        assertEquals("feat/x", branch.branch)
        assertEquals(c2, branch.commit)
        val working = byId("store:0w000001").owner
        assertEquals("/home/dev/src/gate", working.worktree)
        assertNull(working.commit)
        assertNull(working.branch)
        val live = byId("store:0w000003").owner
        assertNull(live.commit)
        assertEquals("x.go", live.path)
        assertEquals(4, live.first)
        assertEquals("/home/dev/src/gate", live.worktree)
        val committed = byId("store:0n000001").owner
        assertEquals(c1, committed.commit)
        assertNull(committed.worktree)
        assertEquals("a.go", committed.path)
        assertEquals(3, committed.first)
        val pr = byId("store:0v000002").owner
        assertEquals(7, pr.pr)
        assertEquals("main...refs/gg/pr/7", pr.preview)
        assertNull(byId("store:0r000001").owner.branch)
        assertEquals("main", byId("store:0v000001").owner.branch)
    }

    @Test
    fun `the time is created, else updated, else null`() {
        assertNull(byId("store:0n000007").time)
        assertEquals(Instant.parse("2026-10-05T08:00:00Z"), byId("store:0n000001").time)
        assertEquals(Instant.parse("2026-10-01T08:00:00Z"), byId("store:0v000001").time)
        assertEquals(Instant.parse("2026-10-13T08:00:00Z"), byId("store:0w000003").time)
    }

    @Test
    fun `a thread's title is its root summary and a reply is not an item`() {
        assertEquals("note a9", byId("store:0n000004").title)
        assertTrue(items().none { it.id == "store:0n000005" })
    }

    @Test
    fun `a review's scoped threads are its children`() {
        val dir = copyStore("examples/commits.toml" to "commits.toml", "examples/previews.toml" to "previews.toml")
        val c = StoreReader().read(dir)
        val scoped = c.records.single { it.id == "91f0aa07" }.copy(preview = "main...refs/gg/pr/7")
        val model = StoreModel.build(c.copy(records = c.records.map { if (it.id == "91f0aa07") scoped else it }), headSha = null, subjects = emptyMap())
        val review = model.commits.flatMap { it.reviews }.single { it.id == "c0ffee02" }
        assertTrue(review.scopedThreads.isNotEmpty())
        val all = NoteItems.build(model, emptyList(), emptyList())
        val item = all.single { it.id == "store:c0ffee02" }
        val scopedIds = review.scopedThreads.map { "store:" + it.root.id }
        assertTrue(item.children.map { it.id }.containsAll(scopedIds))
        assertTrue(item.children.all { it.source == ItemSource.STORE_THREAD })
        assertTrue(all.none { it.id in scopedIds })
        assertNotNull(item.children.first().time)
    }
}
