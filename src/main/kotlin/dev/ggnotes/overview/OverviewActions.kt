package dev.ggnotes.overview

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware

private fun AnActionEvent.service(): OverviewService? = project?.let { OverviewService.getInstance(it) }

/** Re-read every repository's store now (the directory is also polled, SPEC §8.1). */
class RefreshOverviewsAction : AnAction(AllIcons.Actions.Refresh), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }
    override fun actionPerformed(e: AnActionEvent) {
        e.service()?.refresh()
    }
}

abstract class AnchorStepAction(icon: javax.swing.Icon) : AnAction(icon), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.service()?.open?.anchors?.isNotEmpty() == true
    }
}

/** gg's `n` / tab: the next anchor in document order, wrapping. */
class NextAnchorAction : AnchorStepAction(AllIcons.Actions.Forward) {
    override fun actionPerformed(e: AnActionEvent) {
        e.service()?.nextAnchor()
    }
}

/** gg's `p` / shift+tab: the previous anchor, wrapping. */
class PreviousAnchorAction : AnchorStepAction(AllIcons.Actions.Back) {
    override fun actionPerformed(e: AnActionEvent) {
        e.service()?.previousAnchor()
    }
}

/** gg's Backspace: return to the overview, on the anchor the user left. */
class BackToOverviewAction : AnAction(AllIcons.Actions.PreviewDetails), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.service()?.open != null
    }
    override fun actionPerformed(e: AnActionEvent) {
        e.service()?.backToOverview()
    }
}

/**
 * "Document only": hide the list above the document so the document takes the whole tool
 * window; press again to bring the list back. A plain action with [Toggleable] state, not a
 * ToggleAction (CLAUDE.md: Find Action would render that as an On/Off switch).
 */
class DocumentOnlyAction : AnAction(AllIcons.General.FitContent), DumbAware, com.intellij.openapi.actionSystem.Toggleable {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        val s = e.service()
        val hidden = s?.listHidden == true
        // nothing open = nothing to give the whole window to; the toggle stays usable only to bring a hidden list back
        e.presentation.isEnabled = s != null && (s.open != null || hidden)
        com.intellij.openapi.actionSystem.Toggleable.setSelected(e.presentation, hidden)
        e.presentation.text = dev.ggnotes.GgNotesBundle.message(if (hidden) "action.GgNotes.DocumentOnly.showList" else "action.GgNotes.DocumentOnly.text")
    }
    override fun actionPerformed(e: AnActionEvent) {
        val s = e.service() ?: return
        s.setListHidden(!s.listHidden)
    }
}

/** Close the open overview: its remark boxes and bands leave the editors. */
class CloseOverviewAction : AnAction(AllIcons.Actions.Close), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.service()?.open != null
    }
    override fun actionPerformed(e: AnActionEvent) {
        e.service()?.close()
    }
}
