package dev.ggnotes.steer

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.LineMarkerRenderer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.vcs.log.impl.VcsLogNavigationUtil.jumpToHash
import dev.ggnotes.git.GitAccess
import dev.ggnotes.overview.RevisionFiles
import dev.ggnotes.store.StoreLocator
import java.awt.Color
import java.awt.Graphics
import java.awt.Rectangle
import java.nio.file.Path

/**
 * PLUGIN.md §4: one `gg session` command → one reply. Runs on the steer
 * worker thread; editor work is done inside `invokeAndWait` so the reply is
 * written after the change is applied.
 */
class SteerHandler(private val project: Project, private val service: SteerService) {

    fun handle(s: SteerService.RootSession, c: Command): Reply {
        c.worktree?.takeIf { it.isNotEmpty() }?.let { wt ->
            if (!sameCheckout(wt, s.topLevel)) return Reply.fail(c.id, "the IDE shows ${s.topLevel}, not $wt")
        }
        return when (c.cmd) {
            "navigate" -> navigate(s, c)
            "highlight" -> highlight(s, c)
            "highlight_clear" -> highlightClear(s, c)
            "files" -> files(s, c)
            "file_focus" -> fileFocus(s, c)
            "note_add" -> noteAdd(s, c)
            "note_list" -> noteList(s, c)
            "note_show" -> noteShow(s, c)
            "note_rm" -> noteRm(s, c)
            "focus", "reload", "serve" -> Reply.fail(c.id, "${c.cmd} is a gg TUI command")
            else -> Reply.fail(c.id, "unknown command ${c.cmd}")
        }
    }

    // ---- navigate ----------------------------------------------------------------------

    private fun navigate(s: SteerService.RootSession, c: Command): Reply {
        val state = c.target?.state.orEmpty()
        if (state == "preview" || state == "ref" || state == "pair") return Reply.fail(c.id, "the IDE cannot show a $state target")
        if (c.line?.side == "old") return Reply.fail(c.id, "the IDE shows no old side")
        val file = c.file?.takeIf { it.isNotEmpty() }
        if (file == null) {
            c.commit?.takeIf { it.isNotEmpty() }?.let { return showCommit(s, c, it) }
            if (!c.step.isNullOrEmpty()) return stepNote(s, c)
            return Reply.fail(c.id, "navigate needs a file, a commit or a step")
        }
        val resolved = resolveFile(s, c, file) ?: return Reply.fail(c.id, missingFile(c, file))
        val entry = s.memory.touchFile(resolved.source, file, resolved.rev, background = c.background)
        s.files[entry.id] = resolved.vf
        var detail = ""
        onEdt {
            val fem = FileEditorManager.getInstance(project)
            val no = c.line?.no ?: 0
            val end = c.line?.end ?: 0
            if (c.background) {
                if (fem.selectedFiles.firstOrNull() == resolved.vf) {
                    detail = "$file is already open on screen"
                    return@onEdt
                }
                fem.openFile(resolved.vf, false)
                detail = "opened $file in the background" + (if (no > 0) " at line $no" else "")
                return@onEdt
            }
            val editor = fem.openTextEditor(OpenFileDescriptor(project, resolved.vf, (no - 1).coerceAtLeast(0), 0), true)
            // the index's copy is shown read-only here (SPEC §3.3), without touching the IDE's own staging file
            if (resolved.rev == "staged") (editor as? com.intellij.openapi.editor.ex.EditorEx)?.isViewer = true
            val lineCount = editor?.let { Lines.split(it.document.charsSequence).size } ?: 0
            if (editor != null && no > 0) placeCaret(editor, no, end, lineCount)
            detail = Lines.landed("opened $file" + spanSuffix(no, end), no, lineCount)
        }
        return Reply.ok(c.id, detail)
    }

    private class Resolved(val vf: VirtualFile, val source: String, val rev: String?)

    /** PLUGIN.md §4: by `target.state` — the working file, the index's copy, or the file at a commit. */
    private fun resolveFile(s: SteerService.RootSession, c: Command, file: String): Resolved? {
        return when (c.target?.state) {
            "commit" -> {
                val sha = c.target.commit?.takeIf { it.isNotEmpty() } ?: return null
                GitAccess.fileContent(project, s.repo.root, sha, file) ?: return null
                Resolved(RevisionFiles.atCommit(project, s.repo.root, sha, file), "commit", sha)
            }
            "staged" -> {
                GitAccess.indexContent(project, s.repo.root, file) ?: return null
                val vf = RevisionFiles.staged(project, s.repo.root, file) ?: return null
                Resolved(vf, "worktree", "staged")
            }
            else -> workingFile(s, file)?.let { Resolved(it, "worktree", null) }
        }
    }

