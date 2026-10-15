package dev.ggnotes.overview

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import dev.ggnotes.GgNotesBundle
import dev.ggnotes.store.CommitEntry
import dev.ggnotes.store.DocumentResult
import dev.ggnotes.store.FileEntry
import dev.ggnotes.store.NoteRecord
import dev.ggnotes.store.NoteThread
import dev.ggnotes.store.Overview
import dev.ggnotes.store.OverviewGrouping
import dev.ggnotes.store.Review
import dev.ggnotes.store.ReviewContent
import dev.ggnotes.store.Reviews
import dev.ggnotes.store.WorkingEntry
import git4idea.repo.GitRepository
import java.awt.BorderLayout
import java.time.format.DateTimeFormatter
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

/**
 * The "GgNotes" tool window: per repository, the commits that carry
 * overviews, reviews or line notes (HEAD's first, then newest), each with
 * its overviews, its reviews (scoped notes beneath them), its files with
 * note threads; then the working-tree entries of this checkout
 * (SPEC-part2 §8). Below, the rendered document of the selected row.
 */
class OverviewToolWindowPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable, OverviewUi {

    private val service = OverviewService.getInstance(project)
    private val root = DefaultMutableTreeNode()
    private val model = DefaultTreeModel(root)
    private val tree = Tree(model)
    private val status = JBLabel()
    private val document = DocumentPane(project, service)
    private var syncing = false

    private class RepoNode(val repo: GitRepository)
    private class GroupNode(val entry: CommitEntry, val repo: GitRepository)
    private class WorkingNode(val entry: WorkingEntry, val repo: GitRepository)
    private class OverviewNode(val overview: Overview, val repo: GitRepository, val replies: List<NoteRecord>)
    private class ReviewNode(val review: Review, val repo: GitRepository, val treeChanged: List<String> = emptyList())
    private class FileNode(val file: FileEntry, val commit: String, val worktree: String?, val repo: GitRepository)
    private class ThreadNode(val thread: NoteThread, val commit: String, val worktree: String?, val repo: GitRepository)
    private class ReplyNode(val reply: NoteRecord)

    init {
        val group = ActionManager.getInstance().getAction("GgNotes.Toolbar") as ActionGroup
        val toolbar = ActionManager.getInstance().createActionToolbar("GgNotesToolbar", group, true)
        toolbar.targetComponent = this
        setToolbar(toolbar.component)

        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.cellRenderer = Renderer()
        tree.emptyText.text = GgNotesBundle.message("toolwindow.empty")
        tree.addTreeSelectionListener {
            if (syncing) return@addTreeSelectionListener
            val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return@addTreeSelectionListener
            when (val u = node.userObject) {
                is OverviewNode -> service.open(u.overview, u.repo)
                is ReviewNode -> service.openReview(u.review, u.repo)
                is FileNode -> service.openNotes(u.file.threads, u.file.path, u.commit, u.worktree, u.repo)
                is ThreadNode -> service.openNotes(listOf(u.thread), u.thread.root.address.path, u.commit, u.worktree, u.repo)
                is ReplyNode -> (node.parent as? DefaultMutableTreeNode)?.userObject.let { p ->
                    if (p is ThreadNode) service.openNotes(listOf(p.thread), p.thread.root.address.path, p.commit, p.worktree, p.repo)
                }
                else -> Unit
            }
        }
        TreeUtil.installActions(tree)

        status.border = JBUI.Borders.empty(4, 8)
        status.isVisible = false
        val top = JPanel(BorderLayout())
        top.add(status, BorderLayout.NORTH)
        top.add(ScrollPaneFactory.createScrollPane(tree), BorderLayout.CENTER)

        val splitter = OnePixelSplitter(true, 0.38f)
        splitter.firstComponent = top
        splitter.secondComponent = document
        setContent(splitter)

        service.ui = this
        service.addSnapshotListener(this) { rebuild() }
        rebuild()
        service.refresh()
        service.open?.let { showOverview(it) }
    }

