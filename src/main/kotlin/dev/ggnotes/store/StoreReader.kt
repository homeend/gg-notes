package dev.ggnotes.store

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.tomlj.Toml
import org.tomlj.TomlParseResult
import org.tomlj.TomlTable
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime

/**
 * SPEC §4–§5, §8: reads `commits.toml` and `previews.toml` of one store
 * directory and returns its overview records. No IDE types; never writes.
 */
class StoreReader(private val retryDelayMillis: Long = 150) {

    companion object {
        /** The parts overview records live in (SPEC §3.3). Every other file is ignored. */
        val PARTS = listOf("commits.toml", "previews.toml")
        const val KNOWN_FORMAT = 3L
        private val PR_SCOPE = Regex("""\.\.\.refs/gg/pr/(\d+)$""")
    }

    fun read(storeDir: Path): StoreContents {
        if (!Files.isDirectory(storeDir)) return StoreContents.EMPTY
        val overviews = ArrayList<Overview>()
        val problems = ArrayList<String>()
        var newer = false
        for (name in PARTS) {
            val file = storeDir.resolve(name)
            if (!Files.isRegularFile(file)) continue
            val parsed = parseWithRetry(file)
            if (parsed == null || parsed.hasErrors()) {
                val first = parsed?.errors()?.firstOrNull()?.toString() ?: "unreadable"
                problems += "$name: $first"
                continue
            }
            val format = parsed.getLong("format") ?: 0L
            if (format > KNOWN_FORMAT) newer = true
            val notes = parsed.getArray("notes") ?: continue
            for (i in 0 until notes.size()) {
                val table = runCatching { notes.getTable(i) }.getOrNull() ?: continue
                toOverview(table, file)?.let(overviews::add)
            }
        }
        return StoreContents(overviews, newer, problems)
    }

    /** SPEC §8.1: a torn read across gg's rename is momentary — retry once. */
    private fun parseWithRetry(file: Path): TomlParseResult? {
        val first = runCatching { Toml.parse(file) }.getOrNull()
        if (first != null && !first.hasErrors()) return first
        Thread.sleep(retryDelayMillis)
        return runCatching { Toml.parse(file) }.getOrNull() ?: first
    }

    /** SPEC §4.3: the predicate; null when the record is another note kind. */
    private fun toOverview(t: TomlTable, file: Path): Overview? {
        val tags = runCatching { t.getArray("tags")?.toList()?.map { it.toString() } }.getOrNull().orEmpty()
        if ("overview" !in tags) return null
        if (!t.getStringOrNull("parent_id").isNullOrEmpty()) return null
        val address = runCatching { t.getTable("address") }.getOrNull() ?: return null
        val commit = address.getStringOrNull("Commit").orEmpty()
        if (commit.isEmpty()) return null
        if (!address.getStringOrNull("Path").isNullOrEmpty()) return null
        if ((runCatching { address.getLong("State") }.getOrNull() ?: 0L) != 0L) return null

        val title = t.getStringOrNull("summary").orEmpty()
        val preview = t.getStringOrNull("preview")?.takeIf { it.isNotEmpty() }
        val prNumber = preview?.let { PR_SCOPE.find(it)?.groupValues?.get(1)?.toIntOrNull() }
        return Overview(
            id = t.getStringOrNull("id").orEmpty(),
            title = title,
            author = t.getStringOrNull("author").orEmpty(),
            commit = commit,
            branch = address.getStringOrNull("Branch").orEmpty(),
            preview = preview,
            prNumber = prNumber,
            created = t.getDateOrNull("created"),
            updated = t.getDateOrNull("updated"),
            document = decodeDocument(t.getStringOrNull("rationale"), title),
            sourceFile = file,
        )
    }

    private fun TomlTable.getStringOrNull(key: String): String? =
        runCatching { if (isString(key)) getString(key) else null }.getOrNull()

    private fun TomlTable.getDateOrNull(key: String): OffsetDateTime? =
        runCatching { getOffsetDateTime(key) }.getOrNull()

    /** SPEC §5 shape, leniently (unknown keys ignored); any violation is "cannot render" (§8.2). */
    internal fun decodeDocument(json: String?, recordTitle: String): DocumentResult {
        if (json.isNullOrBlank()) return DocumentResult.CannotRender("empty document")
        val root = try {
            JsonParser.parseString(json)
        } catch (e: RuntimeException) {
            return DocumentResult.CannotRender("not JSON: ${e.message}")
        }
        if (!root.isJsonObject) return DocumentResult.CannotRender("document is not a JSON object")
        val obj = root.asJsonObject
        val version = obj.get("version")?.takeIf { it.isNumber() }?.asNumber?.toDouble()
        if (version != 1.0) return DocumentResult.CannotRender("unsupported document version ${obj.get("version")}")
        val title = obj.string("title") ?: return DocumentResult.CannotRender("missing title")
        if (title.isBlank()) return DocumentResult.CannotRender("blank title")
        val overview = obj.string("overview") ?: return DocumentResult.CannotRender("missing overview")
        if (overview.isBlank()) return DocumentResult.CannotRender("blank overview")
        val files = ArrayList<FileRemarks>()
        val filesEl = obj.get("files")
        if (filesEl != null && !filesEl.isJsonNull) {
            if (!filesEl.isJsonArray) return DocumentResult.CannotRender("files is not an array")
            for (f in filesEl.asJsonArray) {
                if (!f.isJsonObject) return DocumentResult.CannotRender("files entry is not an object")
                val fo = f.asJsonObject
                val path = fo.string("path")?.takeIf { it.isNotEmpty() }
                    ?: return DocumentResult.CannotRender("files entry without path")
                val remarks = ArrayList<Remark>()
                val ann = fo.get("annotations")
                if (ann != null && !ann.isJsonNull) {
                    if (!ann.isJsonArray) return DocumentResult.CannotRender("annotations of $path is not an array")
                    for (a in ann.asJsonArray) {
                        remarks += decodeRemark(a, path) ?: return DocumentResult.CannotRender("bad annotation in $path")
                    }
                }
                files += FileRemarks(path, remarks)
            }
        }
        return DocumentResult.Ok(OverviewDocument(title.ifBlank { recordTitle }, overview, files))
    }

    private fun decodeRemark(a: JsonElement, path: String): Remark? {
        if (!a.isJsonObject) return null
        val o = a.asJsonObject
        val range = o.get("newRange")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        if (range.size() != 2 || !range[0].isNumber() || !range[1].isNumber()) return null
        val first = range[0].asInt
        val last = range[1].asInt
        if (first < 1 || last < first) return null
        val summary = o.string("summary")?.takeIf { it.isNotBlank() } ?: return null
        val rationale = o.string("rationale")?.takeIf { it.isNotEmpty() }
        return Remark(first, last, summary, rationale)
    }

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonElement.isNumber(): Boolean = isJsonPrimitive && asJsonPrimitive.isNumber
}
