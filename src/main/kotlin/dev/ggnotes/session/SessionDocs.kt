package dev.ggnotes.session

import dev.ggnotes.steer.SessionAnchor
import dev.ggnotes.steer.SessionAnchors
import dev.ggnotes.store.DisplayDecl
import dev.ggnotes.store.DisplayDecls
import dev.ggnotes.store.MetaEntry
import org.tomlj.Toml
import org.tomlj.TomlArray
import org.tomlj.TomlParseResult
import org.tomlj.TomlTable
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** A parsed `<id>.md` (SPEC §3.2): front matter plus the markdown body whose links are anchors. */
data class SessionOverviewDoc(
    val id: String,
    val format: Int,
    val title: String,
    /** `agent` when the file names none. */
    val author: String,
    val created: OffsetDateTime?,
    val body: String,
) {
    /** The first 100 anchors of the body, in document order. */
    val anchors: List<SessionAnchor> by lazy { SessionAnchors.extract(body) }
}

/** A parsed `<id>.toml` (SPEC §3.3): a note on working-tree lines, carrying the lines it was written on. */
data class SessionNoteDoc(
    val id: String,
    val format: Int,
    val path: String,
    val start: Int,
    val end: Int,
    val summary: String,
    val rationale: String?,
    val author: String,
    val created: OffsetDateTime?,
    /** `start..end` as the writer saw them, no line terminators: the note's anchor text. */
    val lines: List<String>,
    val meta: List<MetaEntry>,
    val display: Map<String, DisplayDecl>,
)

/** A document read from its bytes, or why it is ignored (gg's prose). */
sealed class Parsed<out T> {
    data class Ok<T>(val doc: T) : Parsed<T>()
    data class Refused(val reason: String) : Parsed<Nothing>()
}

/**
 * gg's `ParseOverviewFile` / `ParseNoteFile`: the file shapes of SPEC §3 with the
 * refusals of §3.4. A refused file is not shown; the reader never touches it.
 */
object SessionDocParser {

    const val MAX_OVERVIEW_BYTES = 64 * 1024
    const val MAX_OVERVIEW_TITLE = 200
    const val MAX_NOTE_SUMMARY = 500
    const val MAX_NOTE_RATIONALE = 4000
    const val MAX_META_KEYS = 32
    const val MAX_META_KEY_BYTES = 64
    const val MAX_META_VALUE_BYTES = 256
    const val DOC_FORMAT = 1
    private const val FENCE = "+++"
    private const val NO_FRONT_MATTER = "an overview file starts with a +++ front matter block"
    private const val BAD_CREATED = "created must be an RFC 3339 date-time"

    private class Refusal(val reason: String) : RuntimeException(reason)

    /** The TOML between the fences and the body after the closing fence's line break; null without the block. CRLF is read as LF. */
    fun splitFrontMatter(text: String): Pair<String, String>? {
        val t = text.replace("\r\n", "\n")
        val nl = t.indexOf('\n')
        if (nl < 0 || t.substring(0, nl) != FENCE) return null
        val lines = t.substring(nl + 1).split('\n')
        val close = lines.indexOf(FENCE)
        if (close < 0) return null
        return lines.subList(0, close).joinToString("\n") to lines.subList(close + 1, lines.size).joinToString("\n")
    }

    fun overview(id: String, bytes: ByteArray): Parsed<SessionOverviewDoc> = try {
        val (head, body) = splitFrontMatter(String(bytes, Charsets.UTF_8)) ?: throw Refusal(NO_FRONT_MATTER)
        val t = toml(head)
        val title = t.str("title").orEmpty()
        overviewRefusal(title, body)?.let { throw Refusal(it) }
        Parsed.Ok(SessionOverviewDoc(
            id = id, format = t.int("format") ?: DOC_FORMAT, title = title.trim(),
            author = t.str("author")?.takeIf { it.isNotEmpty() } ?: "agent", created = t.created(), body = body,
        ))
    } catch (r: Refusal) {
        Parsed.Refused(r.reason)
    }

    /** gg's `overviewRefusal` with `needTitle`, in its order. */
    private fun overviewRefusal(title: String, text: String): String? {
        if (text.isBlank()) return "an overview needs text"
        if (text.toByteArray(Charsets.UTF_8).size > MAX_OVERVIEW_BYTES) return "the text is over 64 KiB"
        val ti = title.trim()
        if (ti.isEmpty()) return "an overview needs a title"
        if ('\n' in ti || '\r' in ti || ti.codePointCount(0, ti.length) > MAX_OVERVIEW_TITLE) {
            return "the title must be one line of at most $MAX_OVERVIEW_TITLE characters"
        }
        return null
    }

