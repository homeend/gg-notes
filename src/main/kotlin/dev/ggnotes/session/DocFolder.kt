package dev.ggnotes.session

import java.nio.file.Files
import java.nio.file.Path

/**
 * One worktree's docs directory as a reader sees it: listed and reconciled on
 * every wake into [index]; the only writes are the user's deletes (SPEC §4.4).
 * The directory is never created here: a reader that finds none shows nothing.
 */
class DocFolder(val dir: Path) {

    val index = DocIndex()

    /** List the directory and reconcile; cheap when nothing changed (one stat per entry). */
    fun reload(): DocIndex.Changes = index.reconcile(DocDirectory.list(dir)) { Files.readAllBytes(it.file) }

    /** Unlink one document's file; false when it was not there. Nothing else is ever deleted. */
    fun delete(name: DocName): Boolean = runCatching { Files.deleteIfExists(dir.resolve(name.fileName)) }.getOrDefault(false)

    /** The file a document lives in. */
    fun fileOf(name: DocName): Path = dir.resolve(name.fileName)
}