    private fun rebuild() {
        val snapshot = service.snapshot
        val selectedIdentity = service.open?.overview?.identity
        val selectedRepo = service.open?.repo
        root.removeAllChildren()
        val messages = ArrayList<String>()
        val multi = snapshot.repos.size > 1
        for (repo in snapshot.repos) {
            val parent = if (multi) DefaultMutableTreeNode(RepoNode(repo.repo)).also { root.add(it) } else root
            repo.error?.let { messages += it }
            repo.contents.problems.forEach { messages += "${repo.repo.root.name}: $it" }
            if (repo.contents.newerFormat) messages += GgNotesBundle.message("toolwindow.newerFormat", repo.repo.root.name)
            for (entry in repo.model.commits) {
                if (entry.isEmpty) continue
                val gn = DefaultMutableTreeNode(GroupNode(entry, repo.repo))
                parent.add(gn)
                for (o in entry.overviews) gn.add(DefaultMutableTreeNode(OverviewNode(o, repo.repo, entry.overviewReplies[o.id].orEmpty())))
                for (r in entry.reviews) {
                    val rn = DefaultMutableTreeNode(ReviewNode(r, repo.repo))
                    gn.add(rn)
                    for (t in r.scopedThreads) {
                        val tn = DefaultMutableTreeNode(ThreadNode(t, entry.commit, null, repo.repo))
                        rn.add(tn)
                        for (reply in t.replies) tn.add(DefaultMutableTreeNode(ReplyNode(reply)))
                    }
                }
                for (f in entry.files) gn.add(fileNode(f, entry.commit, null, repo.repo))
            }
            for (w in repo.model.working) {
                val wn = DefaultMutableTreeNode(WorkingNode(w, repo.repo))
                parent.add(wn)
                for (r in w.reviews) wn.add(DefaultMutableTreeNode(ReviewNode(r, repo.repo, repo.treeChanged[r.id].orEmpty())))
                for (f in w.files) wn.add(fileNode(f, "", w.worktree, repo.repo))
            }
        }
        if (snapshot.repos.isEmpty()) messages += GgNotesBundle.message("toolwindow.noGit")
        status.text = "<html>" + messages.joinToString("<br>") { escape(it) } + "</html>"
        status.isVisible = messages.isNotEmpty()
        model.reload()
        TreeUtil.expandAll(tree)
        if (selectedIdentity != null && selectedRepo != null) selectIdentity(selectedIdentity, selectedRepo)
    }

    private fun fileNode(f: FileEntry, commit: String, worktree: String?, repo: GitRepository): DefaultMutableTreeNode {
        val fn = DefaultMutableTreeNode(FileNode(f, commit, worktree, repo))
        for (t in f.threads) {
            val tn = DefaultMutableTreeNode(ThreadNode(t, commit, worktree, repo))
            fn.add(tn)
            for (r in t.replies) tn.add(DefaultMutableTreeNode(ReplyNode(r)))
        }
        return fn
    }

    private fun identityOf(u: Any?): String? = when (u) {
        is OverviewNode -> u.overview.identity
        is ReviewNode -> "review:${u.review.id}:" + (u.review.record.prNumber?.let { "pr:$it" } ?: u.review.record.address.commit) + "\u0000" + u.review.title.trim()
        else -> null
    }

    private fun repoOf(u: Any?): GitRepository? = when (u) {
        is OverviewNode -> u.repo
        is ReviewNode -> u.repo
        else -> null
    }

    private fun selectIdentity(identity: String, repo: GitRepository) {
        val match = TreeUtil.findNode(root) { n -> identityOf(n.userObject) == identity && repoOf(n.userObject) == repo } ?: return
        syncing = true
        try {
            TreeUtil.selectNode(tree, match)
        } finally {
            syncing = false
        }
    }

    override fun showOverview(open: OpenOverview?) {
        document.show(open)
        if (open != null) selectIdentity(open.overview.identity, open.repo)
    }

    override fun showAnchor(n: Int) = document.scrollToAnchor(n)

