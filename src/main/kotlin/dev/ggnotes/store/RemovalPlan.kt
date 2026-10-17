package dev.ggnotes.store

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
