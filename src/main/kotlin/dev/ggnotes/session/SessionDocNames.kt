package dev.ggnotes.session

/** What a session document is: `<id>.md` is an overview, `<id>.toml` a note (SPEC §3.1). */
enum class DocKind { OVERVIEW, NOTE }

/** A directory entry a reader accepts: the id IS the file name without its extension. */
data class DocName(val id: String, val kind: DocKind) {
    val fileName: String get() = SessionDocNames.fileName(id, kind)
}

/** SPEC §3.1 (gg's `DocName` / `ValidID` / `FileName`): ids, file names, and the entries a reader ignores. */
object SessionDocNames {

    val ID = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$")

    private const val TMP_SUFFIX = ".tmp"

    fun isValidId(id: String): Boolean = ID.matches(id)

    /** The id and kind of a document entry, or null for a name a reader ignores (dot files, temp files, other extensions, bad ids). */
    fun parse(name: String): DocName? {
        if (name.startsWith(".") || name.endsWith(TMP_SUFFIX)) return null
        val (id, kind) = when {
            name.endsWith(".md") -> name.removeSuffix(".md") to DocKind.OVERVIEW
            name.endsWith(".toml") -> name.removeSuffix(".toml") to DocKind.NOTE
            else -> return null
        }
        if (!isValidId(id)) return null
        return DocName(id, kind)
    }

    fun fileName(id: String, kind: DocKind): String = if (kind == DocKind.NOTE) "$id.toml" else "$id.md"
}
