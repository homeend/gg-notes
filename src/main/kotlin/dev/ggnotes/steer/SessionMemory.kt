package dev.ggnotes.steer

/**
 * PLUGIN.md §5: what the agent opened in one git root — open files `f<n>`
 * (temporary notes and overviews come in later steps). Pure; the IDE layer
 * feeds it what the editors show. All access under its own lock.
 */
class SessionMemory(val root: String) {

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

    fun findFileByPath(path: String): OpenFile? = synchronized(lock) {
        files.values.filter { it.path == path }.maxByOrNull { it.lastShown }
    }

    fun removeFile(id: String) = synchronized(lock) {
        files.entries.removeIf { it.value.id == id }
    }

    /** Most recently shown first. */
    fun openFiles(): List<OpenFile> = synchronized(lock) { files.values.sortedByDescending { it.lastShown } }

    /** SPEC §5 wire shape; the IDE says which entry is on screen, where its caret is and how many notes it carries. */
    fun wire(f: OpenFile, shownId: String?, caretLine: (OpenFile) -> Int, noteCount: (OpenFile) -> Int): OpenFileWire =
        OpenFileWire(
            id = f.id, path = f.path, source = f.source, title = f.title, rev = f.rev,
            line = caretLine(f), state = if (f.id == shownId) "shown" else "background", notes = noteCount(f),
        )
}
