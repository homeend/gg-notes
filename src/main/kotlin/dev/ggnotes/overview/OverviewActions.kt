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
