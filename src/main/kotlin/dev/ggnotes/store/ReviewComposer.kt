package dev.ggnotes.store

import java.time.format.DateTimeFormatter

/**
 * Turns a review into the document shape the viewer already shows (SPEC-part2 §8):
 * the summary as prose (no anchors), document meta, then each file with its
 * remarks — a new-side remark is an anchor into the file at the commit and
 * an overlay box, an old-side remark is listed with an "old" mark — replies
 * under their remark, and the review's own overview last, with its anchors.
 */
object ReviewComposer {

    private val DATE = DateTimeFormatter.ISO_LOCAL_DATE

    /** [treeChanged]: the working-review files whose bytes no longer match (SPEC-part2 §5.1), shown first. */
    fun compose(review: Review, treeChanged: List<String> = emptyList()): OverviewDocument {
        val md = StringBuilder()
        val files = ArrayList<FileRemarks>()
        if (treeChanged.isNotEmpty()) {
            md.append("⚠ **The tree has changed since this review:** ")
                .append(treeChanged.joinToString(", ") { "`$it`" }).append("\n\n")
        }
        when (val content = review.content) {
            is ReviewContent.Prose -> md.append(MarkdownRenderer.neutralizeAnchors(content.markdown.trim()))
            is ReviewContent.CannotRender -> md.append("_This review cannot be rendered: ").append(content.reason).append("_")
            is ReviewContent.Document -> {
                val doc = content.document
                md.append(MarkdownRenderer.neutralizeAnchors(doc.summary.trim())).append("\n\n")
                if (doc.meta.isNotEmpty()) md.append(badges(doc.meta)).append("\n\n")
                if (review.replies.isNotEmpty()) {
                    md.append("## Replies\n\n")
                    for (r in review.replies) md.append("- ").append(replyLine(r)).append('\n')
                    md.append('\n')
                }
                if (doc.files.isNotEmpty()) {
                    md.append("## Files\n\n")
                    for (file in doc.files) {
                        md.append("### ").append(escapeText(file.path))
                        file.summary?.takeIf { it.isNotBlank() }?.let { md.append(" — ").append(escapeText(it)) }
                        md.append("\n\n")
                        if (file.meta.isNotEmpty()) md.append(badges(file.meta)).append("\n\n")
                        val placed = ArrayList<Remark>()
                        for (remark in file.remarks) {
                            if (review.isMoved(remark)) continue
                            md.append("- ")
                            if (remark.side == RemarkSide.NEW) {
                                md.append('[').append(escapeLabel(remark.summary)).append("](").append(remark.path)
                                    .append(':').append(remark.first).append('-').append(remark.last).append(')')
                            } else {
                                md.append("(old ").append(remark.first).append('–').append(remark.last).append(") ").append(escapeText(remark.summary))
                            }
                            if (remark.meta.isNotEmpty()) md.append(' ').append(badges(remark.meta))
                            if (remark.index in review.resolvedRemarks) md.append(" ✓ resolved")
                            remark.rationale?.takeIf { it.isNotBlank() }?.let { md.append("\n  ").append(escapeText(it.trim()).replace("\n", "\n  ")) }
                            val replies = review.remarkReplies[remark.index].orEmpty()
                            for (r in replies) md.append("\n  ↳ ").append(replyLine(r))
                            md.append('\n')
                            if (remark.side == RemarkSide.NEW) {
                                val extra = replies.joinToString("") { "\n— ${r(it)}" }
                                placed += Remark(remark.first, remark.last, remark.summary, (remark.rationale.orEmpty() + extra).ifBlank { null })
                            }
                        }
                        md.append('\n')
                        if (placed.isNotEmpty()) files += FileRemarks(file.path, placed)
                    }
                }
                if (review.unmatchedReplies.isNotEmpty()) {
                    md.append("## Replies to remarks no longer in this review\n\n")
                    for (r in review.unmatchedReplies) md.append("- ").append(replyLine(r)).append('\n')
                    md.append('\n')
                }
                doc.overview?.let { md.append("## Overview\n\n").append(it.trim()).append('\n') }
            }
        }
        return OverviewDocument(review.title, md.toString().trim().ifBlank { "_(empty review)_" }, files)
    }

    private fun r(reply: NoteRecord): String =
        (reply.author.ifBlank { reply.source }) + ": " + reply.summary + (reply.rationale.takeIf { it.isNotBlank() }?.let { " $it" } ?: "")

    private fun replyLine(reply: NoteRecord): String {
        val who = escapeText(reply.author.ifBlank { reply.source.ifBlank { "reply" } })
        val date = reply.created?.let { " (" + DATE.format(it.toLocalDate()) + ")" } ?: ""
        val body = escapeText(reply.summary) + (reply.rationale.takeIf { it.isNotBlank() }?.let { " — " + escapeText(it) } ?: "")
        val link = reply.link?.let { " `$it`" } ?: ""
        return "**$who**$date: $body$link"
    }

    /** `key: value` as inline code, the first-step badge the brief allows. */
    fun badges(meta: Map<String, String>): String = meta.entries.joinToString(" ") { "`${it.key}: ${it.value}`" }

    private fun escapeLabel(s: String) = s.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]").replace("\n", " ")

    private fun escapeText(s: String) = s.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]").replace("*", "\\*").replace("_", "\\_").replace("`", "\\`").replace("<", "&lt;")
}
