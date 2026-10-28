package dev.ggnotes.items

import dev.ggnotes.session.DocIndex
import dev.ggnotes.session.SessionNoteDoc
import dev.ggnotes.session.SessionOverviewDoc
import dev.ggnotes.store.NoteRecord
import dev.ggnotes.store.NoteThread
import dev.ggnotes.store.Overview
import dev.ggnotes.store.RepoModel
import dev.ggnotes.store.Review
import dev.ggnotes.store.ReviewKind
import java.time.OffsetDateTime

/** Pure: the store model and the session docs as [NoteItem]s with their kind, owner and date. */
object NoteItems {

    fun build(
        model: RepoModel,
        sessionOverviews: List<DocIndex.Entry<SessionOverviewDoc>>,
        sessionNotes: List<DocIndex.Entry<SessionNoteDoc>>,
    ): List<NoteItem> {
        val out = ArrayList<NoteItem>()
        for (c in model.commits) {
            for (o in c.overviews) out += overviewItem(o, c.overviewReplies[o.id].orEmpty())
            for (r in c.reviews) out += reviewItem(r)
            for (f in c.files) for (t in f.threads) out += threadItem(t, t.root.address.commit, null)
        }
        for (w in model.working) {
            for (r in w.reviews) out += reviewItem(r)
            for (f in w.files) for (t in f.threads) out += threadItem(t, "", w.worktree)
        }
        for (e in sessionOverviews) out += NoteItem(
            id = e.doc.id, kind = ItemKind.OTHER, source = ItemSource.SESSION_OVERVIEW, title = e.doc.title, owner = Owner(),
            author = e.doc.author, time = java.time.Instant.ofEpochMilli(e.time), payload = Payload.SessionOverview(e), children = emptyList(),
        )
        for (e in sessionNotes) out += NoteItem(
            id = "note:${e.doc.id}", kind = ItemKind.FILE, source = ItemSource.SESSION_NOTE, title = e.doc.summary,
            owner = Owner(path = e.doc.path, first = e.doc.start, last = e.doc.end),
            author = e.doc.author, time = java.time.Instant.ofEpochMilli(e.time), payload = Payload.SessionNote(e), children = emptyList(),
        )
        return out
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
