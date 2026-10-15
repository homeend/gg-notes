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

    /** Drain every sub-inbox; each command runs on the worker, in order, and is answered there. */
    fun drainAll() {
        if (project.isDisposed) return
        for (s in sessions.values) {
            val commands = runCatching { Inbox.drain(s.ideDir) }.getOrElse { emptyList() }
            for (c in commands) {
                worker.execute {
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
