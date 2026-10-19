package dev.ggnotes.steer

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.Messages
import dev.ggnotes.GgNotesBundle
import dev.ggnotes.session.Placement
import dev.ggnotes.session.SessionNoteDoc

/** The editor's menu on a file with session notes: what the caret's file is and which notes sit on it now. */
private class NoteContext(val session: SteerService.RootSession, val path: String, val editor: Editor, val notes: List<Pair<SessionNoteDoc, Placement>>) {
    val atCaret: Pair<SessionNoteDoc, Placement>? get() {
        val line = editor.caretModel.logicalPosition.line + 1
        return notes.firstOrNull { line in it.second.start..it.second.end }
    }
}

private fun context(e: AnActionEvent): NoteContext? {
    val project = e.project ?: return null
    val editor = e.getData(CommonDataKeys.EDITOR) ?: return null
    val vf = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return null
    if (editor.project != project) return null
    for (s in SteerService.getInstance(project).allSessions()) {
        val path = s.marks.relativePath(vf) ?: continue
        val notes = s.marks.placementsOn(path)
        if (notes.isEmpty()) return null
        return NoteContext(s, path, editor, notes)
    }
    return null
}

/** Delete the session note under the caret: its file in the worktree's session docs is unlinked (session-docs SPEC §4.4). */
class DeleteSessionNoteAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = context(e)?.atCaret != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val ctx = context(e) ?: return
        val (note, _) = ctx.atCaret ?: return
        val answer = Messages.showYesNoDialog(
            e.project, GgNotesBundle.message("remove.confirm.SESSION_NOTE", note.summary, note.id), GgNotesBundle.message("remove.confirm.title"),
            GgNotesBundle.message("action.GgNotes.RemoveFromSession.text"), Messages.getCancelButton(), Messages.getWarningIcon())
        if (answer == Messages.YES) SteerService.getInstance(e.project!!).deleteNote(ctx.session, note.id)
    }
}

/** "Delete all notes on this file": every session note file naming this path is unlinked. */
class DeleteSessionNotesOnFileAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = context(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val ctx = context(e) ?: return
        val answer = Messages.showYesNoDialog(
            e.project, GgNotesBundle.message("remove.confirm.SESSION_FILE", ctx.path, ctx.notes.size), GgNotesBundle.message("remove.confirm.title"),
            GgNotesBundle.message("action.GgNotes.RemoveFromSession.text"), Messages.getCancelButton(), Messages.getWarningIcon())
        if (answer == Messages.YES) SteerService.getInstance(e.project!!).deleteNotesOn(ctx.session, ctx.path)
    }
}
