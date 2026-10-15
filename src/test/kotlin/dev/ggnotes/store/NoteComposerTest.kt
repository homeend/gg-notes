package dev.ggnotes.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** SPEC-part2 §8 line-note rows opened: the file's threads as a document with anchors, boxes, replies and marks. */
class NoteComposerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val examples: Path = Paths.get("examples")

    private fun fixture(): StoreContents {
        val dir = Files.createTempDirectory(tmp.root.toPath(), "store")
        Files.copy(examples.resolve("commits.toml"), dir.resolve("commits.toml"))
        Files.copy(examples.resolve("previews.toml"), dir.resolve("previews.toml"))
        Files.createDirectories(dir.resolve("worktrees"))
        Files.copy(examples.resolve("worktrees/cc36f09eabab0c81.toml"), dir.resolve("worktrees/cc36f09eabab0c81.toml"))
        return StoreReader().read(dir)
    }

    private fun gateThreads(): List<NoteThread> {
        val model = StoreModel.build(fixture(), null, emptyMap())
        return model.commits.single { it.commit.startsWith("d5efa035") }.files.single().threads
    }

    @Test
    fun `threads of a file become anchors with boxes, replies and the resolution mark`() {
        val threads = gateThreads()
        val placements = threads.associate { it.root.id to Relocation(RelocationStatus.ACTIVE, it.root.first, it.root.last) }
        val doc = NoteComposer.compose("internal/x/gate.go", threads, placements)
        assertEquals("internal/x/gate.go · 2 notes", doc.title)
        val rendered = MarkdownRenderer.render(doc.overview)
        assertEquals(listOf(LinkTarget.Anchor("internal/x/gate.go", 2, 2), LinkTarget.Anchor("internal/x/gate.go", 3, 4)), rendered.anchors.map { it.target })
        assertEquals(listOf("Tagged overview but a FILE note", "Why not return early?"), rendered.anchors.map { it.label })
        assertTrue(doc.overview, doc.overview.contains("Agreed; an early return reads better."))
        assertTrue(doc.overview, doc.overview.contains("resolved"))
        assertTrue(doc.overview, doc.overview.contains("kind: question"))
        assertTrue("added meta replaces the author's for display", doc.overview.contains("status: agreed"))
        val boxes = doc.files.single().annotations
        assertEquals(listOf(2 to 2, 3 to 4), boxes.map { it.first to it.last })
        assertTrue(boxes[1].rationale!!.contains("ada"))
    }

    @Test
    fun `stale and orphaned placements are marked and orphaned ones are not placed`() {
        val threads = gateThreads()
        val question = threads.single { it.root.id == "aa110001" }
        val stale = NoteComposer.compose("internal/x/gate.go", listOf(question), mapOf("aa110001" to Relocation(RelocationStatus.STALE, 3, 4)))
        assertTrue(stale.overview, stale.overview.contains("not found"))
        assertEquals(1, stale.files.single().annotations.size)
        val orphaned = NoteComposer.compose("internal/x/gate.go", listOf(question), mapOf("aa110001" to Relocation(RelocationStatus.ORPHANED, 3, 4)))
        assertEquals(emptyList<FileRemarks>(), orphaned.files)
        assertEquals(0, MarkdownRenderer.render(orphaned.overview).anchors.size)
        assertTrue(orphaned.overview, orphaned.overview.contains("gone"))
    }

    @Test
    fun `a relocated live note anchors at its new lines`() {
        val threads = gateThreads()
        val question = threads.single { it.root.id == "aa110001" }
        val moved = NoteComposer.compose("internal/x/gate.go", listOf(question), mapOf("aa110001" to Relocation(RelocationStatus.ACTIVE, 7, 8)))
        assertEquals(listOf(LinkTarget.Anchor("internal/x/gate.go", 7, 8)), MarkdownRenderer.render(moved.overview).anchors.map { it.target })
        assertEquals("Why not return early?", moved.title)
    }

    @Test
    fun `a line note older than the sweep age is marked as one gg will drop`() {
        val threads = gateThreads()
        val question = threads.single { it.root.id == "aa110001" } // created 2026-10-14
        val placements = mapOf("aa110001" to Relocation(RelocationStatus.ACTIVE, 3, 4))
        val fresh = NoteComposer.compose("internal/x/gate.go", listOf(question), placements, now = java.time.OffsetDateTime.parse("2026-10-20T00:00:00Z"))
        assertFalse(fresh.overview, fresh.overview.contains("next start"))
        val old = NoteComposer.compose("internal/x/gate.go", listOf(question), placements, now = java.time.OffsetDateTime.parse("2026-12-01T00:00:00Z"))
        assertTrue(old.overview, old.overview.contains("gg drops it on its next start"))
        assertTrue(NoteComposer.sweepDue(question.root, java.time.OffsetDateTime.parse("2026-12-01T00:00:00Z")))
    }

    @Test
    fun `old-side notes and forge drafts are listed but not placed`() {
        val threads = gateThreads()
        val question = threads.single { it.root.id == "aa110001" }
        val old = NoteThread(question.root.copy(side = "old"), emptyList(), null, false)
        val draft = NoteThread(question.root.copy(id = "f0f0f0f0", parentId = "forge:9", kind = RecordKind.FORGE_DRAFT, summary = "my draft"), emptyList(), null, true)
        val doc = NoteComposer.compose("internal/x/gate.go", listOf(old, draft), mapOf("aa110001" to Relocation(RelocationStatus.ACTIVE, 3, 4), "f0f0f0f0" to Relocation(RelocationStatus.ACTIVE, 3, 4)))
        assertEquals(emptyList<FileRemarks>(), doc.files)
        assertTrue(doc.overview, doc.overview.contains("old"))
        assertTrue(doc.overview, doc.overview.contains("draft"))
        assertFalse(doc.overview.contains("](internal"))
    }
}
