package dev.ggnotes.session

/**
 * The line "Copy Link" puts on the clipboard for a session document: its kind, id, title (or a
 * note's place and summary), the worktree it belongs to and the file it lives in. An agent the
 * user pastes it to needs nothing but a shell to read the document (the agent skills' FILES.md §8).
 */
object SessionDocRefs {

    private const val PRODUCT = "Agent Notes"

    /** gg's `agentdocs.OverviewName`: `overview <id> "<title>"`, the title Go-quoted. */
    fun overviewName(id: String, title: String): String = "overview $id " + quote(title)

    fun overviewLink(id: String, title: String, worktree: String, file: String): String =
        "$PRODUCT ${overviewName(id, title)} (worktree $worktree; file $file)"

    /** [at] is where the note sits NOW (it follows edits), not the numbers its file was written with. */
    fun noteLink(id: String, path: String, at: Placement, summary: String, worktree: String, file: String): String {
        val span = path + ":" + at.start + (if (at.end != at.start) "-" + at.end else "") + (if (at.outdated) " (outdated)" else "")
        return "$PRODUCT note $id at $span ${quote(summary)} (worktree $worktree; file $file)"
    }

    /** Go's `strconv.Quote` for the characters a title or summary can hold. */
    fun quote(s: String): String {
        val out = StringBuilder("\"")
        for (ch in s) {
            when {
                ch == '"' -> out.append("\\\"")
                ch == '\\' -> out.append("\\\\")
                ch == '\n' -> out.append("\\n")
                ch == '\t' -> out.append("\\t")
                ch == '\r' -> out.append("\\r")
                ch.code < 0x20 || ch.code == 0x7f -> out.append("\\x%02x".format(ch.code))
                else -> out.append(ch)
            }
        }
        return out.append('"').toString()
    }
}
