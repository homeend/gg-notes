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
import dev.ggnotes.store.CommitGroup
import dev.ggnotes.store.DocumentResult
import dev.ggnotes.store.Overview
import dev.ggnotes.store.OverviewGrouping
import git4idea.repo.GitRepository
import java.awt.BorderLayout
import java.time.format.DateTimeFormatter
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

/**
 * The "GgNotes" tool window: the overviews of the project's repositories
 * grouped by commit (HEAD's first, then newest) above the rendered document
 * of the selected one. Selecting a row opens it (SPEC §10 step 2–3).
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
    private class GroupNode(val group: CommitGroup, val repo: GitRepository)
    private class OverviewNode(val overview: Overview, val repo: GitRepository)
    private class MessageNode(val text: String)

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
            val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
            val o = node?.userObject as? OverviewNode ?: return@addTreeSelectionListener
            service.open(o.overview, o.repo)
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
        val selected = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? OverviewNode
        root.removeAllChildren()
        val messages = ArrayList<String>()
        val multi = snapshot.repos.size > 1
        for (repo in snapshot.repos) {
            val parent = if (multi) DefaultMutableTreeNode(RepoNode(repo.repo)).also { root.add(it) } else root
            repo.error?.let { messages += it }
            repo.contents.problems.forEach { messages += "${repo.repo.root.name}: $it" }
            if (repo.contents.newerFormat) messages += GgNotesBundle.message("toolwindow.newerFormat", repo.repo.root.name)
            for (group in repo.groups) {
                val gn = DefaultMutableTreeNode(GroupNode(group, repo.repo))
                parent.add(gn)
                for (o in group.overviews) gn.add(DefaultMutableTreeNode(OverviewNode(o, repo.repo)))
            }
        }
        if (snapshot.repos.isEmpty()) messages += GgNotesBundle.message("toolwindow.noGit")
        status.text = "<html>" + messages.joinToString("<br>") { escape(it) } + "</html>"
        status.isVisible = messages.isNotEmpty()
        model.reload()
        TreeUtil.expandAll(tree)
        if (selected != null) selectIdentity(selected.overview.identity, selected.repo)
    }

    private fun selectIdentity(identity: String, repo: GitRepository) {
        val match = TreeUtil.findNode(root) { n ->
            val u = n.userObject
            u is OverviewNode && u.overview.identity == identity && u.repo == repo
        } ?: return
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
                    append(u.group.shortSha, SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES)
                    append("  ")
                    append(u.group.subject ?: GgNotesBundle.message("toolwindow.unknownCommit"),
                        if (u.group.subject != null) SimpleTextAttributes.REGULAR_ATTRIBUTES else SimpleTextAttributes.ERROR_ATTRIBUTES)
                    if (u.group.isHead) append("  HEAD", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                    toolTipText = u.group.commit
                }
                is OverviewNode -> {
                    val o = u.overview
                    append(o.title.ifBlank { "(untitled)" }, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    val tail = ArrayList<String>()
                    if (o.author.isNotBlank()) tail += o.author
                    o.updated?.let { tail += date.format(it.toLocalDate()) }
                    tail += o.shortSha
                    when {
                        o.prNumber != null -> tail += "PR #${o.prNumber}"
                        o.branch.isNotBlank() -> tail += o.branch
                    }
                    append("  " + tail.joinToString(" · "), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    if (o.document is DocumentResult.CannotRender) {
                        append("  " + GgNotesBundle.message("toolwindow.cannotRender"), SimpleTextAttributes.ERROR_ATTRIBUTES)
                    }
                    toolTipText = OverviewGrouping.rowText(o)
                }
                is MessageNode -> append(u.text, SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
    }
}
