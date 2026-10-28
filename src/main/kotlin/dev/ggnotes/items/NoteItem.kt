package dev.ggnotes.items

import dev.ggnotes.session.DocIndex
import dev.ggnotes.session.SessionNoteDoc
import dev.ggnotes.session.SessionOverviewDoc
import dev.ggnotes.store.NoteRecord
import dev.ggnotes.store.NoteThread
import dev.ggnotes.store.Overview
import dev.ggnotes.store.Review
import java.time.Instant

/** What an item is attached to; declaration order is the By Type order. */
enum class ItemKind(val wire: String) { BRANCH("branch"), COMMIT("commit"), PULL_REQUEST("pull-request"), FILE("file"), OTHER("other") }

/** Where an item comes from. */
enum class ItemSource(val wire: String) {
    SESSION_OVERVIEW("overview"), SESSION_NOTE("session-note"), STORE_OVERVIEW("store-overview"), STORE_REVIEW("store-review"), STORE_THREAD("store-thread")
}

/** The git object, file or worktree an item belongs to; unset parts are null. */
data class Owner(
    val commit: String? = null,
    val branch: String? = null,
    val pr: Int? = null,
    val preview: String? = null,
    val path: String? = null,
    val first: Int? = null,
    val last: Int? = null,
    val worktree: String? = null,
)

/** The underlying record, kept so the tool window can open it as it does today. */
sealed class Payload {
    data class StoreOverview(val overview: Overview, val replies: List<NoteRecord>) : Payload()
    data class StoreReview(val review: Review) : Payload()

    /** commit = "" and worktree != null for a live thread (the openNotes arguments). */
    data class StoreThread(val thread: NoteThread, val commit: String, val worktree: String?) : Payload()
    data class SessionOverview(val entry: DocIndex.Entry<SessionOverviewDoc>) : Payload()
    data class SessionNote(val entry: DocIndex.Entry<SessionNoteDoc>) : Payload()
}

/** One row of the By Date / By Type views. [id] is unique across sources; [time] is null when the record has no date. */
data class NoteItem(
    val id: String,
    val kind: ItemKind,
    val source: ItemSource,
    val title: String,
    val owner: Owner,
    val author: String,
    val time: Instant?,
    val payload: Payload,
    val children: List<NoteItem>,
)
