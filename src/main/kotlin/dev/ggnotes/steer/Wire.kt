package dev.ggnotes.steer

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName

/** SPEC §3.1 `target`. */
data class Target(
    val state: String? = null,
    val commit: String? = null,
    val source: String? = null,
    val target: String? = null,
    val ref: String? = null,
    val a: String? = null,
    val b: String? = null,
)

/** SPEC §3.1 `line`. */
data class LineSpec(
    val side: String? = null,
    val no: Int = 0,
    val end: Int = 0,
    val asked: Int = 0,
    val anchor: String? = null,
    val matches: Int = 0,
)

/** SPEC §3.1: a `gg session` command; every field optional except [id] and [cmd]. */
data class Command(
    val id: String,
    val cmd: String,
    val file: String? = null,
    val target: Target? = null,
    val commit: String? = null,
    val line: LineSpec? = null,
    val step: String? = null,
    val background: Boolean = false,
    val fileId: String? = null,
    val side: String? = null,
    val start: Int = 0,
    val end: Int = 0,
    val tone: String? = null,
    val noteId: String? = null,
    val summary: String? = null,
    val rationale: String? = null,
    val author: String? = null,
    val meta: Map<String, String>? = null,
    val display: JsonElement? = null,
    val title: String? = null,
    val text: String? = null,
    val hintKind: String? = null,
    val hintId: String? = null,
    val worktree: String? = null,
    val wait: Boolean = false,
)

/** SPEC §5 open-files entry. */
data class OpenFileWire(
    val id: String,
    val path: String,
    val source: String,
    val title: String? = null,
    val rev: String? = null,
    val line: Int = 0,
    val state: String,
    val notes: Int = 0,
    /** An overview document's front matter (session docs): who wrote it, when (RFC 3339 UTC) and how many anchors; empty on every other row. */
    val author: String? = null,
    val created: String? = null,
    val anchors: Int = 0,
)

/** SPEC §3.2. */
data class Reply(
    val id: String,
    val ok: Boolean,
    val detail: String? = null,
    val error: String? = null,
    val files: List<OpenFileWire>? = null,
) {
    companion object {
        fun ok(id: String, detail: String) = Reply(id, true, detail = detail.ifEmpty { null })
        fun fail(id: String, error: String) = Reply(id, false, error = error)
    }
}

/** JSON in and out, gg's spellings (`file_id`, `hint_kind`, …), nulls and false booleans omitted. */
object Wire {

    private val gson = GsonBuilder().disableHtmlEscaping().create()

    fun parseCommand(json: String): Command? {
        val root = try {
            JsonParser.parseString(json)
        } catch (e: RuntimeException) {
            return null
        }
        if (root == null || !root.isJsonObject) return null
        val o = root.asJsonObject
        val id = o.str("id") ?: return null
        val cmd = o.str("cmd") ?: return null
        if (id.isEmpty() || cmd.isEmpty()) return null
        val meta = o.get("meta")?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()
            ?.mapNotNull { (k, v) -> if (v.isJsonPrimitive) k to v.asString else null }?.toMap()
        return Command(
            id = id, cmd = cmd,
            file = o.str("file"),
            target = o.get("target")?.takeIf { it.isJsonObject }?.asJsonObject?.let { t ->
                Target(t.str("state"), t.str("commit"), t.str("source"), t.str("target"), t.str("ref"), t.str("a"), t.str("b"))
            },
            commit = o.str("commit"),
            line = o.get("line")?.takeIf { it.isJsonObject }?.asJsonObject?.let { l ->
                LineSpec(l.str("side"), l.int("no"), l.int("end"), l.int("asked"), l.str("anchor"), l.int("matches"))
            },
            step = o.str("step"),
            background = o.bool("background"),
            fileId = o.str("file_id"),
            side = o.str("side"),
            start = o.int("start"),
            end = o.int("end"),
            tone = o.str("tone"),
            noteId = o.str("note_id"),
            summary = o.str("summary"),
            rationale = o.str("rationale"),
            author = o.str("author"),
            meta = meta,
            display = o.get("display")?.takeIf { !it.isJsonNull },
            title = o.str("title"),
            text = o.str("text"),
            hintKind = o.str("hint_kind"),
            hintId = o.str("hint_id"),
            worktree = o.str("worktree"),
            wait = o.bool("wait"),
        )
    }

    fun toJson(reply: Reply): String {
        val o = JsonObject()
        o.addProperty("id", reply.id)
        o.addProperty("ok", reply.ok)
        reply.detail?.takeIf { it.isNotEmpty() }?.let { o.addProperty("detail", it) }
        reply.error?.takeIf { it.isNotEmpty() }?.let { o.addProperty("error", it) }
        reply.files?.takeIf { it.isNotEmpty() }?.let { files ->
            o.add("files", gson.toJsonTree(files.map { f ->
                JsonObject().apply {
                    addProperty("id", f.id); addProperty("path", f.path); addProperty("source", f.source)
                    f.title?.let { addProperty("title", it) }
                    f.rev?.let { addProperty("rev", it) }
                    if (f.line != 0) addProperty("line", f.line)
                    addProperty("state", f.state)
                    if (f.notes != 0) addProperty("notes", f.notes)
                    f.author?.takeIf { it.isNotEmpty() }?.let { addProperty("author", it) }
                    f.created?.takeIf { it.isNotEmpty() }?.let { addProperty("created", it) }
                    if (f.anchors != 0) addProperty("anchors", f.anchors)
                }
            }))
        }
        return gson.toJson(o)
    }

    private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonObject.int(key: String): Int = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt ?: 0
    private fun JsonObject.bool(key: String): Boolean = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: false
}