    fun note(id: String, bytes: ByteArray): Parsed<SessionNoteDoc> = try {
        val t = toml(String(bytes, Charsets.UTF_8))
        val path = t.str("path").orEmpty()
        val start = t.int("start") ?: 0
        val end = t.int("end") ?: 0
        val summary = t.str("summary").orEmpty().trim()
        val rationale = t.str("rationale").orEmpty()
        val lines = t.strings("lines").map { it.trimEnd('\r', '\n') }
        noteRefusal(path, start, end, summary, rationale, lines.size)?.let { throw Refusal(it) }
        Parsed.Ok(SessionNoteDoc(
            id = id, format = t.int("format") ?: DOC_FORMAT, path = path, start = start, end = end, summary = summary,
            rationale = rationale.takeIf { it.isNotEmpty() }, author = t.str("author")?.takeIf { it.isNotEmpty() } ?: "agent",
            created = t.created(), lines = lines, meta = checkMeta(t.tables("meta")),
            display = DisplayDecls.fromMap(normalize(t.get("display")) as? Map<*, *>),
        ))
    } catch (r: Refusal) {
        Parsed.Refused(r.reason)
    }

    /** gg's `noteRefusal`, in its order. */
    private fun noteRefusal(path: String, start: Int, end: Int, summary: String, rationale: String, lineCount: Int): String? = when {
        path.isEmpty() -> "a note needs a path"
        !validPath(path) -> "path must be a relative slash path inside the worktree"
        start < 1 -> "a line number is 1-based"
        end < start -> "the range ends before it starts"
        summary.isEmpty() -> "a note needs a summary"
        summary.codePointCount(0, summary.length) > MAX_NOTE_SUMMARY -> "the summary is longer than $MAX_NOTE_SUMMARY characters"
        rationale.codePointCount(0, rationale.length) > MAX_NOTE_RATIONALE -> "the rationale is longer than $MAX_NOTE_RATIONALE characters"
        lineCount != end - start + 1 -> "lines must hold end-start+1 entries (got $lineCount, want ${end - start + 1})"
        else -> null
    }

    /** A slash-form path inside the worktree: relative, no empty, `.` or `..` segment, no backslash, no drive colon. */
    fun validPath(p: String): Boolean {
        if (p.isEmpty() || p.startsWith("/") || '\\' in p || ':' in p) return false
        return p.split('/').none { it.isEmpty() || it == "." || it == ".." }
    }

    /** gg's `notemeta.CheckMeta`: the entries that pass the part-2 limits, a repeated key keeping its first value. */
    private fun checkMeta(tables: List<TomlTable>): List<MetaEntry> {
        val out = ArrayList<MetaEntry>()
        val seen = HashSet<String>()
        for (m in tables) {
            val key = runCatching { m.getString("key") }.getOrNull() ?: continue
            val value = runCatching { m.getString("value") }.getOrNull() ?: ""
            if (key.isEmpty() || '\n' in key || '\r' in key || key.toByteArray(Charsets.UTF_8).size > MAX_META_KEY_BYTES) continue
            if ('\n' in value || '\r' in value || value.toByteArray(Charsets.UTF_8).size > MAX_META_VALUE_BYTES) continue
            if (!seen.add(key)) continue
            if (out.size == MAX_META_KEYS) continue
            out += MetaEntry(key, value)
        }
        return out
    }

    // ---- tomlj helpers (a type that is not the spec's is a decode failure, as gg's decoder treats it) -----------

    private fun toml(text: String): TomlParseResult {
        val t = Toml.parse(text)
        if (t.hasErrors()) throw Refusal("toml: " + t.errors().first().toString())
        return t
    }

    private fun TomlParseResult.str(key: String): String? = when (val v = get(key)) {
        null -> null
        is String -> v
        else -> throw Refusal("toml: $key must be a string")
    }

    private fun TomlParseResult.int(key: String): Int? = when (val v = get(key)) {
        null -> null
        is Long -> v.toInt()
        else -> throw Refusal("toml: $key must be an integer")
    }

    private fun TomlParseResult.strings(key: String): List<String> = when (val v = get(key)) {
        null -> emptyList()
        is TomlArray -> v.toList().map { it as? String ?: throw Refusal("toml: $key must hold strings") }
        else -> throw Refusal("toml: $key must be an array")
    }

    private fun TomlParseResult.tables(key: String): List<TomlTable> = when (val v = get(key)) {
        null -> emptyList()
        is TomlArray -> v.toList().mapNotNull { it as? TomlTable }
        else -> emptyList()
    }

    private fun TomlParseResult.created(): OffsetDateTime? = when (val v = get("created")) {
        null -> null
        is OffsetDateTime -> v.withOffsetSameInstant(ZoneOffset.UTC)
        is LocalDateTime -> v.atOffset(ZoneOffset.UTC)
        else -> throw Refusal(BAD_CREATED)
    }

    /** tomlj keeps TomlTable/TomlArray objects inside toMap(); turn the whole tree into plain Maps and Lists. */
    private fun normalize(v: Any?): Any? = when (v) {
        is TomlTable -> v.toMap().mapValues { normalize(it.value) }
        is TomlArray -> v.toList().map { normalize(it) }
        else -> v
    }
}
