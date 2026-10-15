package dev.ggnotes.overview

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsDirectoryMapping
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.dvcs.repo.VcsRepositoryManager
import dev.ggnotes.store.DocumentResult
import git4idea.GitVcs
import git4idea.repo.GitRepositoryManager
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * AGENTS.md §3.2 in miniature, against a real git repository: the fixture
 * overview rewritten onto a commit of a temp repo, the store under an
 * overridden state dir. Exercises store resolution through git4idea, the
 * tool-window model, anchor resolution, opening a file AT THE COMMIT with
 * the range selected, and the remark overlay. Needs `git` on PATH.
 */
class OverviewFlowIntegrationTest : HeavyPlatformTestCase() {

    private lateinit var repoDir: File
    private lateinit var root: VirtualFile
    private lateinit var sha: String
    private lateinit var stateHome: Path

    override fun setUp() {
        super.setUp()
        repoDir = createTempDirectory()
        git("init", "-q", "-b", "main")
        git("config", "user.email", "t@example.com")
        git("config", "user.name", "t")
        write("internal/x/gate.go", "package x\n\nfunc gate(open bool) bool {\n\treturn open\n}\n")
        write("internal/y/call.go", "package y\n")
        git("add", "-A")
        git("commit", "-q", "-m", "the gate")
        sha = git("rev-parse", "HEAD").trim()
        // the working file moves on: the anchor must still open the committed text
        File(repoDir, "internal/x/gate.go").appendText("// edited after the commit\n")

        stateHome = createTempDirectory().toPath()
        System.setProperty("ggnotes.env.XDG_STATE_HOME", stateHome.toString())
        val common = git("rev-parse", "--path-format=absolute", "--git-common-dir").trim()
        val store = stateHome.resolve("gg").resolve("notes").resolve(dev.ggnotes.store.StoreLocator.key(common))
        Files.createDirectories(store)
        val fixture = Files.readString(Paths.get("examples/commits.toml"))
        Files.writeString(store.resolve("commits.toml"), fixture.replace("d5efa035df1f3c4c949040bebb924a35d0ea61a0", sha))

        root = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(repoDir)!!
        root.refresh(false, true)
        ProjectLevelVcsManager.getInstance(project).directoryMappings = listOf(VcsDirectoryMapping(root.path, GitVcs.NAME))
        VcsRepositoryManager.getInstance(project).waitForAsyncTaskCompletion()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    override fun tearDown() {
        try {
            System.clearProperty("ggnotes.env.XDG_STATE_HOME")
            OverviewService.getInstance(project).close()
        } finally {
            super.tearDown()
        }
    }

    fun testOverviewIsListedOpenedAndItsAnchorOpensTheCommittedFile() {
        val repo = GitRepositoryManager.getInstance(project).getRepositoryForRootQuick(root)
        assertNotNull("git4idea must know the temp repository", repo)
        val service = OverviewService.getInstance(project)

        // 1. the tool window model: one group (HEAD), one overview, the commit's subject
        val snapshot = ApplicationManager.getApplication().executeOnPooledThread<Snapshot> { service.refreshSync() }.get()
        val repoOverviews = snapshot.repos.single()
        assertNull(repoOverviews.error)
        assertEquals(emptyList<String>(), repoOverviews.contents.problems)
        val group = repoOverviews.model.commits.single { it.commit == sha }
        assertTrue(group.isHead)
        assertEquals("the gate", group.subject)
        val overview = group.overviews.single()
        assertEquals("How the gate works", overview.title)
        assertTrue(overview.document is DocumentResult.Ok)

        // 2. opening it renders the document and resolves the anchors against the commit
        service.open(overview, repo!!)
        val open = service.open!!
        assertEquals(4, open.anchors.size)
        waitFor("anchors resolved") { open.resolved }
        assertEquals("config.toml is not at the commit", setOf(4), open.missing)

        // 3. the first anchor opens gate.go AT THE COMMIT, lines 3–4 selected, with its remark box
        service.goToAnchor(1)
        waitFor("gate.go opened") {
            FileEditorManager.getInstance(project).selectedTextEditor?.document?.text?.contains("func gate") == true
        }
        val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
        val file = FileEditorManager.getInstance(project).selectedFiles.single()
        assertEquals("gate.go", file.name)
        assertFalse("the working file moved on, so the committed revision must be shown", file.isInLocalFileSystem)
        assertFalse(editor.document.text.contains("edited after the commit"))
        val doc = editor.document
        assertEquals(doc.getLineStartOffset(2), editor.selectionModel.selectionStart)
        assertEquals(doc.getLineEndOffset(3), editor.selectionModel.selectionEnd)
        assertEquals(1, open.currentAnchor)
        waitFor("remark inlay") { editor.inlayModel.getBlockElementsInRange(0, doc.textLength).isNotEmpty() }
        assertEquals(1, editor.inlayModel.getBlockElementsInRange(0, doc.textLength).size)

        // 4. the second anchor (gate.go:2) keeps the same file; the third opens call.go, identical to the working tree
        service.nextAnchor()
        waitFor("anchor 2") { open.currentAnchor == 2 }
        service.nextAnchor()
        waitFor("call.go opened") { FileEditorManager.getInstance(project).selectedFiles.singleOrNull()?.name == "call.go" }
        assertTrue("identical to the working tree: the real file opens", FileEditorManager.getInstance(project).selectedFiles.single().isInLocalFileSystem)

        // 5. closing the overview removes its overlays
        service.close()
        assertTrue(editor.inlayModel.getBlockElementsInRange(0, doc.textLength).isEmpty())
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for $what")
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(50)
        }
    }

    private fun write(rel: String, text: String) {
        val f = File(repoDir, rel)
        f.parentFile.mkdirs()
        f.writeText(text)
    }

    private fun git(vararg args: String): String {
        val p = ProcessBuilder(listOf("git") + args).directory(repoDir).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $out" }
        return out
    }
}
