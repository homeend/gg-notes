package dev.ggnotes.overview

import com.intellij.openapi.fileEditor.FileEditor
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
 * it is, with Back / Previous / Next — gg's Backspace and n / p.
 */
class OverviewBanner : EditorNotificationProvider, DumbAware {

    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        val ref = file.getUserData(OVERVIEW_FILE_KEY) ?: return null
        val service = OverviewService.getInstance(project)
        val open = service.open ?: return null
        if (open.overview.identity != ref.overviewIdentity || open.overview.commit != ref.commit) return null
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
            panel
        }
    }
}
