package dev.ggnotes.store

import java.time.format.DateTimeFormatter

/** Pure labelling rules of the tool window (AGENTS.md §4, examples/README.md): an overview row's tooltip. */
object OverviewGrouping {

    private val DATE = DateTimeFormatter.ISO_LOCAL_DATE

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
}
