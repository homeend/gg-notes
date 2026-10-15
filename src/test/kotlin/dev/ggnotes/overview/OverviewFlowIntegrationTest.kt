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
    private lateinit var earlyStateHome: Path

    override fun setUp() {
        // before the project opens: nothing of this test may touch the developer's live state dir
        earlyStateHome = Files.createTempDirectory("ggnotes-state")
        System.setProperty("ggnotes.env.XDG_STATE_HOME", earlyStateHome.toString())
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

        stateHome = earlyStateHome
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
            OverviewService.getInstance(project).close()
            com.intellij.openapi.util.Disposer.dispose(dev.ggnotes.steer.SteerService.getInstance(project))
        } finally {
            try {
                super.tearDown()
            } finally {
                System.clearProperty("ggnotes.env.XDG_STATE_HOME")
                runCatching { earlyStateHome.toFile().deleteRecursively() }
            }
        }
    }

    /** Steer PLUGIN.md step 1: presence is published, a hand-posted navigate opens the file with 3–4 selected and is answered. */
    fun testSteerPresenceAndAHandPostedNavigateOpensTheWorkingFile() {
        val repo = GitRepositoryManager.getInstance(project).getRepositoryForRootQuick(root)!!
        val steer = dev.ggnotes.steer.SteerService.getInstance(project)
        val session = ApplicationManager.getApplication().executeOnPooledThread<List<dev.ggnotes.steer.SteerService.RootSession>> { steer.startSync() }.get().single()
        val top = git("rev-parse", "--show-toplevel").trim()
        val common = git("rev-parse", "--path-format=absolute", "--git-common-dir").trim()
        assertEquals(dev.ggnotes.steer.SteerPaths.inboxDir(stateHome, common, top), session.inbox)
        assertTrue("presence lives under the overridden state dir", session.inbox.startsWith(stateHome))
        val presence = session.inbox.resolve("ide.json")
        assertTrue(Files.isRegularFile(presence))
        val json = com.google.gson.JsonParser.parseString(Files.readString(presence)).asJsonObject
        assertEquals(top, json.get("worktree").asString)
        assertEquals(ProcessHandle.current().pid(), json.get("pid").asLong)

        val ide = session.ideDir
        Files.writeString(ide.resolve(".tmp"), """{"id":"1-1","cmd":"navigate","file":"internal/x/gate.go","line":{"side":"new","no":3,"end":4},"wait":true}""")
        Files.move(ide.resolve(".tmp"), ide.resolve("cmd-1-1.json"))
        val reply = ide.resolve("reply-1-1.json")
        waitFor("reply file") { Files.isRegularFile(reply) }
        assertEquals("""{"id":"1-1","ok":true,"detail":"opened internal/x/gate.go:3-4 at line 3"}""", Files.readString(reply))
        assertFalse("the command file was consumed", Files.exists(ide.resolve("cmd-1-1.json")))
        val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
        assertEquals("gate.go", FileEditorManager.getInstance(project).selectedFiles.single().name)
        assertTrue(FileEditorManager.getInstance(project).selectedFiles.single().isInLocalFileSystem)
        assertEquals(editor.document.getLineStartOffset(2), editor.selectionModel.selectionStart)
        assertEquals(editor.document.getLineEndOffset(3), editor.selectionModel.selectionEnd)
        assertEquals(2, editor.caretModel.logicalPosition.line)
        assertEquals(listOf("f1"), session.memory.openFiles().map { it.id })

        // a command without wait is applied and never answered; an unknown one is refused
        Files.writeString(ide.resolve("cmd-1-2.json"), """{"id":"1-2","cmd":"navigate","file":"internal/y/call.go","line":{"side":"new","no":9}}""")
        Files.writeString(ide.resolve("cmd-1-3.json"), """{"id":"1-3","cmd":"dance","wait":true}""")
        waitFor("unknown command refused") { Files.isRegularFile(ide.resolve("reply-1-3.json")) }
        assertEquals("""{"id":"1-3","ok":false,"error":"unknown command dance"}""", Files.readString(ide.resolve("reply-1-3.json")))
        waitFor("call.go opened past its end") { FileEditorManager.getInstance(project).selectedFiles.singleOrNull()?.name == "call.go" }
        assertFalse(Files.exists(ide.resolve("reply-1-2.json")))
        assertEquals(0, FileEditorManager.getInstance(project).selectedTextEditor!!.caretModel.logicalPosition.line)
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

    /** SPEC-part2 §5: live notes relocate against the working file, an absent side orphans, a working review notices a changed tree. */
    fun testLiveNotesRelocateAndAWorkingReviewSeesTheTreeChange() {
        val top = git("rev-parse", "--show-toplevel").trim()
        val wkey = dev.ggnotes.store.StoreLocator.key(top)
        val store = stateHome.resolve("gg").resolve("notes").resolve(dev.ggnotes.store.StoreLocator.key(git("rev-parse", "--path-format=absolute", "--git-common-dir").trim()))
        Files.createDirectories(store.resolve("worktrees"))
        val returnOpenHash = dev.ggnotes.store.Relocator.contextHash(listOf("return open"))
        Files.writeString(store.resolve("worktrees/$wkey.toml"), """
            format = 3

            [[notes]]
            id = 'bb220001'
            source = 'user'
            author = 'me'
            side = 'new'
            range = [4, 4]
            context_hash = '$returnOpenHash'
            summary = 'Still returns the flag.'
            created = 2026-10-14T11:00:00Z
            updated = 2026-10-14T11:00:00Z

            [notes.address]
            Worktree = '$top'
            Branch = ''
            Commit = ''
            ShelfID = ''
            Path = 'internal/x/gate.go'
            State = 3

            [[notes]]
            id = 'bb220009'
            source = 'user'
            author = 'me'
            side = 'new'
            range = [1, 1]
            context_hash = '${dev.ggnotes.store.Relocator.contextHash(listOf("package y"))}'
            summary = 'On a file that is gone.'
            created = 2026-10-14T11:00:00Z
            updated = 2026-10-14T11:00:00Z

            [notes.address]
            Worktree = '$top'
            Branch = ''
            Commit = ''
            ShelfID = ''
            Path = 'internal/y/call.go'
            State = 3

            [[notes]]
            id = 'bb220002'
            source = 'agent'
            author = 'ada'
            side = 'new'
            range = [0, 0]
            context_hash = ''
            summary = 'Review: working changes'
            rationale = '{"version": 1, "summary": "## Summary\nThe uncommitted change.", "files": [{"path": "internal/x/gate.go", "annotations": [{"newRange": [4, 4], "summary": "Rename it back."}]}]}'
            tags = ['review']
            created = 2026-10-14T11:30:00Z
            updated = 2026-10-14T11:30:00Z

            [notes.address]
            Worktree = '$top'
            Branch = ''
            Commit = ''
            ShelfID = ''
            Path = ''
            State = 3

            [[notes.files]]
            path = 'internal/x/gate.go'
            blob = '5d2a7c1e9b3f4a6d8c0e2f1a3b5c7d9e1f3a5b7c'
        """.trimIndent())
        // the working file moves on: two lines inserted above, and call.go removed
        val gate = File(repoDir, "internal/x/gate.go")
        gate.writeText("// one\n// two\n" + gate.readText())
        File(repoDir, "internal/y/call.go").delete()
        root.refresh(false, true)

        val repo = GitRepositoryManager.getInstance(project).getRepositoryForRootQuick(root)!!
        val service = OverviewService.getInstance(project)
        val snapshot = ApplicationManager.getApplication().executeOnPooledThread<Snapshot> { service.refreshSync() }.get()
        val working = snapshot.repos.single().model.working.single()
        assertEquals(listOf("bb220002"), working.reviews.map { it.id })
        assertEquals(listOf("internal/x/gate.go", "internal/y/call.go"), working.files.map { it.path })

        // the moved note relocates to line 6 and opens the working file there
        val gateFile = working.files.single { it.path == "internal/x/gate.go" }
        service.openNotes(gateFile.threads, gateFile.path, "", working.worktree, repo)
        waitFor("live notes opened") { service.open?.overview?.viewKind?.startsWith("notes:") == true }
        val open = service.open!!
        assertTrue(open.overview.isLive)
        assertEquals(listOf(6 to 6), open.document!!.files.single().annotations.map { it.first to it.last })
        assertTrue(open.document!!.overview, open.document!!.overview.contains("moved from 4"))
        service.goToAnchor(1)
        waitFor("working gate.go opened") { FileEditorManager.getInstance(project).selectedFiles.singleOrNull()?.name == "gate.go" }
        val file = FileEditorManager.getInstance(project).selectedFiles.single()
        assertTrue("a live note opens the working file itself", file.isInLocalFileSystem)
        val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
        assertEquals(editor.document.getLineStartOffset(5), editor.selectionModel.selectionStart)

        // the note on the deleted file is orphaned: listed, not placed
        val gone = working.files.single { it.path == "internal/y/call.go" }
        service.openNotes(gone.threads, gone.path, "", working.worktree, repo)
        waitFor("orphaned note opened") { service.open?.overview?.id == "bb220009" }
        assertEquals(emptyList<dev.ggnotes.store.FileRemarks>(), service.open!!.document!!.files)
        assertTrue(service.open!!.document!!.overview.contains("gone"))

        // the working review's blob no longer matches: the tree has changed
        service.openReview(working.reviews.single(), repo)
        waitFor("working review opened") { service.open?.overview?.id == "bb220002" }
        assertTrue(service.open!!.document!!.overview, service.open!!.document!!.overview.contains("tree has changed"))
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
