package dev.ggnotes.steer

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.AppExecutorUtil
import dev.ggnotes.PluginVersion
import dev.ggnotes.git.GitAccess
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * One steering inbox per git root (steer SPEC §2): presence in `ide.json`
 * touched every second, the `ide/` sub-inbox drained every 500 ms (plus a
 * directory watcher), each command handed to [SteerHandler] on a single
 * worker thread, in order, and answered when it asked to wait. The same
 * inbox holds the worktree's session documents (`docs/`, session-docs SPEC
 * §2): the directory is reconciled every 2 s and on every watcher wake, its
 * overviews are the tool window's session rows and its notes are boxes on
 * the editors ([SessionNoteMarks]).
 */
@Service(Service.Level.PROJECT)
class SteerService(private val project: Project) : Disposable {

    companion object {
        private val LOG = logger<SteerService>()
        const val POLL_MILLIS = 500L
        const val TOUCH_MILLIS = 1000L
        /** Session-docs SPEC §4.2: the poll that backs the watcher. */
        const val DOCS_POLL_MILLIS = 2000L
        fun getInstance(project: Project): SteerService = project.getService(SteerService::class.java)
    }

    /** A root's inbox, the memory of what the agent opened there, and its session documents. */
    class RootSession(
        val repo: GitRepository,
        /** `git rev-parse --show-toplevel`, the string gg hashes and the one in `ide.json`. */
        val topLevel: String,
        val inbox: Path,
        val presenceJson: String,
        val memory: SessionMemory,
    ) {
        val ideDir: Path get() = SteerPaths.ideDir(inbox)
        /** The worktree's session documents directory (`<inbox>/docs/`); may not exist yet, and this reader never creates it. */
        val docsDir: Path get() = SteerPaths.docsDir(inbox)
        /** The session documents as last reconciled. */
        val docs = dev.ggnotes.session.DocFolder(SteerPaths.docsDir(inbox))
        /** The virtual file behind each open-files entry (IDE side of [SessionMemory]). */
        val files = ConcurrentHashMap<String, VirtualFile>()
        /** Live `highlight` markers; cleared by `highlight_clear` or when their file closes. */
        val highlights = java.util.concurrent.CopyOnWriteArrayList<com.intellij.openapi.editor.markup.RangeHighlighter>()
        /** The session notes on the editors; set by the service. */
        lateinit var marks: SessionNoteMarks

        /** A file the user closed leaves the list with its highlights; its note boxes go until it opens again (PLUGIN.md §5). */
        fun fileClosed(vf: VirtualFile) {
            for ((id, f) in files.entries.toList()) {
                if (f == vf) {
                    files.remove(id)
                    memory.removeFile(id)
                }
            }
            marks.fileClosed(vf)
            highlights.removeIf { h ->
                val closed = !h.isValid || com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(h.document) == vf
                if (closed) runCatching { if (h.isValid) h.dispose() }
                closed
            }
        }
    }

