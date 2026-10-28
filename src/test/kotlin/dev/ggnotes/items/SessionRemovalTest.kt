package dev.ggnotes.items

import dev.ggnotes.session.DocIndex
import dev.ggnotes.session.Parsed
import dev.ggnotes.session.SessionDocParser
import dev.ggnotes.session.SessionNoteDoc
import dev.ggnotes.session.SessionOverviewDoc
import dev.ggnotes.store.RepoModel
import dev.ggnotes.store.StoreModel
import dev.ggnotes.store.StoreReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Paths

/** What deleting a session item unlinks: the overview and the notes it links, or a lone note. */
class SessionRemovalTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun overview(id: String, links: String): DocIndex.Entry<SessionOverviewDoc> {
        val text = "+++\ntitle = 'Walk'\n+++\ntext $links\n"
        return DocIndex.Entry((SessionDocParser.overview(id, text.toByteArray()) as Parsed.Ok).doc, size = 0, mtime = 0, time = 1)
    }

    private fun note(id: String): DocIndex.Entry<SessionNoteDoc> {
        val text = "path = 'x.go'\nstart = 2\nend = 3\nsummary = 'sn'\nlines = ['l1', 'l2']\n"
        return DocIndex.Entry((SessionDocParser.note(id, text.toByteArray()) as Parsed.Ok).doc, size = 0, mtime = 0, time = 2)
    }

    private fun tops(o: List<DocIndex.Entry<SessionOverviewDoc>>, n: List<DocIndex.Entry<SessionNoteDoc>>): List<NoteItem> {
        val dir = Files.createTempDirectory(tmp.root.toPath(), "store")
        Files.copy(Paths.get("examples/items/commits.toml"), dir.resolve("commits.toml"))
        Files.copy(Paths.get("examples/items/previews.toml"), dir.resolve("previews.toml"))
        val model: RepoModel = StoreModel.build(StoreReader().read(dir), headSha = null, subjects = emptyMap())
        return NoteItems.build(model, o, n)
    }

    @Test
    fun `an overview with two linked notes`() {
        val all = tops(listOf(overview("O1", "[a](note:n1) [b](note:n2)")), listOf(note("n1"), note("n2")))
        val o = all.single { it.source == ItemSource.SESSION_OVERVIEW }
        assertEquals(SessionRemoval(listOf("O1"), listOf("n1", "n2")), SessionRemoval.of(o))
    }

    @Test
    fun `a lone note`() {
        val all = tops(emptyList(), listOf(note("n2")))
        assertEquals(SessionRemoval(emptyList(), listOf("n2")), SessionRemoval.of(all.single { it.source == ItemSource.SESSION_NOTE }))
    }

    @Test
    fun `a store item gives null`() {
        assertNull(SessionRemoval.of(tops(emptyList(), emptyList()).first()))
    }
}
