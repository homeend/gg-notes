package dev.ggnotes.store

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.tomlj.Toml
import org.tomlj.TomlParseResult
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import kotlin.io.path.name

/**
 * SPEC part 1 §4–§5, §8 and part 2 §1.1–§2: reads every part of one store
 * directory, classifies each record, and returns the records plus the
 * part-1 overview view. No IDE types; never writes.
 */
class StoreReader(private val retryDelayMillis: Long = 150) {

    companion object {
        /** The commit-level parts (SPEC part 1 §3.3) plus the legacy pre-split file (SHOULD read). */
        val PARTS = listOf("commits.toml", "previews.toml", "notes.toml")
        const val WORKTREES_DIR = "worktrees"
        const val KNOWN_FORMAT = 3L
    }

    fun read(storeDir: Path): StoreContents {
        if (!Files.isDirectory(storeDir)) return StoreContents.EMPTY
        val files = ArrayList<Path>()
        for (name in PARTS) {
            val f = storeDir.resolve(name)
            if (Files.isRegularFile(f)) files.add(f)
        }
        files.addAll(worktreeParts(storeDir))
        val records = ArrayList<NoteRecord>()
        val resolved = ArrayList<Resolution>()
        val problems = ArrayList<String>()
        var newer = false
        for (file in files) {
            val parsed = parseWithRetry(file)
            if (parsed == null || parsed.hasErrors()) {
                val first = parsed?.errors()?.firstOrNull()?.toString() ?: "unreadable"
                problems += "${storeDir.relativize(file)}: $first"
                continue
            }
            val root = normalize(parsed) as Map<*, *>
            val format = (root["format"] as? Number)?.toLong() ?: 0L
            if (format > KNOWN_FORMAT) newer = true
            for (raw in root["notes"].asList()) {
                val table = raw as? Map<*, *> ?: continue
                val record = try {
                    toRecord(table, file)
                } catch (e: RuntimeException) {
                    problems += "${storeDir.relativize(file)}: record ${table["id"]}: $e"
                    continue
                }
                if (file.parent?.name == WORKTREES_DIR && !passesWorktreeSelfCheck(record, file)) continue
                records += record
            }
            for (raw in root["resolved"].asList()) {
                val table = raw as? Map<*, *> ?: continue
                val rootId = table.str("root") ?: continue
                resolved += Resolution(rootId, table.str("by"), table.date("at"), table.str("remark_fp"))
            }
        }
        val overviews = records.filter { it.kind == RecordKind.OVERVIEW }.map { toOverview(it) }
        return StoreContents(overviews, newer, problems, records, resolved)
    }

