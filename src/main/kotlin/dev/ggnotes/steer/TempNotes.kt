package dev.ggnotes.steer

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.AppExecutorUtil
import dev.ggnotes.overview.RemarkOverlay
import dev.ggnotes.store.Remark
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The IDE side of the session's temporary notes (PLUGIN.md §5): boxes drawn
 * like overview remarks on every editor of the noted file, redrawn whenever
 * the notes move, and a debounced document listener that re-anchors them as
 * the user edits (gg's rule, in [SessionMemory.alignFile]).
 */
class TempNotes(private val project: Project, private val session: SteerService.RootSession, parent: Disposable) : Disposable {

    private val boxes = ConcurrentHashMap<String, MutableList<Disposable>>()   // fileId -> disposables of its boxes
    private val listeners = ConcurrentHashMap<String, Disposable>()            // fileId -> document listener
    private val pending = ConcurrentHashMap<String, ScheduledFuture<*>>()

    init {
        Disposer.register(parent, this)
        EditorFactory.getInstance().addEditorFactoryListener(object : com.intellij.openapi.editor.event.EditorFactoryListener {
            override fun editorCreated(event: com.intellij.openapi.editor.event.EditorFactoryEvent) {
                if (event.editor.project != project) return
                val vf = FileDocumentManager.getInstance().getFile(event.editor.document) ?: return
                for ((id, f) in session.files) if (f == vf && session.memory.noteCount(id) > 0) redraw(id)
            }
        }, this)
    }

    /** The document's current lines by gg's rule (EDT or read action). */
    fun currentLines(document: Document): List<String> = Lines.split(document.charsSequence)

    /** After `note_add`: watch the document and draw. EDT. */
    fun noteAdded(fileId: String, vf: VirtualFile) {
        watch(fileId, vf)
        redraw(fileId)
    }

    private fun watch(fileId: String, vf: VirtualFile) {
        if (listeners.containsKey(fileId)) return
        val document = FileDocumentManager.getInstance().getDocument(vf) ?: return
        val listener = object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                pending[fileId]?.cancel(false)
                pending[fileId] = AppExecutorUtil.getAppScheduledExecutorService().schedule({ realign(fileId, document) }, 200, TimeUnit.MILLISECONDS)
            }
        }
        val disposable = Disposable { document.removeDocumentListener(listener) }
        document.addDocumentListener(listener)
        Disposer.register(this, disposable)
        listeners[fileId] = disposable
    }

    /** Background: re-anchor to the current text; redraw when anything moved. */
    private fun realign(fileId: String, document: Document) {
        if (project.isDisposed) return
        val lines = ReadAction.compute<List<String>, RuntimeException> { currentLines(document) }
        if (session.memory.alignFile(fileId, lines)) {
            ApplicationManager.getApplication().invokeLater({ redraw(fileId) }, project.disposed)
        }
    }

    /** Force a re-anchor now (before answering a command), on the caller's thread with a read action. */
    fun alignNow(fileId: String): Boolean {
        val vf = session.files[fileId] ?: return false
        val document = ReadAction.compute<Document?, RuntimeException> { FileDocumentManager.getInstance().getDocument(vf) } ?: return false
        val lines = ReadAction.compute<List<String>, RuntimeException> { currentLines(document) }
        return session.memory.alignFile(fileId, lines)
    }

    /** EDT: remove and redraw every box of the file on all of its editors. */
    fun redraw(fileId: String) {
        clear(fileId)
        val vf = session.files[fileId] ?: return
        val document = FileDocumentManager.getInstance().getDocument(vf) ?: return
        val notes = session.memory.notesOf(fileId)
        if (notes.isEmpty()) return
        val list = ArrayList<Disposable>()
        for (editor in EditorFactory.getInstance().getEditors(document, project)) {
            for (n in notes) {
                val text = StringBuilder()
                n.rationale?.let { text.append(it) }
                if (n.author.isNotBlank()) text.append(if (text.isEmpty()) "" else "\n").append("— ").append(n.author)
                n.meta?.forEach { (k, v) -> text.append("\n").append(k).append(": ").append(v) }
                val summary = (if (n.outdated) "⚠ outdated · " else "") + n.summary
                list += RemarkOverlay.box(editor, n.start, n.end, Remark(n.start, n.end, summary, text.toString().ifBlank { null }))
            }
        }
        boxes[fileId] = list
    }

    fun clear(fileId: String) {
        boxes.remove(fileId)?.forEach { runCatching { Disposer.dispose(it) } }
    }

    /** A closed file: its boxes and listener go. */
    fun fileClosed(fileId: String) {
        clear(fileId)
        pending.remove(fileId)?.cancel(false)
        listeners.remove(fileId)?.let { Disposer.dispose(it) }
    }

    override fun dispose() {
        for (id in boxes.keys.toList()) clear(id)
        pending.values.forEach { it.cancel(false) }
        pending.clear()
    }
}