    private fun missingFile(c: Command, file: String): String = when (c.target?.state) {
        "commit" -> "$file is not at ${c.target.commit.orEmpty().take(7)}"
        "staged" -> "$file is not in the index"
        else -> "$file is not in the working tree"
    }

    private fun showCommit(s: SteerService.RootSession, c: Command, sha: String): Reply {
        var failure: String? = null
        onEdt {
            try {
                com.intellij.vcs.log.impl.VcsLogContentUtil.runInMainLog(project) { ui ->
                    ui.jumpToHash(sha, false, true)
                }
            } catch (t: Throwable) {
                failure = t.message ?: t.javaClass.simpleName
            }
        }
        failure?.let { return Reply.fail(c.id, "cannot show commit ${sha.take(7)}: $it") }
        return Reply.ok(c.id, "showing commit ${sha.take(7)}")
    }

    private fun spanSuffix(no: Int, end: Int): String = when {
        no <= 0 -> ""
        end > no -> ":$no-$end"
        else -> ":$no"
    }

    /** Caret on [no] (clamped to the last gg-line), `no..end` selected, scrolled to the middle. */
    private fun placeCaret(editor: Editor, no: Int, end: Int, lineCount: Int) {
        val doc = editor.document
        if (doc.lineCount == 0) return
        val last = if (lineCount > 0) lineCount else doc.lineCount
        val first = no.coerceIn(1, last)
        val start = doc.getLineStartOffset(first - 1)
        editor.caretModel.moveToOffset(start)
        if (end > no) {
            val lastLine = end.coerceIn(first, last)
            editor.selectionModel.setSelection(start, doc.getLineEndOffset(lastLine - 1))
        } else {
            editor.selectionModel.removeSelection()
        }
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
    }

    private fun workingFile(s: SteerService.RootSession, file: String): VirtualFile? {
        val nio = Path.of(s.repo.root.path, file)
        return s.repo.root.findFileByRelativePath(file)?.takeIf { !it.isDirectory }
            ?: LocalFileSystem.getInstance().refreshAndFindFileByNioFile(nio)?.takeIf { !it.isDirectory }
    }

    // ---- highlight -----------------------------------------------------------------------

    private fun highlight(s: SteerService.RootSession, c: Command): Reply {
        val file = c.file?.takeIf { it.isNotEmpty() } ?: return Reply.fail(c.id, "highlight needs a file")
        if (c.side == "old") return Reply.fail(c.id, "the IDE shows no old side")
        if (c.start < 1) return Reply.fail(c.id, "a line number is 1-based")
        val end = if (c.end >= c.start) c.end else c.start
        val vf = workingFile(s, file) ?: return Reply.fail(c.id, "$file is not in the working tree")
        val entry = s.memory.touchFile("worktree", file, null, background = true)
        s.files[entry.id] = vf
        var failure: String? = null
        onEdt {
            val fem = FileEditorManager.getInstance(project)
            val editor = fem.openTextEditor(OpenFileDescriptor(project, vf, (c.start - 1).coerceAtLeast(0), 0), false)
            if (editor == null) {
                failure = "no text editor for $file"
                return@onEdt
            }
            val doc = editor.document
            if (doc.lineCount == 0) {
                failure = "$file has no lines"
                return@onEdt
            }
            val first = c.start.coerceIn(1, doc.lineCount)
            val last = end.coerceIn(first, doc.lineCount)
            val color = toneColor(c.tone)
            val h = editor.markupModel.addRangeHighlighter(
                doc.getLineStartOffset(first - 1), doc.getLineEndOffset(last - 1),
                HighlighterLayer.SELECTION - 3, TextAttributes().apply { backgroundColor = color.background },
                HighlighterTargetArea.LINES_IN_RANGE)
            h.lineMarkerRenderer = ToneBand(color.band)
            s.highlights += h
        }
        failure?.let { return Reply.fail(c.id, it) }
        return Reply.ok(c.id, "highlighted $file:" + (if (end > c.start) "${c.start}-$end" else "${c.start}"))
    }

    private fun highlightClear(s: SteerService.RootSession, c: Command): Reply {
        onEdt {
            for (h in s.highlights) runCatching { if (h.isValid) h.dispose() }
            s.highlights.clear()
        }
        return Reply.ok(c.id, "cleared")
    }

