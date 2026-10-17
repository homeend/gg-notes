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

        // step 3: temporary notes on the editor's current text, drawn as boxes, re-anchored on edits
        val added = post("3-1", """{"id":"3-1","cmd":"note_add","file":"internal/x/gate.go","start":4,"end":4,"summary":"Returns the flag.","rationale":"Why.","author":"ada","wait":true}""")
        assertEquals(added.toString(), "noted internal/x/gate.go:4 as t1", added.get("detail")?.asString)
        val noteWire = added.getAsJsonArray("notes")[0].asJsonObject
        assertEquals("t1", noteWire.get("id").asString)
        assertEquals("f1", noteWire.get("file_id").asString)
        assertEquals("ada", noteWire.get("author").asString)
        assertEquals("a line number is 1-based", post("3-2", """{"id":"3-2","cmd":"note_add","file":"internal/x/gate.go","start":0,"end":0,"summary":"x","wait":true}""").get("error").asString)
        assertEquals("a note needs a summary", post("3-3", """{"id":"3-3","cmd":"note_add","file":"internal/x/gate.go","start":1,"end":1,"summary":" ","wait":true}""").get("error").asString)
        val shown = post("3-4", """{"id":"3-4","cmd":"note_show","note_id":"t1","wait":true}""").getAsJsonArray("notes")[0].asJsonObject
        assertEquals(listOf("\treturn open"), shown.getAsJsonArray("text").map { it.asString })
        ApplicationManager.getApplication().invokeAndWait {
            FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(repoDir, "internal/x/gate.go"))!!, 0, 0), true)
        }
        val gateEditor = FileEditorManager.getInstance(project).selectedTextEditor!!
        waitFor("one note box drawn") { gateEditor.inlayModel.getBlockElementsInRange(0, gateEditor.document.textLength).size == 1 }
        assertEquals(1, post("3-5", """{"id":"3-5","cmd":"files","wait":true}""").getAsJsonArray("files").map { it.asJsonObject }.single { it.get("id").asString == "f1" }.get("notes").asInt)

        // the user inserts two lines at the top: the note follows its text to line 6 and the box moves
        ApplicationManager.getApplication().invokeAndWait {
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) { gateEditor.document.insertString(0, "// one\n// two\n") }
        }
        waitFor("note re-anchored") { session.memory.findNote("t1")?.start == 6 }
        val listed = post("3-6", """{"id":"3-6","cmd":"note_list","file":"internal/x/gate.go","wait":true}""").getAsJsonArray("notes")[0].asJsonObject
        assertEquals(6, listed.get("start").asInt)
        assertFalse(listed.has("outdated"))
        waitFor("box redrawn at line 6") { gateEditor.inlayModel.getBlockElementsInRange(0, gateEditor.document.textLength).singleOrNull()?.let { gateEditor.document.getLineNumber(it.offset) } == 5 }

        // the noted line is edited: the note is outdated and says so
        ApplicationManager.getApplication().invokeAndWait {
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
                val l = gateEditor.document.getLineStartOffset(5)
                gateEditor.document.replaceString(l, gateEditor.document.getLineEndOffset(5), "\treturn !open")
            }
        }
        waitFor("note outdated") { session.memory.findNote("t1")?.outdated == true }
        assertTrue(post("3-7", """{"id":"3-7","cmd":"note_show","note_id":"t1","wait":true}""").getAsJsonArray("notes")[0].asJsonObject.get("outdated").asBoolean)

        // stepping and removal
        ApplicationManager.getApplication().invokeAndWait { gateEditor.caretModel.moveToOffset(0) }
        assertEquals("opened internal/x/gate.go:6", post("3-8", """{"id":"3-8","cmd":"navigate","step":"next_note","wait":true}""").get("detail").asString)
        assertEquals("no next note in this diff", post("3-9", """{"id":"3-9","cmd":"navigate","step":"next_note","wait":true}""").get("error").asString)
        assertEquals("removed t1", post("3-10", """{"id":"3-10","cmd":"note_rm","note_id":"t1","wait":true}""").get("detail").asString)
        assertEquals("no note t1", post("3-11", """{"id":"3-11","cmd":"note_rm","note_id":"t1","wait":true}""").get("error").asString)
        assertEquals("no notes", post("3-12", """{"id":"3-12","cmd":"note_list","wait":true}""").get("detail").asString)
        waitFor("box removed") { gateEditor.inlayModel.getBlockElementsInRange(0, gateEditor.document.textLength).isEmpty() }

        // step 4: in-memory overviews in the overview tool window, with note anchors and unresolved destinations
        val noted = post("4-0", """{"id":"4-0","cmd":"note_add","file":"internal/x/gate.go","start":5,"end":5,"summary":"the gate","wait":true}""")
        val noteId = noted.getAsJsonArray("notes")[0].asJsonObject.get("id").asString
        val tour = "# Tour\n\nStart at [the gate](internal/x/gate.go:5), then [the note](note:$noteId); [gone](internal/nope.go:3) is missing."
        val addedOv = post("4-1", com.google.gson.JsonObject().apply { addProperty("id", "4-1"); addProperty("cmd", "overview_add"); addProperty("title", "Tour"); addProperty("text", tour); addProperty("wait", true) }.toString())
        val ovId = Regex("showing (f\\d+)").find(addedOv.get("detail").asString)!!.groupValues[1]
        assertTrue(addedOv.get("detail").asString, addedOv.get("detail").asString.contains("unresolved: internal/nope.go:3"))
        val overviewService = OverviewService.getInstance(project)
        waitFor("tour shown in the tool window") { overviewService.open?.overview?.viewKind == "session:$ovId:" }
        val shownDoc = overviewService.open!!
        assertEquals("Tour", shownDoc.overview.title)
        assertEquals("the note anchor points at the note's lines; the unresolved one is text", 2, shownDoc.anchors.size)
        assertEquals(dev.ggnotes.store.LinkTarget.Anchor("internal/x/gate.go", 5, 5), shownDoc.anchors[1].target)
        assertEquals("the note", shownDoc.anchors[1].label)
        val listedOv = post("4-2", """{"id":"4-2","cmd":"overview_list","wait":true}""")
        assertEquals("1 overviews", listedOv.get("detail").asString)
        val row = listedOv.getAsJsonArray("overviews")[0].asJsonObject
        assertEquals(ovId, row.get("id").asString)
        assertEquals("shown", row.get("state").asString)
        assertEquals(3, row.get("anchors").asInt)
        assertEquals(listOf("internal/nope.go:3"), row.getAsJsonArray("unresolved").map { it.asString })
        assertEquals(tour, post("4-3", """{"id":"4-3","cmd":"overview_show","file_id":"$ovId","wait":true}""").getAsJsonArray("overviews")[0].asJsonObject.get("text").asString)

        // the walk stays on its anchor across overview_set
        ApplicationManager.getApplication().invokeAndWait { overviewService.goToAnchor(2, openFile = false) }
        val setOv = post("4-4", com.google.gson.JsonObject().apply { addProperty("id", "4-4"); addProperty("cmd", "overview_set"); addProperty("file_id", ovId); addProperty("text", "$tour\n\nAnd [more](internal/x/gate.go:1)."); addProperty("wait", true) }.toString())
        assertEquals("showing $ovId", setOv.get("detail").asString.lines().first())
        waitFor("re-rendered with three anchors") { overviewService.open?.anchors?.size == 3 }
        assertEquals(2, overviewService.open!!.currentAnchor)
        assertEquals("an overview needs text", post("4-5", """{"id":"4-5","cmd":"overview_set","file_id":"$ovId","text":" ","wait":true}""").get("error").asString)
        assertEquals("no overview f99", post("4-6", """{"id":"4-6","cmd":"overview_show","file_id":"f99","wait":true}""").get("error").asString)
        val filesWithOv = post("4-7", """{"id":"4-7","cmd":"files","wait":true}""").getAsJsonArray("files").map { it.asJsonObject }
        val ovEntry = filesWithOv.single { it.get("id").asString == ovId }
        assertEquals("overview", ovEntry.get("source").asString)
        assertEquals("Tour", ovEntry.get("title").asString)
        assertEquals("shown", ovEntry.get("state").asString)
        assertEquals("focused Tour", post("4-8", """{"id":"4-8","cmd":"file_focus","file_id":"$ovId","wait":true}""").get("detail").asString)
        assertEquals("added", Regex("added (f\\d+) in the background").find(post("4-9", """{"id":"4-9","cmd":"overview_add","title":"Quiet","text":"nothing","background":true,"wait":true}""").get("detail").asString)!!.groupValues[0].substring(0, 5))
        assertEquals("closed $ovId", post("4-10", """{"id":"4-10","cmd":"overview_rm","file_id":"$ovId","wait":true}""").get("detail").asString)
        assertNull(overviewService.open)
        assertEquals("no overview $ovId", post("4-11", """{"id":"4-11","cmd":"overview_rm","file_id":"$ovId","wait":true}""").get("error").asString)
        assertEquals("1 overviews", post("4-12", """{"id":"4-12","cmd":"overview_list","wait":true}""").get("detail").asString)
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
        assertEquals(15, n)
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
