package dev.ggnotes.git

import java.io.File

/**
 * Where gg is, with no setting from the user. An IDE started from the desktop
 * or from Toolbox often has a PATH without the directory gg was installed to
 * (`go install` puts it in `~/go/bin`, which only the shell's rc adds), so the
 * IDE's PATH is followed by the login shell's PATH and by Go's bin directories.
 * Relative PATH entries are skipped: a `gg.exe` in the checkout is never run.
 */
object GgLocator {

    fun find(path: String?, shellPath: String?, env: (String) -> String?, home: String, windows: Boolean): File? =
        candidates(path, shellPath, env, home, windows).map(::File).firstOrNull { it.isFile && (windows || it.canExecute()) }

    /** The paths to try, in order, as strings of the target OS (`\` and `gg.exe` on Windows). */
    fun candidates(path: String?, shellPath: String?, env: (String) -> String?, home: String, windows: Boolean): List<String> {
        val listSep = if (windows) ';' else ':'
        val sep = if (windows) "\\" else "/"
        fun entries(list: String?): List<String> =
            list.orEmpty().split(listSep).map { it.trim().removeSurrounding("\"") }.filter { it.isNotEmpty() }

        val dirs = buildList {
            addAll(entries(path))
            addAll(entries(shellPath))
            env("GOBIN")?.takeIf { it.isNotBlank() }?.let { add(it) }
            entries(env("GOPATH")).forEach { add(it.trimEnd('/', '\\') + sep + "bin") }
            add(home.trimEnd('/', '\\') + sep + "go" + sep + "bin")
            if (!windows) addAll(listOf("/usr/local/bin", "/opt/homebrew/bin", "/home/linuxbrew/.linuxbrew/bin"))
        }
        return dirs.filter { absolute(it, windows) }
            .map { it.trimEnd('/', '\\') + sep + if (windows) "gg.exe" else "gg" }
            .distinct()
    }

    private fun absolute(dir: String, windows: Boolean): Boolean =
        if (windows) Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(dir) || dir.startsWith("\\\\") else dir.startsWith("/")
}
