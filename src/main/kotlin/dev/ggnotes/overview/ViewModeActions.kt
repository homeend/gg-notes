package dev.ggnotes.overview

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Toggleable
import com.intellij.openapi.project.DumbAware

/** How the tool window groups its notes: by the day they were added, or by what they belong to. */
enum class NoteView {
    DATE, TYPE;

    companion object {
        /** A stored value; missing or unknown means [DATE]. */
        fun parse(value: String?): NoteView = values().firstOrNull { it.name == value } ?: DATE
    }
}

/**
 * One choice of the View radio pair. A plain action with [Toggleable] state, not a ToggleAction
 * (CLAUDE.md: Find Action would render that as an independent On/Off switch).
 */
abstract class ViewModeAction(private val mode: NoteView) : AnAction(), DumbAware, Toggleable {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) {
        val service = e.project?.let { OverviewService.getInstance(it) }
        e.presentation.isEnabled = service != null
        Toggleable.setSelected(e.presentation, service?.view == mode)
    }
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let { OverviewService.getInstance(it) }?.view = mode
    }
}

class ViewByDateAction : ViewModeAction(NoteView.DATE)
class ViewByTypeAction : ViewModeAction(NoteView.TYPE)
