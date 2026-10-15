package dev.ggnotes.store

import java.nio.file.Path
import java.time.OffsetDateTime

/** SPEC-part2 §1.1: what a record is, decided by tags, address and parent_id alone. */
enum class RecordKind { OVERVIEW, REVIEW, WORKING_REVIEW, LINE_NOTE, REPLY, FORGE_DRAFT, UNKNOWN }

/** `address.State` (gg's model.FileState). */
object FileState {
    const val COMMITTED = 0
    const val SHELF = 1
    const val STAGED = 2
    const val UNSTAGED = 3
    const val UNTRACKED = 4
    fun isLive(state: Int) = state == STAGED || state == UNSTAGED || state == UNTRACKED
}

data class Address(
    val worktree: String,
    val branch: String,
    val commit: String,
    val shelfId: String,
    val path: String,
    val state: Int,
)

/** The author's free key/value (SPEC-part2 §6.1). */
data class MetaEntry(val key: String, val value: String)

/** Meta written onto a note later by someone else; replaces the author's value of that key for display. */
data class AddedMeta(val key: String, val value: String, val by: String?, val at: OffsetDateTime?, val remarkFp: String?)

/** One value's presentation in a display declaration (§6.2). */
data class DisplayValue(val color: String?, val glyph: String?, val level: String?, val audience: String?)

/** How one meta KEY is shown (§6.2). */
data class DisplayDecl(
    val show: List<String>,
    val label: String?,
    val order: Int?,
    val style: String?,
    val asKind: String?,
    val values: Map<String, DisplayValue>,
    val other: DisplayValue?,
)

/** A working review's fingerprint of one file it read (§5.1). */
data class ReviewedFile(val path: String, val blob: String?, val deleted: Boolean)

/** A review remark sent to a forge; `moved` means the forge now holds it and gg hides it (§2). */
data class RemarkSend(val remarkFp: String, val moved: Boolean)

/** A `[[resolved]]` entry: a thread marked resolved (§4.2). */
data class Resolution(val root: String, val by: String?, val at: OffsetDateTime?, val remarkFp: String?)

/** One `[[notes]]` record of any kind, with the keys this reader uses (SPEC part 1 §4.2, part 2 §2). */
data class NoteRecord(
    val id: String,
    val parentId: String?,
    val source: String,
    val author: String,
    /** `new` or `old`. */
    val side: String,
    val first: Int,
    val last: Int,
    val contextHash: String,
    val summary: String,
    val rationale: String,
    /** Tags after the legacy `key: value` conversion. */
    val tags: List<String>,
    val scope: String?,
    val preview: String?,
    val previewBase: String?,
    val previewBranch: String?,
    val created: OffsetDateTime?,
    val updated: OffsetDateTime?,
    val address: Address,
    val meta: List<MetaEntry>,
    val display: Map<String, DisplayDecl>,
    val added: List<AddedMeta>,
    val link: String?,
    val remark: String?,
    val remarkFp: String?,
    val remarkSummary: String?,
    val files: List<ReviewedFile>,
    val remarkSends: List<RemarkSend>,
    val sourceFile: Path,
    val kind: RecordKind,
) {
    val isLive: Boolean get() = FileState.isLive(address.state)

    /** A committed line note written in a PR view, merge preview or commit pair (§1.1 "scoped"). */
    val scoped: Boolean get() = kind == RecordKind.LINE_NOTE && !preview.isNullOrEmpty()

    val prNumber: Int? get() = preview?.let { PR_SCOPE.find(it)?.groupValues?.get(1)?.toIntOrNull() }

    val shortSha: String get() = address.commit.take(7)

    /** `range = [0, 0]`: a review, an overview, a working review. */
    val isEntryLevel: Boolean get() = first == 0 && last == 0

    companion object {
        val PR_SCOPE = Regex("""\.\.\.refs/gg/pr/(\d+)$""")
    }
}

/** SPEC-part2 §1.1, the one predicate table. */
object RecordClassifier {
    fun classify(tags: List<String>, parentId: String?, address: Address, preview: String?): RecordKind {
        if (!parentId.isNullOrEmpty()) return if (parentId.startsWith("forge:")) RecordKind.FORGE_DRAFT else RecordKind.REPLY
        val a = address
        if (a.state == FileState.SHELF) return RecordKind.UNKNOWN
        if (a.path.isEmpty()) {
            if (a.state == FileState.COMMITTED && a.commit.isNotEmpty()) {
                if ("overview" in tags) return RecordKind.OVERVIEW
                if ("review" in tags) return RecordKind.REVIEW
                return RecordKind.UNKNOWN
            }
            if (FileState.isLive(a.state) && a.commit.isEmpty() && a.worktree.isNotEmpty() && "review" in tags) {
                return RecordKind.WORKING_REVIEW
            }
            return RecordKind.UNKNOWN
        }
        if (a.state == FileState.COMMITTED && a.commit.isNotEmpty()) return RecordKind.LINE_NOTE
        if (FileState.isLive(a.state) && a.worktree.isNotEmpty()) return RecordKind.LINE_NOTE
        return RecordKind.UNKNOWN
    }
}
