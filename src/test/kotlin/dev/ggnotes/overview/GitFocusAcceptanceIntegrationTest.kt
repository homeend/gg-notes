package dev.ggnotes.overview

import com.intellij.dvcs.repo.VcsRepositoryManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsDirectoryMapping
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import dev.ggnotes.store.DocumentResult
import dev.ggnotes.store.LinkTarget
import dev.ggnotes.store.StoreLocator
import git4idea.GitVcs
import git4idea.repo.GitRepositoryManager
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * AGENTS.md §3.2, the acceptance scenario, as far as a test can take it, on a
 * FROZEN copy: `examples/gitfocus-acceptance/` holds the `~/git-focus` history
 * (`git-focus.bundle`, master) and the 8 records gg wrote about it
 * (`commits.toml`). Each test clones the bundle into a temp dir and puts the
 * store under a temp `XDG_STATE_HOME`, at the key of that clone, so the live
 * store — which gg prunes and rewrites — never decides the result. The human
 * half — looking at the tool window, clicking `rowText`, pressing Back —
 * stays a manual gate (`runIde`).
 */
class GitFocusAcceptanceIntegrationTest : HeavyPlatformTestCase() {

    private val frozen = Paths.get("examples/gitfocus-acceptance")
    private val commit = "d5efa035df1f3c4c949040bebb924a35d0ea61a0"
    private lateinit var checkout: File

    /** Clones the frozen history and installs [storeFiles] as its gg store; returns the clone. */
    private fun frozenCheckout(vararg storeFiles: Path): File {
        checkout = File(createTempDirectory(), "git-focus")
        git(checkout.parentFile, "clone", "--quiet", "--branch", "master", frozen.resolve("git-focus.bundle").toAbsolutePath().toString(), checkout.name)
        val common = git(checkout, "rev-parse", "--path-format=absolute", "--git-common-dir")
        val stateHome = createTempDirectory().toPath()
        val store = stateHome.resolve("gg").resolve("notes").resolve(StoreLocator.key(common))
        Files.createDirectories(store)
        for (f in storeFiles) Files.copy(f, store.resolve(f.fileName.toString()))
        System.setProperty("ggnotes.env.XDG_STATE_HOME", stateHome.toString())
        return checkout
    }

    private fun git(dir: File, vararg args: String): String {
        val p = ProcessBuilder(listOf("git") + args).directory(dir).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        assertEquals("git ${args.joinToString(" ")}: $out", 0, p.waitFor())
        return out
    }

    private fun mapRepository(): git4idea.repo.GitRepository {
        val root = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(checkout)!!
        ProjectLevelVcsManager.getInstance(project).directoryMappings = listOf(VcsDirectoryMapping(root.path, GitVcs.NAME))
        VcsRepositoryManager.getInstance(project).waitForAsyncTaskCompletion()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        return GitRepositoryManager.getInstance(project).getRepositoryForRootQuick(root)!!
    }

    override fun tearDown() {
        try {
            System.clearProperty("ggnotes.env.XDG_STATE_HOME")
            OverviewService.getInstance(project).close()
        } finally {
            super.tearDown()
        }
    }

    fun testFrozenStoreShowsTheBranchFirstRowsOverviewAndRowTextOpensWorktreesList() {
        frozenCheckout(frozen.resolve("commits.toml"))
        val repo = mapRepository()
        val service = OverviewService.getInstance(project)
        val snapshot = ApplicationManager.getApplication().executeOnPooledThread<Snapshot> { service.refreshSync() }.get()
        val repoOverviews = snapshot.repos.single()
        assertNull(repoOverviews.error)
        val overview = repoOverviews.contents.overviews.single { it.commit == commit }
        assertEquals("GitFocus tab: branch-first rows", overview.title)
        assertEquals("Claude Opus 5.5", overview.author)
        assertNotNull("commit $commit must be in the checkout", repoOverviews.model.commits.single { it.commit == commit }.subject)

        service.open(overview, repo)
        val open = service.open!!
        assertEquals(27, open.anchors.size)
        val first = open.anchors.first()
        assertEquals("rowText", first.label)
        assertEquals(LinkTarget.Anchor("src/main/kotlin/dev/gitfocus/worktrees/WorktreesList.kt", 20, 28), first.target)
        waitFor("anchors resolved") { open.resolved }
        assertEquals("every anchor resolves at the commit", emptySet<Int>(), open.missing)

        service.goToAnchor(1)
        waitFor("WorktreesList.kt opened") { FileEditorManager.getInstance(project).selectedFiles.singleOrNull()?.name == "WorktreesList.kt" }
        val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
        val doc = editor.document
        assertEquals(doc.getLineStartOffset(19), editor.selectionModel.selectionStart)
        assertEquals(doc.getLineEndOffset(27), editor.selectionModel.selectionEnd)
        assertTrue(doc.getText(com.intellij.openapi.util.TextRange(doc.getLineStartOffset(19), doc.getLineEndOffset(19))).contains("rowText"))
        waitFor("remark boxes") { editor.inlayModel.getBlockElementsInRange(0, doc.textLength).isNotEmpty() }
        val remarks = (overview.document as DocumentResult.Ok).document.files.single { it.path == first.target.path }.annotations
        assertEquals(remarks.size, editor.inlayModel.getBlockElementsInRange(0, doc.textLength).size)
    }

