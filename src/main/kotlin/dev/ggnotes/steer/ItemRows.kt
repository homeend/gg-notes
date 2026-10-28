package dev.ggnotes.steer

import dev.ggnotes.items.ItemSource
import dev.ggnotes.items.ItemViews
import dev.ggnotes.items.NoteItem
import dev.ggnotes.items.Payload
import dev.ggnotes.session.SessionDocRefs
import dev.ggnotes.store.Overview
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** The `files` rows of the note items, lookup by id and the reply text of `file_focus` (pure). */
object ItemRows {

    /** The `files` rows of [items] after today's rows: every item and child except session overviews (Rulings 1–3). */
    fun rows(items: List<NoteItem>, open: Overview?): List<OpenFileWire> {
        val tops = ItemViews.byDate(items, ZoneOffset.UTC).flatMap { it.items }
        val out = ArrayList<OpenFileWire>()
        fun add(item: NoteItem) {
            if (item.source != ItemSource.SESSION_OVERVIEW) out += row(item, open)
            item.children.forEach(::add)
        }
        tops.forEach(::add)
        return out
    }

    private fun row(item: NoteItem, open: Overview?): OpenFileWire {
        val o = item.owner
        return OpenFileWire(
            id = item.id,
            path = o.path.orEmpty(),
            source = item.source.wire,
            title = item.title,
            line = o.first ?: 0,
            state = if (isShown(item, open)) "shown" else "background",
            author = item.author.ifEmpty { null },
            created = item.time?.let { DateTimeFormatter.ISO_INSTANT.format(it) },
            kind = item.kind.wire,
            owner = OwnerWire(commit = o.commit, branch = o.branch, preview = o.preview, path = o.path),
        )
    }

    /** The item or child with [id], depth first; null when none. */
    fun find(items: List<NoteItem>, id: String): NoteItem? {
        for (i in items) {
            if (i.id == id) return i
            find(i.children, id)?.let { return it }
        }
        return null
    }

    /** Whether the open document [open] is [item]'s record: overview identity, `review:<id>:`, `notes:<root id>:`; never for a session item. */
    fun isShown(item: NoteItem, open: Overview?): Boolean {
        if (open == null) return false
        return when (val p = item.payload) {
            is Payload.StoreOverview -> open.viewKind.isEmpty() && p.overview.identity == open.identity
            is Payload.StoreReview -> open.viewKind == "review:${p.review.id}:"
            is Payload.StoreThread -> open.viewKind == "notes:${p.thread.root.id}:"
            is Payload.SessionOverview, is Payload.SessionNote -> false
        }
    }

    /** `focused <source> <id> "<title>"`. */
    fun focusedReply(item: NoteItem): String = "focused ${item.source.wire} ${item.id} ${SessionDocRefs.quote(item.title)}"
}