    private class Tone(val background: Color, val band: Color)

    private fun toneColor(tone: String?): Tone = when (tone) {
        "warn" -> Tone(JBColor(Color(0xFFF2C2), Color(0x4A4020)), JBColor(Color(0xE0A800), Color(0xD9B55A)))
        "error" -> Tone(JBColor(Color(0xFFDDDD), Color(0x4A2626)), JBColor(Color(0xD64545), Color(0xE06C6C)))
        else -> Tone(JBColor(Color(0xDDEBFF), Color(0x2B3A50)), JBColor(Color(0x4A88C7), Color(0x5B9BD5)))
    }

    private class ToneBand(val color: Color) : LineMarkerRenderer {
        override fun paint(editor: Editor, g: Graphics, r: Rectangle) {
            g.color = color
            val w = JBUI.scale(3)
            g.fillRect(r.x + r.width - w - JBUI.scale(1), r.y, w, r.height)
        }
    }

    // ---- files -------------------------------------------------------------------------

    private fun files(s: SteerService.RootSession, c: Command): Reply {
        var wire: List<OpenFileWire> = emptyList()
        onEdt { wire = openFilesWire(s) }
        val r = Reply.ok(c.id, if (wire.isEmpty()) "no open files" else "").copy(files = wire)
        return r
    }

    /** EDT: the open-files list as the editors show it now. */
    fun openFilesWire(s: SteerService.RootSession): List<OpenFileWire> {
        val fem = FileEditorManager.getInstance(project)
        val selected = fem.selectedFiles.firstOrNull()
        val shownId = s.memory.openFiles().firstOrNull { s.files[it.id] == selected }?.id
        return s.memory.openFiles().map { f ->
            s.memory.wire(f, shownId,
                caretLine = { entry ->
                    val vf = s.files[entry.id]
                    val editor = if (vf != null && vf == selected) fem.selectedTextEditor else null
                    editor?.caretModel?.logicalPosition?.line?.plus(1) ?: 0
                },
                noteCount = { entry -> s.memory.noteCount(entry.id) })
        }
    }

