package dev.ggnotes.items

import java.time.LocalDate
import java.time.ZoneId

/** Items of one local day; [day] == null is the "No date" group. */
data class DayGroup(val day: LocalDate?, val items: List<NoteItem>)

/** Items of one kind, grouped by day. */
data class TypeGroup(val kind: ItemKind, val days: List<DayGroup>)

object ItemViews {
    private val newestFirst: Comparator<NoteItem> =
        compareByDescending<NoteItem> { it.time }.thenBy { it.id }

    /** Local days newest first, the undated group last; items newest first, then by id. Children are not regrouped. */
    fun byDate(items: List<NoteItem>, zone: ZoneId): List<DayGroup> {
        val byDay = items.groupBy { item -> item.time?.atZone(zone)?.toLocalDate() }
        val dated = byDay.filterKeys { it != null }.toSortedMap(compareByDescending { it!! })
        val groups = dated.map { (day, list) -> DayGroup(day, list.sortedWith(newestFirst)) }
        val undated = byDay[null]?.let { DayGroup(null, it.sortedWith(newestFirst)) }
        return if (undated == null) groups else groups + undated
    }

    /** Kinds in declaration order, empty ones left out, days inside. */
    fun byType(items: List<NoteItem>, zone: ZoneId): List<TypeGroup> =
        ItemKind.values().mapNotNull { kind ->
            val of = items.filter { it.kind == kind }
            if (of.isEmpty()) null else TypeGroup(kind, byDate(of, zone))
        }

    /** The grey label after a row's title (spec §4.4 owner label). */
    fun ownerLabel(item: NoteItem): String {
        val o = item.owner
        return when (item.kind) {
            ItemKind.PULL_REQUEST -> if (o.pr != null) "PR #${o.pr}" else o.preview.orEmpty()
            ItemKind.BRANCH -> o.branch.orEmpty()
            ItemKind.COMMIT -> o.commit.orEmpty().take(7)
            ItemKind.FILE -> {
                val path = o.path.orEmpty()
                val range = when {
                    o.first == null -> path
                    o.last == null || o.last == o.first -> "$path:${o.first}"
                    else -> "$path:${o.first}-${o.last}"
                }
                if (o.commit != null) "$range · ${o.commit.take(7)}" else range
            }
            ItemKind.OTHER -> if ((item.source == ItemSource.STORE_THREAD || item.source == ItemSource.STORE_OVERVIEW || item.source == ItemSource.STORE_REVIEW) && o.worktree != null) "working" else "session"
        }
    }
}
