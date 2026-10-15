package dev.ggnotes.store

import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/** The overviews of one commit, as one group of the tool window tree. */
data class CommitGroup(
    val commit: String,
    val isHead: Boolean,
    /** The commit's subject, or null when the commit is not in the repository (SPEC §8.2). */
    val subject: String?,
    /** Newest `updated` first. */
    val overviews: List<Overview>,
) {
    val shortSha: String get() = commit.take(7)
    val newest: OffsetDateTime? get() = overviews.firstOrNull()?.updated
}

/** Pure ordering and labelling rules of the tool window (AGENTS.md §4, examples/README.md). */
object OverviewGrouping {

    private val DATE = DateTimeFormatter.ISO_LOCAL_DATE
    private val EPOCH: OffsetDateTime = OffsetDateTime.parse("1970-01-01T00:00:00Z")

    /** Group by commit: HEAD's group first, then by the group's newest `updated`, descending. */
    fun group(overviews: List<Overview>, headSha: String?, subjects: Map<String, String?>): List<CommitGroup> {
        return overviews.groupBy { it.commit }
            .map { (commit, list) ->
                CommitGroup(
                    commit = commit,
                    isHead = headSha != null && commit == headSha,
                    subject = subjects[commit],
                    overviews = list.sortedByDescending { it.updated ?: EPOCH },
                )
            }
            .sortedWith(compareByDescending<CommitGroup> { it.isHead }.thenByDescending { it.newest ?: EPOCH })
    }

    /** `title · author · date · short sha[ · branch | · PR #n]`. */
    fun rowText(o: Overview): String {
        val parts = ArrayList<String>()
        parts += o.title.ifBlank { "(untitled)" }
        if (o.author.isNotBlank()) parts += o.author
        o.updated?.let { parts += DATE.format(it.toLocalDate()) }
        parts += o.shortSha
        when {
            o.prNumber != null -> parts += "PR #${o.prNumber}"
            o.branch.isNotBlank() -> parts += o.branch
        }
        return parts.joinToString(" · ")
    }

    /** `short sha  subject  (HEAD)`, or a note that the commit is unknown here. */
    fun groupText(g: CommitGroup): String {
        val sb = StringBuilder(g.shortSha)
        sb.append("  ").append(g.subject ?: "(commit not in this repository)")
        if (g.isHead) sb.append("  (HEAD)")
        return sb.toString()
    }
}
