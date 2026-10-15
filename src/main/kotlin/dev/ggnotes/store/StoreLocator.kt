package dev.ggnotes.store

import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest

/**
 * SPEC §3 — where a repository's store is. Pure functions so the rules are
 * testable without an IDE; [defaultBaseDir] binds them to this process.
 */
object StoreLocator {

    /** §3.1: `$XDG_STATE_HOME/gg/notes`, else `%LocalAppData%\gg\notes` on Windows, else `~/.local/state/gg/notes`. */
    fun baseDir(env: (String) -> String?, windows: Boolean, home: Path): Path {
        val xdg = env("XDG_STATE_HOME")
        if (!xdg.isNullOrEmpty()) return Paths.get(xdg, "gg", "notes")
        if (windows) {
            val local = env("LOCALAPPDATA")
            if (!local.isNullOrEmpty()) return Paths.get(local, "gg", "notes")
        }
        return home.resolve(".local").resolve("state").resolve("gg").resolve("notes")
    }

    /**
     * The base dir of this process. A system property `ggnotes.env.<NAME>`
     * overrides the environment variable `<NAME>` (e.g.
     * `-Dggnotes.env.XDG_STATE_HOME=/tmp/x`), for tests and for pointing a
     * running IDE at a throwaway store.
     */
    fun defaultBaseDir(): Path =
        baseDir({ name -> System.getProperty("ggnotes.env.$name") ?: System.getenv(name) }, isWindows(), Paths.get(System.getProperty("user.home")))

    fun isWindows(): Boolean = System.getProperty("os.name").orEmpty().lowercase().startsWith("windows")

    /** §3.2: hex of the first 8 bytes of SHA-256 over the cleaned, trimmed common dir. */
    fun key(gitCommonDir: String, windows: Boolean = isWindows()): String {
        val cleaned = clean(gitCommonDir.trim(), windows)
        val digest = MessageDigest.getInstance("SHA-256").digest(cleaned.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    fun storeDir(base: Path, key: String): Path = base.resolve(key)

    /**
     * Go's `filepath.Clean`, lexically: drop `.` segments, resolve `..`
     * against earlier segments, collapse repeated separators, drop a trailing
     * separator. On Windows every `/` becomes `\` and a drive prefix is kept.
     * Never touches the file system.
     */
    fun clean(path: String, windows: Boolean): String {
        val sep = if (windows) '\\' else '/'
        var rest = if (windows) path.replace('/', '\\') else path
        var volume = ""
        if (windows && rest.length >= 2 && rest[1] == ':' && rest[0].isLetter()) {
            volume = rest.substring(0, 2)
            rest = rest.substring(2)
        }
        if (rest.isEmpty()) return volume.ifEmpty { "." }
        val rooted = rest[0] == sep
        val out = ArrayDeque<String>()
        for (segment in rest.split(sep)) {
            when {
                segment.isEmpty() || segment == "." -> Unit
                segment == ".." -> when {
                    out.isNotEmpty() && out.last() != ".." -> out.removeLast()
                    !rooted -> out.addLast("..")
                }
                else -> out.addLast(segment)
            }
        }
        var result = out.joinToString(sep.toString())
        if (rooted) result = sep + result
        if (result.isEmpty()) result = "."
        return volume + result
    }
}
