package dev.ggnotes.session

import dev.ggnotes.steer.Reanchor

/** Where a session note sits on the file now, and whether its text is still there. */
data class Placement(val start: Int, val end: Int, val outdated: Boolean)

/**
 * SPEC §4.3, gg's `ColdPlace`: a reader that never saw the writer's file places a
 * note from its `lines` alone — at `start` when they are there, else at their one
 * occurrence, else outdated at `start..end` clamped into the file (no lines → 1..1).
 */
object ColdAnchor {

    fun place(file: List<String>?, start: Int, end: Int, lines: List<String>): Placement {
        val cur = file.orEmpty()
        val at = Reanchor.relocate(cur, lines, start)
        if (at > 0) return Placement(at, at + lines.size - 1, false)
        var s = start
        var e = end
        if (e > cur.size) e = cur.size
        if (s > e) s = e
        if (s < 1) {
            s = 1
            e = 1
        }
        return Placement(s, e, true)
    }
}
