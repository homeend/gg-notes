package dev.ggnotes.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The small grey badge after an overview or review title that says what the
 * row refers to beyond its commit: a pull request, a branch (still at that
 * commit, or moved on), a range, a merge preview, a pair, the working tree.
 * A plain commit overview or review gets none: its group header is the commit.
 */
class RowBadgeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val examples: Path = Paths.get("examples")
    private val gate = "d5efa035df1f3c4c949040bebb924a35d0ea61a0"
    private val prHead = "e4a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3"

    private fun model(): RepoModel {
        val dir = Files.createTempDirectory(tmp.root.toPath(), "store")
        Files.copy(examples.resolve("commits.toml"), dir.resolve("commits.toml"))
        Files.copy(examples.resolve("previews.toml"), dir.resolve("previews.toml"))
        Files.createDirectories(dir.resolve("worktrees"))
        Files.copy(examples.resolve("worktrees/cc36f09eabab0c81.toml"), dir.resolve("worktrees/cc36f09eabab0c81.toml"))
        return StoreModel.build(StoreReader().read(dir), headSha = prHead, subjects = emptyMap())
    }

    @Test
    fun `a plain commit overview has no badge`() {
        val o = model().commits.single { it.commit == gate }.overviews.single().copy(branch = "")
        assertNull(RowBadge.of(o, branchTip = null))
    }

    @Test
    fun `the fixture overview was saved on main, so it is a branch overview`() {
        val o = model().commits.single { it.commit == gate }.overviews.single()
        assertEquals("main", o.branch)
        assertEquals(RowBadge.Branch("main"), RowBadge.of(o, branchTip = gate))
        assertEquals(RowBadge.WasTip("main"), RowBadge.of(o, branchTip = null))
    }

    @Test
    fun `a pull request overview says PR`() {
        val o = model().commits.single { it.commit == prHead }.overviews.single()
        assertEquals(RowBadge.Pr(7), RowBadge.of(o, branchTip = null))
    }

    @Test
    fun `a branch overview says branch while the branch is at its commit, and was once it moved`() {
        val o = model().commits.single { it.commit == gate }.overviews.single().copy(branch = "main")
        assertEquals(RowBadge.Branch("main"), RowBadge.of(o, branchTip = gate))
        assertEquals(RowBadge.WasTip("main"), RowBadge.of(o, branchTip = "0000000000000000000000000000000000000000"))
        assertEquals("a branch that is gone also counts as moved on", RowBadge.WasTip("main"), RowBadge.of(o, branchTip = null))
    }

    @Test
    fun `a pull request wins over a recorded branch`() {
        val o = model().commits.single { it.commit == prHead }.overviews.single().copy(branch = "feature")
        assertEquals(RowBadge.Pr(7), RowBadge.of(o, branchTip = prHead))
    }

    @Test
    fun `reviews are badged by their scope kind`() {
        val m = model()
        val commitReview = m.commits.single { it.commit == gate }.reviews.single()
        assertEquals(ReviewKind.COMMIT, commitReview.kind)
        assertNull(RowBadge.ofReview(commitReview, branchTip = null))
        val prReview = m.commits.single { it.commit == prHead }.reviews.single()
        assertEquals(ReviewKind.PULL_REQUEST, prReview.kind)
        assertEquals(RowBadge.Pr(7), RowBadge.ofReview(prReview, branchTip = null))
        val working = m.working.single().reviews.single()
        assertEquals(RowBadge.Working, RowBadge.ofReview(working, branchTip = null))
    }

    @Test
    fun `the other review scopes map to their own words`() {
        val base = model().commits.single { it.commit == gate }.reviews.single()
        assertEquals(RowBadge.Range, RowBadge.ofReview(base.copy(kind = ReviewKind.RANGE), branchTip = null))
        assertEquals(RowBadge.Preview, RowBadge.ofReview(base.copy(kind = ReviewKind.MERGE_PREVIEW), branchTip = null))
        assertEquals(RowBadge.Pair, RowBadge.ofReview(base.copy(kind = ReviewKind.PAIR), branchTip = null))
        val onBranch = base.copy(kind = ReviewKind.BRANCH, record = base.record.copy(address = base.record.address.copy(branch = "topic")))
        assertEquals(RowBadge.Branch("topic"), RowBadge.ofReview(onBranch, branchTip = gate))
        assertEquals(RowBadge.WasTip("topic"), RowBadge.ofReview(onBranch, branchTip = null))
    }
}
