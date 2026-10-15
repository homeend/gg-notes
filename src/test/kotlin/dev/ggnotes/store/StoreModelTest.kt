package dev.ggnotes.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** SPEC-part2 §8: what sits under each commit, each review row and each worktree in the tool window. */
class StoreModelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val examples: Path = Paths.get("examples")
    private val gate = "d5efa035df1f3c4c949040bebb924a35d0ea61a0"
    private val prHead = "e4a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3"

    private fun fixture(): StoreContents {
        val dir = Files.createTempDirectory(tmp.root.toPath(), "store")
        Files.copy(examples.resolve("commits.toml"), dir.resolve("commits.toml"))
        Files.copy(examples.resolve("previews.toml"), dir.resolve("previews.toml"))
        Files.createDirectories(dir.resolve("worktrees"))
        Files.copy(examples.resolve("worktrees/cc36f09eabab0c81.toml"), dir.resolve("worktrees/cc36f09eabab0c81.toml"))
        return StoreReader().read(dir)
    }

    @Test
    fun `commits carry their overviews and reviews, HEAD first`() {
        val model = StoreModel.build(fixture(), headSha = prHead, subjects = mapOf(gate to "Add the gate"))
        assertEquals(listOf(prHead, gate), model.commits.map { it.commit })
        val pr = model.commits[0]
        assertTrue(pr.isHead)
        assertEquals(listOf("3a9e1c20"), pr.overviews.map { it.id })
        assertEquals(listOf("c0ffee02"), pr.reviews.map { it.id })
        assertEquals(7, pr.reviews.single().record.prNumber)
        val commit = model.commits[1]
        assertEquals("Add the gate", commit.subject)
        assertEquals(listOf("7cf0812f"), commit.overviews.map { it.id })
        assertEquals(listOf("c0ffee01"), commit.reviews.map { it.id })
    }

    @Test
    fun `line notes group under their commit's files as threads with replies and resolutions`() {
        val model = StoreModel.build(fixture(), headSha = null, subjects = emptyMap())
        val commit = model.commits.single { it.commit == gate }
        val file = commit.files.single()
        assertEquals("internal/x/gate.go", file.path)
        assertEquals("in line order: line 2, then lines 3-4", listOf("b2c3d4e5", "aa110001"), file.threads.map { it.root.id })
        val question = file.threads.single { it.root.id == "aa110001" }
        assertEquals(listOf("aa110002"), question.replies.map { it.id })
        assertNotNull(question.resolution)
        assertEquals("me", question.resolution?.by)
        assertNull(file.threads.single { it.root.id == "b2c3d4e5" }.resolution)
        val prFile = model.commits.single { it.commit == prHead }.files.single()
        assertEquals(listOf("91f0aa07"), prFile.threads.map { it.root.id })
    }

    @Test
    fun `overview replies thread under their overview`() {
        val model = StoreModel.build(fixture(), headSha = null, subjects = emptyMap())
        val commit = model.commits.single { it.commit == gate }
        assertEquals(listOf("c3d4e5f6"), commit.overviewReplies.getValue("7cf0812f").map { it.id })
    }

    @Test
    fun `live records form a working entry per worktree`() {
        val model = StoreModel.build(fixture(), headSha = null, subjects = emptyMap())
        val working = model.working.single()
        assertEquals("/home/dev/src/gate", working.worktree)
        assertEquals(listOf("bb220002"), working.reviews.map { it.id })
        assertEquals(listOf("bb220001"), working.files.single().threads.map { it.root.id })
        assertEquals("internal/x/gate.go", working.files.single().path)
    }

    @Test
    fun `a scoped note sits under its review row and not under the commit's files`() {
        val c = fixture()
        val scoped = c.records.single { it.id == "91f0aa07" }.copy(preview = "main...refs/gg/pr/7")
        val contents = c.copy(records = c.records.map { if (it.id == "91f0aa07") scoped else it })
        val model = StoreModel.build(contents, headSha = null, subjects = emptyMap())
        val pr = model.commits.single { it.commit == prHead }
        assertEquals(emptyList<FileEntry>(), pr.files)
        assertEquals(listOf("91f0aa07"), pr.reviews.single().scopedNotes.map { it.id })
    }

    @Test
    fun `a forge draft reply is a thread of its own marked as a draft`() {
        val c = fixture()
        val draft = c.records.single { it.id == "aa110002" }.copy(id = "f0f0f0f0", parentId = "forge:42", kind = RecordKind.FORGE_DRAFT)
        val model = StoreModel.build(c.copy(records = c.records + draft), headSha = null, subjects = emptyMap())
        val file = model.commits.single { it.commit == gate }.files.single()
        val thread = file.threads.single { it.root.id == "f0f0f0f0" }
        assertTrue(thread.forgeDraft)
    }
}
