package dev.ggnotes.steer

/** An overview anchor: a file (with optional lines) or a session note `note:<id>` (gg's `agentdocs.Anchor`). */
data class SessionAnchor(val path: String?, val start: Int?, val end: Int?, val note: String?) {
    val dest: String get() = when {
        note != null -> "note:$note"
        start == null -> path.orEmpty()
        end != null && end != start -> "$path:$start-$end"
        else -> "$path:$start"
    }
}

/** gg's `ParseAnchorDest` plus the inline-link scan of an overview's markdown. */
object SessionAnchors {

    /** Does a file anchor resolve: the path exists in the worktree and [end] (when given) is within its lines? */
    fun interface Resolver {
        fun resolves(path: String, end: Int?): Boolean
    }

    const val MAX_ANCHORS = 100

    // character classes only (a repeated alternation recurses per character in java.util.regex)
    private val INLINE_LINK = Regex("""!?\[([^\[\]]{0,2000})\]\(([^)\s]{1,2000})(\s+"[^"]*")?\)""")

    /** `note:<id>` (a session-docs id), or a repo-relative slash path with an optional `:N` / `:N-M`; null for anything else. */
    fun parse(dest: String): SessionAnchor? {
        var d = dest.trim()
        if (d.startsWith("note:")) {
            val id = d.substring(5)
            return if (dev.ggnotes.session.SessionDocNames.isValidId(id)) SessionAnchor(null, null, null, id) else null
        }
        if (d.startsWith("./")) d = d.substring(2)
        if (d.isEmpty() || d.startsWith("/") || '\\' in d || "://" in d) return null
        if (d.length >= 2 && d[1] == ':' && (d.length == 2 || d[2] == '/') && d[0].lowercaseChar() in 'a'..'z') return null
        if (d.any { it.code <= 0x20 || it.code == 0x7f }) return null
        var path = d
        var start: Int? = null
        var end: Int? = null
        val i = d.lastIndexOf(':')
        if (i >= 0) {
            var lo = d.substring(i + 1)
            var hi = ""
            var ranged = false
            val j = lo.indexOf('-')
            if (j >= 0) {
                hi = lo.substring(j + 1)
                lo = lo.substring(0, j)
                ranged = true
            }
            if (allDigits(lo) && (!ranged || allDigits(hi))) {
                val s = lo.toIntOrNull() ?: return null
                val e = if (ranged) hi.toIntOrNull() ?: return null else s
                if (s < 1 || e < s) return null
                path = d.substring(0, i)
                start = s
                end = e
            }
        }
        if (path.isEmpty()) return null
        if (path.split('/').any { it == ".." }) return null
        return SessionAnchor(path, start, end, null)
    }

    private fun allDigits(s: String) = s.isNotEmpty() && s.all { it in '0'..'9' }

    /** The first [MAX_ANCHORS] anchors of a markdown text, in document order. */
    fun extract(markdown: String): List<SessionAnchor> {
        val out = ArrayList<SessionAnchor>()
        for (m in INLINE_LINK.findAll(markdown)) {
            val a = parse(m.groupValues[2]) ?: continue
            out += a
            if (out.size >= MAX_ANCHORS) break
        }
        return out
    }

    /**
     * The markdown the viewer gets: a `note:<id>` link becomes a link to the
     * note's lines now ([noteSpan]), an unresolved anchor becomes `label (dest)` text.
     */
    fun rewrite(markdown: String, unresolved: Set<String>, noteSpan: (String) -> String?): String {
        var count = 0
        return INLINE_LINK.replace(markdown) { m ->
            val label = m.groupValues[1]
            val dest = m.groupValues[2]
            val a = parse(dest) ?: return@replace m.value
            count++
            if (count > MAX_ANCHORS) return@replace m.value
            when {
                a.note != null -> noteSpan(a.note)?.let { "[$label]($it)" } ?: "$label ($dest)"
                a.dest in unresolved || dest.trim() in unresolved -> "$label ($dest)"
                else -> m.value
            }
        }
    }
}
