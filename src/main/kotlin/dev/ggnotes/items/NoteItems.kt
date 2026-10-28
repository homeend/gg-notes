package dev.ggnotes.items

import dev.ggnotes.session.DocIndex
import dev.ggnotes.session.SessionNoteDoc
import dev.ggnotes.session.SessionOverviewDoc
import dev.ggnotes.store.DocumentResult
import dev.ggnotes.store.NoteRecord
import dev.ggnotes.store.NoteThread
import dev.ggnotes.store.Overview
import dev.ggnotes.store.RepoModel
import dev.ggnotes.store.Review
import dev.ggnotes.store.ReviewContent
import dev.ggnotes.store.ReviewKind
import java.time.Instant
import java.time.OffsetDateTime

/** Pure: the store model and the session docs as [NoteItem]s with their kind, owner and date. */
object NoteItems {

    fun build(
        model: RepoModel,
        sessionOverviews: List<DocIndex.Entry<SessionOverviewDoc>>,
        sessionNotes: List<DocIndex.Entry<SessionNoteDoc>>,
    ): List<NoteItem> {
        val tops = ArrayList<NoteItem>()
        val adopted = HashMap<String, MutableList<NoteItem>>()
        fun adopt(parent: String, child: NoteItem) { adopted.getOrPut(parent) { ArrayList() } += child }

        for (c in model.commits) {
            val overviews = c.overviews.map { overviewItem(it, c.overviewReplies[it.id].orEmpty()) }
            val reviews = c.reviews.map { reviewItem(it) }
            tops += overviews + reviews
            val docs = c.overviews.associate { "store:${it.id}" to (it.document as? DocumentResult.Ok)?.document?.files?.map { f -> f.path }.orEmpty().toSet() }
            val lists = c.reviews.associate { "store:${it.id}" to reviewPaths(it) }
            for (f in c.files) for (t in f.threads) {
                place(threadItem(t, t.root.address.commit, null), f.path, reviews, lists, overviews, docs, tops, ::adopt)
            }
        }
        for (w in model.working) {
            val reviews = w.reviews.map { reviewItem(it) }
            tops += reviews
            val lists = w.reviews.associate { "store:${it.id}" to reviewPaths(it) }
            for (f in w.files) for (t in f.threads) {
                place(threadItem(t, "", w.worktree), f.path, reviews, lists, emptyList(), emptyMap(), tops, ::adopt)
            }
        }
        val notesLinked = HashMap<String, DocIndex.Entry<SessionOverviewDoc>>()
        for (e in sessionOverviews.sortedWith(NEWEST_SESSION)) for (n in e.doc.anchors.mapNotNull { it.note }) notesLinked.putIfAbsent(n, e)
        for (e in sessionOverviews) tops += NoteItem(
            id = e.doc.id, kind = ItemKind.OTHER, source = ItemSource.SESSION_OVERVIEW, title = e.doc.title, owner = Owner(),
            author = e.doc.author, time = java.time.Instant.ofEpochMilli(e.time), payload = Payload.SessionOverview(e), children = emptyList(),
        )
        for (e in sessionNotes) {
            val item = NoteItem(
                id = "note:${e.doc.id}", kind = ItemKind.FILE, source = ItemSource.SESSION_NOTE, title = e.doc.summary,
                owner = Owner(path = e.doc.path, first = e.doc.start, last = e.doc.end),
                author = e.doc.author, time = java.time.Instant.ofEpochMilli(e.time), payload = Payload.SessionNote(e), children = emptyList(),
            )
            val parent = notesLinked[e.doc.id]
            if (parent != null) adopt(parent.doc.id, item) else tops += item
        }
        return tops.map { t ->
            val extra = adopted[t.id].orEmpty()
            if (extra.isEmpty()) t else t.copy(children = (t.children + extra).sortedWith(CHILD_ORDER))
        }
    }

