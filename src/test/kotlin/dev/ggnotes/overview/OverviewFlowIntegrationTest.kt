package dev.ggnotes.overview

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
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
            // git4idea's index file system is disposed before the framework closes editors, and an
            // editor on a GitIndexVirtualFile then trips an assertion: close everything first
            val fem = FileEditorManager.getInstance(project)
            ApplicationManager.getApplication().invokeAndWait { fem.openFiles.forEach { fem.closeFile(it) } }
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
        assertEquals("""{"id":"1-1","ok":true,"detail":"opened internal/x/gate.go:3-4"}""", Files.readString(reply))
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
        assertEquals("""{"id":"1-3","ok":false,"error":"unknown command \"dance\""}""", Files.readString(ide.resolve("reply-1-3.json")))
        waitFor("call.go opened past its end") { FileEditorManager.getInstance(project).selectedFiles.singleOrNull()?.name == "call.go" }
        assertFalse(Files.exists(ide.resolve("reply-1-2.json")))
        assertEquals(0, FileEditorManager.getInstance(project).selectedTextEditor!!.caretModel.logicalPosition.line)

        // step 2: a commit target opens a read-only revision tab; staged reads the index; past-the-end prose; files; file_focus; highlight
        fun post(id: String, body: String): com.google.gson.JsonObject {
            Files.writeString(ide.resolve(".tmp-$id"), body)
            Files.move(ide.resolve(".tmp-$id"), ide.resolve("cmd-$id.json"))
            waitFor("reply $id") { Files.isRegularFile(ide.resolve("reply-$id.json")) }
            return com.google.gson.JsonParser.parseString(Files.readString(ide.resolve("reply-$id.json"))).asJsonObject
        }
        // step 2-0: a stored document's link (`gg://…@<sha>?overview=<id>` / `?review=<id>`, gg's hint fields)
        // opens the document itself in the tool window, as gg's TUI does; a gone id degrades to the commit
        val storedOverview = post("2-0a", """{"id":"2-0a","cmd":"navigate","commit":"$sha","hint_kind":"overview","hint_id":"7cf0812f","wait":true}""")
        assertEquals(storedOverview.toString(), "opened overview 7cf0812f", storedOverview.get("detail")?.asString)
        assertEquals("7cf0812f", OverviewService.getInstance(project).open?.overview?.id)
        assertEquals("", OverviewService.getInstance(project).open?.overview?.viewKind)
        val storedReview = post("2-0b", """{"id":"2-0b","cmd":"navigate","commit":"$sha","hint_kind":"review","hint_id":"c0ffee01","wait":true}""")
        assertEquals(storedReview.toString(), "opened review c0ffee01", storedReview.get("detail")?.asString)
        assertEquals("review:c0ffee01:", OverviewService.getInstance(project).open?.overview?.viewKind)
        val goneOverview = post("2-0c", """{"id":"2-0c","cmd":"navigate","commit":"$sha","hint_kind":"overview","hint_id":"deadbeef","wait":true}""")
        assertEquals(goneOverview.toString(), "revealed commit ${sha.take(7)}", goneOverview.get("detail")?.asString)
        assertEquals("the shown document stays", "review:c0ffee01:", OverviewService.getInstance(project).open?.overview?.viewKind)

        val atCommit = post("2-1", """{"id":"2-1","cmd":"navigate","file":"internal/x/gate.go","target":{"state":"commit","commit":"$sha"},"line":{"side":"new","no":4},"wait":true}""")
        assertEquals("opened internal/x/gate.go:4", atCommit.get("detail").asString)
        val revisionFile = FileEditorManager.getInstance(project).selectedFiles.single()
        assertFalse("the working file moved on, so this is the revision tab", revisionFile.isInLocalFileSystem)
        assertFalse(FileEditorManager.getInstance(project).selectedTextEditor!!.document.text.contains("edited after the commit"))

        git("add", "internal/x/gate.go")
        val staged = post("2-2", """{"id":"2-2","cmd":"navigate","file":"internal/x/gate.go","target":{"state":"staged"},"line":{"side":"new","no":99},"wait":true}""")
        assertEquals(staged.toString(), "opened internal/x/gate.go:99; clamped to line 6", staged.get("detail")?.asString)
        assertTrue(FileEditorManager.getInstance(project).selectedTextEditor!!.document.text.contains("edited after the commit"))
        assertTrue("the index copy opens in viewer mode", (FileEditorManager.getInstance(project).selectedTextEditor as com.intellij.openapi.editor.ex.EditorEx).isViewer)
        assertTrue(FileEditorManager.getInstance(project).selectedFiles.single() is git4idea.index.vfs.GitIndexVirtualFile)

        val missing = post("2-3", """{"id":"2-3","cmd":"navigate","file":"nope.go","wait":true}""")
        assertEquals("nope.go is not in the working tree", missing.get("error").asString)
        val preview = post("2-4", """{"id":"2-4","cmd":"navigate","file":"internal/x/gate.go","target":{"state":"preview","source":"a","target":"b"},"wait":true}""")
        assertEquals("the IDE cannot show a preview target", preview.get("error").asString)
        val old = post("2-5", """{"id":"2-5","cmd":"navigate","file":"internal/x/gate.go","line":{"side":"old","no":1},"wait":true}""")
        assertEquals("the IDE shows no old side", old.get("error").asString)
        val wrongTree = post("2-6", """{"id":"2-6","cmd":"files","worktree":"/somewhere/else","wait":true}""")
        assertTrue(wrongTree.get("error").asString, wrongTree.get("error").asString.startsWith("the IDE shows ") && wrongTree.get("error").asString.endsWith(", not /somewhere/else"))

        val files = post("2-7", """{"id":"2-7","cmd":"files","wait":true}""")
        val list = files.getAsJsonArray("files").map { it.asJsonObject }
        assertEquals("most recently shown first", "internal/x/gate.go", list[0].get("path").asString)
        assertEquals("shown", list[0].get("state").asString)
        assertTrue(list.drop(1).all { it.get("state").asString == "background" })
        assertEquals(listOf("f1", "f2", "f3", "f4"), list.map { it.get("id").asString }.sorted())
        val commitEntry = list.single { it.has("rev") && it.get("rev").asString == sha }
        assertEquals("commit", commitEntry.get("source").asString)

        val focus = post("2-8", """{"id":"2-8","cmd":"file_focus","file_id":"f1","line":{"side":"new","no":2},"wait":true}""")
        assertEquals("focused internal/x/gate.go at line 2", focus.get("detail").asString)
        assertTrue(FileEditorManager.getInstance(project).selectedFiles.single().isInLocalFileSystem)
        assertEquals(1, FileEditorManager.getInstance(project).selectedTextEditor!!.caretModel.logicalPosition.line)
        assertEquals("no open file f9", post("2-9", """{"id":"2-9","cmd":"file_focus","file_id":"f9","wait":true}""").get("error").asString)

        val hl = post("2-10", """{"id":"2-10","cmd":"highlight","file":"internal/x/gate.go","side":"new","start":3,"end":4,"tone":"warn","wait":true}""")
        assertEquals("marked internal/x/gate.go new:3-4 as warn", hl.get("detail").asString)
        assertEquals(1, session.highlights.size)
        assertTrue(session.highlights.single().isValid)
        assertEquals("cleared every mark", post("2-11", """{"id":"2-11","cmd":"highlight_clear","wait":true}""").get("detail").asString)
        assertEquals(0, session.highlights.size)
        assertEquals("focus is a gg TUI command", post("2-12", """{"id":"2-12","cmd":"focus","panel":"commits","wait":true}""").get("error").asString)

        // a closed file leaves the list
        ApplicationManager.getApplication().invokeAndWait { FileEditorManager.getInstance(project).closeFile(revisionFile) }
        val after = post("2-13", """{"id":"2-13","cmd":"files","wait":true}""").getAsJsonArray("files").map { it.asJsonObject.get("id").asString }
        assertFalse(after.contains(commitEntry.get("id").asString))

        // step 3: the in-memory note and overview verbs are retired (session-docs SPEC §6.3): the refusal names the fix
        assertEquals("note_add is retired: write the file (gg session note add)",
            post("3-1", """{"id":"3-1","cmd":"note_add","file":"internal/x/gate.go","start":4,"end":4,"summary":"x","wait":true}""").get("error").asString)
        assertEquals("overview_add is retired: write the file (gg session overview add)",
            post("3-2", """{"id":"3-2","cmd":"overview_add","title":"Tour","text":"t","wait":true}""").get("error").asString)
        assertEquals("overview_list is retired: write the file (gg session overview add)",
            post("3-3", """{"id":"3-3","cmd":"overview_list","wait":true}""").get("error").asString)
        // steer SPEC revision 2026-10-19: the retired refusal comes before any other check, the worktree guard included
        assertEquals("note_list is retired: write the file (gg session note add)",
            post("3-4", """{"id":"3-4","cmd":"note_list","worktree":"/somewhere/else","wait":true}""").get("error").asString)

        // step 4: session docs — files an agent drops into the worktree's docs directory (SPEC §4); the plugin never creates it
        val docs = session.docsDir
        assertEquals(session.inbox.resolve("docs"), docs)
        assertFalse("a reader creates nothing", Files.exists(docs))
        Files.createDirectories(docs)
        fun drop(name: String, text: String) {
            Files.writeString(docs.resolve(".$name.1234.tmp"), text)
            Files.move(docs.resolve(".$name.1234.tmp"), docs.resolve(name), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        }
        val ovId = "20261018T150000-aaaa"
        val tour = "Start at [the gate](internal/x/gate.go:5), then [the note](note:n-gate); [gone](internal/nope.go:3) is missing."
        drop("n-gate.toml", "path = 'internal/x/gate.go'\nstart = 4\nend = 4\nsummary = 'Returns the flag.'\nrationale = 'Why.'\nauthor = 'ada'\nlines = ['\treturn open']\n")
        drop("$ovId.md", "+++\ntitle = 'Tour'\nauthor = 'claude'\ncreated = 2026-10-18T15:00:00Z\n+++\n$tour\n")
        waitFor("the overview row appears through the poll") { steer.sessionOverviews(root).size == 1 }
        val row = steer.sessionOverviews(root).single()
        assertEquals("Tour", row.entry.doc.title)
        assertEquals("claude", row.entry.doc.author)
        assertEquals(3, row.entry.doc.anchors.size)
        assertEquals(listOf("internal/nope.go:3"), row.unresolved)
        assertFalse(row.shown)

        // the note is a box on the editor of its file, placed from its lines (cold anchoring): line 4 still reads `return open`
        ApplicationManager.getApplication().invokeAndWait {
            FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(repoDir, "internal/x/gate.go"))!!, 0, 0), true)
        }
        val gateEditor = FileEditorManager.getInstance(project).selectedTextEditor!!
        waitFor("one note box drawn at line 4") { gateEditor.inlayModel.getBlockElementsInRange(0, gateEditor.document.textLength).singleOrNull()?.let { gateEditor.document.getLineNumber(it.offset) } == 3 }
        assertEquals(listOf("n-gate" to dev.ggnotes.session.Placement(4, 4, false)), session.marks.placementsOn("internal/x/gate.go").map { it.first.id to it.second })

        // `files`: the file counts its session notes; the overview is listed under its document id
        val withDocs = post("4-1", """{"id":"4-1","cmd":"files","wait":true}""").getAsJsonArray("files").map { it.asJsonObject }
        assertEquals(1, withDocs.single { it.get("id").asString == "f1" }.get("notes").asInt)
        val ovEntry = withDocs.single { it.get("id").asString == ovId }
        assertEquals("overview", ovEntry.get("source").asString)
        assertEquals("Tour", ovEntry.get("title").asString)
        assertEquals("background", ovEntry.get("state").asString)
        // the overview row carries its front matter (steer SPEC revision 2026-10-19): author, created (RFC 3339 UTC), anchors
        assertEquals("claude", ovEntry.get("author").asString)
        assertEquals("2026-10-18T15:00:00Z", ovEntry.get("created").asString)
        assertEquals(3, ovEntry.get("anchors").asInt)

        // file_focus by document id shows it in the tool window, with gg's own lead; the note anchor points at the note's lines, the unresolved one is text
        assertEquals("focused overview $ovId \"Tour\"", post("4-2", """{"id":"4-2","cmd":"file_focus","file_id":"$ovId","wait":true}""").get("detail").asString)
        val overviewService = OverviewService.getInstance(project)
        val viewKind = steer.viewerOverview(session, row.entry).viewKind
        waitFor("tour shown in the tool window") { overviewService.open?.overview?.viewKind == viewKind }
        assertTrue(steer.sessionOverviews(root).single().shown)
        val shownDoc = overviewService.open!!
        assertEquals("Tour", shownDoc.overview.title)
        assertEquals(2, shownDoc.anchors.size)
        assertEquals(dev.ggnotes.store.LinkTarget.Anchor("internal/x/gate.go", 4, 4), shownDoc.anchors[1].target)
        assertEquals("the note", shownDoc.anchors[1].label)
        assertEquals("no open file nope", post("4-3", """{"id":"4-3","cmd":"file_focus","file_id":"nope","wait":true}""").get("error").asString)
        // `gg session overview add` posts the focus right after its rename, ahead of any watch: an id not listed yet
        // makes the IDE re-read the directory once before refusing (session-docs rulings §6.3)
        drop("20261018T150100-bbbb.md", "+++\ntitle = 'Fresh'\n+++\nnew [a](internal/x/gate.go:1)\n")
        assertEquals("focused overview 20261018T150100-bbbb \"Fresh\"",
            post("4-3b", """{"id":"4-3b","cmd":"file_focus","file_id":"20261018T150100-bbbb","wait":true}""").get("detail").asString)
        waitFor("the fresh overview is shown") { overviewService.open?.overview?.id == "20261018T150100-bbbb" }
        assertEquals(2, steer.sessionOverviews(root).size)
        Files.delete(docs.resolve("20261018T150100-bbbb.md"))
        steer.reloadDocs()
        waitFor("back to the tour's row alone") { steer.sessionOverviews(root).size == 1 }
        assertEquals("focused overview $ovId \"Tour\"", post("4-3c", """{"id":"4-3c","cmd":"file_focus","file_id":"$ovId","wait":true}""").get("detail").asString)
        waitFor("tour shown again") { overviewService.open?.overview?.viewKind == viewKind }

        // the user inserts two lines at the top: the note follows its text to line 6 and the box moves
        ApplicationManager.getApplication().invokeAndWait {
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) { gateEditor.document.insertString(0, "// one\n// two\n") }
        }
        waitFor("note re-anchored") { session.marks.placementsOn("internal/x/gate.go").single().second.start == 6 }
        waitFor("box redrawn at line 6") { gateEditor.inlayModel.getBlockElementsInRange(0, gateEditor.document.textLength).singleOrNull()?.let { gateEditor.document.getLineNumber(it.offset) } == 5 }
        ApplicationManager.getApplication().invokeAndWait { gateEditor.caretModel.moveToOffset(0) }
        assertEquals("opened internal/x/gate.go:6", post("4-4", """{"id":"4-4","cmd":"navigate","step":"next_note","wait":true}""").get("detail").asString)
        assertEquals("no next note in this diff", post("4-5", """{"id":"4-5","cmd":"navigate","step":"next_note","wait":true}""").get("error").asString)

        // the noted line is edited: the note is outdated and its box says so
        ApplicationManager.getApplication().invokeAndWait {
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
                val l = gateEditor.document.getLineStartOffset(5)
                gateEditor.document.replaceString(l, gateEditor.document.getLineEndOffset(5), "\treturn !open")
            }
        }
        waitFor("note outdated") { session.marks.placementsOn("internal/x/gate.go").single().second.outdated }

        // a replaced overview file (new size) is REPLACED in place: the open document keeps its anchor, the row stays one
        ApplicationManager.getApplication().invokeAndWait { overviewService.goToAnchor(2, openFile = false) }
        drop("$ovId.md", "+++\ntitle = 'Tour'\nauthor = 'claude'\ncreated = 2026-10-18T15:00:00Z\n+++\n$tour\n\nAnd [more](internal/x/gate.go:1).\n")
        steer.reloadDocs()
        waitFor("re-rendered with three anchors") { overviewService.open?.anchors?.size == 3 }
        assertEquals(2, overviewService.open!!.currentAnchor)
        assertEquals(1, steer.sessionOverviews(root).size)
        // a replaced note whose place did not change keeps its placement (outdated at 6, the text is what moved on)
        drop("n-gate.toml", "path = 'internal/x/gate.go'\nstart = 4\nend = 4\nsummary = 'Returns the flag!'\nauthor = 'ada'\nlines = ['\treturn open']\n")
        steer.reloadDocs()
        waitFor("summary replaced") { session.marks.placementsOn("internal/x/gate.go").singleOrNull()?.first?.summary == "Returns the flag!" }
        assertEquals(dev.ggnotes.session.Placement(6, 6, true), session.marks.placementsOn("internal/x/gate.go").single().second)

        // gone: the overview's file is removed → the row goes and the document closes
        Files.delete(docs.resolve("$ovId.md"))
        steer.reloadDocs()
        // the index empties inside the reconcile; the close that follows it is posted to the EDT, so wait for it too
        waitFor("row gone") { steer.sessionOverviews(root).isEmpty() }
        waitFor("the removed overview's document closes") { overviewService.open == null }

        // Delete (the tree's and the editor's menu items) unlinks the file; nothing else is ever deleted
        drop("n-two.toml", "path = 'internal/x/gate.go'\nstart = 1\nend = 1\nsummary = 'two'\nlines = ['// one']\n")
        drop("n-other.toml", "path = 'internal/y/call.go'\nstart = 1\nend = 1\nsummary = 'other'\nlines = ['package y']\n")
        drop("$ovId.md", "+++\ntitle = 'Again'\n+++\ntext\n")
        steer.reloadDocs()
        waitFor("two notes on gate.go") { session.marks.placementsOn("internal/x/gate.go").size == 2 }
        assertTrue(steer.deleteNote(session, "n-gate"))
        assertFalse(Files.exists(docs.resolve("n-gate.toml")))
        assertFalse(steer.deleteNote(session, "n-gate"))
        waitFor("one box left") { gateEditor.inlayModel.getBlockElementsInRange(0, gateEditor.document.textLength).size == 1 }
        assertEquals(1, steer.deleteNotesOn(session, "internal/x/gate.go"))
        assertTrue("another file's note stays", Files.exists(docs.resolve("n-other.toml")))
        waitFor("no boxes") { gateEditor.inlayModel.getBlockElementsInRange(0, gateEditor.document.textLength).isEmpty() }
        waitFor("the row is back") { steer.sessionOverviews(root).size == 1 }
        assertTrue(steer.deleteOverview(session, ovId))
        assertFalse(Files.exists(docs.resolve("$ovId.md")))
        waitFor("row gone again") { steer.sessionOverviews(root).isEmpty() }
        assertEquals("the other note file is the only one left", listOf("n-other.toml"), Files.list(docs).use { it.map { f -> f.fileName.toString() }.toList() })
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

    /** gg's replay corpus (docs/gg-steer/steer-cases.json): every case that is not tui_only, through the real handler. */
    fun testSteerReplayCorpus() {
        val corpus = com.google.gson.JsonParser.parseString(Files.readString(Paths.get("docs/gg-steer/steer-cases.json"))).asJsonObject
        for ((name, content) in corpus.getAsJsonObject("files").entrySet()) write(name, content.asString)
        root.refresh(false, true)
        val steer = dev.ggnotes.steer.SteerService.getInstance(project)
        val session = ApplicationManager.getApplication().executeOnPooledThread<List<dev.ggnotes.steer.SteerService.RootSession>> { steer.startSync() }.get().single()
        val ide = session.ideDir
        var n = 0
        val failures = ArrayList<String>()
        for (c in corpus.getAsJsonArray("cases")) {
            val case = c.asJsonObject
            if (case.get("tui_only")?.asBoolean == true) continue
            val id = "9-${++n}"
            val command = case.getAsJsonObject("command").deepCopy().apply { addProperty("id", id); addProperty("wait", true) }
            Files.writeString(ide.resolve(".tmp-$id"), command.toString())
            Files.move(ide.resolve(".tmp-$id"), ide.resolve("cmd-$id.json"))
            waitFor("reply $id (${case.get("name").asString})") { Files.isRegularFile(ide.resolve("reply-$id.json")) }
            val reply = com.google.gson.JsonParser.parseString(Files.readString(ide.resolve("reply-$id.json"))).asJsonObject
            val expect = case.getAsJsonObject("reply")
            val caseName = case.get("name").asString
            if (reply.get("ok").asBoolean != expect.get("ok").asBoolean) failures += "$caseName: ok=${reply.get("ok")} want ${expect.get("ok")} (reply $reply)"
            expect.get("detail")?.asString?.let { want -> if (reply.get("detail")?.asString != want) failures += "$caseName: detail=${reply.get("detail")} want \"$want\"" }
            expect.get("error")?.asString?.let { want -> if (reply.get("error")?.asString != want) failures += "$caseName: error=${reply.get("error")} want \"$want\"" }
            expect.get("error_prefix")?.asString?.let { want -> if (reply.get("error")?.asString?.startsWith(want) != true) failures += "$caseName: error=${reply.get("error")} want prefix \"$want\"" }
        }
        assertEquals("corpus mismatches:\n" + failures.joinToString("\n"), emptyList<String>(), failures)
        assertEquals("every non-TUI case of the refreshed corpus ran (two retired ones included)", 16, n)
    }

    /**
     * Opening a revision file at a line makes the platform ask the file for its length on the
     * EDT (OpenFileDescriptor → lazy range marker → getLength). git4idea refuses to load content
     * there, so the bytes must already sit in the virtual file when it leaves the pooled thread.
     */
    fun testRevisionFileCarriesItsBytesBeforeTheEdtSeesIt() {
        val vf = com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService()
            .submit<VirtualFile> { RevisionFiles.atCommit(project, root, sha, "internal/x/gate.go") }
            .get(30, java.util.concurrent.TimeUnit.SECONDS)
        // git4idea's own cache is the witness: a load on the EDT would fill it again
        val cache = ProjectLevelVcsManager.getInstance(project).contentRevisionCache
        cache.clearAll()
        val filePath = com.intellij.vcsUtil.VcsUtil.getFilePath(root.path + "/internal/x/gate.go", false)
        fun loadedThroughGit() = cache.getBytes(filePath, git4idea.GitRevisionNumber(sha), GitVcs.getKey(),
            com.intellij.openapi.vcs.impl.ContentRevisionCache.UniqueType.REPOSITORY_CONTENT) != null
        assertFalse(loadedThroughGit())
        assertTrue(ApplicationManager.getApplication().isDispatchThread)
        val committed = "package x\n\nfunc gate(open bool) bool {\n\treturn open\n}\n"
        assertEquals(committed.length.toLong(), vf.length)
        assertEquals(committed, String(vf.contentsToByteArray()))
        assertFalse("the EDT asked git for the content", loadedThroughGit())
    }

    /**
     * "Remove from gg store" hands the deletion to gg itself (`gg batch` with `overview rm` /
     * `note rm` lines, the store's XDG_STATE_HOME override passed through): the overview and the
     * reply under it are gone from the re-read store, the review beside it stays, and the open
     * document that showed the overview closes.
     */
    fun testRemoveFromStoreGoesThroughGg() {
        val repo = GitRepositoryManager.getInstance(project).getRepositoryForRootQuick(root)!!
        val service = OverviewService.getInstance(project)
        val before = ApplicationManager.getApplication().executeOnPooledThread<Snapshot> { service.refreshSync() }.get()
        val commit = before.repos.single().model.commits.single { it.commit == sha }
        val overview = commit.overviews.single { it.id == "7cf0812f" }
        assertEquals(listOf("c3d4e5f6"), commit.overviewReplies.getValue("7cf0812f").map { it.id })
        service.open(overview, repo)
        assertEquals("7cf0812f", service.open?.overview?.id)

        // the completion closes the document on the EDT, which is this thread: wait while dispatching
        val future = dev.ggnotes.overview.StoreRemoval.perform(project, repo, dev.ggnotes.store.RemovalPlan.forOverview(overview))
        waitFor("gg batch to finish") { future.isDone }
        val result = future.get()
        assertTrue(result.stdout + result.stderr, result.ok)
        assertTrue(result.stdout, result.stdout.contains("#1 ok overview rm 7cf0812f"))
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        val after = service.snapshot.repos.single().model.commits.single { it.commit == sha }
        assertEquals(emptyList<String>(), after.overviews.map { it.id })
        assertEquals(listOf("c0ffee01"), after.reviews.map { it.id })
        val ids = service.snapshot.repos.single().contents.records.map { it.id }
        assertFalse("the reply went with its root", "c3d4e5f6" in ids)
        assertTrue("other records stay", "aa110001" in ids)
        assertNull("the document that showed the removed overview is closed", service.open)
    }

    /**
     * Session overviews are the files of the worktree's docs directory: the steer service lists
     * them per git root newest first with their shown state and tells the panel on every change;
     * a file that does not parse is no row; Delete unlinks the file and the rest follows.
     */
    fun testSessionDocsRowsFollowTheDirectory() {
        val steer = dev.ggnotes.steer.SteerService.getInstance(project)
        val session = ApplicationManager.getApplication().executeOnPooledThread<List<dev.ggnotes.steer.SteerService.RootSession>> { steer.startSync() }.get().single()
        var changes = 0
        steer.addSessionListener(testRootDisposable) { changes++ }
        assertEquals(emptyList<String>(), steer.sessionOverviews(root).map { it.entry.doc.id })
        val docs = session.docsDir
        Files.createDirectories(docs)
        Files.writeString(docs.resolve("old.md"), "+++\ntitle = 'Old'\ncreated = 2026-10-18T10:00:00Z\n+++\n- [gate](internal/x/gate.go:3)\n")
        Files.writeString(docs.resolve("new.md"), "+++\ntitle = 'New'\ncreated = 2026-10-18T12:00:00Z\n+++\nnothing\n")
        Files.writeString(docs.resolve("bad.md"), "no front matter\n")
        Files.writeString(docs.resolve(".new.ab12.tmp"), "+++\ntitle = 'Half written'\n+++\nx\n")
        steer.reloadDocs()
        waitFor("rows") { steer.sessionOverviews(root).size == 2 }
        val rows = steer.sessionOverviews(root)
        assertEquals(listOf("new" to "New", "old" to "Old"), rows.map { it.entry.doc.id to it.entry.doc.title })
        assertEquals(listOf(false, false), rows.map { it.shown })
        assertTrue("the panel was told", changes >= 1)

        steer.showOverview(session, rows[1].entry, keepAnchor = false)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals(listOf(false, true), steer.sessionOverviews(root).map { it.shown })
        assertEquals(steer.viewerOverview(session, rows[1].entry).viewKind, OverviewService.getInstance(project).open?.overview?.viewKind)
        assertEquals(1, OverviewService.getInstance(project).open?.anchors?.size)

        val before = changes
        assertTrue(steer.deleteOverview(session, "old"))
        waitFor("row gone") { steer.sessionOverviews(root).map { it.entry.doc.id } == listOf("new") }
        waitFor("the panel was told of the drop") { changes > before }
        waitFor("the deleted overview's document closes") { OverviewService.getInstance(project).open == null }
        assertFalse(steer.deleteOverview(session, "old"))
        assertTrue("the half-written file is nobody's to touch", Files.exists(docs.resolve(".new.ab12.tmp")))
        assertTrue(Files.exists(docs.resolve("bad.md")))
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for $what")
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(50)
        }
    }

    /** The toolbar's "Document Only" toggle: the service flips its flag and tells the panel to hide or show the list. */
    fun testDocumentOnlyToggleHidesAndShowsTheList() {
        val service = OverviewService.getInstance(project)
        val calls = ArrayList<Boolean>()
        val previous = service.ui
        service.ui = object : OverviewUi {
            override fun showOverview(open: OpenOverview?) = Unit
            override fun showAnchor(n: Int) = Unit
            override fun showList(visible: Boolean) { calls += visible }
        }
        try {
            assertFalse(service.listHidden)
            val action = DocumentOnlyAction()
            val context = com.intellij.openapi.actionSystem.impl.SimpleDataContext.getProjectContext(project)
            val press = com.intellij.testFramework.TestActionEvent.createTestEvent(action, context)
            action.actionPerformed(press)
            assertTrue("hidden after the first press", service.listHidden)
            val shown = com.intellij.testFramework.TestActionEvent.createTestEvent(action, context)
            action.update(shown)
            assertTrue("the button shows as pressed", com.intellij.openapi.actionSystem.Toggleable.isSelected(shown.presentation))
            assertEquals("Show List", shown.presentation.text)
            action.actionPerformed(press)
            assertFalse("the list is back after the second press", service.listHidden)
            assertEquals(listOf(false, true), calls)
        } finally {
            service.ui = previous
        }
    }

    /**
     * Close leaves the window ready for the next click: no row stays selected (a selected row
     * fires no selection change, so it could not be reopened), the document half leaves the
     * splitter so the list takes the window, and Document Only is disabled with nothing open.
     */
    fun testCloseClearsTheSelectionHidesTheDocumentHalfAndDisablesDocumentOnly() {
        val repo = GitRepositoryManager.getInstance(project).getRepositoryForRootQuick(root)!!
        val service = OverviewService.getInstance(project)
        val previous = service.ui
        val panel = OverviewToolWindowPanel(project) // registers itself as the service's ui
        try {
            val snapshot = ApplicationManager.getApplication().executeOnPooledThread<Snapshot> { service.refreshSync() }.get()
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            val overview = snapshot.repos.single().model.commits.single { it.commit == sha }.overviews.single { it.id == "7cf0812f" }
            assertNull("nothing open: no document half", panel.selectedRow)
            assertFalse(panel.documentShown)

            val action = DocumentOnlyAction()
            val context = com.intellij.openapi.actionSystem.impl.SimpleDataContext.getProjectContext(project)
            val idle = com.intellij.testFramework.TestActionEvent.createTestEvent(action, context)
            action.update(idle)
            assertFalse("Document Only is disabled with nothing open", idle.presentation.isEnabled)

            service.open(overview, repo)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            assertNotNull("the opened overview's row is selected", panel.selectedRow)
            assertTrue(panel.documentShown)
            val busy = com.intellij.testFramework.TestActionEvent.createTestEvent(action, context)
            action.update(busy)
            assertTrue(busy.presentation.isEnabled)

            service.setListHidden(true)
            service.close()
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            assertNull("Close clears the selection", panel.selectedRow)
            assertFalse("Close takes the document half away", panel.documentShown)
            assertFalse("Close brings a hidden list back", service.listHidden)

            // the same row can be clicked again: selecting it opens it
            service.open(overview, repo)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            assertEquals("7cf0812f", service.open?.overview?.id)
            assertTrue(panel.documentShown)
        } finally {
            service.close()
            com.intellij.openapi.util.Disposer.dispose(panel)
            service.ui = previous
        }
    }

    /**
     * Two gestures on the tree: a double-click on a row opens it and gives the document the whole
     * window (the Document Only state), and the Delete key is the row's Delete — the same
     * confirm-then-delete the context menu offers.
     */
    fun testDoubleClickFillsTheWindowAndDeleteKeyIsBoundToTheRowsDelete() {
        val repo = GitRepositoryManager.getInstance(project).getRepositoryForRootQuick(root)!!
        val service = OverviewService.getInstance(project)
        val previous = service.ui
        val panel = OverviewToolWindowPanel(project)
        try {
            val snapshot = ApplicationManager.getApplication().executeOnPooledThread<Snapshot> { service.refreshSync() }.get()
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            val overview = snapshot.repos.single().model.commits.single { it.commit == sha }.overviews.single { it.id == "7cf0812f" }
            // nothing selected: a double-click on empty space changes nothing
            panel.openSelectedFullArea()
            assertFalse(service.listHidden)
            assertNull(service.open)
            // the first click of a double-click selects (and so opens) the row; the second fills the window with it
            service.open(overview, repo)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            assertNotNull(panel.selectedRow)
            panel.openSelectedFullArea()
            assertTrue("the list is hidden, the document takes the window", service.listHidden)
            assertEquals("7cf0812f", service.open?.overview?.id)
            assertTrue(panel.documentShown)
            // Close brings the list back, as before
            service.close()
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            assertFalse(service.listHidden)

            // the Delete key on the tree runs the row's Delete action
            val bound = com.intellij.openapi.actionSystem.ex.ActionUtil.getActions(panel.treeComponent)
            val deleteKey = bound.filter { a -> a.shortcutSet.shortcuts.any { it is com.intellij.openapi.actionSystem.KeyboardShortcut && it.firstKeyStroke.keyCode == java.awt.event.KeyEvent.VK_DELETE } }
            assertEquals("one action answers the Delete key", 1, deleteKey.size)
            assertEquals("Delete", deleteKey.single().templatePresentation.text)
        } finally {
            service.setListHidden(false)
            service.close()
            com.intellij.openapi.util.Disposer.dispose(panel)
            service.ui = previous
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
