package dev.ggnotes.items

import dev.ggnotes.session.SessionDocRefs

/**
 * The line "Copy Link" puts on the clipboard for a stored row (a review, an overview, a note thread):
 * what the record is, its id and title, the worktree, the commit it belongs to, and the `gg` command
 * that prints it (the CLI reads the store; no gg instance is needed). The agent skills' FILES.md §8
 * reads it. A session item's line is [SessionDocRefs]'s.
 */
object StoreRefs {

    private const val PRODUCT = "Agent Notes"

    /** Null for a session item. */
    fun link(item: NoteItem, worktree: String): String? {
        val id = item.id.removePrefix("store:")
        val where = item.owner.commit?.let { "commit " + it.take(7) } ?: "working tree"
        val title = SessionDocRefs.quote(item.title)
        return when (item.payload) {
            is Payload.StoreReview -> "$PRODUCT review $id $title (worktree $worktree; $where; read: gg note show $id --json)"
            is Payload.StoreOverview -> "$PRODUCT stored overview $id $title (worktree $worktree; $where; read: gg overview show $id --json)"
            is Payload.StoreThread -> {
                val o = item.owner
                val at = o.path.orEmpty() + (o.first?.let { ":$it" + (if (o.last != null && o.last != it) "-${o.last}" else "") } ?: "")
                "$PRODUCT stored note $id at $at $title (worktree $worktree; $where; read: gg note show $id --json)"
            }
            is Payload.SessionOverview, is Payload.SessionNote -> null
        }
    }
}