    private val NEWEST_SESSION = compareByDescending<DocIndex.Entry<SessionOverviewDoc>> { it.time }.thenBy { it.doc.id }
    private val NEWEST_ITEM = compareByDescending<NoteItem, Instant?>(nullsFirst()) { it.time }.thenBy { it.id }
    private val CHILD_ORDER = compareBy<NoteItem, String?>(nullsFirst()) { it.owner.path }
        .thenBy(nullsFirst()) { it.owner.first }.thenBy { it.id }

    /** A prose review contains every note of its commit (null = all); a document review the notes on the files it lists. */
    private fun reviewPaths(r: Review): Set<String>? = if (r.content is ReviewContent.Prose) null else r.document?.files?.map { it.path }?.toSet().orEmpty()

    private fun place(
        thread: NoteItem, path: String,
        reviews: List<NoteItem>, lists: Map<String, Set<String>?>,
        overviews: List<NoteItem>, docs: Map<String, Set<String>>,
        tops: MutableList<NoteItem>, adopt: (String, NoteItem) -> Unit,
    ) {
        val review = reviews.filter { lists[it.id].let { p -> p == null || path in p } }.minWithOrNull(NEWEST_ITEM)
        val parent = review ?: overviews.filter { path in docs[it.id].orEmpty() }.minWithOrNull(NEWEST_ITEM)
        if (parent != null) adopt(parent.id, thread) else tops += thread
    }

    private fun overviewItem(o: Overview, replies: List<NoteRecord>) = NoteItem(
        id = "store:${o.id}",
        kind = if (o.prNumber != null || o.sourceFile.fileName?.toString() == "previews.toml") ItemKind.PULL_REQUEST else ItemKind.COMMIT,
        source = ItemSource.STORE_OVERVIEW,
        title = o.title,
        owner = Owner(commit = o.commit.ifEmpty { null }, branch = o.branch.ifBlank { null }, pr = o.prNumber, preview = o.preview),
        author = o.author,
        time = (o.created ?: o.updated)?.toInstant(),
        payload = Payload.StoreOverview(o, replies),
        children = emptyList(),
    )

    private fun reviewItem(r: Review): NoteItem {
        val a = r.record.address
        return NoteItem(
            id = "store:${r.id}",
            kind = when (r.kind) {
                ReviewKind.COMMIT, ReviewKind.RANGE, ReviewKind.PAIR -> ItemKind.COMMIT
                ReviewKind.BRANCH -> ItemKind.BRANCH
                ReviewKind.PULL_REQUEST, ReviewKind.MERGE_PREVIEW -> ItemKind.PULL_REQUEST
                ReviewKind.WORKING -> ItemKind.OTHER
            },
            source = ItemSource.STORE_REVIEW,
            title = r.title,
            owner = Owner(
                commit = a.commit.ifEmpty { null },
                branch = if (r.kind == ReviewKind.BRANCH) a.branch.ifBlank { null } else null,
                pr = r.record.prNumber,
                preview = r.record.preview,
                worktree = if (r.kind == ReviewKind.WORKING) a.worktree else null,
            ),
            author = r.record.author,
            time = date(r.record.created, r.record.updated),
            payload = Payload.StoreReview(r),
            children = r.scopedThreads.map { threadItem(it, it.root.address.commit, null) },
        )
    }

    private fun threadItem(t: NoteThread, commit: String, worktree: String?): NoteItem {
        val root = t.root
        return NoteItem(
            id = "store:${root.id}",
            kind = ItemKind.FILE,
            source = ItemSource.STORE_THREAD,
            title = root.summary,
            owner = Owner(commit = commit.ifEmpty { null }, path = root.address.path, first = root.first, last = root.last, worktree = worktree),
            author = root.author,
            time = date(root.created, root.updated),
            payload = Payload.StoreThread(t, commit, worktree),
            children = emptyList(),
        )
    }

    private fun date(created: OffsetDateTime?, updated: OffsetDateTime?) = (created ?: updated)?.toInstant()
}
