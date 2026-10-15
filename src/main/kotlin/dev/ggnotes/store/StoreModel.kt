package dev.ggnotes.store

import java.time.OffsetDateTime

/** A root note with its replies (flat threads, SPEC-part2 §4.2) and its resolution, if any. */
data class NoteThread(
    val root: NoteRecord,
    val replies: List<NoteRecord>,
    val resolution: Resolution?,
    /** §7: a local draft reply to a forge thread whose root is not stored. */
    val forgeDraft: Boolean,
) {
    val resolved: Boolean get() = resolution != null
    val newest: OffsetDateTime? get() = (listOf(root) + replies).mapNotNull { it.updated ?: it.created }.maxOrNull()
}

/** One file of a commit (or worktree) with its note threads, in creation order. */
data class FileEntry(val path: String, val threads: List<NoteThread>)

/** Everything under one commit in the tool window (SPEC-part2 §8). */
data class CommitEntry(
    val commit: String,
    val isHead: Boolean,
    /** The commit's subject, or null when the commit is not in the repository. */
    val subject: String?,
    val overviews: List<Overview>,
    val overviewReplies: Map<String, List<NoteRecord>>,
    val reviews: List<Review>,
    val files: List<FileEntry>,
) {
    val shortSha: String get() = commit.take(7)
    val newest: OffsetDateTime? get() = (
        overviews.mapNotNull { it.updated } +
            reviews.mapNotNull { it.record.updated ?: it.record.created } +
            files.flatMap { f -> f.threads.mapNotNull { it.newest } }
        ).maxOrNull()
    val isEmpty: Boolean get() = overviews.isEmpty() && reviews.isEmpty() && files.isEmpty()
}

/** The live records of one checkout (SPEC-part2 §5): working reviews and notes on the working tree. */
data class WorkingEntry(val worktree: String, val reviews: List<Review>, val files: List<FileEntry>)

data class RepoModel(val commits: List<CommitEntry>, val working: List<WorkingEntry>) {
    companion object {
        val EMPTY = RepoModel(emptyList(), emptyList())
    }
}

/** Pure arrangement of a store's records into what the tool window shows. */
object StoreModel {

    private val EPOCH: OffsetDateTime = OffsetDateTime.parse("1970-01-01T00:00:00Z")

    fun build(contents: StoreContents, headSha: String?, subjects: Map<String, String?>): RepoModel {
        val records = contents.records
        val repliesByParent = records.filter { it.kind == RecordKind.REPLY }.groupBy { it.parentId!! }
        val resolutions = contents.resolved.associateBy { it.root }
        val reviews = Reviews.build(contents)

        fun threadsOf(roots: List<NoteRecord>): List<NoteThread> = roots
            .sortedWith(compareBy<NoteRecord> { it.first }.thenBy { it.created ?: EPOCH })
            .map { root ->
                NoteThread(
                    root = root,
                    replies = repliesByParent[root.id].orEmpty().sortedBy { it.created ?: EPOCH },
                    resolution = resolutions[root.id],
                    forgeDraft = root.kind == RecordKind.FORGE_DRAFT,
                )
            }

        fun filesOf(notes: List<NoteRecord>): List<FileEntry> = notes
            .groupBy { it.address.path }
            .toSortedMap()
            .map { (path, roots) -> FileEntry(path, threadsOf(roots)) }

        // committed, unscoped line notes and forge drafts, per commit
        val committedNotes = records.filter {
            (it.kind == RecordKind.LINE_NOTE && !it.isLive && !it.scoped) || it.kind == RecordKind.FORGE_DRAFT
        }
        val commitShas = LinkedHashSet<String>()
        contents.overviews.forEach { commitShas += it.commit }
        reviews.filter { it.kind != ReviewKind.WORKING }.forEach { commitShas += it.record.address.commit }
        committedNotes.forEach { commitShas += it.address.commit }

        val commits = commitShas.map { sha ->
            val overviews = contents.overviews.filter { it.commit == sha }.sortedByDescending { it.updated ?: EPOCH }
            CommitEntry(
                commit = sha,
                isHead = headSha != null && sha == headSha,
                subject = subjects[sha],
                overviews = overviews,
                overviewReplies = overviews.associate { o -> o.id to repliesByParent[o.id].orEmpty().sortedBy { it.created ?: EPOCH } },
                reviews = reviews.filter { it.kind != ReviewKind.WORKING && it.record.address.commit == sha }
                    .sortedByDescending { it.record.updated ?: it.record.created ?: EPOCH },
                files = filesOf(committedNotes.filter { it.address.commit == sha }),
            )
        }.sortedWith(compareByDescending<CommitEntry> { it.isHead }.thenByDescending { it.newest ?: EPOCH })

        val liveNotes = records.filter { it.kind == RecordKind.LINE_NOTE && it.isLive }
        val worktrees = LinkedHashSet<String>()
        reviews.filter { it.kind == ReviewKind.WORKING }.forEach { worktrees += it.record.address.worktree }
        liveNotes.forEach { worktrees += it.address.worktree }
        val working = worktrees.map { wt ->
            WorkingEntry(
                worktree = wt,
                reviews = reviews.filter { it.kind == ReviewKind.WORKING && it.record.address.worktree == wt }
                    .sortedByDescending { it.record.updated ?: it.record.created ?: EPOCH },
                files = filesOf(liveNotes.filter { it.address.worktree == wt }),
            )
        }
        return RepoModel(commits, working)
    }
}
