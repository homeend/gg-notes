package dev.ggnotes.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.OffsetDateTime

/** SPEC-part2 §1.1: every record of the fixture store classified by tags, address and parent_id alone. */
class RecordClassificationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val examples: Path = Paths.get("examples")

    private fun fixtureStore(): Path {
        val dir = tmp.newFolder("store").toPath()
        Files.copy(examples.resolve("commits.toml"), dir.resolve("commits.toml"))
        Files.copy(examples.resolve("previews.toml"), dir.resolve("previews.toml"))
        Files.createDirectories(dir.resolve("worktrees"))
        Files.copy(examples.resolve("worktrees/cc36f09eabab0c81.toml"), dir.resolve("worktrees/cc36f09eabab0c81.toml"))
        return dir
    }

    private fun ids(contents: StoreContents, kind: RecordKind) =
        contents.records.filter { it.kind == kind }.map { it.id }.sorted()

    @Test
    fun `fixture records fall into the kinds and counts of the brief`() {
        val c = StoreReader().read(fixtureStore())
        assertEquals(emptyList<String>(), c.problems)
        assertEquals(listOf("3a9e1c20", "7cf0812f"), ids(c, RecordKind.OVERVIEW))
        assertEquals(listOf("c0ffee01", "c0ffee02"), ids(c, RecordKind.REVIEW))
        assertEquals(listOf("bb220002"), ids(c, RecordKind.WORKING_REVIEW))
        assertEquals(listOf("91f0aa07", "aa110001", "b2c3d4e5", "bb220001"), ids(c, RecordKind.LINE_NOTE))
        assertEquals(listOf("aa110002", "c0ffee03", "c3d4e5f6"), ids(c, RecordKind.REPLY))
        assertEquals(emptyList<String>(), ids(c, RecordKind.UNKNOWN))
        assertEquals("7 in commits.toml + 3 in previews.toml + 2 in the worktree part", 12, c.records.size)
        assertEquals(listOf("aa110001"), c.resolved.map { it.root })
        assertEquals("me", c.resolved.single().by)
        // the part-1 view is unchanged
        assertEquals(listOf("3a9e1c20", "7cf0812f"), c.overviews.map { it.id }.sorted())
    }

    @Test
    fun `line notes know whether they are live or scoped and which part they came from`() {
        val c = StoreReader().read(fixtureStore())
        val live = c.records.single { it.id == "bb220001" }
        assertTrue(live.isLive)
        assertEquals(3, live.address.state)
        assertEquals("/home/dev/src/gate", live.address.worktree)
        assertEquals("cc36f09eabab0c81.toml", live.sourceFile.fileName.toString())
        assertEquals(6, live.first)
        assertEquals(6, live.last)
        val committed = c.records.single { it.id == "aa110001" }
        assertTrue(!committed.isLive && !committed.scoped)
        assertEquals("internal/x/gate.go", committed.address.path)
        assertEquals("b1bc9c91a8cc51f3b0cd715540473b6191cdc8efcc69f33bf890d9de4affed84", committed.contextHash)
    }

    @Test
    fun `record level meta display and added are read with their exact spellings`() {
        val c = StoreReader().read(fixtureStore())
        val n = c.records.single { it.id == "aa110001" }
        assertEquals(listOf(MetaEntry("kind", "question")), n.meta)
        val decl = n.display.getValue("kind")
        assertEquals(listOf("badge"), decl.show)
        assertEquals("blue", decl.values.getValue("question").color)
        assertEquals("?", decl.values.getValue("question").glyph)
        val added = n.added.single()
        assertEquals("status", added.key)
        assertEquals("agreed", added.value)
        assertEquals("ada", added.by)
        assertEquals(OffsetDateTime.parse("2026-10-14T10:05:00Z"), added.at)
        val reply = c.records.single { it.id == "aa110002" }
        assertEquals("aa110001", reply.parentId)
        assertEquals("gg://gate/internal/x/gate.go@d5efa035df1f3c4c949040bebb924a35d0ea61a0:3", reply.link)
    }

    @Test
    fun `review records carry scope preview and remark reply fields`() {
        val c = StoreReader().read(fixtureStore())
        val pr = c.records.single { it.id == "c0ffee02" }
        assertEquals("main...refs/gg/pr/7", pr.preview)
        assertEquals(7, pr.prNumber)
        assertEquals("0a0b0c0d0e0f1a1b2c3d4e5f6a7b8c9d0e1f2a3b..e4a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3", pr.scope)
        val reply = c.records.single { it.id == "c0ffee03" }
        assertEquals("review:c0ffee02:0", reply.remark)
        assertEquals("c647680d796166ee", reply.remarkFp)
        assertEquals("Name the parameter.", reply.remarkSummary)
        val working = c.records.single { it.id == "bb220002" }
        assertEquals(listOf(ReviewedFile("internal/x/gate.go", "5d2a7c1e9b3f4a6d8c0e2f1a3b5c7d9e1f3a5b7c", false)), working.files)
    }

    @Test
    fun `legacy tags and confidence become meta on read, unscoped worktree part is ignored, notes toml is read`() {
        val dir = tmp.newFolder("legacy").toPath()
        Files.writeString(dir.resolve("notes.toml"), """
            [[notes]]
            id = 'a1a1a1a1'
            source = 'agent'
            author = 'old gg'
            side = 'new'
            range = [3, 3]
            context_hash = ''
            summary = 'legacy note'
            tags = ['severity: high', 'review', 'not a meta']
            confidence = 0.9
            created = 2026-09-01T08:00:00Z
            updated = 2026-09-01T08:00:00Z
            [notes.address]
            Commit = 'd5efa035df1f3c4c949040bebb924a35d0ea61a0'
            Path = 'a.go'
            State = 0

            [[notes]]
            id = 'f0f0f0f0'
            parent_id = 'forge:123456'
            source = 'user'
            side = 'new'
            range = [10, 12]
            context_hash = ''
            summary = 'a draft reply to a GitHub thread'
            created = 2026-09-02T08:00:00Z
            updated = 2026-09-02T08:00:00Z
            [notes.address]
            Commit = 'e4a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3'
            Path = 'b.go'
            State = 0
        """.trimIndent())
        Files.createDirectories(dir.resolve("worktrees"))
        Files.writeString(dir.resolve("worktrees/unscoped.toml"), """
            format = 3
            [[notes]]
            id = 'bad00001'
            side = 'new'
            range = [1, 1]
            summary = 'no worktree recorded'
            [notes.address]
            Worktree = ''
            Commit = ''
            Path = 'a.go'
            State = 3
        """.trimIndent())
        val c = StoreReader().read(dir)
        assertEquals(emptyList<String>(), c.problems)
        val legacy = c.records.single { it.id == "a1a1a1a1" }
        assertEquals(RecordKind.LINE_NOTE, legacy.kind)
        assertEquals(listOf("review", "not a meta"), legacy.tags)
        assertEquals(listOf(MetaEntry("severity", "high"), MetaEntry("confidence", "high")), legacy.meta)
        val draft = c.records.single { it.id == "f0f0f0f0" }
        assertEquals(RecordKind.FORGE_DRAFT, draft.kind)
        assertEquals("forge:123456", draft.parentId)
        assertNull(c.records.firstOrNull { it.id == "bad00001" })
    }

    @Test
    fun `confidence numbers map to words or their shortest decimal`() {
        assertEquals("low", LegacyMeta.confidenceWord(0.3))
        assertEquals("medium", LegacyMeta.confidenceWord(0.6))
        assertEquals("high", LegacyMeta.confidenceWord(0.9))
        assertEquals("0.75", LegacyMeta.confidenceWord(0.75))
        assertEquals("1", LegacyMeta.confidenceWord(1.0))
        assertNull(LegacyMeta.confidenceWord(0.0))
    }
}
