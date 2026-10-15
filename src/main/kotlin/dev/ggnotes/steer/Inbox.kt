package dev.ggnotes.steer

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.io.path.name

/** PLUGIN.md §3: the IDE's sub-inbox — drain, reply, sweep. Pure file I/O, no IDE types. */
object Inbox {

    const val MAX_COMMAND_BYTES = 512L * 1024

    /** A reply name must stay inside the directory: one plain path component. */
    fun validId(id: String): Boolean =
        id.isNotEmpty() && id != "." && id != ".." && '/' !in id && '\\' !in id && '\u0000' !in id

    /**
     * `cmd-*.json` in name order: a file over the cap is deleted unread; every
     * other one is read, DELETED, then parsed; a command without a usable id is dropped.
     */
    fun drain(ideDir: Path): List<Command> {
        if (!Files.isDirectory(ideDir)) return emptyList()
        val files = Files.list(ideDir).use { s ->
            s.filter { Files.isRegularFile(it) && it.name.startsWith("cmd-") && it.name.endsWith(".json") }
                .sorted(compareBy { it.name }).toList()
        }
        val out = ArrayList<Command>()
        for (f in files) {
            val size = runCatching { Files.size(f) }.getOrDefault(-1L)
            if (size > MAX_COMMAND_BYTES) {
                runCatching { Files.deleteIfExists(f) }
                continue
            }
            val bytes = runCatching { Files.readAllBytes(f) }.getOrNull()
            runCatching { Files.deleteIfExists(f) }
            if (bytes == null) continue
            val command = Wire.parseCommand(String(bytes, StandardCharsets.UTF_8)) ?: continue
            if (!validId(command.id)) continue
            out += command
        }
        return out
    }

    /** `reply-<id>.json`, temp + rename (SPEC §2.3). */
    fun writeReply(ideDir: Path, reply: Reply) {
        Files.createDirectories(ideDir)
        atomicWrite(ideDir.resolve("reply-${reply.id}.json"), Wire.toJson(reply))
    }

    /** At project open: a crashed session's leftovers in the sub-inbox, never the top-level files. */
    fun sweep(ideDir: Path) {
        if (!Files.isDirectory(ideDir)) return
        Files.list(ideDir).use { s ->
            s.filter { Files.isRegularFile(it) && it.name.endsWith(".json") && (it.name.startsWith("cmd-") || it.name.startsWith("reply-")) }
                .forEach { runCatching { Files.deleteIfExists(it) } }
        }
    }

    internal fun atomicWrite(target: Path, text: String) {
        val tmp = target.resolveSibling(".tmp-" + target.name + "-" + System.nanoTime())
        Files.writeString(tmp, text)
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

/** PLUGIN.md §2: `ide.json`, touched every second; gg counts it live while the mtime is under 5 s old. */
object Presence {

    fun json(pid: Long, worktree: String, started: Instant, ide: String, plugin: String): String {
        val o = com.google.gson.JsonObject()
        o.addProperty("pid", pid)
        o.addProperty("worktree", worktree)
        o.addProperty("started", DateTimeFormatter.ISO_INSTANT.format(started.with(java.time.temporal.ChronoField.NANO_OF_SECOND, 0)))
        o.addProperty("ide", ide)
        o.addProperty("plugin", plugin)
        return com.google.gson.GsonBuilder().disableHtmlEscaping().create().toJson(o)
    }

    fun file(inbox: Path): Path = inbox.resolve(SteerPaths.PRESENCE)

    fun write(inbox: Path, json: String) {
        Files.createDirectories(inbox)
        Inbox.atomicWrite(file(inbox), json)
    }

    /** Set the mtime to now; rewrite the file when that fails or it is gone. */
    fun touch(inbox: Path, json: String) {
        val f = file(inbox)
        try {
            if (!Files.isRegularFile(f)) throw java.nio.file.NoSuchFileException(f.toString())
            Files.setLastModifiedTime(f, FileTime.from(Instant.now()))
        } catch (e: Exception) {
            write(inbox, json)
        }
    }

    fun remove(inbox: Path) {
        runCatching { Files.deleteIfExists(file(inbox)) }
    }
}
