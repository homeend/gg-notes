package dev.ggnotes.store

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * What "Remove from gg store" on a tree row asks gg to delete: the `gg batch`
 * lines, children before parents so nothing is reported missing twice.
 * gg's `note rm <id>` takes a root's replies with it; `overview rm <id>` is the
 * overview's own verb.
 */
class RemovalPlanTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val examples: Path = Paths.get("examples")
    private val gate = "d5efa035df1f3c4c949040bebb924a35d0ea61a0"

    private fun model(): RepoModel {
        val dir = Files.createTempDirectory(tmp.root.toPath(), "store")
        Files.copy(examples.resolve("commits.toml"), dir.resolve("commits.toml"))
        Files.copy(examples.resolve("previews.toml"), dir.resolve("previews.toml"))
        Files.createDirectories(dir.resolve("worktrees"))
        Files.copy(examples.resolve("worktrees/cc36f09eabab0c81.toml"), dir.resolve("worktrees/cc36f09eabab0c81.toml"))
        return StoreModel.build(StoreReader().read(dir), headSha = gate, subjects = emptyMap())
    }

    private val commit get() = model().commits.single { it.commit == gate }

    @Test
    fun `an overview is removed with gg's overview verb`() {
        val plan = RemovalPlan.forOverview(commit.overviews.single())
        assertEquals(listOf("overview rm 7cf0812f"), plan.lines)
        assertEquals(RemovalPlan.Kind.OVERVIEW, plan.kind)
        assertEquals(1, plan.records)
    }

    @Test
    fun `a review goes with the scoped threads shown under it, threads first`() {
        val review = commit.reviews.single()
        val plan = RemovalPlan.forReview(review)
        assertEquals(review.scopedThreads.map { "note rm ${it.root.id}" } + "note rm c0ffee01", plan.lines)
        assertEquals(1 + review.scopedThreads.size, plan.records)
    }

    @Test
    fun `a thread is its root, a reply is itself`() {
        val file = commit.files.single { it.path == "internal/x/gate.go" }
        val thread = file.threads.single { it.root.id == "aa110001" }
        assertEquals(listOf("note rm aa110001"), RemovalPlan.forThread(thread).lines)
        assertEquals(listOf("note rm aa110002"), RemovalPlan.forReply(thread.replies.single()).lines)
        assertEquals(RemovalPlan.Kind.REPLY, RemovalPlan.forReply(thread.replies.single()).kind)
    }

    @Test
    fun `a commit is everything under it, notes before reviews before overviews`() {
        val c = commit
        val plan = RemovalPlan.forCommit(c)
        val expected = c.files.flatMap { f -> f.threads.map { "note rm ${it.root.id}" } } +
            c.reviews.flatMap { r -> r.scopedThreads.map { "note rm ${it.root.id}" } + "note rm ${r.id}" } +
            c.overviews.map { "overview rm ${it.id}" }
        assertEquals(expected, plan.lines)
        assertEquals(expected.size, plan.records)
        assertEquals(RemovalPlan.Kind.COMMIT, plan.kind)
    }

    @Test
    fun `the ids a plan removes are what the open document may be showing`() {
        val plan = RemovalPlan.forCommit(commit)
        assertEquals(plan.lines.map { it.substringAfterLast(' ') }.toSet(), plan.ids)
    }

    private fun itemTops(): List<dev.ggnotes.items.NoteItem> {
        val dir = Files.createTempDirectory(tmp.root.toPath(), "items")
        Files.copy(Paths.get("examples/items/commits.toml"), dir.resolve("commits.toml"))
        Files.copy(Paths.get("examples/items/previews.toml"), dir.resolve("previews.toml"))
        Files.createDirectories(dir.resolve("worktrees"))
        Files.copy(Paths.get("examples/items/worktrees/cc36f09eabab0c81.toml"), dir.resolve("worktrees/cc36f09eabab0c81.toml"))
        val m = StoreModel.build(StoreReader().read(dir), headSha = "1".repeat(40), subjects = emptyMap())
        return dev.ggnotes.items.NoteItems.build(m, emptyList(), emptyList())
    }

    @Test
    fun `an item goes with its children, children first`() {
        val tops = itemTops()
        val o = RemovalPlan.forItem(tops.single { it.id == "store:0v000001" })!!
        assertEquals(listOf("note rm 0n000001", "note rm 0n000004", "overview rm 0v000001"), o.lines)
        assertEquals(RemovalPlan.Kind.OVERVIEW, o.kind)
        val r = RemovalPlan.forItem(tops.single { it.id == "store:0r000001" })!!
        assertEquals(listOf("note rm 0n000002", "note rm 0r000001"), r.lines)
        assertEquals(RemovalPlan.Kind.REVIEW, r.kind)
        val child = tops.single { it.id == "store:0v000001" }.children.first()
        val t = RemovalPlan.forItem(child)!!
        assertEquals(listOf("note rm 0n000001"), t.lines)
        assertEquals(RemovalPlan.Kind.THREAD, t.kind)
        assertEquals("a.go:3", t.label)
    }

    @Test
    fun `a session item has no store plan`() {
        val text = "path = 'x.go'\nstart = 2\nend = 3\nsummary = 'sn'\nlines = ['l1', 'l2']\n"
        val doc = (dev.ggnotes.session.SessionDocParser.note("n1", text.toByteArray()) as dev.ggnotes.session.Parsed.Ok).doc
        val entry = dev.ggnotes.session.DocIndex.Entry(doc, size = 0, mtime = 0, time = 1)
        val item = dev.ggnotes.items.NoteItems.build(model(), emptyList(), listOf(entry)).single { it.source == dev.ggnotes.items.ItemSource.SESSION_NOTE }
        org.junit.Assert.assertNull(RemovalPlan.forItem(item))
    }
}