    private fun fileFocus(s: SteerService.RootSession, c: Command): Reply {
        val name = c.fileId?.takeIf { it.isNotEmpty() } ?: c.file?.takeIf { it.isNotEmpty() } ?: return Reply.fail(c.id, "file_focus needs a file")
        val entry = (if (!c.fileId.isNullOrEmpty()) s.memory.findFile(c.fileId) else s.memory.findFileByPath(name))
            ?: return Reply.fail(c.id, "no open file $name")
        val vf = s.files[entry.id] ?: return Reply.fail(c.id, "no open file $name")
        s.memory.touchFile(entry.source, entry.path, entry.rev)
        var detail = ""
        onEdt {
            val no = c.line?.no ?: 0
            val editor = FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, vf, (no - 1).coerceAtLeast(0), 0), true)
            if (editor != null && no > 0) placeCaret(editor, no, c.line?.end ?: 0, Lines.split(editor.document.charsSequence).size)
            detail = if (entry.source == "overview") "focused ${entry.title ?: entry.path}" else "focused ${entry.path}" + (if (no > 0) " at line $no" else "")
        }
        return Reply.ok(c.id, detail)
    }

    // ---- temporary notes ----------------------------------------------------------------

    /** PLUGIN.md §5: resolve the file as navigate would, open it in the background, note the editor's CURRENT lines. */
    private fun noteAdd(s: SteerService.RootSession, c: Command): Reply {
        val file = c.file?.takeIf { it.isNotEmpty() } ?: return Reply.fail(c.id, "note_add needs a file")
        val state = c.target?.state.orEmpty()
        if (state == "preview" || state == "ref" || state == "pair") return Reply.fail(c.id, "the IDE cannot show a $state target")
        if (c.side == "old") return Reply.fail(c.id, "the IDE shows no old side")
        val resolved = resolveFile(s, c, file) ?: return Reply.fail(c.id, missingFile(c, file))
        val entry = s.memory.touchFile(resolved.source, file, resolved.rev, background = true)
        s.files[entry.id] = resolved.vf
        var result: NoteResult? = null
        onEdt {
            val fem = FileEditorManager.getInstance(project)
            if (!fem.isFileOpen(resolved.vf)) fem.openFile(resolved.vf, false)
            val document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(resolved.vf)
            val lines = document?.let { s.notes.currentLines(it) } ?: emptyList()
            result = s.memory.addNote(entry.id, lines, c.start, if (c.end >= c.start) c.end else c.start, c.summary, c.rationale, c.author, c.meta, c.display)
            if (result is NoteResult.Ok) s.notes.noteAdded(entry.id, resolved.vf)
        }
        return when (val r = result) {
            is NoteResult.Ok -> Reply.ok(c.id, s.memory.notedDetail(r.note)).copy(notes = listOf(s.memory.noteWire(r.note, withText = false)))
            is NoteResult.Refused -> Reply.fail(c.id, r.error)
            null -> Reply.fail(c.id, "no editor for $file")
        }
    }

    private fun noteList(s: SteerService.RootSession, c: Command): Reply {
        val scope: SessionMemory.OpenFile? = when {
            !c.fileId.isNullOrEmpty() -> s.memory.findFile(c.fileId) ?: return Reply.fail(c.id, "no open file ${c.fileId}")
            !c.file.isNullOrEmpty() -> s.memory.findFileByPath(c.file) ?: return Reply.fail(c.id, "no open file ${c.file}")
            else -> null
        }
        for (f in listOfNotNull(scope).ifEmpty { s.memory.openFiles() }) s.notes.alignNow(f.id)
        val notes = (if (scope != null) s.memory.notesOf(scope.id) else s.memory.allNotes()).map { s.memory.noteWire(it, withText = false) }
        return Reply.ok(c.id, if (notes.isEmpty()) "no notes" else "").copy(notes = notes)
    }

    private fun noteShow(s: SteerService.RootSession, c: Command): Reply {
        val id = c.noteId.orEmpty()
        val n = s.memory.findNote(id) ?: return Reply.fail(c.id, "no note $id")
        s.notes.alignNow(n.fileId)
        return Reply.ok(c.id, "").copy(notes = listOf(s.memory.noteWire(s.memory.findNote(id) ?: n, withText = true)))
    }

    private fun noteRm(s: SteerService.RootSession, c: Command): Reply {
        val id = c.noteId.orEmpty()
        val n = s.memory.findNote(id) ?: return Reply.fail(c.id, "no note $id")
        s.memory.removeNote(id)
        onEdt { s.notes.redraw(n.fileId) }
        return Reply.ok(c.id, "removed $id")
    }

    /** `navigate --step next_note|prev_note`: from the file in front, the next/previous temporary note by line. */
    private fun stepNote(s: SteerService.RootSession, c: Command): Reply {
        val forward = c.step != "prev_note"
        val none = if (forward) "no next note in this diff" else "no previous note in this diff"
        var reply: Reply? = null
        onEdt {
            val fem = FileEditorManager.getInstance(project)
            val vf = fem.selectedFiles.firstOrNull()
            val editor = fem.selectedTextEditor
            val entry = s.memory.openFiles().firstOrNull { s.files[it.id] == vf }
            if (vf == null || editor == null || entry == null) {
                reply = Reply.fail(c.id, none)
                return@onEdt
            }
            s.notes.alignNow(entry.id)
            val caret = editor.caretModel.logicalPosition.line + 1
            val notes = s.memory.notesOf(entry.id)
            val target = if (forward) notes.filter { it.start > caret }.minByOrNull { it.start } else notes.filter { it.start < caret }.maxByOrNull { it.start }
            if (target == null) {
                reply = Reply.fail(c.id, none)
                return@onEdt
            }
            placeCaret(editor, target.start, target.end, Lines.split(editor.document.charsSequence).size)
            reply = Reply.ok(c.id, "opened ${entry.path}:${target.start} at line ${target.start}")
        }
        return reply ?: Reply.fail(c.id, none)
    }

    // ---- helpers -------------------------------------------------------------------------

    private fun onEdt(block: () -> Unit) {
        if (ApplicationManager.getApplication().isDispatchThread) block() else ApplicationManager.getApplication().invokeAndWait(block)
    }

    /** SPEC §3.4: cleaned, slash form, case-folded on Windows/macOS. */
    private fun sameCheckout(a: String, b: String): Boolean {
        val windows = StoreLocator.isWindows()
        val fold = windows || System.getProperty("os.name").orEmpty().lowercase().contains("mac")
        fun norm(p: String): String {
            val c = StoreLocator.clean(p, windows).replace('\\', '/')
            return if (fold) c.lowercase() else c
        }
        if (norm(a) == norm(b)) return true
        return runCatching { norm(Path.of(a).toRealPath().toString()) == norm(Path.of(b).toRealPath().toString()) }.getOrDefault(false)
    }
}
