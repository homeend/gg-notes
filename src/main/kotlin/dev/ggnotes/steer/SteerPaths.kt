package dev.ggnotes.steer

import dev.ggnotes.store.StoreLocator
import java.nio.file.Path

/**
 * SPEC §2 / PLUGIN.md §2: where a worktree's steering inbox is.
 *
 * `inbox = <state>/gg/sessions/<Enc(common dir)>/steer/<Enc(worktree)>/`,
 * `Enc(p) = filepath.Clean(p)` with every `/`, `\` and `:` replaced by `-`.
 */
object SteerPaths {

    /** gg's `EncodeRepoKey`: clean (Go's `filepath.Clean`, `/` → `\` first on Windows), then the replacement. */
    fun enc(path: String, windows: Boolean = StoreLocator.isWindows()): String {
        val cleaned = StoreLocator.clean(path.trim(), windows)
        return buildString(cleaned.length) {
            for (c in cleaned) append(if (c == '/' || c == '\\' || c == ':') '-' else c)
        }
    }

    fun sessionsDir(stateBase: Path): Path = stateBase.resolve("gg").resolve("sessions")

    fun inboxDir(stateBase: Path, commonDir: String, worktree: String, windows: Boolean = StoreLocator.isWindows()): Path =
        sessionsDir(stateBase).resolve(enc(commonDir, windows)).resolve("steer").resolve(enc(worktree, windows))

    /** The IDE's own sub-inbox (SPEC §2.1). */
    fun ideDir(inbox: Path): Path = inbox.resolve(IDE_DIR)

    /** The worktree's session documents (session-docs SPEC §2): `<inbox>/docs/`, flat; created by writers, never by this reader. */
    fun docsDir(inbox: Path): Path = inbox.resolve(DOCS_DIR)

    const val IDE_DIR = "ide"
    const val DOCS_DIR = "docs"
    const val PRESENCE = "ide.json"

    /** The state base of this process: `$XDG_STATE_HOME`, `%LocalAppData%`, `~/.local/state` (the notes-store rule). */
    fun defaultStateBase(): Path = StoreLocator.defaultBaseDir().parent.parent
}
