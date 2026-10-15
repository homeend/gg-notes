package dev.ggnotes.steer

import com.google.gson.JsonElement
import java.security.MessageDigest

/** `note_add`'s outcome: the note, or gg's refusal text. */
sealed class NoteResult {
    data class Ok(val note: SessionMemory.Note) : NoteResult()
    data class Refused(val error: String) : NoteResult()
}

/**
 * PLUGIN.md §5: what the agent opened in one git root — open files `f<n>`
 * and temporary notes `t<n>` with gg's exact-line re-anchoring. Pure; the
 * IDE layer feeds it what the editors show. All access under its own lock.
 */
class SessionMemory(val root: String) {

    companion object {
        const val MAX_NOTES_PER_FILE = 50
        const val MAX_NOTE_SUMMARY = 500
        const val MAX_NOTE_RATIONALE = 4000
    }

    /** One entry of the open-files list. */
    class OpenFile(
        val id: String,
        val path: String,
        /** `worktree` | `commit` | `overview`. */
        val source: String,
        val rev: String?,
        var title: String?,
    ) {
        /** Monotonic: higher = shown more recently. */
        var lastShown: Long = 0
    }

    /** A temporary note; its lines move with the file (see [Reanchor]). */
    class Note(
        val id: String,
        val seq: Int,
        val fileId: String,
        val path: String,
        val summary: String,
        val rationale: String?,
        val author: String,
        val meta: Map<String, String>?,
        val display: JsonElement?,
        anchor: List<String>,
        start: Int,
        end: Int,
    ) {
        internal val live = Reanchor.Note(start, end, false, anchor)
        val start: Int get() = live.start
        val end: Int get() = live.end
        val outdated: Boolean get() = live.outdated
    }

    private class FileNotes(var lines: List<String>) {
        var print: String = fingerprint(lines)
        val notes = ArrayList<Note>()

        /** Re-anchor to [cur] when it differs from what the notes were aligned to; true when anything changed. */
        fun align(cur: List<String>): Boolean {
            val p = fingerprint(cur)
            if (p == print) return false
            Reanchor.reanchor(notes.map { it.live }, lines, cur)
            lines = cur
            print = p
            sort()
            return true
        }

        fun sort() = notes.sortWith(compareBy<Note> { it.start }.thenBy { it.end }.thenBy { it.seq })

        companion object {
            fun fingerprint(lines: List<String>): String {
                val md = MessageDigest.getInstance("SHA-256")
                for (l in lines) {
                    md.update(l.toByteArray(Charsets.UTF_8))
                    md.update('\n'.code.toByte())
                }
                return md.digest().joinToString("") { "%02x".format(it) }
            }
        }
    }

    private val lock = Any()
    private var fileSeq = 0
    private var noteSeq = 0
    private var clock = 0L
    private val files = LinkedHashMap<String, OpenFile>()
    private val notesByFile = HashMap<String, FileNotes>()

    private fun key(source: String, path: String, rev: String?) = "$source\u0000$path\u0000${rev.orEmpty()}"

    // ---- open files -----------------------------------------------------------------------

    /** Register (or re-find) a file the agent touched; it becomes the most recently shown unless [background]. */
    fun touchFile(source: String, path: String, rev: String?, title: String? = null, background: Boolean = false): OpenFile = synchronized(lock) {
        val k = key(source, path, rev)
        val f = files.getOrPut(k) { OpenFile("f${++fileSeq}", path, source, rev, title) }
        if (title != null) f.title = title
        if (!background || f.lastShown == 0L) f.lastShown = ++clock
        f
    }

    fun findFile(id: String): OpenFile? = synchronized(lock) { files.values.firstOrNull { it.id == id } }

    fun findFileByPath(path: String): OpenFile? = synchronized(lock) {
        files.values.filter { it.path == path }.maxByOrNull { it.lastShown }
    }

    /** A file the user closed leaves the list; its notes go with it (PLUGIN.md §5). */
    fun removeFile(id: String) = synchronized(lock) {
        files.entries.removeIf { it.value.id == id }
        notesByFile.remove(id)
        Unit
    }

    /** Most recently shown first. */
    fun openFiles(): List<OpenFile> = synchronized(lock) { files.values.sortedByDescending { it.lastShown } }

    /** SPEC §5 wire shape; the IDE says which entry is on screen, where its caret is and how many notes it carries. */
    fun wire(f: OpenFile, shownId: String?, caretLine: (OpenFile) -> Int, noteCount: (OpenFile) -> Int): OpenFileWire =
        OpenFileWire(
            id = f.id, path = f.path, source = f.source, title = f.title, rev = f.rev,
            line = caretLine(f), state = if (f.id == shownId) "shown" else "background", notes = noteCount(f),
        )

