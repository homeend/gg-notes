package dev.ggnotes.overview

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.EditorNotifications
import com.intellij.util.concurrency.AppExecutorUtil
import dev.ggnotes.git.GitAccess
import dev.ggnotes.store.CommitGroup
import dev.ggnotes.store.DocumentResult
import dev.ggnotes.store.MarkdownRenderer
import dev.ggnotes.store.Overview
import dev.ggnotes.store.OverviewGrouping
import dev.ggnotes.store.RenderedAnchor
import dev.ggnotes.store.RenderedDocument
import dev.ggnotes.store.StoreContents
import dev.ggnotes.store.StoreLocator
import dev.ggnotes.store.StoreReader
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import git4idea.repo.GitRepositoryManager
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A file opened from an overview: which overview, which commit, which repo-relative path. */
data class OverviewFileRef(val overviewIdentity: String, val root: VirtualFile, val commit: String, val path: String)

/** Set on every virtual file the plugin opens from an anchor; the banner and the overlays key on it. */
val OVERVIEW_FILE_KEY: Key<OverviewFileRef> = Key.create("dev.ggnotes.overviewFile")

/** One repository's store as last read. */
class RepoOverviews(
    val repo: GitRepository,
    val storeDir: Path?,
    val contents: StoreContents,
    val headSha: String?,
    val groups: List<CommitGroup>,
    /** A problem resolving the store (git failed), shown as a row. */
    val error: String?,
)

/** What the tool window shows: every repository of the project. */
class Snapshot(val repos: List<RepoOverviews>) {
    companion object {
        val EMPTY = Snapshot(emptyList())
    }
}

/** The overview the user has open: its rendering, anchor state and the editor overlays bound to it. */
class OpenOverview(val overview: Overview, val repo: GitRepository, rendered: RenderedDocument) : Disposable {
    @Volatile var rendered: RenderedDocument = rendered
    @Volatile var currentAnchor: Int? = null
    /** Anchors whose file or lines are not at the commit (SPEC §6), known after [resolved]. */
    @Volatile var missing: Set<Int> = emptySet()
    @Volatile var resolved: Boolean = false
    /** `git show <commit>:<path>` per path, null = not at the commit. */
    val contents = ConcurrentHashMap<String, java.util.Optional<ByteArray>>()
    val anchors: List<RenderedAnchor> get() = rendered.anchors
    val document get() = (overview.document as? DocumentResult.Ok)?.document
    override fun dispose() = Unit
}

/** What the tool window panel offers the service: showing an overview and scrolling to an anchor. */
interface OverviewUi {
    fun showOverview(open: OpenOverview?)
    fun showAnchor(n: Int)
}

/**
 * Project service: resolves each repository's store (SPEC §3), reads it,
 * re-reads on change (polling the directory — files are replaced by rename,
 * SPEC §8.1), and holds the open overview with its anchor navigation.
 */
@Service(Service.Level.PROJECT)
class OverviewService(private val project: Project) : Disposable {

    companion object {
        const val TOOL_WINDOW_ID = "GgNotes"
        private val LOG = logger<OverviewService>()
        fun getInstance(project: Project): OverviewService = project.getService(OverviewService::class.java)
    }

    private val reader = StoreReader()

    @Volatile var snapshot: Snapshot = Snapshot.EMPTY
        private set

    @Volatile var open: OpenOverview? = null
        private set

    @Volatile var ui: OverviewUi? = null

    private val snapshotListeners = CopyOnWriteArrayList<() -> Unit>()
    private val subjects = ConcurrentHashMap<String, java.util.Optional<String>>() // "root|sha" -> subject
    private val refreshing = AtomicBoolean(false)
    private var poll: ScheduledFuture<*>? = null
    @Volatile private var signature: Map<Path, Pair<Long, Long>> = emptyMap()
    @Volatile private var headSignature: Map<String, String?> = emptyMap()

    init {
        project.messageBus.connect(this).subscribe(GitRepository.GIT_REPO_CHANGE, GitRepositoryChangeListener { refresh() })
        poll = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({ pollForChanges() }, 2, 2, TimeUnit.SECONDS)
    }

    fun addSnapshotListener(parent: Disposable, listener: () -> Unit) {
        snapshotListeners += listener
        Disposer.register(parent) { snapshotListeners -= listener }
    }

    // ---- reading the stores ----------------------------------------------------------------

    /** Re-read every repository's store in the background; listeners fire on the EDT. */
    fun refresh() {
        if (project.isDisposed) return
        if (!refreshing.compareAndSet(false, true)) return
        AppExecutorUtil.getAppExecutorService().execute {
            try {
                val snap = computeSnapshot()
                snapshot = snap
                invokeLater { snapshotListeners.forEach { it() } }
            } catch (t: Throwable) {
                LOG.warn("GgNotes: refresh failed", t)
            } finally {
                refreshing.set(false)
            }
        }
    }

    private fun computeSnapshot(): Snapshot {
        val repos = GitRepositoryManager.getInstance(project).repositories
        val sig = HashMap<Path, Pair<Long, Long>>()
        val heads = HashMap<String, String?>()
        val result = repos.map { repo ->
            val head = repo.currentRevision
            heads[repo.root.path] = head
            val common = GitAccess.commonDir(project, repo.root)
            if (common == null) {
                RepoOverviews(repo, null, StoreContents.EMPTY, head, emptyList(), "git rev-parse failed in ${repo.root.name}")
            } else {
                val dir = StoreLocator.storeDir(StoreLocator.defaultBaseDir(), StoreLocator.key(common))
                for (name in StoreReader.PARTS) {
                    val f = dir.resolve(name)
                    sig[f] = fileSignature(f)
                }
                val contents = reader.read(dir)
                val subjectMap = HashMap<String, String?>()
                for (sha in contents.overviews.map { it.commit }.distinct()) {
                    subjectMap[sha] = subjectOf(repo.root, sha)
                }
                RepoOverviews(repo, dir, contents, head, OverviewGrouping.group(contents.overviews, head, subjectMap), null)
            }
        }
        signature = sig
        headSignature = heads
        return Snapshot(result)
    }

