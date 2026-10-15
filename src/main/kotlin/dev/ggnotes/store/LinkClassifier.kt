package dev.ggnotes.store

import java.net.URI

/** What a markdown link destination means in an overview (SPEC §6–§7). */
sealed class LinkTarget {
    /** A file at the overview's commit; [line]/[end] 1-based inclusive, null = whole file. */
    data class Anchor(val path: String, val line: Int?, val end: Int?) : LinkTarget()

    /** A clean http(s) URL: an ordinary link. */
    data class Url(val url: String) : LinkTarget()

    /** Not classifiable: rendered as `label (dest)` text so the destination is not lost. */
    data class Text(val dest: String) : LinkTarget()

    /** A dropped scheme (`javascript:` …): the label alone. */
    object LabelOnly : LinkTarget() {
        override fun toString() = "LabelOnly"
    }
}

object LinkClassifier {

    private val DROPPED_SCHEMES = listOf("javascript:", "data:", "vbscript:")
    private val HTTP_URL = Regex("""^https?://[^\u0000- \u007F]+$""", RegexOption.IGNORE_CASE)
    private val DRIVE = Regex("""^[A-Za-z]:($|/)""")
    private val LINE = Regex("""^(\d+)(?:-(\d+))?$""")

    /** The four steps of SPEC §6, in order. */
    fun classify(dest: String): LinkTarget {
        val squeezed = dest.filter { it.code > 0x20 && it.code != 0x7F }.lowercase()
        if (DROPPED_SCHEMES.any { squeezed.startsWith(it) }) return LinkTarget.LabelOnly
        if (isCleanHttpUrl(dest)) return LinkTarget.Url(dest)
        return parseAnchor(dest) ?: LinkTarget.Text(dest)
    }

    private fun isCleanHttpUrl(dest: String): Boolean {
        if (!HTTP_URL.matches(dest)) return false
        return runCatching { URI(dest).host != null }.getOrDefault(false)
    }

    /** The grammar of SPEC §6; null when the destination is not an anchor. */
    fun parseAnchor(dest: String): LinkTarget.Anchor? {
        var s = dest.trim()
        if (s.startsWith("./")) s = s.substring(2)
        if (s.isEmpty()) return null
        if (s.startsWith("/")) return null
        if ('\\' in s) return null
        if ("://" in s) return null
        if (DRIVE.containsMatchIn(s)) return null
        if (s.any { it.code <= 0x20 || it.code == 0x7F }) return null
        if (s.split('/').any { it == ".." }) return null
        if (s.startsWith("note:")) return null

        val colon = s.lastIndexOf(':')
        var path = s
        var line: Int? = null
        var end: Int? = null
        if (colon >= 0) {
            val tail = s.substring(colon + 1)
            val m = LINE.matchEntire(tail)
            if (m != null) {
                path = s.substring(0, colon)
                line = m.groupValues[1].toIntOrNull() ?: return null
                end = m.groupValues[2].takeIf { it.isNotEmpty() }?.let { it.toIntOrNull() ?: return null } ?: line
                if (line < 1 || end < line) return null
            }
        }
        if (path.isEmpty()) return null
        return LinkTarget.Anchor(path, line, end)
    }
}
