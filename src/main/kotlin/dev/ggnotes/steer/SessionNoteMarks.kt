package dev.ggnotes.steer

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.AppExecutorUtil
import dev.ggnotes.overview.NoteVisibility
import dev.ggnotes.overview.RemarkOverlay
import dev.ggnotes.session.ColdAnchor
import dev.ggnotes.session.DocIndex
import dev.ggnotes.session.Placement
import dev.ggnotes.session.SessionNoteDoc
import dev.ggnotes.store.Remark
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The session notes of one git root on the editors (session-docs SPEC §4.3,
 * §4.4): a box like an overview remark on every editor of the noted file,
 * placed from the note's own lines the first time (cold anchoring), then
 * re-anchored as the user edits (gg's rule, [Reanchor]). A file's state is
 * kept only while an editor of it is open; a closed file is placed cold
 * again when it opens next. The notes themselves are the files in
 * [DocIndex]; nothing here outlives them.
 */
class SessionNoteMarks(private val project: Project, private val session: SteerService.RootSession, parent: Disposable) : Disposable {

    companion object {
        /** SPEC §3.4: the first 50 notes of a path by `created`, then id, are shown. */
        const val MAX_NOTES_PER_FILE = 50
        private const val REALIGN_DELAY_MILLIS = 200L
    }

    private class Live(@Volatile var doc: SessionNoteDoc, val live: Reanchor.Note)

    /** A file with an open editor: the text the notes are aligned to and their live positions. */
    private class Tracked(val path: String, val document: Document, var lines: List<String>) {
        var print: String = fingerprint(lines)
        val notes = LinkedHashMap<String, Live>()
        var listener: Disposable? = null
        var boxes: List<Disposable> = emptyList()

        companion object {
            fun fingerprint(lines: List<String>): String {
                val md = MessageDigest.getInstance("SHA-256")
                for (l in lines) {
                    md.update(l.toByteArray(Charsets.UTF_8))
                    md.update('\n'.code.toByte())
                }
                return md.digest().joinToString("") { "%02x".format(it) }
            }
        }
    }

    private val index: DocIndex get() = session.docs.index
    private val tracked = ConcurrentHashMap<String, Tracked>()
    private val pending = ConcurrentHashMap<String, ScheduledFuture<*>>()

    init {
        Disposer.register(parent, this)
        EditorFactory.getInstance().addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) {
                if (event.editor.project != project) return
                val vf = FileDocumentManager.getInstance().getFile(event.editor.document) ?: return
                val path = relativePath(vf) ?: return
                if (shownNotes(path).isEmpty()) return
                track(path, event.editor.document)
                redraw(path)
            }
        }, this)
    }

    /** The worktree-relative slash path of [vf] when it is under this root, else null. */
    fun relativePath(vf: VirtualFile): String? {
        val root = session.repo.root
        if (!VfsUtilCore.isAncestor(root, vf, true)) return null
        return VfsUtilCore.getRelativePath(vf, root, '/')
    }

    /** The notes a path shows: the first [MAX_NOTES_PER_FILE] by `created` then id (SPEC §3.4). */
    private fun shownNotes(path: String): List<SessionNoteDoc> =
        index.notesOn(path).sortedWith(compareBy<DocIndex.Entry<SessionNoteDoc>> { it.time }.thenBy { it.doc.id }).take(MAX_NOTES_PER_FILE).map { it.doc }

    fun noteCount(path: String): Int = shownNotes(path).size

    private fun cold(doc: SessionNoteDoc, lines: List<String>): Live {
        val p = ColdAnchor.place(lines, doc.start, doc.end, doc.lines)
        return Live(doc, Reanchor.Note(p.start, p.end, p.outdated, doc.lines))
    }

    /** EDT: start following [document] for [path]; idempotent. */
    private fun track(path: String, document: Document): Tracked {
        tracked[path]?.let { return it }
        val lines = Lines.split(document.charsSequence)
        val t = Tracked(path, document, lines)
        for (doc in shownNotes(path)) t.notes[doc.id] = cold(doc, lines)
        val listener = object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                pending[path]?.cancel(false)
                pending[path] = AppExecutorUtil.getAppScheduledExecutorService().schedule({ realign(path) }, REALIGN_DELAY_MILLIS, TimeUnit.MILLISECONDS)
            }
        }
        document.addDocumentListener(listener)
        t.listener = Disposable { document.removeDocumentListener(listener) }.also { Disposer.register(this, it) }
        tracked[path] = t
        return t
    }

    private fun untrack(path: String) {
        val t = tracked.remove(path) ?: return
        pending.remove(path)?.cancel(false)
        clearBoxes(t)
        t.listener?.let { Disposer.dispose(it) }
    }

    /** Re-anchor [t] to [cur] when the text changed; true when it did (caller holds [t]). */
    private fun align(t: Tracked, cur: List<String>): Boolean {
        if (cur.isEmpty()) return false
        val p = Tracked.fingerprint(cur)
        if (p == t.print) return false
        Reanchor.reanchor(t.notes.values.map { it.live }, t.lines, cur)
        t.lines = cur
        t.print = p
        return true
    }

    /** Background: re-anchor to the current text; redraw when anything moved. */
    private fun realign(path: String) {
        if (project.isDisposed) return
        val t = tracked[path] ?: return
        val lines = ReadAction.compute<List<String>, RuntimeException> { Lines.split(t.document.charsSequence) }
        val moved = synchronized(t) { align(t, lines) }
        if (moved) ApplicationManager.getApplication().invokeLater({ redraw(path) }, project.disposed)
    }

    /** Force a re-anchor now (before answering a command), on the caller's thread with a read action. */
    fun alignNow(path: String): Boolean {
        val t = tracked[path] ?: return false
        val lines = ReadAction.compute<List<String>, RuntimeException> { Lines.split(t.document.charsSequence) }
        return synchronized(t) { align(t, lines) }
    }

    /**
     * Any thread: the directory changed. Followed files take the new, replaced and gone
     * notes (a replaced note whose path, lines and numbers are unchanged keeps its
     * placement, as gg does); a file with an open editor that had no notes starts
     * being followed. Boxes are redrawn on the EDT.
     */
    fun docsChanged() {
        val touched = HashSet<String>()
        for (t in tracked.values) {
            synchronized(t) {
                val now = shownNotes(t.path).associateBy { it.id }
                if (t.notes.keys.retainAll(now.keys)) touched += t.path
                for ((id, doc) in now) {
                    val cur = t.notes[id]
                    when {
                        cur == null -> { t.notes[id] = cold(doc, t.lines); touched += t.path }
                        cur.doc === doc -> Unit
                        samePlace(cur.doc, doc) -> { cur.doc = doc; touched += t.path }
                        else -> { t.notes[id] = cold(doc, t.lines); touched += t.path }
                    }
                }
            }
        }
        ApplicationManager.getApplication().invokeLater({
            for (path in touched) redraw(path)
            // notes on a file that is open but not followed yet: follow it now
            val fem = FileEditorManager.getInstance(project)
            for (vf in fem.openFiles) {
                val path = relativePath(vf) ?: continue
                if (tracked.containsKey(path) || shownNotes(path).isEmpty()) continue
                val document = FileDocumentManager.getInstance().getCachedDocument(vf) ?: continue
                track(path, document)
                redraw(path)
            }
        }, project.disposed)
    }

    private fun samePlace(a: SessionNoteDoc, b: SessionNoteDoc) =
        a.path == b.path && a.start == b.start && a.end == b.end && a.lines == b.lines

    /** Where each note of [path] sits now, by line: the followed text's placements, else cold against the file on disk. */
    fun placementsOn(path: String): List<Pair<SessionNoteDoc, Placement>> {
        tracked[path]?.let { t ->
            synchronized(t) {
                return t.notes.values.map { it.doc to Placement(it.live.start, it.live.end, it.live.outdated) }
                    .sortedWith(compareBy<Pair<SessionNoteDoc, Placement>> { it.second.start }.thenBy { it.second.end }.thenBy { it.first.id })
            }
        }
        val notes = shownNotes(path)
        if (notes.isEmpty()) return emptyList()
        val lines = diskLines(path)
        return notes.map { it to ColdAnchor.place(lines, it.start, it.end, it.lines) }
            .sortedWith(compareBy<Pair<SessionNoteDoc, Placement>> { it.second.start }.thenBy { it.second.end }.thenBy { it.first.id })
    }

    fun placementOf(doc: SessionNoteDoc): Placement =
        placementsOn(doc.path).firstOrNull { it.first.id == doc.id }?.second ?: Placement(1, 1, true)

    private fun diskLines(path: String): List<String>? {
        val f = Path.of(session.repo.root.path, path)
        if (!Files.isRegularFile(f)) return null
        val bytes = runCatching { Files.readAllBytes(f) }.getOrNull() ?: return null
        return Lines.split(String(bytes, Charsets.UTF_8))
    }

    /** EDT: remove and redraw every box of the file on all of its editors (none while its notes are hidden). */
    fun redraw(path: String) {
        val t = tracked[path] ?: return
        clearBoxes(t)
        if (NoteVisibility.getInstance(project).isHidden(session.repo.root.path + "/" + path)) return
        val notes = synchronized(t) { t.notes.values.map { it.doc to Placement(it.live.start, it.live.end, it.live.outdated) } }
            .sortedWith(compareBy<Pair<SessionNoteDoc, Placement>> { it.second.start }.thenBy { it.first.id })
        if (notes.isEmpty()) return
        val list = ArrayList<Disposable>()
        for (editor in EditorFactory.getInstance().getEditors(t.document, project)) {
            for ((n, p) in notes) {
                val text = StringBuilder()
                n.rationale?.let { text.append(it) }
                if (n.author.isNotBlank()) text.append(if (text.isEmpty()) "" else "\n").append("— ").append(n.author)
                n.meta.forEach { m -> text.append("\n").append(m.key).append(": ").append(m.value) }
                val summary = (if (p.outdated) "⚠ outdated · " else "") + n.summary
                list += RemarkOverlay.box(editor, p.start, p.end, Remark(p.start, p.end, summary, text.toString().ifBlank { null }))
            }
        }
        t.boxes = list
    }

    /** EDT: Hide notes / Show notes flipped for the file at [key] (a working-tree path); redraw it when it is ours. */
    fun visibilityChanged(key: String) {
        val prefix = session.repo.root.path + "/"
        if (key.startsWith(prefix)) redraw(key.removePrefix(prefix))
    }

    private fun clearBoxes(t: Tracked) {
        val boxes = t.boxes
        t.boxes = emptyList()
        boxes.forEach { runCatching { Disposer.dispose(it) } }
    }

    /** A closed file: once no editor shows it, its boxes and listener go; it is placed cold again when it opens next. */
    fun fileClosed(vf: VirtualFile) {
        val path = relativePath(vf) ?: return
        if (!tracked.containsKey(path)) return
        if (FileEditorManager.getInstance(project).isFileOpen(vf)) return
        untrack(path)
    }

    override fun dispose() {
        for (path in tracked.keys.toList()) untrack(path)
        pending.values.forEach { it.cancel(false) }
        pending.clear()
    }
}
