package dev.ggnotes.overview

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.project.Project
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.ggnotes.GgNotesBundle
import dev.ggnotes.store.DocumentResult
import java.awt.BorderLayout
import java.time.format.DateTimeFormatter
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.event.HyperlinkEvent

/**
 * The rendered document (SPEC §7) with anchors as links. `anchor:N` goes
 * to the service; http(s) opens the browser. The current anchor is
 * highlighted; a missing one is greyed (SPEC §6).
 */
class DocumentPane(private val project: Project, private val service: OverviewService) : JPanel(BorderLayout()) {

    private val header = JBLabel()
    private val pane = JEditorPane()
    private val scroll = ScrollPaneFactory.createScrollPane(pane)
    private var shown: OpenOverview? = null

    init {
        header.border = JBUI.Borders.empty(6, 8, 4, 8)
        pane.isEditable = false
        pane.editorKit = HTMLEditorKitBuilder().withWordWrapViewFactory().build()
        pane.border = JBUI.Borders.empty(4, 8)
        pane.background = UIUtil.getPanelBackground()
        pane.addHyperlinkListener { e ->
            if (e.eventType != HyperlinkEvent.EventType.ACTIVATED) return@addHyperlinkListener
            val href = e.description ?: return@addHyperlinkListener
            if (href.startsWith("anchor:")) {
                href.removePrefix("anchor:").toIntOrNull()?.let { service.goToAnchor(it) }
            } else if (href.startsWith("http://") || href.startsWith("https://")) {
                BrowserUtil.browse(href)
            }
        }
        add(header, BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)
        show(null)
    }

    fun show(open: OpenOverview?) {
        shown = open
        if (open == null) {
            header.text = GgNotesBundle.message("document.none")
            pane.text = ""
            return
        }
        val o = open.overview
        val date = o.updated?.let { DateTimeFormatter.ISO_LOCAL_DATE.format(it.toLocalDate()) } ?: ""
        header.text = "<html><b>${esc(o.title)}</b><br><span style='color:${hex(UIUtil.getContextHelpForeground())}'>" +
            esc(listOf(o.author, date, o.shortSha, o.prNumber?.let { "PR #$it" } ?: o.branch).filter { it.isNotBlank() }.joinToString(" · ")) +
            (if (open.anchors.isNotEmpty()) " · " + GgNotesBundle.message("document.anchors", open.anchors.size) else "") +
            "</span></html>"
        val body = when (val d = o.document) {
            is DocumentResult.CannotRender -> "<body><p class='missing'>" + esc(GgNotesBundle.message("document.cannotRender", d.reason)) + "</p></body>"
            is DocumentResult.Ok -> open.rendered.html
        }
        val keep = scroll.verticalScrollBar.value
        pane.text = "<html><head><style>${css()}</style></head>$body</html>"
        pane.caretPosition = 0
        ApplicationManager.getApplication().invokeLater({ scroll.verticalScrollBar.value = keep }, project.disposed)
    }

    fun scrollToAnchor(n: Int) {
        ApplicationManager.getApplication().invokeLater({
            if (shown != null) pane.scrollToReference("a$n")
        }, project.disposed)
    }

    private fun css(): String {
        val label = JBFont.label()
        val editorFont = EditorColorsManager.getInstance().globalScheme.editorFontName
        val fg = hex(UIUtil.getLabelForeground())
        val link = hex(JBUI.CurrentTheme.Link.Foreground.ENABLED)
        val current = hex(JBColor(0xFFE28A, 0x5C4E1E))
        val missing = hex(UIUtil.getContextHelpForeground())
        val codeBg = hex(JBColor(0xF0F0F0, 0x3B3F44))
        val border = hex(JBColor(0xC8C8C8, 0x555555))
        return """
            body { font-family: '${label.family}'; font-size: ${label.size}pt; color: $fg; }
            h1 { font-size: ${label.size + 5}pt; } h2 { font-size: ${label.size + 3}pt; } h3 { font-size: ${label.size + 1}pt; }
            a { color: $link; text-decoration: none; }
            .current { background-color: $current; color: $fg; }
            .missing { color: $missing; text-decoration: line-through; }
            code { font-family: '$editorFont'; background-color: $codeBg; }
            pre { font-family: '$editorFont'; background-color: $codeBg; padding: 6px; }
            blockquote { border-left: 3px solid $border; margin-left: 4px; padding-left: 8px; color: $missing; }
            table { border-collapse: collapse; } th, td { border: 1px solid $border; padding: 2px 6px; }
            li { margin-bottom: 2px; }
        """.trimIndent()
    }

    private fun hex(c: java.awt.Color) = "#" + ColorUtil.toHex(c)
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
