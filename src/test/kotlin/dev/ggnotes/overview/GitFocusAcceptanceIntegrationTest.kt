package dev.ggnotes.overview

import com.intellij.dvcs.repo.VcsRepositoryManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
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
import java.nio.file.Paths

/**
 * AGENTS.md §3.2, the acceptance scenario, as far as a test can take it:
 * the real `~/git-focus` checkout and its live gg store. Skips (passes
 * with a log line) on a machine without that checkout or that store, so
 * the suite stays portable. The human half — looking at the tool window,
 * clicking `rowText`, pressing Back — stays a manual gate (`runIde`).
 */
class GitFocusAcceptanceIntegrationTest : HeavyPlatformTestCase() {

    private val log = logger<GitFocusAcceptanceIntegrationTest>()
    private val checkout = File(System.getProperty("user.home"), "git-focus")
    private val commit = "d5efa035df1f3c4c949040bebb924a35d0ea61a0"

    private fun prerequisites(): Boolean {
        if (!File(checkout, ".git").exists()) return skip("no ~/git-focus checkout")
        val common = runCatching {
            val p = ProcessBuilder("git", "rev-parse", "--path-format=absolute", "--git-common-dir").directory(checkout).start()
            p.inputStream.bufferedReader().readText().trim().also { p.waitFor() }
        }.getOrNull() ?: return skip("git rev-parse failed in ~/git-focus")
        val key = StoreLocator.key(common)
        if (key != "4acf7db7161a42e0") return skip("unexpected key $key for $common")
        val store = StoreLocator.defaultBaseDir().resolve(key).resolve("commits.toml")
        if (!Files.isRegularFile(store)) return skip("no live store at $store")
        return true
    }

    private fun skip(why: String): Boolean {
        log.warn("GitFocusAcceptanceIntegrationTest skipped: $why")
        return false
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

    fun testLiveStoreShowsTheBranchFirstRowsOverviewAndRowTextOpensWorktreesList() {
        if (!prerequisites()) return
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

    fun testFixtureStoreUnderOverriddenStateHomeShowsExactlyTheTwoFixtureOverviews() {
        if (!prerequisites()) return
        val stateHome = createTempDirectory().toPath()
        val store = stateHome.resolve("gg").resolve("notes").resolve("4acf7db7161a42e0")
        Files.createDirectories(store)
        Files.copy(Paths.get("examples/commits.toml"), store.resolve("commits.toml"))
        Files.copy(Paths.get("examples/previews.toml"), store.resolve("previews.toml"))
        System.setProperty("ggnotes.env.XDG_STATE_HOME", stateHome.toString())
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
