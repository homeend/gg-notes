package dev.ggnotes.items

import dev.ggnotes.store.StoreModel
import dev.ggnotes.store.StoreReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * "Copy Link" on a stored row: one line that names the record and says how an agent reads it with
 * the gg CLI (no gg instance needed), so the user can hand a review to an agent to address.
 */
class StoreRefsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val wt = "/home/dev/src/gate"

    private fun items(): List<NoteItem> {
        val dir = Files.createTempDirectory(tmp.root.toPath(), "store")
        val src = Paths.get("examples/items")
        Files.walk(src).use { paths ->
            paths.forEach { p ->
                val target = dir.resolve(src.relativize(p).toString())
                if (Files.isDirectory(p)) Files.createDirectories(target) else Files.copy(p, target)
            }
        }
        val model = StoreModel.build(StoreReader().read(dir), headSha = null, subjects = emptyMap())
        return NoteItems.build(model, emptyList(), emptyList())
    }

    private fun flat(items: List<NoteItem>): List<NoteItem> = items.flatMap { listOf(it) + flat(it.children) }

    private fun one(pred: (NoteItem) -> Boolean): NoteItem = flat(items()).first(pred)

    @Test
    fun `a review names its id, title, commit and the gg command that reads it`() {
        val r = one { it.source == ItemSource.STORE_REVIEW && it.kind == ItemKind.COMMIT }
        val id = r.id.removePrefix("store:")
        val sha7 = r.owner.commit!!.take(7)
        assertEquals(
            "Agent Notes review $id ${dev.ggnotes.session.SessionDocRefs.quote(r.title)} (worktree $wt; commit $sha7; read: gg note show $id --json)",
            StoreRefs.link(r, wt),
        )
    }

    @Test
    fun `a stored overview is read with gg overview show`() {
        val o = one { it.source == ItemSource.STORE_OVERVIEW && it.kind == ItemKind.COMMIT }
        val id = o.id.removePrefix("store:")
        assertEquals(
            "Agent Notes stored overview $id ${dev.ggnotes.session.SessionDocRefs.quote(o.title)} (worktree $wt; commit ${o.owner.commit!!.take(7)}; read: gg overview show $id --json)",
            StoreRefs.link(o, wt),
        )
    }

    @Test
    fun `a stored note names its place, and a working one says working tree`() {
        val committed = one { it.source == ItemSource.STORE_THREAD && it.owner.commit != null && it.owner.first != null }
        val id = committed.id.removePrefix("store:")
        val at = committed.owner.path + ":" + committed.owner.first +
            (if (committed.owner.last != null && committed.owner.last != committed.owner.first) "-" + committed.owner.last else "")
        assertEquals(
            "Agent Notes stored note $id at $at ${dev.ggnotes.session.SessionDocRefs.quote(committed.title)} (worktree $wt; commit ${committed.owner.commit!!.take(7)}; read: gg note show $id --json)",
            StoreRefs.link(committed, wt),
        )
        val live = one { it.source == ItemSource.STORE_THREAD && it.owner.commit == null }
        val liveId = live.id.removePrefix("store:")
        assertEquals(
            "Agent Notes stored note $liveId at ${live.owner.path}:${live.owner.first} ${dev.ggnotes.session.SessionDocRefs.quote(live.title)} (worktree $wt; working tree; read: gg note show $liveId --json)",
            StoreRefs.link(live, wt),
        )
    }

    @Test
    fun `a working review says working tree`() {
        val w = one { it.source == ItemSource.STORE_REVIEW && it.kind == ItemKind.OTHER }
        val id = w.id.removePrefix("store:")
        assertEquals(
            "Agent Notes review $id ${dev.ggnotes.session.SessionDocRefs.quote(w.title)} (worktree $wt; working tree; read: gg note show $id --json)",
            StoreRefs.link(w, wt),
        )
    }

    @Test
    fun `a session item has no stored link`() {
        val session = NoteItem("x", ItemKind.OTHER, ItemSource.SESSION_OVERVIEW, "t", Owner(), "a", null,
            Payload.SessionOverview(dev.ggnotes.session.DocIndex.Entry(
                (dev.ggnotes.session.SessionDocParser.overview("x", "+++\ntitle = 't'\n+++\nbody\n".toByteArray()) as dev.ggnotes.session.Parsed.Ok).doc,
                0, 0, 0)),
            emptyList())
        assertNull(StoreRefs.link(session, wt))
    }
}