    private val sessions = ConcurrentHashMap<String, RootSession>()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "GgNotes steer").apply { isDaemon = true } }
    private var touchTask: ScheduledFuture<*>? = null
    private var pollTask: ScheduledFuture<*>? = null
    private var docsTask: ScheduledFuture<*>? = null
    private var watcher: InboxWatcher? = null
    /** The docs directories the watcher already covers (they appear after start, when a writer creates them). */
    private val watchedDocs = java.util.concurrent.ConcurrentHashMap.newKeySet<Path>()
    @Volatile private var started = false
    val handler = SteerHandler(project, this)

    /** Publish presence for every git root; idempotent. Runs git, so call off the EDT. */
    fun start() {
        if (started || project.isDisposed) return
        // tests publish presence only on purpose (startSync), never into the developer's live state dir
        if (com.intellij.openapi.application.ApplicationManager.getApplication().isUnitTestMode) return
        started = true
        AppExecutorUtil.getAppExecutorService().execute {
            try {
                for (repo in GitRepositoryManager.getInstance(project).repositories) register(repo)
                touchTask = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({ touchAll() }, TOUCH_MILLIS, TOUCH_MILLIS, TimeUnit.MILLISECONDS)
                pollTask = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({ drainAll() }, POLL_MILLIS, POLL_MILLIS, TimeUnit.MILLISECONDS)
                docsTask = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({ reloadDocs() }, DOCS_POLL_MILLIS, DOCS_POLL_MILLIS, TimeUnit.MILLISECONDS)
                // the inbox itself is watched too: that is where a writer creates docs/
                watcher = InboxWatcher(sessions.values.map { it.ideDir }) { drainAll(); reloadDocs() }.also { it.start() }
                for (s in sessions.values) watcher?.add(s.inbox, createOnly = true)
                watchDocsDirs()
            } catch (t: Throwable) {
                LOG.warn("GgNotes steer: start failed", t)
            }
        }
    }

    init {
        project.messageBus.connect(this).subscribe(com.intellij.openapi.fileEditor.FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : com.intellij.openapi.fileEditor.FileEditorManagerListener {
                override fun fileClosed(source: com.intellij.openapi.fileEditor.FileEditorManager, file: VirtualFile) {
                    for (s in sessions.values) s.fileClosed(file)
                }
            })
    }

    /** Blocking variant for tests: register every root now and return the sessions. */
    fun startSync(): List<RootSession> {
        for (repo in GitRepositoryManager.getInstance(project).repositories) register(repo)
        if (touchTask == null) touchTask = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({ touchAll() }, TOUCH_MILLIS, TOUCH_MILLIS, TimeUnit.MILLISECONDS)
        if (pollTask == null) pollTask = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({ drainAll() }, POLL_MILLIS, POLL_MILLIS, TimeUnit.MILLISECONDS)
        if (docsTask == null) docsTask = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({ reloadDocs() }, DOCS_POLL_MILLIS, DOCS_POLL_MILLIS, TimeUnit.MILLISECONDS)
        started = true
        return sessions.values.toList()
    }

    private fun register(repo: GitRepository): RootSession? {
        sessions[repo.root.path]?.let { return it }
        val common = GitAccess.commonDir(project, repo.root) ?: return null
        val top = GitAccess.topLevel(project, repo.root) ?: return null
        val inbox = SteerPaths.inboxDir(SteerPaths.defaultStateBase(), common, top)
        val ideDir = SteerPaths.ideDir(inbox)
        Files.createDirectories(ideDir)
        Inbox.sweep(ideDir)
        val json = Presence.json(
            pid = ProcessHandle.current().pid(),
            worktree = top,
            started = Instant.now(),
            ide = ApplicationInfo.getInstance().fullApplicationName,
            plugin = PluginVersion.current,
        )
        Presence.write(inbox, json)
        val session = RootSession(repo, top, inbox, json, SessionMemory(top))
        session.marks = SessionNoteMarks(project, session, this)
        sessions[repo.root.path] = session
        LOG.info("GgNotes steer: presence for ${repo.root.name} at $inbox")
        return session
    }

    fun sessionFor(root: VirtualFile): RootSession? = sessions[root.path]

    fun allSessions(): Collection<RootSession> = sessions.values

    // ---- session documents as the tool window sees them ----------------------------------

    /** One row of the tool window's session group: an overview file, whether it is on screen, and its unresolved anchors. */
    class SessionRow(val session: RootSession, val entry: dev.ggnotes.session.DocIndex.Entry<dev.ggnotes.session.SessionOverviewDoc>, val shown: Boolean, val unresolved: List<String>)

    private val sessionListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /** Told (on any thread) whenever a session document appears, changes, is shown or goes. */
    fun addSessionListener(parent: Disposable, listener: () -> Unit) {
        sessionListeners.add(listener)
        com.intellij.openapi.util.Disposer.register(parent) { sessionListeners.remove(listener) }
    }

    fun sessionChanged() {
        for (l in sessionListeners) runCatching { l() }.onFailure { LOG.warn("GgNotes steer: session listener", it) }
    }

    /** Reconcile every root's docs directory now, on the worker (the 2 s poll and the watcher call this). */
    fun reloadDocs() {
        if (project.isDisposed) return
        runCatching {
            worker.execute {
                for (s in sessions.values) {
                    val changes = runCatching { s.docs.reload() }.getOrElse { LOG.warn("GgNotes: reading ${s.docsDir}", it); null } ?: continue
                    if (!changes.isEmpty || changes.refused.isNotEmpty()) docsChanged(s, changes)
                }
            }
        }
    }

    /** On the worker (a command in flight): reconcile [s]'s directory now and apply what changed, before answering. */
    fun reloadDocsNow(s: RootSession) {
        val changes = runCatching { s.docs.reload() }.getOrElse { LOG.warn("GgNotes: reading ${s.docsDir}", it); return }
        if (!changes.isEmpty || changes.refused.isNotEmpty()) docsChanged(s, changes)
    }

    /** Worker: the directory changed — boxes follow, a replaced open overview is re-rendered in place, a gone one closes, the tree is told. */
    private fun docsChanged(s: RootSession, changes: dev.ggnotes.session.DocIndex.Changes) {
        LOG.info("GgNotes: session docs of ${s.repo.root.name}: +${changes.added.size} ~${changes.replaced.size} -${changes.removed.size}")
        // SPEC §3.4: a file a reader ignores is named once, with the reason, so a writer can find out why
        for ((name, reason) in changes.refused) LOG.info("GgNotes: session docs of ${s.repo.root.name}: ${name.fileName} ignored: $reason")
        if (changes.notesChanged) s.marks.docsChanged()
        val open = dev.ggnotes.overview.OverviewService.getInstance(project).open?.overview
        for (name in changes.replaced) {
            if (name.kind != dev.ggnotes.session.DocKind.OVERVIEW || open?.viewKind != viewKind(s, name.id)) continue
            val entry = s.docs.index.overview(name.id) ?: continue
            showOverview(s, entry, keepAnchor = true, raise = false)
        }
        for (name in changes.removed) {
            if (name.kind == dev.ggnotes.session.DocKind.OVERVIEW && open?.viewKind == viewKind(s, name.id)) onEdt { dev.ggnotes.overview.OverviewService.getInstance(project).close() }
        }
        sessionChanged()
    }

    /** The session overviews of [root] newest first; empty when the root has no session or no docs directory (yet). */
    fun sessionOverviews(root: VirtualFile): List<SessionRow> {
        val s = sessions[root.path] ?: return emptyList()
        return s.docs.index.overviews().map { SessionRow(s, it, isShown(s, it.doc.id), unresolved(s, it.doc)) }
    }

    /** SPEC §3.2: anchors whose file is not in the worktree or whose note is not in the directory. */
    fun unresolved(s: RootSession, doc: dev.ggnotes.session.SessionOverviewDoc): List<String> = doc.anchors.filter { a ->
        if (a.note != null) s.docs.index.note(a.note) == null
        else s.repo.root.findFileByRelativePath(a.path!!)?.let { it.isDirectory } != false
    }.map { it.dest }

    /** The viewer's identity of a session overview: the root is part of it, ids being unique per worktree only. */
    fun viewKind(s: RootSession, id: String): String = "session:${s.topLevel}\u0000$id:"

    /** [dev.ggnotes.store.Overview.identity] of the viewer form, without building it (the tree compares identities on every rebuild). */
    fun sessionIdentity(s: RootSession, doc: dev.ggnotes.session.SessionOverviewDoc): String = viewKind(s, doc.id) + "\u0000" + doc.title.trim()

    /** The session overview as the overview viewer shows it: live anchors into the worktree, note anchors rewritten to the note's lines now. */
    fun viewerOverview(s: RootSession, entry: dev.ggnotes.session.DocIndex.Entry<dev.ggnotes.session.SessionOverviewDoc>): dev.ggnotes.store.Overview {
        val doc = entry.doc
        val unresolved = unresolved(s, doc).toSet()
        val md = SessionAnchors.rewrite(doc.body, unresolved) { noteId ->
            s.docs.index.note(noteId)?.let { n ->
                val p = s.marks.placementOf(n.doc)
                n.doc.path + ":" + p.start + (if (p.end != p.start) "-" + p.end else "")
            }
        }
        return dev.ggnotes.store.Overview(
            id = doc.id, title = doc.title, author = doc.author, commit = "", branch = "", preview = null, prNumber = null,
            created = doc.created, updated = java.time.OffsetDateTime.ofInstant(Instant.ofEpochMilli(entry.time), java.time.ZoneOffset.UTC),
            document = dev.ggnotes.store.DocumentResult.Ok(dev.ggnotes.store.OverviewDocument(doc.title, md.ifBlank { "_(empty)_" }, emptyList())),
            sourceFile = s.docs.fileOf(dev.ggnotes.session.DocName(doc.id, dev.ggnotes.session.DocKind.OVERVIEW)),
            viewKind = viewKind(s, doc.id), liveWorktree = s.topLevel,
        )
    }

    /** "Copy Link" of a session overview row (see [dev.ggnotes.session.SessionDocRefs]). */
    fun overviewLink(s: RootSession, doc: dev.ggnotes.session.SessionOverviewDoc): String =
        dev.ggnotes.session.SessionDocRefs.overviewLink(doc.id, doc.title, Path.of(s.topLevel).toString(),
            s.docs.fileOf(dev.ggnotes.session.DocName(doc.id, dev.ggnotes.session.DocKind.OVERVIEW)).toString())

    /** "Copy Session Note Link": the note where it sits now. */
    fun noteLink(s: RootSession, doc: dev.ggnotes.session.SessionNoteDoc): String =
        dev.ggnotes.session.SessionDocRefs.noteLink(doc.id, doc.path, s.marks.placementOf(doc), doc.summary, Path.of(s.topLevel).toString(),
            s.docs.fileOf(dev.ggnotes.session.DocName(doc.id, dev.ggnotes.session.DocKind.NOTE)).toString())

    fun isShown(s: RootSession, id: String): Boolean =
        dev.ggnotes.overview.OverviewService.getInstance(project).open?.overview?.viewKind == viewKind(s, id)

    /**
     * Open [entry] in the tool window, synchronously on the EDT; [keepAnchor] re-renders a replaced
     * document on the same anchor. [raise] shows the tool window (a rewrite of an open file — gg's
     * tours are rewritten on every report — re-renders without raising a window the user hid).
     */
    fun showOverview(s: RootSession, entry: dev.ggnotes.session.DocIndex.Entry<dev.ggnotes.session.SessionOverviewDoc>, keepAnchor: Boolean, raise: Boolean = true) {
        val overview = viewerOverview(s, entry)
        onEdt {
            val service = dev.ggnotes.overview.OverviewService.getInstance(project)
            if (keepAnchor) service.openKeepingAnchor(overview, s.repo) else service.open(overview, s.repo)
            if (raise) com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow(dev.ggnotes.overview.OverviewService.TOOL_WINDOW_ID)?.show()
        }
        sessionChanged()
    }

    /** EDT (the tree's row click): open the note's working file with its lines, where they sit now, selected; focus stays in the tree (its Delete and Ctrl+C are the row's). */
    fun showNote(s: RootSession, entry: dev.ggnotes.session.DocIndex.Entry<dev.ggnotes.session.SessionNoteDoc>) {
        val doc = entry.doc
        val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(s.topLevel, doc.path))
            ?.takeIf { !it.isDirectory } ?: return
        val p = s.marks.placementOf(doc)
        val editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
            .openTextEditor(com.intellij.openapi.fileEditor.OpenFileDescriptor(project, vf, (p.start - 1).coerceAtLeast(0), 0), false) ?: return
        val text = editor.document
        if (text.lineCount == 0) return
        val first = p.start.coerceIn(1, text.lineCount)
        val last = p.end.coerceIn(first, text.lineCount)
        editor.selectionModel.setSelection(text.getLineStartOffset(first - 1), text.getLineEndOffset(last - 1))
    }

    /** Delete (the tree's row action): unlink the overview's file; the reconcile that follows drops the row and closes its document. */
    fun deleteOverview(s: RootSession, id: String): Boolean {
        val gone = s.docs.delete(dev.ggnotes.session.DocName(id, dev.ggnotes.session.DocKind.OVERVIEW))
        reloadDocs()
        return gone
    }

    /** Delete (the editor's menu): unlink one note's file. */
    fun deleteNote(s: RootSession, id: String): Boolean {
        val gone = s.docs.delete(dev.ggnotes.session.DocName(id, dev.ggnotes.session.DocKind.NOTE))
        reloadDocs()
        return gone
    }

    /** "Delete all notes on this file": unlink every note file naming [path]; returns how many went. */
    fun deleteNotesOn(s: RootSession, path: String): Int {
        val n = s.docs.index.notesOn(path).count { s.docs.delete(dev.ggnotes.session.DocName(it.doc.id, dev.ggnotes.session.DocKind.NOTE)) }
        reloadDocs()
        return n
    }

    private val retiredNoticed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** A gg older than the session-docs contract sent an in-memory verb: say so once per root and verb, the reply names the fix. */
    fun noteRetiredVerb(s: RootSession, verb: String) {
        if (!retiredNoticed.add(s.repo.root.path + "\u0000" + verb)) return
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater({
            com.intellij.notification.NotificationGroupManager.getInstance().getNotificationGroup("GgNotes")
                .createNotification(dev.ggnotes.GgNotesBundle.message("steer.retired", verb, s.docsDir.toString()), com.intellij.notification.NotificationType.WARNING)
                .notify(project)
        }, project.disposed)
    }

    private fun onEdt(block: () -> Unit) {
        val app = com.intellij.openapi.application.ApplicationManager.getApplication()
        if (app.isDispatchThread) { if (!project.isDisposed) block() } else app.invokeAndWait { if (!project.isDisposed) block() }
    }

    /** Watch every docs directory that exists now (a writer may create one at any time; the poll covers the gap). */
    private fun watchDocsDirs() {
        val w = watcher ?: return
        for (s in sessions.values) {
            if (s.docsDir in watchedDocs || !Files.isDirectory(s.docsDir)) continue
            w.add(s.docsDir)
            watchedDocs.add(s.docsDir)
        }
    }

    /** Every second: keep presence fresh, and publish it for roots git4idea detected after project open. */
    private fun touchAll() {
        if (project.isDisposed) return
        runCatching {
            for (repo in GitRepositoryManager.getInstance(project).repositories) {
                if (!sessions.containsKey(repo.root.path)) register(repo)?.let { watcher?.add(it.ideDir); watcher?.add(it.inbox, createOnly = true) }
            }
            watchDocsDirs()
        }.onFailure { LOG.warn("GgNotes steer: registering roots", it) }
        for (s in sessions.values) runCatching { Presence.touch(s.inbox, s.presenceJson) }
    }

    /**
     * Drain every sub-inbox ON THE WORKER: listing, reading, deleting, handling
     * and replying happen on one thread, so a poll wake and a watcher wake can
     * never both pick up the same command (PLUGIN.md §6: one at a time, in order).
     */
    fun drainAll() {
        if (project.isDisposed) return
        runCatching {
            worker.execute {
                for (s in sessions.values) {
                    val commands = runCatching { Inbox.drain(s.ideDir) }.getOrElse { emptyList() }
                    for (c in commands) {
                        val reply = try {
                            handler.handle(s, c)
                        } catch (t: Throwable) {
                            LOG.warn("GgNotes steer: ${c.cmd} ${c.id} failed", t)
                            Reply.fail(c.id, t.message ?: t.javaClass.simpleName)
                        }
                        if (c.wait) runCatching { Inbox.writeReply(s.ideDir, reply) }.onFailure { LOG.warn("GgNotes steer: reply ${c.id}", it) }
                    }
                }
            }
        } // a rejected execution after shutdown is nothing to report
    }

    override fun dispose() {
        touchTask?.cancel(false)
        pollTask?.cancel(false)
        docsTask?.cancel(false)
        watcher?.stop()
        worker.shutdownNow()
        for (s in sessions.values) Presence.remove(s.inbox)
        sessions.clear()
    }
}
