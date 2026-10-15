package dev.ggnotes.store

import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * The note threads of one file as the document the viewer shows (SPEC-part2
 * §8): each placeable thread is an anchor into the file at its (relocated)
 * lines and an overlay box; old-side notes, forge drafts and orphaned notes
 * are listed with a mark; stale ones are placed and marked.
 */
object NoteComposer {

    private val DATE = DateTimeFormatter.ISO_LOCAL_DATE

    /** SPEC-part2 §4.1 lifecycle: gg's default `[notes] max_age_days`. */
    const val SWEEP_AGE_DAYS = 30L

    /** True when gg's next startup sweep in that repository would drop this line note for age. */
    fun sweepDue(note: NoteRecord, now: OffsetDateTime): Boolean {
        val created = note.created ?: return false
        return created.plusDays(SWEEP_AGE_DAYS).isBefore(now)
    }

    fun compose(path: String, threads: List<NoteThread>, placements: Map<String, Relocation>, now: OffsetDateTime = OffsetDateTime.now()): OverviewDocument {
        val md = StringBuilder()
        val boxes = ArrayList<Remark>()
        for (thread in threads) {
            val n = thread.root
            val p = placements[n.id] ?: Relocation(RelocationStatus.UNVERIFIED, n.first, n.last)
            val placeable = n.side != "old" && !thread.forgeDraft && p.status != RelocationStatus.ORPHANED && p.first >= 1
            md.append("- ")
            if (placeable) {
                md.append('[').append(escapeLabel(n.summary.ifBlank { "(no text)" })).append("](").append(path).append(':').append(p.first).append('-').append(p.last).append(')')
            } else {
                md.append(escapeText(n.summary.ifBlank { "(no text)" }))
            }
            md.append(" — ").append(escapeText(n.author.ifBlank { n.source }))
            (n.updated ?: n.created)?.let { md.append(" · ").append(DATE.format(it.toLocalDate())) }
            val meta = effectiveMeta(n)
            if (meta.isNotEmpty()) md.append(' ').append(meta.entries.joinToString(" ") { "`${it.key}: ${it.value}`" })
            if (n.side == "old") md.append(" `old side`")
            if (thread.forgeDraft) md.append(" _draft reply to a GitHub thread_")
            if (thread.resolved) md.append(" ✓ resolved").also { r -> thread.resolution?.by?.let { md.append(" by ").append(escapeText(it)) } }
            when (p.status) {
                RelocationStatus.STALE -> md.append(" ⚠ not found at its lines; shown at ").append(p.first).append('–').append(p.last)
                RelocationStatus.ORPHANED -> md.append(" ⚠ gone: the file or its side is absent")
                RelocationStatus.UNVERIFIED -> md.append(" _(no fingerprint)_")
                RelocationStatus.ACTIVE -> if (p.first != n.first || p.last != n.last) md.append(" (moved from ").append(n.first).append('–').append(n.last).append(')')
            }
            if (sweepDue(n, now) || p.status == RelocationStatus.STALE || p.status == RelocationStatus.ORPHANED) {
                md.append(" _(gg drops it on its next start here)_")
            }
            n.rationale.takeIf { it.isNotBlank() }?.let { md.append("\n  ").append(escapeText(it.trim()).replace("\n", "\n  ")) }
            for (r in thread.replies) md.append("\n  ↳ ").append(replyLine(r))
            md.append('\n')
            if (placeable) {
                val extra = StringBuilder(n.rationale.trim())
                for (r in thread.replies) extra.append("\n— ").append(r.author.ifBlank { r.source }).append(": ").append(r.summary)
                if (thread.resolved) extra.append("\n✓ resolved").also { thread.resolution?.by?.let { b -> extra.append(" by ").append(b) } }
                if (p.status == RelocationStatus.STALE) extra.append("\n⚠ not found at its lines")
                boxes += Remark(p.first, p.last, n.summary, extra.toString().trim().ifBlank { null })
            }
        }
        val title = if (threads.size == 1) threads.single().root.summary.ifBlank { path } else "$path · ${threads.size} notes"
        return OverviewDocument(title, md.toString().trim().ifBlank { "_(no notes)_" }, if (boxes.isEmpty()) emptyList() else listOf(FileRemarks(path, boxes)))
    }

    /** §6.1: the author's meta with later `added` values replacing the same key. */
    fun effectiveMeta(n: NoteRecord): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (m in n.meta) if (m.key !in out) out[m.key] = m.value
        for (a in n.added) if (a.remarkFp == null) out[a.key] = a.value
        return out
    }

    private fun replyLine(reply: NoteRecord): String {
        val who = escapeText(reply.author.ifBlank { reply.source.ifBlank { "reply" } })
        val date = reply.created?.let { " (" + DATE.format(it.toLocalDate()) + ")" } ?: ""
        val body = escapeText(reply.summary) + (reply.rationale.takeIf { it.isNotBlank() }?.let { " — " + escapeText(it) } ?: "")
        val link = reply.link?.let { " `$it`" } ?: ""
        return "**$who**$date: $body$link"
    }

    private fun escapeLabel(s: String) = s.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]").replace("\n", " ")

    private fun escapeText(s: String) = s.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]").replace("*", "\\*").replace("_", "\\_").replace("`", "\\`").replace("<", "&lt;")
}
