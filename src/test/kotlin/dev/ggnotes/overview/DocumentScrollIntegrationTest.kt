package dev.ggnotes.overview

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import dev.ggnotes.store.DocumentResult
import dev.ggnotes.store.MarkdownRenderer
import dev.ggnotes.store.Overview
import dev.ggnotes.store.OverviewDocument
import git4idea.repo.GitRepository
import java.awt.Container
import java.awt.Point
import java.nio.file.Path
import javax.swing.JScrollPane

/**
 * A click on an anchor in a long overview re-renders the document (the clicked anchor becomes
 * the current one) and opens the file; the overview must stay where the user scrolled it.
 * Reported 2026-10-29: every click put the overview back at its top. The cause: the re-render
 * moved the caret to 0 and Swing's caret scrolled to it later, racing the scroll restore.
 */
class DocumentScrollIntegrationTest : BasePlatformTestCase() {

    // 60 paragraphs, one anchor each: far taller than the viewport
    private fun markdown(tag: String) = (1..60).joinToString("\n\n") { i ->
        "$tag paragraph $i explains one step of the change in a few words. See [step $i](src/F$i.kt:$i)."
    }

    private fun overview(id: String, markdown: String): Overview = Overview(
        id = id, title = "Long $id", author = "a", commit = "0123456789abcdef0123456789abcdef01234567", branch = "",
        preview = null, prNumber = null, created = null, updated = null,
        document = DocumentResult.Ok(OverviewDocument("Long $id", markdown, emptyList())), sourceFile = Path.of("commits.toml"),
    )

    /** The pane reads the overview and its rendering and compares repositories; nothing else of the repository. */
    private val repo: GitRepository = java.lang.reflect.Proxy.newProxyInstance(
        GitRepository::class.java.classLoader, arrayOf(GitRepository::class.java),
    ) { self, method, args ->
        when (method.name) {
            "equals" -> self === args?.get(0)
            "hashCode" -> System.identityHashCode(self)
            "toString" -> "repo"
            else -> throw UnsupportedOperationException(method.name)
        }
    } as GitRepository

    /** Headless: no peer validates the tree, so lay it out by hand, as a shown window would. */
    private fun layout(c: Container) {
        c.doLayout()
        for (child in c.components) if (child is Container) layout(child)
    }

    private class Fixture(val pane: DocumentPane, val scroll: JScrollPane, val open: OpenOverview, val markdown: String)

    private fun shownAndScrolled(): Fixture {
        val pane = DocumentPane(project, OverviewService.getInstance(project))
        pane.setSize(400, 300)
        val md = markdown("First")
        val open = OpenOverview(overview("o1", md), repo, MarkdownRenderer.render(md))
        pane.show(open)
        layout(pane)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        val scroll = UIUtil.findComponentOfType(pane, JScrollPane::class.java)!!
        assertTrue("the document is longer than the viewport", scroll.verticalScrollBar.maximum > 1500)
        scroll.viewport.viewPosition = Point(0, 1200)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals(1200, scroll.verticalScrollBar.value)
        return Fixture(pane, scroll, open, md)
    }

    fun testAnAnchorClickNeverMovesTheOverviewToItsTop() {
        val f = shownAndScrolled()
        // every value the scroll bar takes while the click is processed: none may be the top
        val seen = ArrayList<Int>()
        f.scroll.verticalScrollBar.model.addChangeListener { seen += f.scroll.verticalScrollBar.value }

        // what OverviewService.goToAnchor does: re-render with the current anchor and show it again
        f.open.currentAnchor = 30
        f.open.rendered = MarkdownRenderer.render(f.markdown, 30)
        f.pane.show(f.open)
        layout(f.pane)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertTrue("the overview never jumps to its top: $seen", seen.none { it < 1200 })
        assertEquals("the overview stays where the user scrolled it", 1200, f.scroll.verticalScrollBar.value)
    }

    fun testAnotherDocumentOpensAtItsTop() {
        val f = shownAndScrolled()
        val md = markdown("Second")
        f.pane.show(OpenOverview(overview("o2", md), repo, MarkdownRenderer.render(md)))
        layout(f.pane)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals("a different document starts at its top", 0, f.scroll.verticalScrollBar.value)
    }
}
