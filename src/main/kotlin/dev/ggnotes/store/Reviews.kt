package dev.ggnotes.store

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.security.MessageDigest

enum class RemarkSide { NEW, OLD }

/** One annotation of a review document (SPEC-part2 §3.2), numbered in document order across all files. */
data class ReviewRemark(
    val index: Int,
    val path: String,
    val side: RemarkSide,
    val first: Int,
    val last: Int,
    val summary: String,
    val rationale: String?,
    val meta: Map<String, String>,
    val display: Map<String, DisplayDecl>,
    /** §3.4 fingerprint. */
    val fp: String,
)

data class ReviewFile(
    val path: String,
    val summary: String?,
    val meta: Map<String, String>,
    val display: Map<String, DisplayDecl>,
    val remarks: List<ReviewRemark>,
)

/** SPEC-part2 §3.2, decoded. */
data class ReviewDocument(
    val summary: String,
    val overview: String?,
    val meta: Map<String, String>,
    val display: Map<String, DisplayDecl>,
    val files: List<ReviewFile>,
) {
    val remarks: List<ReviewRemark> get() = files.flatMap { it.remarks }
}

sealed class ReviewContent {
    data class Document(val document: ReviewDocument) : ReviewContent()
    /** §3.1: an agent's reply gg could not parse, shown as markdown with no remarks. */
    data class Prose(val markdown: String) : ReviewContent()
    data class CannotRender(val reason: String) : ReviewContent()
}

/** §3.3 (+ §5 working changes). */
enum class ReviewKind { COMMIT, RANGE, BRANCH, PULL_REQUEST, MERGE_PREVIEW, PAIR, WORKING }

/** One counted meta value of a review: `n` remarks carry `key = value` (§6.2 `count`). */
data class MetaCount(val key: String, val value: String, val count: Int)

/** A review record with its document, its threads and the scoped line notes gg shows under it. */
data class Review(
    val record: NoteRecord,
    val content: ReviewContent,
    val kind: ReviewKind,
    /** The old side of the reviewed diff: `<a>` of `scope`, `<sha>^` for one commit, null when unknown. */
    val scopeBase: String?,
    val scopeTip: String?,
    /** Replies to the review as a whole (§3.5). */
    val replies: List<NoteRecord>,
    /** Replies per remark index. */
    val remarkReplies: Map<Int, List<NoteRecord>>,
    /** Remark replies whose remark could be matched neither by fingerprint nor by index (§3.4). */
    val unmatchedReplies: List<NoteRecord>,
    val resolvedRemarks: Set<Int>,
    /** Committed line notes written in this review's scope (§1.1 "scoped"), shown under its row (§8). */
    val scopedNotes: List<NoteRecord>,
) {
    val id: String get() = record.id
    val title: String get() = record.summary
    val document: ReviewDocument? get() = (content as? ReviewContent.Document)?.document
    private val movedFps: Set<String> get() = record.remarkSends.filter { it.moved }.map { it.remarkFp }.toSet()

    /** Remarks that are placed and counted: every remark not moved to the forge (§2 `remark_sends`). */
    val activeRemarks: List<ReviewRemark> get() = document?.remarks?.filter { it.fp !in movedFps }.orEmpty()

    fun isMoved(remark: ReviewRemark): Boolean = remark.fp in movedFps

    /** Effective display for a remark: document ← file ← remark, nearest wins per key (§6.2 inheritance). */
    fun effectiveDisplay(remark: ReviewRemark): Map<String, DisplayDecl> {
        val doc = document ?: return emptyMap()
        val file = doc.files.firstOrNull { it.path == remark.path }
        return doc.display + (file?.display ?: emptyMap()) + remark.display
    }

    /** §8: "the counts of its count-declared meta", over the active remarks, in declaration order. */
    fun counts(): List<MetaCount> {
        val tally = LinkedHashMap<Pair<String, String>, Int>()
        val order = HashMap<Pair<String, String>, Triple<Int, String, Int>>()
        for (remark in activeRemarks) {
            val display = effectiveDisplay(remark)
            for ((key, value) in remark.meta) {
                val decl = display[key] ?: continue
                if ("count" !in decl.show) continue
                val k = key to value
                tally[k] = (tally[k] ?: 0) + 1
                val valueIndex = decl.values.keys.indexOf(value).let { if (it < 0) Int.MAX_VALUE else it }
                order[k] = Triple(decl.order ?: Int.MAX_VALUE, key, valueIndex)
            }
        }
        return tally.entries
            .sortedWith(compareBy({ order.getValue(it.key).first }, { order.getValue(it.key).second }, { order.getValue(it.key).third }, { it.key.second }))
            .map { MetaCount(it.key.first, it.key.second, it.value) }
    }
}

