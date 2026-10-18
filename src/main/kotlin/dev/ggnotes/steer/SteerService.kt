package dev.ggnotes.steer

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.AppExecutorUtil
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
 * worker thread, in order, and answered when it asked to wait.
 */
@Service(Service.Level.PROJECT)
class SteerService(private val project: Project) : Disposable {

    companion object {
        private val LOG = logger<SteerService>()
        const val POLL_MILLIS = 500L
        const val TOUCH_MILLIS = 1000L
        fun getInstance(project: Project): SteerService = project.getService(SteerService::class.java)
    }

    /** A root's inbox and the memory of what the agent opened there. */
    class RootSession(
        val repo: GitRepository,
        /** `git rev-parse --show-toplevel`, the string gg hashes and the one in `ide.json`. */
        val topLevel: String,
        val inbox: Path,
        val presenceJson: String,
        val memory: SessionMemory,
    ) {
        val ideDir: Path get() = SteerPaths.ideDir(inbox)
        /** The virtual file behind each open-files entry (IDE side of [SessionMemory]). */
        val files = ConcurrentHashMap<String, VirtualFile>()
        /** Live `highlight` markers; cleared by `highlight_clear` or when their file closes. */
        val highlights = java.util.concurrent.CopyOnWriteArrayList<com.intellij.openapi.editor.markup.RangeHighlighter>()
        /** The boxes and listeners of the temporary notes; set by the service. */
        lateinit var notes: TempNotes

        /** A file the user closed leaves the list with its highlights and notes (PLUGIN.md §5). */
        fun fileClosed(vf: VirtualFile) {
            for ((id, f) in files.entries.toList()) {
                if (f == vf) {
                    files.remove(id)
                    memory.removeFile(id)
                    notes.fileClosed(id)
                }
            }
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
    private var watcher: InboxWatcher? = null
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
                watcher = InboxWatcher(sessions.values.map { it.ideDir }) { drainAll() }.also { it.start() }
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
            plugin = PluginManagerCore.getPlugin(PluginId.getId("dev.ggnotes"))?.version ?: "dev",
        )
        Presence.write(inbox, json)
        val session = RootSession(repo, top, inbox, json, SessionMemory(top))
        session.notes = TempNotes(project, session, this)
        sessions[repo.root.path] = session
        LOG.info("GgNotes steer: presence for ${repo.root.name} at $inbox")
        return session
    }

    fun sessionFor(root: VirtualFile): RootSession? = sessions[root.path]

    fun allSessions(): Collection<RootSession> = sessions.values

    // ---- in-memory overviews as the tool window sees them --------------------------------

    /** One row of the tool window's "this session" group: an in-memory overview and whether it is on screen. */
    class SessionRow(val session: RootSession, val overview: SessionMemory.Overview, val shown: Boolean)

    private val sessionListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /** Told (on any thread) whenever an in-memory overview is added, replaced, shown or removed. */
    fun addSessionListener(parent: Disposable, listener: () -> Unit) {
        sessionListeners.add(listener)
        com.intellij.openapi.util.Disposer.register(parent) { sessionListeners.remove(listener) }
    }

    fun sessionChanged() {
        for (l in sessionListeners) runCatching { l() }.onFailure { LOG.warn("GgNotes steer: session listener", it) }
    }

    /** The in-memory overviews of [root], in creation order; empty when the root has no session (yet). */
    fun sessionOverviews(root: VirtualFile): List<SessionRow> {
        val s = sessions[root.path] ?: return emptyList()
        return s.memory.overviews().map { SessionRow(s, it, isShown(s, it)) }
    }

    /** The session overview as the overview viewer shows it: live anchors into the worktree, note anchors rewritten. */
    fun viewerOverview(s: RootSession, o: SessionMemory.Overview): dev.ggnotes.store.Overview {
        val md = s.memory.viewerMarkdown(o)
        return dev.ggnotes.store.Overview(
            id = o.id, title = o.title, author = "agent", commit = "", branch = "", preview = null, prNumber = null,
            created = null, updated = java.time.OffsetDateTime.now(),
            document = dev.ggnotes.store.DocumentResult.Ok(dev.ggnotes.store.OverviewDocument(o.title, md.ifBlank { "_(empty)_" }, emptyList())),
            sourceFile = s.inbox, viewKind = "session:${o.id}:", liveWorktree = s.topLevel,
        )
    }

    fun isShown(s: RootSession, o: SessionMemory.Overview): Boolean =
        dev.ggnotes.overview.OverviewService.getInstance(project).open?.overview?.identity == viewerOverview(s, o).identity

    /** Open [o] in the tool window, synchronously on the EDT; [keepAnchor] is `overview_set`'s in-place path. */
    fun showOverview(s: RootSession, o: SessionMemory.Overview, keepAnchor: Boolean) {
        val overview = viewerOverview(s, o)
        onEdt {
            val service = dev.ggnotes.overview.OverviewService.getInstance(project)
            if (keepAnchor) service.openKeepingAnchor(overview, s.repo) else service.open(overview, s.repo)
            com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow(dev.ggnotes.overview.OverviewService.TOOL_WINDOW_ID)?.show()
        }
        s.memory.markShown(o.id)
        sessionChanged()
    }

    /** Drop in-memory overview [id] (the tree's Remove, `overview_rm`): closes its document when it is on screen. */
    fun removeOverview(s: RootSession, id: String): Boolean {
        val o = s.memory.findOverview(id) ?: return false
        val shown = isShown(s, o)
        s.memory.removeOverview(id)
        if (shown) onEdt { dev.ggnotes.overview.OverviewService.getInstance(project).close() }
        sessionChanged()
        return true
    }

    private fun onEdt(block: () -> Unit) {
        val app = com.intellij.openapi.application.ApplicationManager.getApplication()
        if (app.isDispatchThread) { if (!project.isDisposed) block() } else app.invokeAndWait { if (!project.isDisposed) block() }
    }

    /** Every second: keep presence fresh, and publish it for roots git4idea detected after project open. */
    private fun touchAll() {
        if (project.isDisposed) return
        runCatching {
            for (repo in GitRepositoryManager.getInstance(project).repositories) {
                if (!sessions.containsKey(repo.root.path)) register(repo)?.let { watcher?.add(it.ideDir) }
            }
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
        watcher?.stop()
        worker.shutdownNow()
        for (s in sessions.values) Presence.remove(s.inbox)
        sessions.clear()
    }
}
