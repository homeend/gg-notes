package dev.ggnotes.overview

/**
 * Where the overview pane scrolls when an anchor becomes current. The anchor
 * keeps [CONTEXT_LINES] of its own height above it, so the heading or sentence
 * it belongs to stays on screen, and a pane that already shows the whole
 * anchor does not move.
 */
object AnchorScroll {

    const val CONTEXT_LINES = 3

    /**
     * The viewport's new top, or null when [anchorY]..[anchorY]+[anchorHeight]
     * is already fully inside [viewTop]..[viewTop]+[viewHeight]. All in document
     * pixels; the result is clamped to the document's scrollable range.
     */
    fun target(anchorY: Int, anchorHeight: Int, viewTop: Int, viewHeight: Int, docHeight: Int): Int? {
        if (anchorY >= viewTop && anchorY + anchorHeight <= viewTop + viewHeight) return null
        val maxTop = (docHeight - viewHeight).coerceAtLeast(0)
        return (anchorY - CONTEXT_LINES * anchorHeight).coerceIn(0, maxTop)
    }
}
