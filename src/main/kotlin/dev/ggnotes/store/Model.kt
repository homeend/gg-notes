package dev.ggnotes.store

import java.nio.file.Path
import java.time.OffsetDateTime

/** SPEC §5 `files[].annotations[]`: a remark on lines [first]..[last] (1-based, inclusive) at the overview's commit. */
data class Remark(val first: Int, val last: Int, val summary: String, val rationale: String?)

/** SPEC §5 `files[]`: one anchored file and its remarks, in the author's order. */
data class FileRemarks(val path: String, val annotations: List<Remark>)

/** SPEC §5: the decoded document (version 1). */
data class OverviewDocument(val title: String, val overview: String, val files: List<FileRemarks>)

/** The document of an overview record, or why it cannot be rendered (SPEC §8.2). */
sealed class DocumentResult {
    data class Ok(val document: OverviewDocument) : DocumentResult()
    data class CannotRender(val reason: String) : DocumentResult()
}

/** SPEC §4: one overview record, with the fields a reader shows. */
data class Overview(
    /** Machine-local; never an identity across tools (SPEC §1.2). */
    val id: String,
    val title: String,
    val author: String,
    /** Full sha of the commit the overview explains. */
    val commit: String,
    /** Branch recorded at save time, or empty. */
    val branch: String,
    /** `<base>...refs/gg/pr/<n>` on a PR overview, else null. */
    val preview: String?,
    /** PR number parsed from [preview], else null. */
    val prNumber: Int?,
    val created: OffsetDateTime?,
    val updated: OffsetDateTime?,
    val document: DocumentResult,
    /** The part the record came from (`commits.toml` / `previews.toml`). */
    val sourceFile: Path,
    /**
     * Empty for a real overview. A review or a note thread shown through the
     * overview viewer sets it (e.g. `review:<id>`) so its identity never
     * collides with an overview's (commit, title) and anchors of a live
     * document resolve against the working tree instead of the commit.
     */
    val viewKind: String = "",
    /** Set when the document's files are the WORKING TREE of this worktree, not the commit. */
    val liveWorktree: String? = null,
) {
    val shortSha: String get() = commit.take(7)

    /** SPEC §4.4: (commit or PR scope, title) names one overview; other view kinds key on their record. */
    val identity: String get() = viewKind + (prNumber?.let { "pr:$it" } ?: commit) + "\u0000" + title.trim()

    val isLive: Boolean get() = liveWorktree != null
}

/** Everything a reader found in one store directory. */
data class StoreContents(
    /** The part-1 view: the overview records, decoded. */
    val overviews: List<Overview>,
    /** A part had `format` > 3: readable, possibly incomplete (SPEC §8.3). */
    val newerFormat: Boolean,
    /** Files that could not be parsed even after a retry (SPEC §8.1). */
    val problems: List<String>,
    /** Every classified record of every part (SPEC-part2 §1.1), unknown kinds included. */
    val records: List<NoteRecord> = emptyList(),
    /** `[[resolved]]` entries of every part (SPEC-part2 §4.2). */
    val resolved: List<Resolution> = emptyList(),
) {
    companion object {
        val EMPTY = StoreContents(emptyList(), newerFormat = false, problems = emptyList())
    }
}