    override fun dispose() {
        if (service.ui === this) service.ui = null
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private class Renderer : ColoredTreeCellRenderer() {
        private val date = DateTimeFormatter.ISO_LOCAL_DATE

        override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            when (val u = (value as? DefaultMutableTreeNode)?.userObject) {
                is RepoNode -> append(u.repo.root.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                is GroupNode -> {
                    append(u.entry.shortSha, SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES)
                    append("  ")
                    append(u.entry.subject ?: GgNotesBundle.message("toolwindow.unknownCommit"),
                        if (u.entry.subject != null) SimpleTextAttributes.REGULAR_ATTRIBUTES else SimpleTextAttributes.ERROR_ATTRIBUTES)
                    if (u.entry.isHead) append("  HEAD", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                    toolTipText = u.entry.commit
                }
                is WorkingNode -> {
                    append(GgNotesBundle.message("toolwindow.working"), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append("  " + u.entry.worktree, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is OverviewNode -> {
                    val o = u.overview
                    append(o.title.ifBlank { "(untitled)" }, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    append("  " + tail(o.author, o.updated, o.shortSha, o.prNumber, o.branch), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    if (u.replies.isNotEmpty()) append("  " + GgNotesBundle.message("toolwindow.replies", u.replies.size), SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                    if (o.document is DocumentResult.CannotRender) append("  " + GgNotesBundle.message("toolwindow.cannotRender"), SimpleTextAttributes.ERROR_ATTRIBUTES)
                    toolTipText = OverviewGrouping.rowText(o)
                }
                is ReviewNode -> {
                    val r = u.review.record
                    append(u.review.title.ifBlank { "Review" }, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    append("  " + tail(r.author, r.updated ?: r.created, r.shortSha, r.prNumber, r.address.branch), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    val counts = u.review.counts()
                    if (counts.isNotEmpty()) append("  " + Reviews.countsText(counts), SimpleTextAttributes.REGULAR_ITALIC_ATTRIBUTES)
                    when (u.review.content) {
                        is ReviewContent.Prose -> append("  prose", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                        is ReviewContent.CannotRender -> append("  " + GgNotesBundle.message("toolwindow.cannotRender"), SimpleTextAttributes.ERROR_ATTRIBUTES)
                        else -> Unit
                    }
                    if (u.treeChanged.isNotEmpty()) append("  ⚠ " + GgNotesBundle.message("toolwindow.treeChanged"), SimpleTextAttributes.ERROR_ATTRIBUTES)
                    toolTipText = r.scope
                }
                is FileNode -> {
                    append(u.file.path, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    append("  (${u.file.threads.size})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is ThreadNode -> {
                    val n = u.thread.root
                    val range = if (n.first == n.last) "${n.first}" else "${n.first}–${n.last}"
                    append("$range  ", SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES)
                    append(n.summary.ifBlank { "(no text)" }, if (u.thread.resolved) SimpleTextAttributes.GRAYED_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    append("  " + listOfNotNull(n.author.ifBlank { n.source }, (n.updated ?: n.created)?.let { date.format(it.toLocalDate()) }).joinToString(" · "), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    if (n.side == "old") append("  " + GgNotesBundle.message("toolwindow.old"), SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                    if (u.thread.resolved) append("  ✓ " + GgNotesBundle.message("toolwindow.resolved"), SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                    if (u.thread.forgeDraft) append("  " + GgNotesBundle.message("toolwindow.draft"), SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                    for (m in n.meta) append("  ${m.key}: ${m.value}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    toolTipText = n.rationale.ifBlank { null }
                }
                is ReplyNode -> {
                    val r = u.reply
                    append("↳ ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    append(r.author.ifBlank { r.source }, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append(": " + r.summary, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    toolTipText = r.rationale.ifBlank { null }
                }
            }
        }

        private fun tail(author: String, date: java.time.OffsetDateTime?, sha: String, pr: Int?, branch: String): String {
            val parts = ArrayList<String>()
            if (author.isNotBlank()) parts += author
            date?.let { parts += this.date.format(it.toLocalDate()) }
            if (sha.isNotBlank()) parts += sha
            when {
                pr != null -> parts += "PR #$pr"
                branch.isNotBlank() -> parts += branch
            }
            return parts.joinToString(" · ")
        }
    }
}