    /** Every `.toml` under `worktrees` except `unscoped.toml` (SPEC-part2 §5.1); lock, tmp and corrupt files never match. */
    private fun worktreeParts(storeDir: Path): List<Path> {
        val dir = storeDir.resolve(WORKTREES_DIR)
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.name.endsWith(".toml") && it.name != "unscoped.toml" }
                .sorted().toList()
        }
    }

    /** §5.1: `wkey == sha256(Worktree)[:8]`, the file name proving the record belongs to that checkout. */
    private fun passesWorktreeSelfCheck(record: NoteRecord, file: Path): Boolean {
        val worktree = record.address.worktree
        if (worktree.isEmpty()) return false
        return file.name.removeSuffix(".toml") == StoreLocator.key(worktree, windows = false) ||
            file.name.removeSuffix(".toml") == StoreLocator.key(worktree, windows = true)
    }

    /** tomlj keeps TomlTable/TomlArray objects inside toMap(); turn the whole tree into plain Maps and Lists. */
    private fun normalize(v: Any?): Any? = when (v) {
        is org.tomlj.TomlTable -> v.toMap().mapValues { normalize(it.value) }
        is org.tomlj.TomlArray -> v.toList().map { normalize(it) }
        is Map<*, *> -> v.mapValues { normalize(it.value) }
        is List<*> -> v.map { normalize(it) }
        else -> v
    }

    /** SPEC §8.1: a torn read across gg's rename is momentary — retry once. */
    private fun parseWithRetry(file: Path): TomlParseResult? {
        val first = runCatching { Toml.parse(file) }.getOrNull()
        if (first != null && !first.hasErrors()) return first
        Thread.sleep(retryDelayMillis)
        return runCatching { Toml.parse(file) }.getOrNull() ?: first
    }

    // ---- record decoding ------------------------------------------------------------------

    private fun toRecord(t: Map<*, *>, file: Path): NoteRecord {
        val addressMap = t["address"] as? Map<*, *> ?: emptyMap<Any, Any>()
        val address = Address(
            worktree = addressMap.str("Worktree").orEmpty(),
            branch = addressMap.str("Branch").orEmpty(),
            commit = addressMap.str("Commit").orEmpty(),
            shelfId = addressMap.str("ShelfID").orEmpty(),
            path = addressMap.str("Path").orEmpty(),
            state = (addressMap["State"] as? Number)?.toInt() ?: 0,
        )
        val (tags, tagMeta) = LegacyMeta.splitTags(t["tags"].asList().map { it.toString() })
        val meta = ArrayList<MetaEntry>()
        for (m in t["meta"].asList()) {
            val mm = m as? Map<*, *> ?: continue
            val key = mm.str("key") ?: continue
            meta += MetaEntry(key, mm.str("value").orEmpty())
        }
        meta += tagMeta
        (t["confidence"] as? Number)?.let { c -> LegacyMeta.confidenceWord(c.toDouble())?.let { meta += MetaEntry("confidence", it) } }
        val added = t["added"].asList().mapNotNull { a ->
            val am = a as? Map<*, *> ?: return@mapNotNull null
            AddedMeta(am.str("key") ?: return@mapNotNull null, am.str("value").orEmpty(), am.str("by"), am.date("at"), am.str("remark_fp"))
        }
        val files = t["files"].asList().mapNotNull { f ->
            val fm = f as? Map<*, *> ?: return@mapNotNull null
            ReviewedFile(fm.str("path") ?: return@mapNotNull null, fm.str("blob"), fm["deleted"] == true)
        }
        val sends = t["remark_sends"].asList().mapNotNull { s ->
            val sm = s as? Map<*, *> ?: return@mapNotNull null
            RemarkSend(sm.str("remark_fp") ?: return@mapNotNull null, sm["moved"] == true)
        }
        val range = t["range"].asList().map { (it as? Number)?.toInt() ?: 0 }
        val parentId = t.str("parent_id")?.takeIf { it.isNotEmpty() }
        val preview = t.str("preview")?.takeIf { it.isNotEmpty() }
        return NoteRecord(
            id = t.str("id").orEmpty(),
            parentId = parentId,
            source = t.str("source").orEmpty(),
            author = t.str("author").orEmpty(),
            side = t.str("side")?.takeIf { it.isNotEmpty() } ?: "new",
            first = range.getOrElse(0) { 0 },
            last = range.getOrElse(1) { 0 },
            contextHash = t.str("context_hash").orEmpty(),
            summary = t.str("summary").orEmpty(),
            rationale = t.str("rationale").orEmpty(),
            tags = tags,
            scope = t.str("scope")?.takeIf { it.isNotEmpty() },
            preview = preview,
            previewBase = t.str("preview_base")?.takeIf { it.isNotEmpty() },
            previewBranch = t.str("preview_branch")?.takeIf { it.isNotEmpty() },
            created = t.date("created"),
            updated = t.date("updated"),
            address = address,
            meta = meta,
            display = DisplayDecls.fromMap(t["display"] as? Map<*, *>),
            added = added,
            link = t.str("link")?.takeIf { it.isNotEmpty() },
            remark = t.str("remark")?.takeIf { it.isNotEmpty() },
            remarkFp = t.str("remark_fp")?.takeIf { it.isNotEmpty() },
            remarkSummary = t.str("remark_summary")?.takeIf { it.isNotEmpty() },
            files = files,
            remarkSends = sends,
            sourceFile = file,
            kind = RecordClassifier.classify(tags, parentId, address, preview),
        )
    }

    private fun Any?.asList(): List<Any?> = this as? List<Any?> ?: emptyList()
    private fun Map<*, *>.str(key: String): String? = this[key] as? String
    private fun Map<*, *>.date(key: String): OffsetDateTime? = this[key] as? OffsetDateTime

    // ---- the part-1 overview view -------------------------------------------------------

    private fun toOverview(r: NoteRecord): Overview = Overview(
        id = r.id,
        title = r.summary,
        author = r.author,
        commit = r.address.commit,
        branch = r.address.branch,
        preview = r.preview,
        prNumber = r.prNumber,
        created = r.created,
        updated = r.updated,
        document = decodeDocument(r.rationale, r.summary),
        sourceFile = r.sourceFile,
    )

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
                        remarks += decodeRemark(a) ?: return DocumentResult.CannotRender("bad annotation in $path")
                    }
                }
                files += FileRemarks(path, remarks)
            }
        }
        return DocumentResult.Ok(OverviewDocument(title.ifBlank { recordTitle }, overview, files))
    }

    private fun decodeRemark(a: JsonElement): Remark? {
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

/** Decodes a `display` declaration from a TOML table map or a JSON object (SPEC-part2 §6.2). */
object DisplayDecls {

    fun fromMap(m: Map<*, *>?): Map<String, DisplayDecl> {
        if (m == null) return emptyMap()
        val out = LinkedHashMap<String, DisplayDecl>()
        for ((k, v) in m) {
            val key = k as? String ?: continue
            val decl = v as? Map<*, *> ?: continue
            out[key] = DisplayDecl(
                show = (decl["show"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
                label = decl["label"] as? String,
                order = (decl["order"] as? Number)?.toInt(),
                style = decl["style"] as? String,
                asKind = decl["as"] as? String,
                values = (decl["values"] as? Map<*, *>)?.entries?.mapNotNull { (vk, vv) ->
                    val name = vk as? String ?: return@mapNotNull null
                    name to valueFromMap(vv as? Map<*, *> ?: return@mapNotNull null)
                }?.toMap().orEmpty(),
                other = (decl["other"] as? Map<*, *>)?.let { valueFromMap(it) },
            )
        }
        return out
    }

    private fun valueFromMap(m: Map<*, *>) = DisplayValue(
        color = m["color"] as? String, glyph = m["glyph"] as? String,
        level = m["level"] as? String, audience = m["audience"] as? String,
    )

    fun fromJson(o: JsonObject?): Map<String, DisplayDecl> {
        if (o == null) return emptyMap()
        val out = LinkedHashMap<String, DisplayDecl>()
        for ((key, v) in o.entrySet()) {
            val decl = v.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            out[key] = DisplayDecl(
                show = decl.get("show")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString } ?: emptyList(),
                label = decl.str("label"),
                order = decl.get("order")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt,
                style = decl.str("style"),
                asKind = decl.str("as"),
                values = decl.get("values")?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()
                    ?.mapNotNull { (name, vv) -> vv.takeIf { it.isJsonObject }?.let { name to valueFromJson(it.asJsonObject) } }?.toMap().orEmpty(),
                other = decl.get("other")?.takeIf { it.isJsonObject }?.let { valueFromJson(it.asJsonObject) },
            )
        }
        return out
    }

    private fun valueFromJson(o: JsonObject) = DisplayValue(o.str("color"), o.str("glyph"), o.str("level"), o.str("audience"))

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
}
