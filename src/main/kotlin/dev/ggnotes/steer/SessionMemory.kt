package dev.ggnotes.steer

/**
 * PLUGIN.md §5: the open-files list of one git root — every file the agent
 * opened through the IDE, as `f<n>` entries (ids never reused in a session).
 * Pure; the IDE layer feeds it what the editors show. All access under its
 * own lock. Notes and overviews are files of the session docs directory now
 * ([dev.ggnotes.session.DocFolder]) and no longer live here.
 */
class SessionMemory(val root: String) {

    /** One entry of the open-files list. */
    class OpenFile(
        val id: String,
        val path: String,
        /** `worktree` | `commit`. */
        val source: String,
        val rev: String?,
        var title: String?,
    ) {
        /** Monotonic: higher = shown more recently. */
        var lastShown: Long = 0
    }

    private val lock = Any()
    private var fileSeq = 0
    private var clock = 0L
    private val files = LinkedHashMap<String, OpenFile>()

    private fun key(source: String, path: String, rev: String?) = "$source\u0000$path\u0000${rev.orEmpty()}"

    /** Register (or re-find) a file the agent touched; it becomes the most recently shown unless [background]. */
    fun touchFile(source: String, path: String, rev: String?, title: String? = null, background: Boolean = false): OpenFile = synchronized(lock) {
        val k = key(source, path, rev)
        val f = files.getOrPut(k) { OpenFile("f${++fileSeq}", path, source, rev, title) }
        if (title != null) f.title = title
        if (!background || f.lastShown == 0L) f.lastShown = ++clock
        f
    }

    fun findFile(id: String): OpenFile? = synchronized(lock) { files.values.firstOrNull { it.id == id } }

    /** An existing entry was brought to the front (a file focused). */
    fun markShown(id: String) = synchronized(lock) {
        files.values.firstOrNull { it.id == id }?.let { it.lastShown = ++clock }
        Unit
    }

    fun findFileByPath(path: String): OpenFile? = synchronized(lock) {
        files.values.filter { it.path == path }.maxByOrNull { it.lastShown }
    }

    /** A file the user closed leaves the list (PLUGIN.md §5). */
    fun removeFile(id: String) = synchronized(lock) {
        files.entries.removeIf { it.value.id == id }
        Unit
    }

    /** Most recently shown first. */
    fun openFiles(): List<OpenFile> = synchronized(lock) { files.values.sortedByDescending { it.lastShown } }

    /** SPEC §5 wire shape; the IDE says whether each entry is on screen, where its caret is and how many session notes its file carries. */
    fun wire(f: OpenFile, isShown: (OpenFile) -> Boolean, caretLine: (OpenFile) -> Int, noteCount: (OpenFile) -> Int): OpenFileWire =
        OpenFileWire(
            id = f.id, path = f.path, source = f.source, title = f.title, rev = f.rev,
            line = caretLine(f), state = if (isShown(f)) "shown" else "background", notes = noteCount(f),
        )
}