    // ---- temporary notes -------------------------------------------------------------------

    /**
     * gg's `AddNoteMeta`: [lines] is the file's CURRENT text (canonicalized by
     * gg's line rule); the refusals come in gg's order with gg's strings.
     */
    fun addNote(fileId: String, lines: List<String>, start: Int, end: Int, summary: String?, rationale: String?, author: String?,
                meta: Map<String, String>?, display: JsonElement?): NoteResult = synchronized(lock) {
        val file = files.values.firstOrNull { it.id == fileId } ?: return NoteResult.Refused("no open file $fileId")
        val path = file.path
        val canon = Lines.split(lines.joinToString("\n"))
        val fn = notesByFile[fileId]
        if (fn != null && canon.isNotEmpty()) fn.align(canon)
        val count = fn?.notes?.size ?: 0
        val sum = summary.orEmpty().trim()
        val rat = rationale.orEmpty()
        val error = when {
            canon.isEmpty() -> "$path has no lines to note"
            start < 1 -> "a line number is 1-based"
            end < start -> "the range ends before it starts"
            end > canon.size -> "line $end is past the end of $path (${canon.size} lines)"
            sum.isEmpty() -> "a note needs a summary"
            sum.codePointCount(0, sum.length) > MAX_NOTE_SUMMARY -> "the summary is longer than $MAX_NOTE_SUMMARY characters"
            rat.codePointCount(0, rat.length) > MAX_NOTE_RATIONALE -> "the rationale is longer than $MAX_NOTE_RATIONALE characters"
            count >= MAX_NOTES_PER_FILE -> "$path already carries $MAX_NOTES_PER_FILE notes"
            else -> null
        }
        if (error != null) return NoteResult.Refused(error)
        val holder = fn ?: FileNotes(canon).also { notesByFile[fileId] = it }
        val n = Note(
            id = "t${++noteSeq}", seq = noteSeq, fileId = fileId, path = path, summary = sum,
            rationale = rat.takeIf { it.isNotEmpty() }, author = author?.takeIf { it.isNotBlank() } ?: "agent",
            meta = meta?.takeIf { it.isNotEmpty() }, display = display,
            anchor = canon.subList(start - 1, end), start = start, end = end,
        )
        holder.notes += n
        holder.sort()
        NoteResult.Ok(n)
    }

    /** gg's `Align`: re-anchor a file's notes to its current text; the same text again is a no-op; no lines never align. */
    fun alignFile(fileId: String, lines: List<String>): Boolean = synchronized(lock) {
        val canon = Lines.split(lines.joinToString("\n"))
        if (canon.isEmpty()) return false
        notesByFile[fileId]?.align(canon) ?: false
    }

    fun notesOf(fileId: String): List<Note> = synchronized(lock) { notesByFile[fileId]?.notes?.toList().orEmpty() }

    fun allNotes(): List<Note> = synchronized(lock) {
        openFiles().flatMap { f -> notesByFile[f.id]?.notes?.toList().orEmpty() }
    }

    fun noteCount(fileId: String): Int = synchronized(lock) { notesByFile[fileId]?.notes?.size ?: 0 }

    fun findNote(id: String): Note? = synchronized(lock) { notesByFile.values.flatMap { it.notes }.firstOrNull { it.id == id } }

    /** `note_show`'s `text`: the lines the note sits on now. */
    fun noteText(id: String): List<String>? = synchronized(lock) {
        val n = findNote(id) ?: return null
        val lines = notesByFile[n.fileId]?.lines ?: return emptyList()
        if (n.start < 1 || n.start > lines.size) return emptyList()
        lines.subList(n.start - 1, n.end.coerceAtMost(lines.size))
    }

    fun removeNote(id: String): Boolean = synchronized(lock) {
        notesByFile.values.any { fn -> fn.notes.removeIf { it.id == id } }
    }

    /** gg's `NotedDetail`: `noted <path>:<start>[-<end>] as t<n>`. */
    fun notedDetail(n: Note): String = "noted " + span(n) + " as " + n.id

    fun span(n: Note): String = n.path + ":" + n.start + (if (n.end != n.start) "-" + n.end else "")

    fun noteWire(n: Note, withText: Boolean): NoteWire = NoteWire(
        id = n.id, fileId = n.fileId, path = n.path, start = n.start, end = n.end, summary = n.summary,
        rationale = n.rationale, author = n.author, outdated = n.outdated,
        text = if (withText) noteText(n.id) else null, meta = n.meta, display = n.display,
    )
}
