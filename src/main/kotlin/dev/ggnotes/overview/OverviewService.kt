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
import dev.ggnotes.store.DocumentResult
import dev.ggnotes.store.FileState
import dev.ggnotes.store.MarkdownRenderer
import dev.ggnotes.store.NoteComposer
import dev.ggnotes.store.NoteThread
import dev.ggnotes.store.Relocation
import dev.ggnotes.store.RelocationStatus
import dev.ggnotes.store.Relocator
import dev.ggnotes.store.Overview
import dev.ggnotes.store.RenderedAnchor
import dev.ggnotes.store.RenderedDocument
import dev.ggnotes.store.RepoModel
import dev.ggnotes.store.Review
import dev.ggnotes.store.ReviewComposer
import dev.ggnotes.store.Reviews
import dev.ggnotes.store.StoreContents
import dev.ggnotes.store.StoreLocator
import dev.ggnotes.store.StoreModel
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

/** A file opened from an overview: which overview, which commit (empty for a live file), which repo-relative path. */
data class OverviewFileRef(val overviewIdentity: String, val root: VirtualFile, val commit: String, val path: String)

/** Set on every virtual file the plugin opens from an anchor; the banner and the overlays key on it. */
val OVERVIEW_FILE_KEY: Key<OverviewFileRef> = Key.create("dev.ggnotes.overviewFile")

/** One repository's store as last read. */
class RepoOverviews(
    val repo: GitRepository,
    val storeDir: Path?,
    val contents: StoreContents,
    val headSha: String?,
    val model: RepoModel,
    /** A problem resolving the store (git failed), shown as a row. */
    val error: String?,
    /** Working reviews whose `files[].blob` no longer match the tree, by review id (SPEC-part2 §5.1). */
    val treeChanged: Map<String, List<String>> = emptyMap(),
    /** The current tip of every branch an overview or review names (null = the branch is gone): gg's on-branch vs was-tip. */
    val branchTips: Map<String, String?> = emptyMap(),
    /** The subject of every commit a record names (null = not in the repository), looked up on refresh. */
    val subjects: Map<String, String?> = emptyMap(),
)

/** What the tool window shows: every repository of the project. */
class Snapshot(val repos: List<RepoOverviews>) {
    companion object {
        val EMPTY = Snapshot(emptyList())
    }
}

/** The document the user has open (an overview, a review or a note thread): its rendering, anchor state and overlays. */
class OpenOverview(val overview: Overview, val repo: GitRepository, rendered: RenderedDocument) : Disposable {
    @Volatile var rendered: RenderedDocument = rendered
    @Volatile var currentAnchor: Int? = null
    /** Anchors whose file or lines are not at the commit (SPEC §6), known after [resolved]. */
    @Volatile var missing: Set<Int> = emptySet()
    @Volatile var resolved: Boolean = false
    /** The side's content per path (`git show <commit>:<path>`, or the working file), null = absent. */
    val contents = ConcurrentHashMap<String, java.util.Optional<ByteArray>>()
    val anchors: List<RenderedAnchor> get() = rendered.anchors
    val document get() = (overview.document as? DocumentResult.Ok)?.document
    override fun dispose() = Unit
}

/** What the tool window panel offers the service: showing a document and scrolling to an anchor. */
interface OverviewUi {
    fun showOverview(open: OpenOverview?)
    fun showAnchor(n: Int)
    /** Show or hide the list above the document; hidden, the document takes the whole tool window. */
    fun showList(visible: Boolean)
}

/**
 * Project service: resolves each repository's store (SPEC §3), reads it,
 * re-reads on change (polling the directory — files are replaced by rename,
 * SPEC §8.1), and holds the open document with its anchor navigation.
 */
@Service(Service.Level.PROJECT)
class OverviewService(private val project: Project) : Disposable {

    companion object {
        const val TOOL_WINDOW_ID = "GgNotes"
        const val VIEW_PROPERTY = "ggnotes.view"
        private val LOG = logger<OverviewService>()
        fun getInstance(project: Project): OverviewService = project.getService(OverviewService::class.java)
    }

    private val reader = StoreReader()

    @Volatile var snapshot: Snapshot = Snapshot.EMPTY
        private set

    @Volatile var open: OpenOverview? = null
        private set

    @Volatile var ui: OverviewUi? = null

    /** "Document only": the list above the document is hidden (toolbar toggle; per project, not persisted). */
    @Volatile var listHidden: Boolean = false
        private set

    fun setListHidden(hidden: Boolean) {
        listHidden = hidden
        ui?.showList(!hidden)
    }

