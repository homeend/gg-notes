package dev.ggnotes.overview

import com.intellij.openapi.project.Project
import dev.ggnotes.items.NoteItem
import dev.ggnotes.items.Payload
import dev.ggnotes.steer.SteerService
import git4idea.repo.GitRepository

/** The one route from a note item to what shows it: the tool window's row click and `file_focus` both open through here. */
object ItemOpener {

    /**
     * Open [item] as its tool-window row's click does; [session] is the root's steer session (session
     * items need it, and without one they open nothing). [focusEditor] gives a session note's editor
     * the focus (the row click keeps it in the tree). EDT.
     */
    fun open(project: Project, item: NoteItem, repo: GitRepository, session: SteerService.RootSession?, focusEditor: Boolean = false) {
        val service = OverviewService.getInstance(project)
        val steer = SteerService.getInstance(project)
        when (val p = item.payload) {
            is Payload.StoreOverview -> service.open(p.overview, repo)
            is Payload.StoreReview -> service.openReview(p.review, repo)
            is Payload.StoreThread -> service.openNotes(listOf(p.thread), p.thread.root.address.path, p.commit, p.worktree, repo)
            is Payload.SessionOverview -> session?.let { steer.showOverview(it, p.entry, keepAnchor = false) }
            is Payload.SessionNote -> session?.let { steer.showNote(it, p.entry, focus = focusEditor) }
        }
    }
}
