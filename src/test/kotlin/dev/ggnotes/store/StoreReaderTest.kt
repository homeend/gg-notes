package dev.ggnotes.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.OffsetDateTime

/** SPEC §4–§5 and §8.2 against the example stores in `examples/`. */
class StoreReaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val examples: Path = Paths.get("examples")

    private fun fixtureStore(): Path {
        val dir = tmp.newFolder("store").toPath()
        Files.copy(examples.resolve("commits.toml"), dir.resolve("commits.toml"))
        Files.copy(examples.resolve("previews.toml"), dir.resolve("previews.toml"))
        return dir
    }

    @Test
    fun `fixture store yields exactly the two overviews`() {
        val contents = StoreReader().read(fixtureStore())
        assertEquals(listOf("How the gate works", "PR 7: the gate, reviewed"),
            contents.overviews.map { it.title }.sorted())
        assertEquals(emptyList<String>(), contents.problems)
        assertFalse(contents.newerFormat)
    }

    @Test
    fun `the three non-overview fixture records are skipped`() {
        val ids = StoreReader().read(fixtureStore()).overviews.map { it.id }.toSet()
        assertEquals(setOf("7cf0812f", "3a9e1c20"), ids)
    }

    @Test
    fun `commit overview carries record fields and the decoded document`() {
        val o = StoreReader().read(fixtureStore()).overviews.single { it.id == "7cf0812f" }
        assertEquals("Claude Opus 5.5", o.author)
        assertEquals("d5efa035df1f3c4c949040bebb924a35d0ea61a0", o.commit)
        assertEquals("main", o.branch)
        assertNull(o.prNumber)
        assertEquals(OffsetDateTime.parse("2026-10-13T23:53:56.742993129Z"), o.updated)
        assertEquals("commits.toml", o.sourceFile.fileName.toString())
        val doc = (o.document as DocumentResult.Ok).document
        assertEquals("How the gate works", doc.title)
        assertTrue(doc.overview.startsWith("The gate is one function."))
        assertEquals(listOf("internal/x/gate.go", "internal/y/call.go"), doc.files.map { it.path })
        val gate = doc.files[0].annotations.single()
        assertEquals(3, gate.first)
        assertEquals(4, gate.last)
        assertEquals("The refusal.", gate.summary)
        assertEquals("A closed door must not pass.", gate.rationale)
        assertNull(doc.files[1].annotations.single().rationale)
    }

    @Test
    fun `PR overview is read from previews toml with its PR number`() {
        val o = StoreReader().read(fixtureStore()).overviews.single { it.id == "3a9e1c20" }
        assertEquals(7, o.prNumber)
        assertEquals("main...refs/gg/pr/7", o.preview)
        assertEquals("previews.toml", o.sourceFile.fileName.toString())
        assertEquals(emptyList<FileRemarks>(), (o.document as DocumentResult.Ok).document.files)
    }

    @Test
    fun `real gitfocus record is one overview with three remark files`() {
        val dir = tmp.newFolder("real").toPath()
        Files.copy(examples.resolve("gitfocus-real/commits.toml"), dir.resolve("commits.toml"))
        val contents = StoreReader().read(dir)
        val o = contents.overviews.single()
        assertEquals("GitFocus tab: branch-first rows", o.title)
        assertEquals("Claude Opus 5.5", o.author)
        assertEquals("d5efa035df1f3c4c949040bebb924a35d0ea61a0", o.commit)
        val doc = (o.document as DocumentResult.Ok).document
        assertEquals(3, doc.files.size)
        assertTrue(doc.files.all { it.annotations.isNotEmpty() })
    }

    @Test
    fun `missing store directory or files mean no overviews`() {
        val missing = tmp.root.toPath().resolve("nowhere")
        val contents = StoreReader().read(missing)
        assertEquals(emptyList<Overview>(), contents.overviews)
        assertEquals(emptyList<String>(), contents.problems)
    }

    @Test
    fun `an overview whose document is not JSON is listed as cannot render`() {
        val dir = tmp.newFolder("bad").toPath()
        Files.writeString(dir.resolve("commits.toml"), """
            format = 3

            [[notes]]
            id = 'deadbeef'
            author = 'ada'
            summary = 'Broken one'
            rationale = 'not json at all'
            tags = ['overview']
            created = 2026-10-14T08:00:00Z
            updated = 2026-10-14T08:00:00Z

            [notes.address]
            Commit = 'e4a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3'
            Path = ''
            State = 0
        """.trimIndent())
        val o = StoreReader().read(dir).overviews.single()
        assertEquals("Broken one", o.title)
        assertEquals("ada", o.author)
        assertTrue(o.document is DocumentResult.CannotRender)
    }

    @Test
    fun `a document with another version is cannot render`() {
        val dir = tmp.newFolder("v2").toPath()
        Files.writeString(dir.resolve("commits.toml"), """
            format = 3

            [[notes]]
            id = 'deadbeef'
            author = 'ada'
            summary = 'Future doc'
            rationale = '{"version": 2, "title": "Future doc", "overview": "x"}'
            tags = ['overview']
            created = 2026-10-14T08:00:00Z
            updated = 2026-10-14T08:00:00Z

            [notes.address]
            Commit = 'e4a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3'
            Path = ''
            State = 0
        """.trimIndent())
        assertTrue(StoreReader().read(dir).overviews.single().document is DocumentResult.CannotRender)
    }

    @Test
    fun `a newer file format is read but flagged`() {
        val dir = tmp.newFolder("newer").toPath()
        Files.writeString(dir.resolve("commits.toml"), """
            format = 4
            [[notes]]
            id = 'deadbeef'
            author = 'ada'
            summary = 'From the future'
            rationale = '{"version": 1, "title": "From the future", "overview": "x"}'
            tags = ['overview']
            created = 2026-10-14T08:00:00Z
            updated = 2026-10-14T08:00:00Z
            [notes.address]
            Commit = 'e4a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3'
            Path = ''
            State = 0
        """.trimIndent())
        val contents = StoreReader().read(dir)
        assertEquals(1, contents.overviews.size)
        assertTrue(contents.newerFormat)
    }

    @Test
    fun `lock temp corrupt and migrated files are ignored`() {
        val dir = fixtureStore()
        Files.writeString(dir.resolve("commits.toml.lock"), "garbage")
        Files.writeString(dir.resolve(".notes-123.tmp"), "garbage")
        Files.writeString(dir.resolve("commits.toml.corrupt-1700000000"), "garbage")
        Files.writeString(dir.resolve("notes.toml.migrated-1700000000"), "garbage")
        Files.writeString(dir.resolve("shelf.toml"), "format = 3\n")
        val contents = StoreReader().read(dir)
        assertEquals(2, contents.overviews.size)
        assertEquals(emptyList<String>(), contents.problems)
    }

    @Test
    fun `an unparsable toml file is reported as a problem not a crash`() {
        val dir = tmp.newFolder("corrupt").toPath()
        Files.writeString(dir.resolve("commits.toml"), "format = 3\n[[notes]\nbroken")
        val contents = StoreReader().read(dir)
        assertEquals(emptyList<Overview>(), contents.overviews)
        assertEquals(1, contents.problems.size)
    }
}
