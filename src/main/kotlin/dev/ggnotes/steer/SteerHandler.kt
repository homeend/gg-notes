package dev.ggnotes.steer

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import dev.ggnotes.store.StoreLocator
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
            "focus", "reload", "serve" -> Reply.fail(c.id, "${c.cmd} is a gg TUI command")
            else -> Reply.fail(c.id, "unknown command ${c.cmd}")
        }
    }

    // ---- navigate ----------------------------------------------------------------------

    private fun navigate(s: SteerService.RootSession, c: Command): Reply {
        val file = c.file?.takeIf { it.isNotEmpty() } ?: return Reply.fail(c.id, "navigate needs a file, a commit or a step")
        val state = c.target?.state.orEmpty()
        if (state == "preview" || state == "ref" || state == "pair") return Reply.fail(c.id, "the IDE cannot show a $state target")
        if (c.line?.side == "old") return Reply.fail(c.id, "the IDE shows no old side")
        val vf = workingFile(s, file) ?: return Reply.fail(c.id, "$file is not in the working tree")
        val entry = s.memory.touchFile("worktree", file, null, background = c.background)
        var detail = ""
        onEdt {
            val fem = FileEditorManager.getInstance(project)
            val no = c.line?.no ?: 0
            val end = c.line?.end ?: 0
            val lead = "opened $file" + spanSuffix(no, end)
            if (c.background) {
                fem.openFile(vf, false)
                detail = "opened $file in the background" + (if (no > 0) " at line $no" else "")
                return@onEdt
            }
            val editor = fem.openTextEditor(OpenFileDescriptor(project, vf, (no - 1).coerceAtLeast(0), 0), true)
            val lineCount = editor?.let { Lines.split(it.document.charsSequence).size } ?: 0
            if (editor != null && no > 0) placeCaret(editor, no, end, lineCount)
            detail = Lines.landed(lead, no, lineCount)
        }
        return Reply.ok(c.id, detail)
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