    /**
     * The tool window's grouping (toolbar "View"): remembered per project. Setting it runs the
     * snapshot listeners, as a snapshot change does, so the panel rebuilds.
     */
    var view: NoteView
        get() = NoteView.parse(com.intellij.ide.util.PropertiesComponent.getInstance(project).getValue(VIEW_PROPERTY))
        set(value) {
            com.intellij.ide.util.PropertiesComponent.getInstance(project).setValue(VIEW_PROPERTY, value.name)
            invokeLater { snapshotListeners.forEach { it() } }
        }

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
                LOG.info("GgNotes: " + snap.repos.joinToString("; ") { r ->
                    "${r.repo.root.name}: ${r.contents.overviews.size} overview(s), ${r.contents.records.size} record(s) in ${r.storeDir ?: "<no store>"}" +
                        (r.error?.let { " ($it)" } ?: "") + (if (r.contents.problems.isEmpty()) "" else " problems=${r.contents.problems}")
                }.ifEmpty { "no git repositories" })
                invokeLater { snapshotListeners.forEach { it() } }
            } catch (t: Throwable) {
                LOG.warn("GgNotes: refresh failed", t)
            } finally {
                refreshing.set(false)
            }
        }
    }

    /** Read every store on the calling thread (never the EDT) and publish the result; for tests and tools. */
    fun refreshSync(): Snapshot {
        val snap = computeSnapshot()
        snapshot = snap
        invokeLater { snapshotListeners.forEach { it() } }
        return snap
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
                RepoOverviews(repo, null, StoreContents.EMPTY, head, RepoModel.EMPTY, "git rev-parse failed in ${repo.root.name}")
            } else {
                val dir = StoreLocator.storeDir(StoreLocator.defaultBaseDir(), StoreLocator.key(common))
                for (f in watchedFiles(dir)) sig[f] = fileSignature(f)
                val contents = reader.read(dir)
                val commits = LinkedHashSet<String>()
                contents.records.forEach { if (it.address.commit.isNotEmpty()) commits += it.address.commit }
                val subjectMap = HashMap<String, String?>()
                for (sha in commits) subjectMap[sha] = subjectOf(repo.root, sha)
                val model = StoreModel.build(contents, head, subjectMap).let { m ->
                    // live records belong to the checkout gg wrote them in: keep the ones naming this root
                    m.copy(working = m.working.filter { sameCheckout(it.worktree, repo.root) })
                }
                val changed = HashMap<String, List<String>>()
                for (w in model.working) for (rv in w.reviews) treeChanged(rv, repo).takeIf { it.isNotEmpty() }?.let { changed[rv.id] = it }
                // the branches the rows name, resolved once per refresh: a row says "branch x" while x is still at its commit, "was x" after
                val branches = LinkedHashSet<String>()
                for (c in model.commits) {
                    c.overviews.forEach { if (it.branch.isNotBlank()) branches += it.branch }
                    c.reviews.forEach { if (it.record.address.branch.isNotBlank()) branches += it.record.address.branch }
                }
                val tips = HashMap<String, String?>()
                for (b in branches) tips[b] = GitAccess.revParse(project, repo.root, b)
                RepoOverviews(repo, dir, contents, head, model, null, changed, tips, subjectMap)
            }
        }
        signature = sig
        headSignature = heads
        return Snapshot(result)
    }

    /** SPEC-part2 §5.1: the record's `Worktree` must name the checkout shown; compare cleaned, real paths. */
    private fun sameCheckout(worktree: String, root: VirtualFile): Boolean {
        val windows = StoreLocator.isWindows()
        fun norm(p: String): String {
            val cleaned = StoreLocator.clean(p, windows)
            return if (windows || System.getProperty("os.name").orEmpty().lowercase().contains("mac")) cleaned.lowercase() else cleaned
        }
        val candidates = HashSet<String>()
        candidates += norm(root.path)
        runCatching { candidates += norm(Path.of(root.path).toRealPath().toString()) }
        val wt = norm(worktree)
        if (wt in candidates) return true
        return runCatching { norm(Path.of(worktree).toRealPath().toString()) in candidates }.getOrDefault(false)
    }

    /** The files whose change must trigger a re-read: the parts and every worktree part (SPEC §8.1). */
    private fun watchedFiles(dir: Path): List<Path> {
        val out = ArrayList<Path>()
        for (name in StoreReader.PARTS) out.add(dir.resolve(name))
        val wt = dir.resolve(StoreReader.WORKTREES_DIR)
        out.add(wt)
        if (Files.isDirectory(wt)) runCatching { Files.list(wt).use { s -> s.forEach { out.add(it) } } }
        return out
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
        when {
            Files.isRegularFile(f) -> Files.getLastModifiedTime(f).toMillis() to Files.size(f)
            Files.isDirectory(f) -> Files.getLastModifiedTime(f).toMillis() to Files.list(f).use { it.count() }
            else -> -1L to -1L
        }
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

    // ---- the open document -----------------------------------------------------------------

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

    /** A review through the overview viewer: new-side remarks are anchors and boxes, old-side ones are listed. */
    fun openReview(review: Review, repo: GitRepository) {
        val r = review.record
        fun asOverview(treeChanged: List<String>) = Overview(
            id = r.id, title = review.title, author = r.author, commit = r.address.commit, branch = r.address.branch,
            preview = r.preview, prNumber = r.prNumber, created = r.created, updated = r.updated,
            document = DocumentResult.Ok(ReviewComposer.compose(review, treeChanged)), sourceFile = r.sourceFile,
            viewKind = "review:${r.id}:", liveWorktree = r.address.worktree.takeIf { r.isLive },
        )
        if (!r.isLive) {
            open(asOverview(emptyList()), repo)
            return
        }
        // a working review: compare its files[].blob with the working bytes first (SPEC-part2 §5.1)
        AppExecutorUtil.getAppExecutorService().execute {
            val changed = treeChanged(review, repo)
            invokeLater { open(asOverview(changed), repo) }
        }
    }

    /** Background only: the working-review files whose bytes no longer match what it saw. */
    fun treeChanged(review: Review, repo: GitRepository): List<String> = Reviews.treeChanged(review) { path ->
        Path.of(repo.root.path, path).let { f -> if (Files.isRegularFile(f)) runCatching { Files.readAllBytes(f) }.getOrNull() else null }
    }

    /**
     * The note threads of one file (SPEC-part2 §8): the side's text is read in the
     * background (the file at the commit, the working file, or the index copy for a
     * staged note), each note is verified or relocated (§4.3 / §5.2), and the
     * threads open as a document whose anchors are the notes at their lines.
     */
    fun openNotes(threads: List<NoteThread>, path: String, commit: String, worktree: String?, repo: GitRepository) {
        if (threads.isEmpty()) return
        AppExecutorUtil.getAppExecutorService().execute {
            val live = worktree != null
            val sides = HashMap<Int, java.util.Optional<List<String>>>()
            fun sideLines(state: Int): List<String>? = sides.getOrPut(state) {
                val bytes: ByteArray? = when {
                    !live -> GitAccess.fileContent(project, repo.root, commit, path)
                    state == FileState.STAGED -> GitAccess.indexContent(project, repo.root, path)
                    else -> Path.of(repo.root.path, path).let { f -> if (Files.isRegularFile(f)) runCatching { Files.readAllBytes(f) }.getOrNull() else null }
                }
                java.util.Optional.ofNullable(bytes?.let { Relocator.lines(it) })
            }.orElse(null)
            val placements = threads.associate { t ->
                val n = t.root
                n.id to when {
                    // the old side (<sha>^, the index copy, HEAD) is not shown: listed, never placed
                    n.side == "old" -> Relocation(RelocationStatus.UNVERIFIED, n.first, n.last)
                    live -> Relocator.relocate(sideLines(n.address.state), n.first, n.last, n.contextHash)
                    else -> Relocator.verifyCommitted(sideLines(n.address.state), n.first, n.last, n.contextHash)
                }
            }
            val doc = NoteComposer.compose(path, threads, placements)
            val first = threads.first().root
            val ids = threads.joinToString(",") { it.root.id }
            val overview = Overview(
                id = ids, title = doc.title, author = first.author, commit = commit, branch = "", preview = null, prNumber = null,
                created = first.created, updated = threads.mapNotNull { it.newest }.maxOrNull(),
                document = DocumentResult.Ok(doc), sourceFile = first.sourceFile,
                viewKind = "notes:$ids:", liveWorktree = worktree,
            )
            invokeLater { open(overview, repo) }
        }
    }

    /** Re-open a document whose text changed (a session `overview_set`), keeping the walk on the same anchor index. */
    fun openKeepingAnchor(overview: Overview, repo: GitRepository) {
        val keep = open?.takeIf { it.overview.identity == overview.identity }?.currentAnchor
        val previous = open
        if (previous != null && previous.overview.identity == overview.identity) {
            // force a fresh render: the early-return in open() keys on identity and updated
            Disposer.dispose(previous)
            open = null
        }
        open(overview, repo)
        val o = open ?: return
        if (keep != null && o.anchors.any { it.index == keep }) {
            o.currentAnchor = keep
            o.rendered = MarkdownRenderer.render(o.document?.overview ?: "", keep, o.missing)
            ui?.showOverview(o)
            ui?.showAnchor(keep)
        }
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

    /** Cached content of the document's side: the file at the commit, or the working file for a live document. Background only. */
    fun contentOf(o: OpenOverview, path: String): ByteArray? {
        o.contents[path]?.let { return it.orElse(null) }
        val bytes = if (o.overview.isLive) {
            val f = Path.of(o.repo.root.path, path)
            if (Files.isRegularFile(f)) runCatching { Files.readAllBytes(f) }.getOrNull() else null
        } else {
            GitAccess.fileContent(project, o.repo.root, o.overview.commit, path)
        }
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

    /** gg's Backspace: focus the tool window on the document, at the anchor the user left. */
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
