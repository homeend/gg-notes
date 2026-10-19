package dev.ggnotes.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

/** SPEC §4.2: the directory listed and reconciled by id — new, replaced (size or mtime), gone, unchanged not re-read. */
class DocIndexTest {

    private fun overview(title: String, created: String? = null, body: String = "text [a](x.go:1)") =
        "+++\ntitle = '$title'\n" + (created?.let { "created = $it\n" } ?: "") + "+++\n$body\n"

    private fun note(path: String = "x.go", start: Int = 1) =
        "path = '$path'\nstart = $start\nend = $start\nsummary = 'n'\nlines = ['l']\n"

    private fun drop(dir: Path, name: String, text: String, mtime: Long? = null) {
        val tmp = dir.resolve(".$name.tmp")
        Files.writeString(tmp, text)
        Files.move(tmp, dir.resolve(name), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        if (mtime != null) Files.setLastModifiedTime(dir.resolve(name), FileTime.fromMillis(mtime))
    }

    @Test
    fun `a missing directory lists nothing and the ignore rules apply`() {
        val dir = Files.createTempDirectory("ggnotes-docs")
        assertEquals(emptyList<DocDirectory.Listed>(), DocDirectory.list(dir.resolve("docs")))
        drop(dir, "a.md", overview("A"))
        drop(dir, "b.toml", note())
        Files.writeString(dir.resolve(".hidden.md"), overview("H"))
        Files.writeString(dir.resolve("c.md.tmp"), overview("T"))
        Files.writeString(dir.resolve("d.txt"), "x")
        Files.writeString(dir.resolve("-e.md"), overview("E"))
        Files.createDirectories(dir.resolve("sub.md"))
        Files.writeString(dir.resolve("big.md"), overview("Big", body = "x".repeat(600 * 1024)))
        assertEquals(listOf(DocName("a", DocKind.OVERVIEW), DocName("b", DocKind.NOTE)), DocDirectory.list(dir).map { it.name })
    }

    @Test
    fun `reconcile adds replaces and removes by id and re-reads only a changed stamp`() {
        val dir = Files.createTempDirectory("ggnotes-docs")
        val index = DocIndex()
        var reads = 0
        fun sync(): DocIndex.Changes = index.reconcile(DocDirectory.list(dir)) { reads++; Files.readAllBytes(it.file) }

        assertTrue(sync().isEmpty)
        drop(dir, "20261018T143201-9f3a.md", overview("First", "2026-10-18T14:32:01Z"), mtime = 1_000_000)
        drop(dir, "n1.toml", note("x.go", 3), mtime = 1_000_000)
        drop(dir, "bad.toml", "path = 'x.go'\nstart = 1\n", mtime = 1_000_000)
        val first = sync()
        assertEquals(listOf("20261018T143201-9f3a", "n1"), first.added.map { it.id })
        assertEquals("the unreadable entry is not a document", null, index.note("bad"))
        assertEquals(3, reads)
        assertEquals("First", index.overview("20261018T143201-9f3a")!!.doc.title)
        assertEquals(3, index.note("n1")!!.doc.start)

        // unchanged stamps: nothing re-read, nothing reported (the bad one included)
        assertTrue(sync().isEmpty)
        assertEquals(3, reads)

        // a replacement with a new mtime is re-read and REPLACED in place
        drop(dir, "20261018T143201-9f3a.md", overview("Second", "2026-10-18T14:32:01Z"), mtime = 2_000_000)
        val second = sync()
        assertEquals(listOf("20261018T143201-9f3a"), second.replaced.map { it.id })
        assertTrue(second.added.isEmpty() && second.removed.isEmpty())
        assertEquals("Second", index.overview("20261018T143201-9f3a")!!.doc.title)
        assertEquals(4, reads)

        // a replacement that no longer parses drops the document
        drop(dir, "n1.toml", "garbage = \n", mtime = 2_000_000)
        assertEquals(listOf("n1"), sync().removed.map { it.id })
        assertNull(index.note("n1"))

        // gone
        Files.delete(dir.resolve("20261018T143201-9f3a.md"))
        val gone = sync()
        assertEquals(listOf("20261018T143201-9f3a"), gone.removed.map { it.id })
        assertEquals(emptyList<String>(), index.overviews().map { it.doc.id })
    }

    @Test
    fun `overviews come newest first by created then id, the file time standing in for a missing created`() {
        val dir = Files.createTempDirectory("ggnotes-docs")
        val index = DocIndex()
        drop(dir, "old.md", overview("Old", "2026-10-18T10:00:00Z"), mtime = 9_000_000_000)
        drop(dir, "new.md", overview("New", "2026-10-18T12:00:00Z"), mtime = 1_000)
        drop(dir, "b-same.md", overview("B", "2026-10-18T11:00:00Z"), mtime = 1_000)
        drop(dir, "a-same.md", overview("A", "2026-10-18T11:00:00Z"), mtime = 1_000)
        drop(dir, "mtime.md", overview("Mtime"), mtime = 1_729_000_000_000) // 2024-10-15 by file time: older than the dated ones
        index.reconcile(DocDirectory.list(dir)) { Files.readAllBytes(it.file) }
        assertEquals(listOf("new", "a-same", "b-same", "old", "mtime"), index.overviews().map { it.doc.id })
        assertEquals(1_729_000_000_000, index.overview("mtime")!!.time)
        assertEquals(1, index.overview("mtime")!!.doc.anchors.size)
    }

    @Test
    fun `notes of one path`() {
        val dir = Files.createTempDirectory("ggnotes-docs")
        val index = DocIndex()
        drop(dir, "n2.toml", note("a.go", 5))
        drop(dir, "n1.toml", note("a.go", 2))
        drop(dir, "n3.toml", note("b.go", 1))
        index.reconcile(DocDirectory.list(dir)) { Files.readAllBytes(it.file) }
        assertEquals(listOf("n1", "n2"), index.notesOn("a.go").map { it.doc.id })
        assertEquals(listOf("n3"), index.notesOn("b.go").map { it.doc.id })
        assertEquals(3, index.notes().size)
    }
}
