package dev.ggnotes.overview

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.InlayProperties
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.LineMarkerRenderer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import dev.ggnotes.store.Remark
import java.awt.Color
import java.awt.Font
import java.awt.Graphics
import java.awt.Rectangle
import java.awt.RenderingHints

/**
 * Document-scoped overlays on a file opened from an overview (SPEC §5,
 * HOW-GG §3): a box above each remark's first line with its summary and
 * rationale, a tint over the remark's lines, and a gutter band on every
 * anchor of the open overview in that file. All of it is parented to the
 * [OpenOverview] and vanishes when another overview opens or it is closed.
 */
object RemarkOverlay {

    private val APPLIED: Key<String> = Key.create("dev.ggnotes.overlayApplied")

    val REMARK_BG = JBColor(Color(0xFFF4D6), Color(0x3A3522))
    val REMARK_BORDER = JBColor(Color(0xD9B55A), Color(0x8A7A3C))
    val REMARK_TEXT = JBColor(Color(0x4A3F10), Color(0xE8DDA8))
    val ANCHOR_BAND = JBColor(Color(0x4A88C7), Color(0x5B9BD5))
    val LINE_TINT = JBColor(Color(0xFFF9E8), Color(0x2F2C22))

    /** Watch editors that open while [o] is current and overlay the ones showing its files. */
    fun install(project: Project, o: OpenOverview) {
        EditorFactory.getInstance().addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) {
                val editor = event.editor
                if (editor.project != project) return
                apply(project, o, editor)
            }
        }, o)
        for (editor in EditorFactory.getInstance().allEditors) {
            if (editor.project == project) apply(project, o, editor)
        }
    }

    /** Overlay [editor] when its file was opened from [o]; idempotent per (editor, overview). */
    fun apply(project: Project, o: OpenOverview, editor: Editor) {
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        val ref = file.getUserData(OVERVIEW_FILE_KEY) ?: return
        if (ref.overviewIdentity != o.overview.identity || ref.commit != o.overview.commit) return
        val stamp = o.overview.identity + "@" + o.overview.commit
        if (editor.getUserData(APPLIED) == stamp) return
        editor.putUserData(APPLIED, stamp)
        Disposer.register(o) { editor.putUserData(APPLIED, null) }

        val doc = editor.document
        val lineCount = doc.lineCount
        val markup = editor.markupModel

        for (anchor in o.anchors) {
            if (anchor.target.path != ref.path) continue
            val first = anchor.target.line ?: continue
            val last = anchor.target.end ?: first
            if (first < 1 || first > lineCount) continue
            val h = markup.addRangeHighlighter(
                doc.getLineStartOffset(first - 1), doc.getLineEndOffset((last - 1).coerceAtMost(lineCount - 1)),
                HighlighterLayer.SELECTION - 2, null, HighlighterTargetArea.LINES_IN_RANGE)
            h.lineMarkerRenderer = BandRenderer(anchor.index)
            registerRemoval(o, h)
        }

        val remarks = o.document?.files?.firstOrNull { it.path == ref.path }?.annotations ?: return
        for (remark in remarks) {
            if (remark.first < 1 || remark.first > lineCount) continue
            val start = doc.getLineStartOffset(remark.first - 1)
            val end = doc.getLineEndOffset((remark.last - 1).coerceAtMost(lineCount - 1))
            val tint = TextAttributes().apply { backgroundColor = LINE_TINT }
            val h = markup.addRangeHighlighter(start, end, HighlighterLayer.SELECTION - 1, tint, HighlighterTargetArea.LINES_IN_RANGE)
            registerRemoval(o, h)
            val inlay: Inlay<*>? = editor.inlayModel.addBlockElement(
                start, InlayProperties().showAbove(true).relatesToPrecedingText(false).priority(100),
                RemarkBox(remark))
            if (inlay != null) Disposer.register(o, inlay)
            reflowOnResize(editor)
        }
    }

    private fun registerRemoval(o: OpenOverview, h: RangeHighlighter) {
        Disposer.register(o) { if (h.isValid) h.dispose() }
    }

    /** A thin bar in the gutter marking an anchor's lines (gg's `┃` band). */
    private class BandRenderer(val index: Int) : LineMarkerRenderer {
        override fun paint(editor: Editor, g: Graphics, r: Rectangle) {
            g.color = ANCHOR_BAND
            val w = JBUI.scale(3)
            g.fillRect(r.x + r.width - w - JBUI.scale(1), r.y, w, r.height)
        }
    }

    /**
     * One box plus its line tint on [first]..[last] of [editor]; the returned
     * disposables remove them. Shared with the steer session's temporary notes.
     */
    fun box(editor: Editor, first: Int, last: Int, remark: Remark): List<com.intellij.openapi.Disposable> {
        val doc = editor.document
        val lineCount = doc.lineCount
        if (first < 1 || first > lineCount) return emptyList()
        val start = doc.getLineStartOffset(first - 1)
        val end = doc.getLineEndOffset((last - 1).coerceIn(first - 1, lineCount - 1))
        val out = ArrayList<com.intellij.openapi.Disposable>()
        val tint = TextAttributes().apply { backgroundColor = LINE_TINT }
        val h = editor.markupModel.addRangeHighlighter(start, end, HighlighterLayer.SELECTION - 1, tint, HighlighterTargetArea.LINES_IN_RANGE)
        out += com.intellij.openapi.Disposable { if (h.isValid) h.dispose() }
        editor.inlayModel.addBlockElement(start, InlayProperties().showAbove(true).relatesToPrecedingText(false).priority(100), RemarkBox(remark))
            ?.let { out += it }
        reflowOnResize(editor)
        return out
    }

    private val REFLOWS: Key<Boolean> = Key.create("dev.ggnotes.remarkBoxReflow")

    /**
     * Once per editor: when its visible width changes (the tool window opens or closes, the IDE
     * window is resized, the editor first gets its size), every remark box in it re-measures.
     */
    private fun reflowOnResize(editor: Editor) {
        if (editor.getUserData(REFLOWS) == true) return
        editor.putUserData(REFLOWS, true)
        editor.scrollingModel.addVisibleAreaListener { e ->
            if (e.oldRectangle?.width != e.newRectangle.width) reflow(editor)
        }
    }

    /** Re-measure every remark box of [editor] (EDT). */
    fun reflow(editor: Editor) {
        if (editor.isDisposed) return
        for (inlay in editor.inlayModel.getBlockElementsInRange(0, editor.document.textLength, RemarkBox::class.java)) inlay.update()
    }

    /** The remark box: summary in bold, rationale (wrapped) beneath, drawn above the first remarked line. */
    class RemarkBox(val remark: Remark) : EditorCustomElementRenderer {

        /** How many text lines the last paint drew (tests compare it with the height the box reserved). */
        @Volatile internal var paintedLines: Int = 0

        /**
         * The lines as the editor last MEASURED them. The editor asks for the size only when the box
         * is added or updated, so paint must draw these, never a fresh wrap at today's width: that
         * left a tall box with three lines in it, or lines past the right border.
         */
        @Volatile private var measured: List<Pair<String, Boolean>>? = null

        private fun measure(editor: Editor): List<Pair<String, Boolean>> = lines(editor).also { measured = it }

        private fun font(editor: Editor): Font = editor.colorsScheme.getFont(EditorFontType.PLAIN)

        private fun lines(editor: Editor): List<Pair<String, Boolean>> {
            val metrics = editor.contentComponent.getFontMetrics(font(editor))
            val width = (editor.scrollingModel.visibleArea.width - JBUI.scale(40)).coerceAtLeast(JBUI.scale(200))
            val out = ArrayList<Pair<String, Boolean>>()
            out += remark.summary.trim() to true
            val rationale = remark.rationale?.trim().orEmpty()
            if (rationale.isNotEmpty()) {
                for (paragraph in rationale.split('\n')) {
                    var line = StringBuilder()
                    for (word in paragraph.split(' ')) {
                        val candidate = if (line.isEmpty()) word else "$line $word"
                        if (metrics.stringWidth(candidate) > width - JBUI.scale(16) && line.isNotEmpty()) {
                            out += line.toString() to false
                            line = StringBuilder(word)
                        } else {
                            line = StringBuilder(candidate)
                        }
                    }
                    out += line.toString() to false
                }
            }
            return out
        }

        override fun calcWidthInPixels(inlay: Inlay<*>): Int {
            val editor = inlay.editor
            val metrics = editor.contentComponent.getFontMetrics(font(editor))
            val bold = editor.contentComponent.getFontMetrics(font(editor).deriveFont(Font.BOLD))
            return measure(editor).maxOf { (text, isSummary) -> (if (isSummary) bold else metrics).stringWidth(text) } + JBUI.scale(16)
        }

        override fun calcHeightInPixels(inlay: Inlay<*>): Int =
            measure(inlay.editor).size * inlay.editor.lineHeight + JBUI.scale(8)

        override fun paint(inlay: Inlay<*>, g: Graphics, target: Rectangle, textAttributes: TextAttributes) {
            val editor = inlay.editor
            val g2 = g.create() as java.awt.Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                val arc = JBUI.scale(6)
                g2.color = REMARK_BG
                g2.fillRoundRect(target.x, target.y + JBUI.scale(2), target.width, target.height - JBUI.scale(4), arc, arc)
                g2.color = REMARK_BORDER
                g2.drawRoundRect(target.x, target.y + JBUI.scale(2), target.width - 1, target.height - JBUI.scale(5), arc, arc)
                g2.color = REMARK_TEXT
                val plain = font(editor)
                val bold = plain.deriveFont(Font.BOLD)
                val metrics = editor.contentComponent.getFontMetrics(plain)
                var y = target.y + JBUI.scale(4) + metrics.ascent
                val drawn = measured ?: measure(editor)
                for ((text, isSummary) in drawn) {
                    g2.font = if (isSummary) bold else plain
                    g2.drawString(text, target.x + JBUI.scale(8), y)
                    y += editor.lineHeight
                }
                paintedLines = drawn.size
            } finally {
                g2.dispose()
            }
        }
    }
}
