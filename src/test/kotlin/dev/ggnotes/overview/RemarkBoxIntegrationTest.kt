package dev.ggnotes.overview

import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.JBUI
import dev.ggnotes.store.Remark
import java.awt.Dimension
import java.awt.Rectangle
import java.awt.image.BufferedImage

/**
 * A remark box (an overview remark or a session note) must paint exactly the text it reserved room
 * for. The editor measures the box once, when it is added, often while the editor is still narrow;
 * when the editor then gets wider the box must re-measure, and until it does, it must keep painting
 * the lines it measured: never fewer lines in a tall box (empty space), never longer lines than
 * its width (text over the border). Reported 2026-10-21 with a screenshot of exactly that.
 */
class RemarkBoxIntegrationTest : BasePlatformTestCase() {

    private val rationale = (1..40).joinToString(" ") { "word$it" } + " " +
        "The turn registers a proxy that every child context inherits and the probe reaches the routed provider."

    private fun setViewWidth(editor: EditorEx, width: Int) {
        editor.scrollPane.viewport.extentSize = Dimension(width, 600)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    private fun paint(inlay: com.intellij.openapi.editor.Inlay<*>): Int {
        val box = inlay.renderer as RemarkOverlay.RemarkBox
        val image = BufferedImage(inlay.widthInPixels.coerceAtLeast(1), inlay.heightInPixels.coerceAtLeast(1), BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            box.paint(inlay, g as java.awt.Graphics, Rectangle(0, 0, inlay.widthInPixels, inlay.heightInPixels), TextAttributes())
        } finally {
            g.dispose()
        }
        return box.paintedLines
    }

    /** The lines a box of this height holds (the box's own formula: lines × line height + padding). */
    private fun reservedLines(inlay: com.intellij.openapi.editor.Inlay<*>): Int =
        (inlay.heightInPixels - JBUI.scale(8)) / inlay.editor.lineHeight

    fun testTheBoxPaintsWhatItReservedAndReflowsWhenTheEditorWidens() {
        myFixture.configureByText("a.txt", "one\ntwo\nthree\nfour\n")
        val editor = myFixture.editor as EditorEx
        setViewWidth(editor, 320)
        RemarkOverlay.box(editor, 2, 2, Remark(2, 2, "Capability filtering can send a root-model request.", rationale))
        val inlay = editor.inlayModel.getBlockElementsInRange(0, editor.document.textLength).single()
        val narrowHeight = inlay.heightInPixels
        assertEquals("narrow: the paint fills the box", reservedLines(inlay), paint(inlay))

        // the editor gets wider (the tool window closes, the IDE window is maximised)
        setViewWidth(editor, 1600)
        assertEquals("wide: the paint still fills the box exactly", reservedLines(inlay), paint(inlay))
        assertTrue("wide: the box re-measured to fewer, longer lines ($narrowHeight -> ${inlay.heightInPixels})", inlay.heightInPixels < narrowHeight)
    }
}
