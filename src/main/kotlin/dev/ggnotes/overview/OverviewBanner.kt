package dev.ggnotes.overview

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import dev.ggnotes.GgNotesBundle
import java.util.function.Function
import javax.swing.JComponent

/**
 * The strip above a file opened from an overview: which overview and anchor
 * it is, with Back / Previous / Next — gg's Backspace and n / p — then Hide
 * notes / Show notes for this file and, on a revision tab, Open current
 * version. A file whose notes are hidden keeps a strip to show them again
 * after its overview is gone.
 */
class OverviewBanner : EditorNotificationProvider, DumbAware {

    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        val visibility = NoteVisibility.getInstance(project)
        val hidden = visibility.isHidden(file)
        val ref = file.getUserData(OVERVIEW_FILE_KEY)
        val service = OverviewService.getInstance(project)
        val open = service.open?.takeIf { ref != null && it.overview.identity == ref.overviewIdentity && it.overview.commit == ref.commit }
        if (open == null) {
            if (!hidden) return null
            return Function { editor ->
                val panel = EditorNotificationPanel(editor, EditorNotificationPanel.Status.Info)
                panel.text = GgNotesBundle.message("banner.text.hidden")
                panel.createActionLabel(GgNotesBundle.message("banner.showNotes")) { visibility.setHidden(file, false) }
                panel
            }
        }
        val working = AnchorNavigator.workingCopyOf(file)
        return Function { editor ->
            val panel = EditorNotificationPanel(editor, EditorNotificationPanel.Status.Info)
            val n = open.currentAnchor
            val label = n?.let { i -> open.anchors.firstOrNull { it.index == i }?.label }
            panel.text = if (n != null && label != null)
                GgNotesBundle.message("banner.text.anchor", open.overview.title, n, open.anchors.size, label)
            else
                GgNotesBundle.message("banner.text", open.overview.title)
            panel.createActionLabel(GgNotesBundle.message("banner.back")) { service.backToOverview() }
            panel.createActionLabel(GgNotesBundle.message("banner.previous")) { service.previousAnchor() }
            panel.createActionLabel(GgNotesBundle.message("banner.next")) { service.nextAnchor() }
            panel.createActionLabel(GgNotesBundle.message(if (hidden) "banner.showNotes" else "banner.hideNotes")) { visibility.setHidden(file, !hidden) }
            if (working != null) {
                panel.createActionLabel(GgNotesBundle.message("banner.openCurrent")) {
                    val line = (editor as? TextEditor)?.editor?.caretModel?.logicalPosition?.line ?: 0
                    AnchorNavigator.openWorkingCopy(project, file, line)
                }
            }
            panel
        }
    }
}
