package dev.ggnotes.session

import java.nio.file.Files
import java.nio.file.Path

/** SPEC §2 / §3.4: listing the docs directory — what is a document entry and what a reader skips unread. */
object DocDirectory {

    /** A document file over this many bytes is ignored unread (gg's `MaxDocBytes`). */
    const val MAX_DOC_BYTES = 512L * 1024

    /** One accepted entry with the stamp that decides whether it is re-read. */
    data class Listed(val name: DocName, val file: Path, val size: Long, val mtime: Long)

    /** The directory's document entries in file-name order; a missing directory is an empty list. */
    fun list(dir: Path): List<Listed> {
        if (!Files.isDirectory(dir)) return emptyList()
        val out = ArrayList<Listed>()
        val entries = runCatching { Files.list(dir).use { s -> s.toList() } }.getOrElse { return emptyList() }
        for (f in entries) {
            val name = SessionDocNames.parse(f.fileName.toString()) ?: continue
            val attrs = runCatching { Files.readAttributes(f, java.nio.file.attribute.BasicFileAttributes::class.java) }.getOrNull() ?: continue
            if (!attrs.isRegularFile) continue
            if (attrs.size() > MAX_DOC_BYTES) continue
            out += Listed(name, f, attrs.size(), attrs.lastModifiedTime().toMillis())
        }
        out.sortBy { it.name.fileName }
        return out
    }
}

/**
 * SPEC §4.2: the documents of one directory, reconciled by id on every wake. A
 * known entry whose (size, mtime) stamp is unchanged is not re-read; a changed
 * one is re-read and replaced in place; a gone one is dropped; an entry that
 * does not parse is not a document (and is not re-read until its stamp changes).
 */
class DocIndex {

    /** A document plus the file facts a row shows: [time] is `created`, else the file's modification time (millis). */
    class Entry<D>(val doc: D, val size: Long, val mtime: Long, val time: Long)

    data class Changes(val added: List<DocName>, val replaced: List<DocName>, val removed: List<DocName>) {
        val isEmpty: Boolean get() = added.isEmpty() && replaced.isEmpty() && removed.isEmpty()
        val overviewsChanged: Boolean get() = (added + replaced + removed).any { it.kind == DocKind.OVERVIEW }
        val notesChanged: Boolean get() = (added + replaced + removed).any { it.kind == DocKind.NOTE }
    }

    private val lock = Any()
    private val overviews = HashMap<String, Entry<SessionOverviewDoc>>()
    private val notes = HashMap<String, Entry<SessionNoteDoc>>()
    /** The stamp of every listed entry, documents and refused files alike. */
    private val stamps = HashMap<DocName, Pair<Long, Long>>()

    /** Reconcile against [listing]; [read] returns a file's bytes (null = unreadable now). */
    fun reconcile(listing: List<DocDirectory.Listed>, read: (DocDirectory.Listed) -> ByteArray?): Changes = synchronized(lock) {
        val added = ArrayList<DocName>()
        val replaced = ArrayList<DocName>()
        val removed = ArrayList<DocName>()
        val present = HashSet<DocName>()
        for (l in listing) {
            present += l.name
            val stamp = l.size to l.mtime
            if (stamps[l.name] == stamp) continue
            stamps[l.name] = stamp
            val known = isKnown(l.name)
            val bytes = runCatching { read(l) }.getOrNull()
            val ok = bytes != null && load(l, bytes)
            when {
                ok && known -> replaced += l.name
                ok -> added += l.name
                known -> { drop(l.name); removed += l.name }
            }
        }
        for (name in stamps.keys.toList()) {
            if (name in present) continue
            stamps.remove(name)
            if (isKnown(name)) { drop(name); removed += name }
        }
        Changes(added, replaced, removed)
    }

    private fun isKnown(name: DocName) = if (name.kind == DocKind.OVERVIEW) name.id in overviews else name.id in notes

    private fun drop(name: DocName) {
        if (name.kind == DocKind.OVERVIEW) overviews.remove(name.id) else notes.remove(name.id)
    }

    private fun load(l: DocDirectory.Listed, bytes: ByteArray): Boolean {
        return if (l.name.kind == DocKind.OVERVIEW) {
            val p = SessionDocParser.overview(l.name.id, bytes) as? Parsed.Ok ?: return false
            overviews[l.name.id] = Entry(p.doc, l.size, l.mtime, p.doc.created?.toInstant()?.toEpochMilli() ?: l.mtime)
            true
        } else {
            val p = SessionDocParser.note(l.name.id, bytes) as? Parsed.Ok ?: return false
            notes[l.name.id] = Entry(p.doc, l.size, l.mtime, p.doc.created?.toInstant()?.toEpochMilli() ?: l.mtime)
            true
        }
    }

    fun overview(id: String): Entry<SessionOverviewDoc>? = synchronized(lock) { overviews[id] }

    fun note(id: String): Entry<SessionNoteDoc>? = synchronized(lock) { notes[id] }

    /** Newest first by `created` (else file time), then id. */
    fun overviews(): List<Entry<SessionOverviewDoc>> = synchronized(lock) {
        overviews.values.sortedWith(compareByDescending<Entry<SessionOverviewDoc>> { it.time }.thenBy { it.doc.id })
    }

    fun notes(): List<Entry<SessionNoteDoc>> = synchronized(lock) { notes.values.sortedWith(noteOrder) }

    /** The notes on one worktree path, by line. */
    fun notesOn(path: String): List<Entry<SessionNoteDoc>> = synchronized(lock) { notes.values.filter { it.doc.path == path }.sortedWith(noteOrder) }

    private val noteOrder = compareBy<Entry<SessionNoteDoc>> { it.doc.path }.thenBy { it.doc.start }.thenBy { it.doc.end }.thenBy { it.doc.id }

    val isEmpty: Boolean get() = synchronized(lock) { overviews.isEmpty() && notes.isEmpty() }
}