    private fun subjectOf(root: VirtualFile, sha: String): String? {
        val key = root.path + "|" + sha
        subjects[key]?.let { return it.orElse(null) }
        val subject = GitAccess.commitSubject(project, root, sha)
        // an unknown commit may be fetched later: do not cache the miss
        if (subject != null) subjects[key] = java.util.Optional.of(subject)
        return subject
    }

    private fun fileSignature(f: Path): Pair<Long, Long> = try {
        if (Files.isRegularFile(f)) Files.getLastModifiedTime(f).toMillis() to Files.size(f) else -1L to -1L
    } catch (e: Exception) {
        -1L to -1L
    }

    private fun pollForChanges() {
        if (project.isDisposed) return
        val current = signature
        val changed = current.any { (f, s) -> fileSignature(f) != s } ||
            headSignature.any { (rootPath, head) ->
                GitRepositoryManager.getInstance(project).repositories.firstOrNull { it.root.path == rootPath }?.currentRevision != head
            }
        if (changed) refresh()
    }

    // ---- the open overview -----------------------------------------------------------------

    /** Open (select) an overview: render it, then resolve its anchors against the commit in the background. */
    fun open(overview: Overview, repo: GitRepository) {
        val previous = open
        if (previous != null && previous.overview.identity == overview.identity && previous.repo == repo && previous.overview.updated == overview.updated) {
            ui?.showOverview(previous)
            return
        }
        previous?.let { Disposer.dispose(it) }
        val doc = (overview.document as? DocumentResult.Ok)?.document
        val rendered = if (doc != null) MarkdownRenderer.render(doc.overview) else RenderedDocument("", emptyList())
        val o = OpenOverview(overview, repo, rendered)
        Disposer.register(this, o)
        open = o
        RemarkOverlay.install(project, o)
        ui?.showOverview(o)
        EditorNotifications.getInstance(project).updateAllNotifications()
        if (doc != null) resolveAnchors(o)
    }

    fun close() {
        open?.let { Disposer.dispose(it) }
        open = null
        ui?.showOverview(null)
        EditorNotifications.getInstance(project).updateAllNotifications()
    }

    /** SPEC §6: an anchor whose path is not at the commit or whose lines exceed the file is marked missing. */
    private fun resolveAnchors(o: OpenOverview) {
        AppExecutorUtil.getAppExecutorService().execute {
            val missing = HashSet<Int>()
            for (a in o.anchors) {
                val bytes = contentOf(o, a.target.path)
                if (bytes == null) {
                    missing += a.index
                    continue
                }
                val end = a.target.end ?: continue
                if (end > lineCount(bytes)) missing += a.index
            }
            if (open !== o) return@execute
            o.missing = missing
            o.resolved = true
            o.rendered = MarkdownRenderer.render(o.document!!.overview, o.currentAnchor, missing)
            invokeLater { if (open === o) ui?.showOverview(o) }
        }
    }

    /** Cached `git show`; null when the path is not at the commit. Background only. */
    fun contentOf(o: OpenOverview, path: String): ByteArray? {
        o.contents[path]?.let { return it.orElse(null) }
        val bytes = GitAccess.fileContent(project, o.repo.root, o.overview.commit, path)
        o.contents[path] = java.util.Optional.ofNullable(bytes)
        return bytes
    }

    private fun lineCount(bytes: ByteArray): Int {
        if (bytes.isEmpty()) return 0
        var n = 0
        for (b in bytes) if (b == '\n'.code.toByte()) n++
        if (bytes.last() != '\n'.code.toByte()) n++
        return n
    }

    // ---- anchor navigation -----------------------------------------------------------------

    /** Make anchor [n] current, re-render the document with it marked, and open its file in the editor. */
    fun goToAnchor(n: Int, openFile: Boolean = true) {
        val o = open ?: return
        val anchor = o.anchors.firstOrNull { it.index == n } ?: return
        o.currentAnchor = n
        o.rendered = MarkdownRenderer.render(o.document?.overview ?: "", n, o.missing)
        ui?.showOverview(o)
        ui?.showAnchor(n)
        EditorNotifications.getInstance(project).updateAllNotifications()
        if (openFile && n !in o.missing) AnchorNavigator.open(project, this, o, anchor)
    }

    fun nextAnchor() = step(+1)

    fun previousAnchor() = step(-1)

    private fun step(delta: Int) {
        val o = open ?: return
        if (o.anchors.isEmpty()) return
        val current = o.currentAnchor
        val next = when {
            current == null -> if (delta > 0) 1 else o.anchors.size
            else -> ((current - 1 + delta).mod(o.anchors.size)) + 1
        }
        goToAnchor(next)
    }

    /** gg's Backspace: focus the tool window on the overview, at the anchor the user left. */
    fun backToOverview() {
        val o = open ?: return
        val tw = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
        tw.activate({
            ui?.showOverview(o)
            o.currentAnchor?.let { ui?.showAnchor(it) }
        }, true)
    }

    private fun invokeLater(block: () -> Unit) {
        ApplicationManager.getApplication().invokeLater(block, project.disposed)
    }

    override fun dispose() {
        poll?.cancel(false)
        open = null
    }
}
