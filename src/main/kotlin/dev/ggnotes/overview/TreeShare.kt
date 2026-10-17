package dev.ggnotes.overview

/**
 * How much of the tool window the notes tree takes above the document: the
 * height its rows need, clamped between a small floor and [MAX_SHARE] of the
 * window. A list longer than its share scrolls inside it; a short list hands
 * the rest to the document.
 */
object TreeShare {

    /** The most of the window the tree takes by default: a third, the document keeps the rest. */
    const val MAX_SHARE = 1f / 3f

    /** The splitter proportion for a tree whose rows need [contentHeight] px in a window [totalHeight] px high. */
    fun proportion(contentHeight: Int, totalHeight: Int, minHeight: Int): Float {
        if (totalHeight <= 0) return MAX_SHARE
        val wanted = contentHeight.coerceAtLeast(minHeight)
        return (wanted.toFloat() / totalHeight).coerceIn(0f, MAX_SHARE)
    }
}
