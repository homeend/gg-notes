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
    fun `a file is every thread root on it`() {
        val file = commit.files.single { it.path == "internal/x/gate.go" }
        assertEquals(listOf("note rm b2c3d4e5", "note rm aa110001"), RemovalPlan.forFile(file).lines)
        assertEquals(2, RemovalPlan.forFile(file).records)
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
    fun `a working entry is its reviews and its live notes`() {
        val w = model().working.single()
        val plan = RemovalPlan.forWorking(w)
        val expected = w.files.flatMap { f -> f.threads.map { "note rm ${it.root.id}" } } +
            w.reviews.flatMap { r -> r.scopedThreads.map { "note rm ${it.root.id}" } + "note rm ${r.id}" }
        assertEquals(expected, plan.lines)
        assertEquals(RemovalPlan.Kind.WORKING, plan.kind)
    }

    @Test
    fun `the ids a plan removes are what the open document may be showing`() {
        val plan = RemovalPlan.forCommit(commit)
        assertEquals(plan.lines.map { it.substringAfterLast(' ') }.toSet(), plan.ids)
    }
}
