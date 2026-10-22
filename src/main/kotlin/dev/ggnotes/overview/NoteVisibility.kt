package dev.ggnotes.overview

import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotifications
import dev.ggnotes.steer.SteerService
import java.util.concurrent.ConcurrentHashMap

/**
 * "Hide notes" in a file's banner: the files whose remark boxes, line tints,
 * anchor bands and session notes are not drawn, so the file reads as a plain
 * editor. Kept by working-tree path, so a revision tab and the working copy of
 * the same file share it, and for as long as the IDE runs; a hidden file keeps
 * a banner to show them again even when no overview is open.
 */
@Service(Service.Level.PROJECT)
class NoteVisibility(private val project: Project) {

    companion object {
        fun getInstance(project: Project): NoteVisibility = project.getService(NoteVisibility::class.java)

        /** The working-tree path [file] stands for: an overview file's root + path, else a local file's own path. */
        fun keyOf(file: VirtualFile): String? =
            file.getUserData(OVERVIEW_FILE_KEY)?.let { it.root.path + "/" + it.path }
                ?: file.takeIf { it.isInLocalFileSystem }?.path
    }

    private val hidden = ConcurrentHashMap.newKeySet<String>()

    fun isHidden(key: String): Boolean = key in hidden

    fun isHidden(file: VirtualFile): Boolean = keyOf(file)?.let { it in hidden } ?: false

    /** EDT: hide (or show again) every note of [file]'s path on all of its editors, and flip the banners. */
    fun setHidden(file: VirtualFile, hide: Boolean) {
        val key = keyOf(file) ?: return
        if (hide) hidden += key else hidden -= key
        val open = OverviewService.getInstance(project).open
        for (editor in EditorFactory.getInstance().allEditors) {
            if (editor.project != project) continue
            val vf = FileDocumentManager.getInstance().getFile(editor.document) ?: continue
            if (keyOf(vf) != key) continue
            if (hide) {
                RemarkOverlay.clear(editor)
                editor.selectionModel.removeSelection()
            } else if (open != null) {
                RemarkOverlay.apply(project, open, editor)
            }
        }
        for (s in SteerService.getInstance(project).allSessions()) s.marks.visibilityChanged(key)
        EditorNotifications.getInstance(project).updateAllNotifications()
    }
}