object Reviews {

    private val PR_SCOPE = NoteRecord.PR_SCOPE
    private val REMARK_REF = Regex("""^review:([0-9a-f]+):(\d+)$""")

    /** §3.4: `hex(sha256(path\0side\0first\0last\0summary)[:8])`, one leading `./` stripped from the path. */
    fun remarkFp(path: String, side: String, first: Int, last: Int, summary: String): String {
        val p = if (path.startsWith("./")) path.substring(2) else path
        val bytes = "$p\u0000$side\u0000$first\u0000$last\u0000$summary".toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes).take(8).joinToString("") { "%02x".format(it) }
    }

    /** §3.3 by the record's fields. */
    fun kindOf(scope: String?, preview: String?, branch: String, live: Boolean): ReviewKind {
        if (live) return ReviewKind.WORKING
        if (!preview.isNullOrEmpty()) {
            if (PR_SCOPE.containsMatchIn(preview)) return ReviewKind.PULL_REQUEST
            if ("..." in preview) return ReviewKind.MERGE_PREVIEW
            return ReviewKind.PAIR
        }
        if (branch.isNotBlank()) return ReviewKind.BRANCH
        if (scope.isNullOrEmpty() || ".." !in scope) return ReviewKind.COMMIT
        val (a, b) = scope.split("..", limit = 2)
        return if (a == "$b^") ReviewKind.COMMIT else ReviewKind.RANGE
    }

    /** `scope` → (base, tip): `a..b` → (a, b); `sha^..sha` → (sha^, sha); a bare sha → (null, sha). */
    fun parseScope(scope: String?): Pair<String?, String?> {
        if (scope.isNullOrEmpty()) return null to null
        val i = scope.indexOf("..")
        if (i < 0) return null to scope
        return scope.substring(0, i) to scope.substring(i + 2)
    }

    fun countsText(counts: List<MetaCount>): String = counts.joinToString(" · ") { "${it.count} ${it.value}" }

    /** Every review record of the store with its threads and scoped notes attached. */
    fun build(contents: StoreContents): List<Review> {
        val replies = contents.records.filter { it.kind == RecordKind.REPLY }.groupBy { it.parentId!! }
        val scoped = contents.records.filter { it.scoped }
        return contents.records
            .filter { it.kind == RecordKind.REVIEW || it.kind == RecordKind.WORKING_REVIEW }
            .map { record ->
                val content = decode(record.rationale)
                val remarks = (content as? ReviewContent.Document)?.document?.remarks.orEmpty()
                val whole = ArrayList<NoteRecord>()
                val perRemark = LinkedHashMap<Int, MutableList<NoteRecord>>()
                val unmatched = ArrayList<NoteRecord>()
                for (reply in replies[record.id].orEmpty().sortedBy { it.created }) {
                    val ref = reply.remark?.let { REMARK_REF.matchEntire(it) }
                    if (ref == null) {
                        whole += reply
                        continue
                    }
                    val n = ref.groupValues[2].toInt()
                    val byFp = reply.remarkFp?.let { fp -> remarks.firstOrNull { it.fp == fp } }
                    val target = byFp ?: remarks.firstOrNull { it.index == n }
                    if (target == null) unmatched += reply else perRemark.getOrPut(target.index) { ArrayList() }.add(reply)
                }
                val resolved = HashSet<Int>()
                for (r in contents.resolved) {
                    val ref = REMARK_REF.matchEntire(r.root)
                    if (ref != null && ref.groupValues[1] == record.id) {
                        val byFp = r.remarkFp?.let { fp -> remarks.firstOrNull { it.fp == fp } }
                        resolved += byFp?.index ?: ref.groupValues[2].toInt()
                    }
                }
                val (base, tip) = parseScope(record.scope)
                Review(
                    record = record,
                    content = content,
                    kind = kindOf(record.scope, record.preview, record.address.branch, record.isLive),
                    scopeBase = base,
                    scopeTip = tip ?: record.address.commit.takeIf { it.isNotEmpty() },
                    replies = whole,
                    remarkReplies = perRemark,
                    unmatchedReplies = unmatched,
                    resolvedRemarks = resolved,
                    scopedNotes = scoped.filter { sameScope(it, record) },
                )
            }
    }

    private fun sameScope(note: NoteRecord, review: NoteRecord): Boolean {
        if (review.preview.isNullOrEmpty() || note.preview.isNullOrEmpty()) return false
        val pr = review.prNumber
        if (pr != null) return note.prNumber == pr
        return note.preview == review.preview
    }

    // ---- the document --------------------------------------------------------------------

    /** §3.2: JSON → document; not JSON → prose; JSON that breaks the rules → cannot render. */
    fun decode(rationale: String): ReviewContent {
        val root = try {
            JsonParser.parseString(rationale)
        } catch (e: RuntimeException) {
            return ReviewContent.Prose(rationale)
        }
        if (root == null || !root.isJsonObject) return ReviewContent.Prose(rationale)
        val o = root.asJsonObject
        val version = o.get("version")?.takeIf { it.isNumber() }?.asNumber?.toDouble()
        if (version != 1.0) return ReviewContent.CannotRender("unsupported review document version ${o.get("version")}")
        val summary = o.string("summary")?.takeIf { it.isNotBlank() } ?: return ReviewContent.CannotRender("missing summary")
        val overview = o.string("overview")?.takeIf { it.isNotBlank() }
        val files = ArrayList<ReviewFile>()
        var index = 0
        val filesEl = o.get("files")
        if (filesEl != null && !filesEl.isJsonNull) {
            if (!filesEl.isJsonArray) return ReviewContent.CannotRender("files is not an array")
            for (f in filesEl.asJsonArray) {
                if (!f.isJsonObject) return ReviewContent.CannotRender("files entry is not an object")
                val fo = f.asJsonObject
                val path = fo.string("path")?.takeIf { it.isNotEmpty() } ?: return ReviewContent.CannotRender("files entry without path")
                val remarks = ArrayList<ReviewRemark>()
                val ann = fo.get("annotations")
                if (ann != null && !ann.isJsonNull) {
                    if (!ann.isJsonArray) return ReviewContent.CannotRender("annotations of $path is not an array")
                    for (a in ann.asJsonArray) {
                        remarks += decodeRemark(a, path, index) ?: return ReviewContent.CannotRender("bad annotation ${index} in $path")
                        index++
                    }
                }
                files += ReviewFile(path, fo.string("summary"), metaOf(fo.get("meta")), DisplayDecls.fromJson(fo.get("display")?.takeIf { it.isJsonObject }?.asJsonObject), remarks)
            }
        }
        return ReviewContent.Document(ReviewDocument(summary, overview, metaOf(o.get("meta")), DisplayDecls.fromJson(o.get("display")?.takeIf { it.isJsonObject }?.asJsonObject), files))
    }

    private fun decodeRemark(a: JsonElement, path: String, index: Int): ReviewRemark? {
        if (!a.isJsonObject) return null
        val o = a.asJsonObject
        val newRange = o.get("newRange")?.takeIf { it.isJsonArray }?.asJsonArray
        val oldRange = o.get("oldRange")?.takeIf { it.isJsonArray }?.asJsonArray
        if ((newRange == null) == (oldRange == null)) return null
        val range = newRange ?: oldRange!!
        if (range.size() != 2 || !range[0].isNumber() || !range[1].isNumber()) return null
        val first = range[0].asInt
        val last = range[1].asInt
        if (first < 1 || last < first) return null
        val summary = o.string("summary")?.takeIf { it.isNotBlank() } ?: return null
        val side = if (newRange != null) RemarkSide.NEW else RemarkSide.OLD
        val cleanPath = if (path.startsWith("./")) path.substring(2) else path
        return ReviewRemark(
            index = index, path = cleanPath, side = side, first = first, last = last, summary = summary,
            rationale = o.string("rationale")?.takeIf { it.isNotEmpty() },
            meta = metaOf(o.get("meta")),
            display = DisplayDecls.fromJson(o.get("display")?.takeIf { it.isJsonObject }?.asJsonObject),
            fp = remarkFp(cleanPath, if (side == RemarkSide.NEW) "new" else "old", first, last, summary),
        )
    }

    /** §6.1: a JSON object of values; a non-string value is its compact JSON text. */
    private fun metaOf(el: JsonElement?): Map<String, String> {
        if (el == null || !el.isJsonObject) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for ((k, v) in el.asJsonObject.entrySet()) {
            out[k] = if (v.isJsonPrimitive && v.asJsonPrimitive.isString) v.asString else v.toString()
        }
        return out
    }

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonElement.isNumber(): Boolean = isJsonPrimitive && asJsonPrimitive.isNumber
}
