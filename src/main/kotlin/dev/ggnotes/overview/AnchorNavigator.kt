package dev.ggnotes.overview

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.AppExecutorUtil
import dev.ggnotes.GgNotesBundle
import dev.ggnotes.store.RenderedAnchor

/**
 * Opens an anchor's file AT THE OVERVIEW'S COMMIT (the working-tree file
 * only when its bytes equal the committed ones), scrolled to the line with
 * the range selected. Content comes from git on a pooled thread; the editor
 * opens on the EDT.
 */
object AnchorNavigator {

    private val LOG = logger<AnchorNavigator>()

    fun open(project: Project, service: OverviewService, o: OpenOverview, anchor: RenderedAnchor) {
        AppExecutorUtil.getAppExecutorService().execute {
            val committed = service.contentOf(o, anchor.target.path)
            if (committed == null) {
                notify(project, GgNotesBundle.message("anchor.missing", anchor.target.path, o.overview.shortSha))
                return@execute
            }
            val root = o.repo.root
            val working = root.findFileByRelativePath(anchor.target.path)
            // a live document (working review, live notes) always opens the working file itself
            val sameAsWorking = o.overview.isLive && working != null ||
                working != null && !working.isDirectory && runCatching { working.contentsToByteArray().contentEquals(committed) }.getOrDefault(false)
            if (o.overview.isLive && working == null) {
                notify(project, GgNotesBundle.message("anchor.missing", anchor.target.path, GgNotesBundle.message("working.tree")))
                return@execute
            }
            val file: VirtualFile = if (sameAsWorking) working!! else RevisionFiles.atCommit(project, root, o.overview.commit, anchor.target.path)
            file.putUserData(OVERVIEW_FILE_KEY, OverviewFileRef(o.overview.identity, root, o.overview.commit, anchor.target.path))
            ApplicationManager.getApplication().invokeLater({
                if (project.isDisposed || service.open !== o) return@invokeLater
                val line0 = ((anchor.target.line ?: 1) - 1).coerceAtLeast(0)
                val editor = FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, file, line0, 0), true)
                if (editor == null) {
                    LOG.info("GgNotes: no text editor for ${file.path}")
                    return@invokeLater
                }
                select(editor, anchor, highlight = !NoteVisibility.getInstance(project).isHidden(file))
                RemarkOverlay.apply(project, o, editor)
            }, project.disposed)
        }
    }

    /**
     * The banner's "Open current version": the working-tree file behind a revision tab opened
     * from an overview, at 0-based [line] (the revision's caret line; the lines may have moved
     * since the commit). False when [file] is no such tab or the path is gone from the tree.
     */
    fun openWorkingCopy(project: Project, file: VirtualFile, line: Int): Boolean {
        val working = workingCopyOf(file) ?: return false
        return FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, working, line.coerceAtLeast(0), 0), true) != null
    }

    /** The working-tree file of a revision tab opened from an overview, when the path is still in the tree. */
    fun workingCopyOf(file: VirtualFile): VirtualFile? {
        if (file.isInLocalFileSystem) return null
        val ref = file.getUserData(OVERVIEW_FILE_KEY) ?: return null
        return ref.root.findFileByRelativePath(ref.path)?.takeIf { !it.isDirectory }
    }

    /** Caret on the first line, the whole range selected (unless the file's notes are hidden), scrolled to the middle. */
    fun select(editor: Editor, anchor: RenderedAnchor, highlight: Boolean = true) {
        val doc = editor.document
        val first = anchor.target.line ?: return
        val last = anchor.target.end ?: first
        if (first < 1 || first > doc.lineCount) return
        val start = doc.getLineStartOffset(first - 1)
        val end = doc.getLineEndOffset((last - 1).coerceAtMost(doc.lineCount - 1))
        editor.caretModel.moveToOffset(start)
        if (highlight) editor.selectionModel.setSelection(start, end) else editor.selectionModel.removeSelection()
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
    }

    private fun notify(project: Project, text: String) {
        ApplicationManager.getApplication().invokeLater({
            NotificationGroupManager.getInstance().getNotificationGroup("GgNotes")
                .createNotification(text, NotificationType.WARNING).notify(project)
        }, project.disposed)
    }
}
