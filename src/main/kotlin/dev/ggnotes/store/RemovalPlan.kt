package dev.ggnotes.store

import dev.ggnotes.items.NoteItem

/**
 * What "Remove from gg store" on a tool-window row asks gg to delete, as the
 * lines of one `gg batch` script. gg owns the store format, so the plugin
 * never rewrites the files: `note rm <id>` (a root takes its replies with
 * it) and `overview rm <id>` do the work. Children come before parents so a
 * cascade never makes a later line report "not found".
 */
data class RemovalPlan(val kind: Kind, val label: String, val lines: List<String>) {

    enum class Kind { OVERVIEW, REVIEW, THREAD, REPLY, FILE, COMMIT, WORKING }

    /** How many store records the lines name (replies a root cascades to are not counted). */
    val records: Int get() = lines.size

    /** The ids the lines remove: a document showing one of them has to close. */
    val ids: Set<String> get() = lines.map { it.substringAfterLast(' ') }.toSet()

    companion object {
        private fun note(id: String) = "note rm $id"
        private fun overview(id: String) = "overview rm $id"
        private fun threadLines(threads: List<NoteThread>) = threads.map { note(it.root.id) }
        private fun reviewLines(r: Review) = threadLines(r.scopedThreads) + note(r.id)

        fun forOverview(o: Overview) = RemovalPlan(Kind.OVERVIEW, o.title, listOf(overview(o.id)))

        fun forReview(r: Review) = RemovalPlan(Kind.REVIEW, r.title, reviewLines(r))

        fun forThread(t: NoteThread) =
            RemovalPlan(Kind.THREAD, t.root.address.path + lineSuffix(t.root), listOf(note(t.root.id)))

        fun forReply(reply: NoteRecord) = RemovalPlan(Kind.REPLY, reply.summary, listOf(note(reply.id)))

        /** Store items only (null for a session item): each child's lines first, then the item's own. */
        fun forItem(item: NoteItem): RemovalPlan? {
            val own: Triple<Kind, String, String> = when (val p = item.payload) {
                is dev.ggnotes.items.Payload.StoreOverview -> Triple(Kind.OVERVIEW, item.title, overview(p.overview.id))
                is dev.ggnotes.items.Payload.StoreReview -> Triple(Kind.REVIEW, item.title, note(p.review.id))
                is dev.ggnotes.items.Payload.StoreThread ->
                    Triple(Kind.THREAD, p.thread.root.address.path + lineSuffix(p.thread.root), note(p.thread.root.id))
                else -> return null
            }
            return RemovalPlan(own.first, own.second, childLines(item) + own.third)
        }

        private fun childLines(item: NoteItem): List<String> =
            item.children.flatMap { c -> forItem(c)?.lines.orEmpty() }

        fun forFile(f: FileEntry) = RemovalPlan(Kind.FILE, f.path, threadLines(f.threads))

        fun forCommit(c: CommitEntry) = RemovalPlan(
            Kind.COMMIT, c.shortSha,
            c.files.flatMap { threadLines(it.threads) } + c.reviews.flatMap { reviewLines(it) } + c.overviews.map { overview(it.id) },
        )

        fun forWorking(w: WorkingEntry) = RemovalPlan(
            Kind.WORKING, w.worktree,
            w.files.flatMap { threadLines(it.threads) } + w.reviews.flatMap { reviewLines(it) },
        )

        private fun lineSuffix(n: NoteRecord): String = when {
            n.first <= 0 -> ""
            n.last > n.first -> ":${n.first}-${n.last}"
            else -> ":${n.first}"
        }
    }
}