    /** AGENTS.md §5: the store's 7 other records — a prose review and six line notes gg wrote — list under their commits. */
    fun testFrozenStoreListsTheProseReviewAndTheSixLineNotesUnderTheirCommits() {
        frozenCheckout(frozen.resolve("commits.toml"))
        val repo = mapRepository()
        val service = OverviewService.getInstance(project)
        val snapshot = ApplicationManager.getApplication().executeOnPooledThread<Snapshot> { service.refreshSync() }.get()
        val repoOverviews = snapshot.repos.single()
        assertEquals(8, repoOverviews.contents.records.size)
        val model = repoOverviews.model
        val reviewCommit = model.commits.single { it.commit.startsWith("4e55504b") }
        val review = reviewCommit.reviews.single()
        assertEquals("fd1eaedb", review.id)
        assertTrue("gg stored this review as prose", review.content is dev.ggnotes.store.ReviewContent.Prose)
        assertNotNull("the reviewed commit is in the checkout", reviewCommit.subject)

        val notesCommit = model.commits.single { it.commit.startsWith("b434be1") }
        assertEquals(listOf(
            "src/main/kotlin/dev/gitfocus/worktrees/RowTextFit.kt",
            "src/main/kotlin/dev/gitfocus/worktrees/WorktreesPanel.kt",
            "src/test/kotlin/dev/gitfocus/worktrees/RowTextFitTest.kt",
        ), notesCommit.files.map { it.path })
        assertEquals(6, notesCommit.files.sumOf { it.threads.size })

        // the prose review opens as markdown, never as "cannot render"
        service.openReview(review, repo)
        waitFor("review opened") { service.open?.overview?.id == "fd1eaedb" }
        val reviewDoc = service.open!!.document!!
        assertTrue(reviewDoc.overview.length > 1000)
        assertFalse(reviewDoc.overview.contains("cannot be rendered"))

        // the two notes on RowTextFit.kt verify against the file at the commit and open it there
        val fit = notesCommit.files.single { it.path.endsWith("RowTextFit.kt") }
        assertEquals(listOf(63, 67), fit.threads.map { it.root.first })
        service.openNotes(fit.threads, fit.path, notesCommit.commit, null, repo)
        waitFor("notes opened") { service.open?.overview?.viewKind?.startsWith("notes:") == true }
        val notesDoc = service.open!!.document!!
        assertFalse("every fingerprint verifies: no stale mark", notesDoc.overview.contains("not found"))
        assertFalse(notesDoc.overview.contains("no fingerprint"))
        assertEquals(listOf(63 to 63, 67 to 67), notesDoc.files.single().annotations.map { it.first to it.last })
        service.goToAnchor(1)
        waitFor("RowTextFit.kt opened") { FileEditorManager.getInstance(project).selectedFiles.singleOrNull()?.name == "RowTextFit.kt" }
        val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
        assertEquals(editor.document.getLineStartOffset(62), editor.selectionModel.selectionStart)
        waitFor("two remark boxes") { editor.inlayModel.getBlockElementsInRange(0, editor.document.textLength).size == 2 }
    }

    fun testFixtureStoreUnderOverriddenStateHomeShowsExactlyTheTwoFixtureOverviews() {
        frozenCheckout(Paths.get("examples/commits.toml"), Paths.get("examples/previews.toml"))
        mapRepository()
        val service = OverviewService.getInstance(project)
        val snapshot = ApplicationManager.getApplication().executeOnPooledThread<Snapshot> { service.refreshSync() }.get()
        val titles = snapshot.repos.single().contents.overviews.map { it.title }.sorted()
        assertEquals(listOf("How the gate works", "PR 7: the gate, reviewed"), titles)
        // d5efa035 exists in git-focus, so the commit overview sits under a known commit; the PR one does not
        val groups = snapshot.repos.single().model.commits
        assertNotNull(groups.single { it.commit == commit }.subject)
        assertNull(groups.single { it.commit != commit }.subject)
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for $what")
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(50)
        }
    }
}
