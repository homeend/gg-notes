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
    private val splitter = OnePixelSplitter(true, TreeShare.MAX_SHARE)
    /** The list half: status line plus tree; detached from the splitter in "document only" mode. */
    private val listPane = JPanel(BorderLayout())
    private var syncing = false
    /** Set by a drag on the divider: from then on the user's size stands and [fitTree] stays out. */
    private var userSized = false

    private class RepoNode(val repo: GitRepository)
    private class GroupNode(val entry: CommitEntry, val repo: GitRepository)
    private class WorkingNode(val entry: WorkingEntry, val repo: GitRepository)
    private class OverviewNode(val overview: Overview, val repo: GitRepository, val replies: List<NoteRecord>)
    private class ReviewNode(val review: Review, val repo: GitRepository, val treeChanged: List<String> = emptyList())
    private class FileNode(val file: FileEntry, val commit: String, val worktree: String?, val repo: GitRepository)
    private class ThreadNode(val thread: NoteThread, val commit: String, val worktree: String?, val repo: GitRepository)
    private class ReplyNode(val reply: NoteRecord)
    /** "This session": the in-memory overviews an agent added over the steer inbox (gone with the project). */
    private class SessionGroupNode(val repo: GitRepository)
    /** [identity] is the viewer form's identity, so "on screen" is judged at paint time against the open document. */
    private class SessionOverviewNode(val row: dev.ggnotes.steer.SteerService.SessionRow, val repo: GitRepository, val identity: String)

    private val steer = dev.ggnotes.steer.SteerService.getInstance(project)

    private companion object {
        /** Unscaled px around the rows: the scroll pane's border and a little air under the last row. */
        const val TREE_PADDING = 10
        /** Unscaled px the tree pane never shrinks below: room for the empty-text placeholder. */
        const val TREE_MIN_HEIGHT = 56
    }

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
                is SessionOverviewNode -> steer.showOverview(u.row.session, u.row.overview, keepAnchor = false)
                else -> Unit
            }
        }
        TreeUtil.installActions(tree)
        // right-click: select the row under the pointer (so the document shows what the menu is about), then the menu
        tree.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mousePressed(e: java.awt.event.MouseEvent) = selectAt(e)
            override fun mouseReleased(e: java.awt.event.MouseEvent) = selectAt(e)
            private fun selectAt(e: java.awt.event.MouseEvent) {
                if (!e.isPopupTrigger) return
                val path = tree.getPathForLocation(e.x, e.y) ?: return
                if (tree.selectionPath != path) tree.selectionPath = path
            }
        })
        com.intellij.ui.PopupHandler.installPopupMenu(
            tree, com.intellij.openapi.actionSystem.DefaultActionGroup(RemoveFromStoreAction()), "GgNotesTreePopup")

        status.border = JBUI.Borders.empty(4, 8)
        status.isVisible = false
        listPane.add(status, BorderLayout.NORTH)
        listPane.add(ScrollPaneFactory.createScrollPane(tree), BorderLayout.CENTER)

        splitter.firstComponent = if (service.listHidden) null else listPane
        splitter.secondComponent = document
        setContent(splitter)
        // the tree takes what its rows need, up to TreeShare.MAX_SHARE of the window, until the user drags the divider
        // (a drag on the divider itself, never a proportion event: the splitter fires those on its own too)
        splitter.divider.addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
            override fun mouseDragged(e: java.awt.event.MouseEvent) {
                userSized = true
            }
        })
        splitter.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent) = fitTree()
        })
        tree.addTreeExpansionListener(object : javax.swing.event.TreeExpansionListener {
            override fun treeExpanded(event: javax.swing.event.TreeExpansionEvent) = fitTree()
            override fun treeCollapsed(event: javax.swing.event.TreeExpansionEvent) = fitTree()
        })

        service.ui = this
        service.addSnapshotListener(this) { rebuild() }
        steer.addSessionListener(this) {
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater({ rebuild() }, project.disposed)
        }
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
            // in-memory overviews first: they are what the agent is showing right now, and they outlive nothing
            val sessionRows = steer.sessionOverviews(repo.repo.root)
            if (sessionRows.isNotEmpty()) {
                val sn = DefaultMutableTreeNode(SessionGroupNode(repo.repo))
                parent.add(sn)
                for (row in sessionRows) sn.add(DefaultMutableTreeNode(SessionOverviewNode(row, repo.repo, steer.viewerOverview(row.session, row.overview).identity)))
            }
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
        fitTree()
    }

    /**
     * Size the tree pane to its rows (plus the status line), capped at [TreeShare.MAX_SHARE]
     * of the window so a long list scrolls inside it. Stops once the user drags the divider.
     */
    private fun fitTree() {
        if (userSized || splitter.height <= 0 || splitter.firstComponent == null) return
        val rows = tree.rowCount
        val rowsHeight = if (rows > 0) tree.getRowBounds(rows - 1)?.let { it.y + it.height } ?: rows * tree.rowHeight else 0
        val statusHeight = if (status.isVisible) status.preferredSize.height else 0
        val content = rowsHeight + statusHeight + JBUI.scale(TREE_PADDING)
        val proportion = TreeShare.proportion(content, splitter.height, JBUI.scale(TREE_MIN_HEIGHT))
        if (kotlin.math.abs(proportion - splitter.proportion) < 0.001f) return
        splitter.proportion = proportion
    }

    /** The removal a row stands for (every row kind has one), with the repository whose store holds it. */
    private fun planFor(node: DefaultMutableTreeNode): Pair<dev.ggnotes.store.RemovalPlan, GitRepository>? {
        val parentRepo = generateSequence(node.parent as? DefaultMutableTreeNode) { it.parent as? DefaultMutableTreeNode }
            .mapNotNull { (it.userObject as? RepoNode)?.repo }.firstOrNull()
        fun repoOrSingle(repo: GitRepository?) = repo ?: parentRepo ?: service.snapshot.repos.singleOrNull()?.repo
        return when (val u = node.userObject) {
            is OverviewNode -> dev.ggnotes.store.RemovalPlan.forOverview(u.overview) to u.repo
            is ReviewNode -> dev.ggnotes.store.RemovalPlan.forReview(u.review) to u.repo
            is FileNode -> dev.ggnotes.store.RemovalPlan.forFile(u.file) to u.repo
            is ThreadNode -> dev.ggnotes.store.RemovalPlan.forThread(u.thread) to u.repo
            is ReplyNode -> repoOrSingle(null)?.let { dev.ggnotes.store.RemovalPlan.forReply(u.reply) to it }
            is GroupNode -> dev.ggnotes.store.RemovalPlan.forCommit(u.entry) to u.repo
            is WorkingNode -> dev.ggnotes.store.RemovalPlan.forWorking(u.entry) to u.repo
            else -> null
        }
    }

    /** The tree's context menu: hand the selected row's records to gg for deletion, after a confirmation. */
    private inner class RemoveFromStoreAction : com.intellij.openapi.actionSystem.AnAction(
        GgNotesBundle.message("action.GgNotes.RemoveFromStore.text"),
        GgNotesBundle.message("action.GgNotes.RemoveFromStore.description"),
        com.intellij.icons.AllIcons.Actions.GC,
    ) {
        override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT

        override fun update(e: com.intellij.openapi.actionSystem.AnActionEvent) {
            val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
            val session = node?.userObject is SessionOverviewNode
            e.presentation.isEnabledAndVisible = node != null && (session || planFor(node)?.first?.lines?.isNotEmpty() == true)
            // an in-memory row promises no store deletion, and the label says so
            e.presentation.text = GgNotesBundle.message(if (session) "action.GgNotes.RemoveFromSession.text" else "action.GgNotes.RemoveFromStore.text")
        }

        override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
            val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
            (node.userObject as? SessionOverviewNode)?.let { u ->
                // an in-memory overview: nothing is in gg's store, the drop is the session's own (`overview_rm`)
                val answer = com.intellij.openapi.ui.Messages.showYesNoDialog(
                    project, GgNotesBundle.message("remove.confirm.SESSION", u.row.overview.title, u.row.overview.id),
                    GgNotesBundle.message("remove.confirm.title"),
                    GgNotesBundle.message("action.GgNotes.RemoveFromSession.text"), com.intellij.openapi.ui.Messages.getCancelButton(),
                    com.intellij.openapi.ui.Messages.getQuestionIcon())
                if (answer == com.intellij.openapi.ui.Messages.YES) steer.removeOverview(u.row.session, u.row.overview.id)
                return
            }
            val (plan, repo) = planFor(node) ?: return
            val question = GgNotesBundle.message("remove.confirm.${plan.kind.name}", plan.label, plan.records) + "\n\n" +
                GgNotesBundle.message("remove.confirm.note")
            val answer = com.intellij.openapi.ui.Messages.showYesNoDialog(
                project, question, GgNotesBundle.message("remove.confirm.title"),
                GgNotesBundle.message("action.GgNotes.RemoveFromStore.text"), com.intellij.openapi.ui.Messages.getCancelButton(),
                com.intellij.openapi.ui.Messages.getWarningIcon())
            if (answer != com.intellij.openapi.ui.Messages.YES) return
            StoreRemoval.perform(project, repo, plan)
        }
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
        is SessionOverviewNode -> u.identity
        else -> null
    }

    private fun repoOf(u: Any?): GitRepository? = when (u) {
        is OverviewNode -> u.repo
        is ReviewNode -> u.repo
        is SessionOverviewNode -> u.repo
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
        // "document only" with no document would show only the placeholder: bring the list back
        if (open == null && service.listHidden) service.setListHidden(false)
        tree.repaint() // the session rows' "on screen" badge follows the open document
    }

    override fun showAnchor(n: Int) = document.scrollToAnchor(n)

    override fun showList(visible: Boolean) {
        val shown = splitter.firstComponent != null
        if (shown == visible) return
        splitter.firstComponent = if (visible) listPane else null
        splitter.revalidate()
        splitter.repaint()
        if (visible) fitTree()
    }

    override fun dispose() {
        if (service.ui === this) service.ui = null
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private inner class Renderer : ColoredTreeCellRenderer() {
        private val date = DateTimeFormatter.ISO_LOCAL_DATE

        override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            when (val u = (value as? DefaultMutableTreeNode)?.userObject) {
                is RepoNode -> append(u.repo.root.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                is SessionGroupNode -> {
                    append(GgNotesBundle.message("toolwindow.session"), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append("  " + GgNotesBundle.message("toolwindow.inMemory"), SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                    toolTipText = GgNotesBundle.message("toolwindow.session.tooltip")
                }
                is SessionOverviewNode -> {
                    val o = u.row.overview
                    append(o.title.ifBlank { "(untitled)" }, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    append("  " + listOf(o.id, GgNotesBundle.message("document.anchors", o.anchors.size)).joinToString(" · "), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    append("  " + GgNotesBundle.message("toolwindow.inMemory"), SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                    // judged now, not at build time: Close or another row's click changes it without a rebuild
                    if (service.open?.overview?.identity == u.identity) append("  " + GgNotesBundle.message("toolwindow.shown"), SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                    if (o.unresolved.isNotEmpty()) append("  ⚠ " + GgNotesBundle.message("toolwindow.unresolved", o.unresolved.size), SimpleTextAttributes.ERROR_ATTRIBUTES)
                    toolTipText = GgNotesBundle.message("toolwindow.session.tooltip")
                }
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
