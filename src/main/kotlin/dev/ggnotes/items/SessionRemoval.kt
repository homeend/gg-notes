package dev.ggnotes.items

/** The session doc files deleting a session item unlinks: overview ids and note ids. */
data class SessionRemoval(val overviews: List<String>, val notes: List<String>) {
    companion object {
        /** Null for a store item. A session overview takes the notes it contains with it. */
        fun of(item: NoteItem): SessionRemoval? = when (val p = item.payload) {
            is Payload.SessionOverview -> SessionRemoval(
                listOf(p.entry.doc.id),
                item.children.mapNotNull { (it.payload as? Payload.SessionNote)?.entry?.doc?.id },
            )
            is Payload.SessionNote -> SessionRemoval(emptyList(), listOf(p.entry.doc.id))
            else -> null
        }
    }
}
