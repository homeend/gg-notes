package dev.ggnotes.overview

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import dev.ggnotes.GgNotesBundle
import dev.ggnotes.items.ItemKind
import dev.ggnotes.items.ItemViews
import dev.ggnotes.items.NoteItem
import dev.ggnotes.items.NoteItems
import dev.ggnotes.items.Payload
import dev.ggnotes.items.SessionRemoval
import dev.ggnotes.store.DocumentResult
import dev.ggnotes.store.NoteRecord
import dev.ggnotes.store.Overview
import dev.ggnotes.store.OverviewGrouping
import dev.ggnotes.store.RemovalPlan
import dev.ggnotes.store.ReviewContent
import dev.ggnotes.store.Reviews
import dev.ggnotes.store.RowBadge
import dev.ggnotes.steer.SteerService
import git4idea.repo.GitRepository
import java.awt.BorderLayout
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

/**
 * The "Agent Notes" tool window: per repository, the note items (store overviews,
 * reviews and note threads; session overviews and notes) By Date (day → items)
 * or By Type (kind → day → items), a contained note under its review or
 * overview. Below, the rendered document of the selected row.
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
    private class TypeNode(val kind: ItemKind)
    /** [day] == null is the "No date" group. */
    private class DayNode(val day: LocalDate?)
    /**
     * One note item. [session] is its root's steer session (null when none); [tips] the repository's
     * branch tips at refresh time (`branch x` / `was x`); [treeChanged] a working review's changed
     * files; [subject] the owner commit's subject (null: no commit, or not in the repository);
     * [unresolved] a session overview's unresolved anchors.
     */
    private class ItemNode(
        val item: NoteItem,
        val repo: GitRepository,
        val session: SteerService.RootSession?,
        val tips: Map<String, String?>,
        val treeChanged: List<String>,
        val subject: String?,
        val unresolved: List<String>,
    )
    private class ReplyNode(val reply: NoteRecord)

    private val steer = SteerService.getInstance(project)
    private val renderer = Renderer()
    private val copyLink = CopyLinkAction()
    private val remove = RemoveFromStoreAction()
    private val removeCommit = RemoveCommitAction()

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
        tree.cellRenderer = renderer
        tree.emptyText.text = GgNotesBundle.message("toolwindow.empty")
        tree.addTreeSelectionListener {
            if (syncing) return@addTreeSelectionListener
            val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return@addTreeSelectionListener
            itemNodeOf(node)?.let { open(it) }
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
        // the row's Delete: the context menu and the Delete key run the same confirm-then-delete;
        // Copy Link: a session row's line for an agent (the menu item and Ctrl+C are one action)
        com.intellij.ui.PopupHandler.installPopupMenu(tree, com.intellij.openapi.actionSystem.DefaultActionGroup(popupActions), "GgNotesTreePopup")
        remove.registerCustomShortcutSet(com.intellij.openapi.actionSystem.CommonShortcuts.getDelete(), tree, this)
        copyLink.registerCustomShortcutSet(com.intellij.openapi.actionSystem.CommonShortcuts.getCopy(), tree, this)
        // a double-click opens the row (its first click selected it) and gives the document the whole window
        object : com.intellij.ui.DoubleClickListener() {
            override fun onDoubleClick(event: java.awt.event.MouseEvent): Boolean {
                val path = tree.getPathForLocation(event.x, event.y) ?: return false
                if (tree.selectionPath != path) tree.selectionPath = path
                openSelectedFullArea()
                return true
            }
        }.installOn(tree)

        status.border = JBUI.Borders.empty(4, 8)
        status.isVisible = false
        listPane.add(status, BorderLayout.NORTH)
        listPane.add(ScrollPaneFactory.createScrollPane(tree), BorderLayout.CENTER)

        splitter.firstComponent = if (service.listHidden) null else listPane
        splitter.secondComponent = if (service.open == null) null else document
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
        val open = service.open
        val zone = ZoneId.systemDefault()
        val view = service.view
        root.removeAllChildren()
        val messages = ArrayList<String>()
        val multi = snapshot.repos.size > 1
        for (repo in snapshot.repos) {
            val parent = if (multi) DefaultMutableTreeNode(RepoNode(repo.repo)).also { root.add(it) } else root
            repo.error?.let { messages += it }
            repo.contents.problems.forEach { messages += "${repo.repo.root.name}: $it" }
            if (repo.contents.newerFormat) messages += GgNotesBundle.message("toolwindow.newerFormat", repo.repo.root.name)
            val s = steer.sessionFor(repo.repo.root)
            val items = NoteItems.build(repo.model, s?.docs?.index?.overviews().orEmpty(), s?.docs?.index?.notes().orEmpty())
            fun itemNode(item: NoteItem): DefaultMutableTreeNode {
                val p = item.payload
                val node = DefaultMutableTreeNode(ItemNode(
                    item, repo.repo, s, repo.branchTips,
                    treeChanged = (p as? Payload.StoreReview)?.let { repo.treeChanged[it.review.id] }.orEmpty(),
                    subject = item.owner.commit?.let { c -> repo.model.commits.firstOrNull { it.commit == c }?.subject },
                    unresolved = if (p is Payload.SessionOverview && s != null) steer.unresolved(s, p.entry.doc) else emptyList(),
                ))
                val replies = when (p) {
                    is Payload.StoreThread -> p.thread.replies
                    is Payload.StoreOverview -> p.replies
                    else -> emptyList()
                }
                for (r in replies) node.add(DefaultMutableTreeNode(ReplyNode(r)))
                for (c in item.children) node.add(itemNode(c))
                return node
            }
            fun dayNodes(into: DefaultMutableTreeNode, days: List<dev.ggnotes.items.DayGroup>) {
                for (d in days) {
                    val dn = DefaultMutableTreeNode(DayNode(d.day))
                    into.add(dn)
                    for (item in d.items) dn.add(itemNode(item))
                }
            }
            when (view) {
                NoteView.DATE -> dayNodes(parent, ItemViews.byDate(items, zone))
                NoteView.TYPE -> for (t in ItemViews.byType(items, zone)) {
                    val tn = DefaultMutableTreeNode(TypeNode(t.kind))
                    parent.add(tn)
                    dayNodes(tn, t.days)
                }
            }
        }
        if (snapshot.repos.isEmpty()) messages += GgNotesBundle.message("toolwindow.noGit")
        status.text = "<html>" + messages.joinToString("<br>") { escape(it) } + "</html>"
        status.isVisible = messages.isNotEmpty()
        model.reload()
        TreeUtil.expandAll(tree)
        if (open != null) selectShowing(open)
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

    /** The item a row opens: the row's own item, or a reply's parent item. */
    private fun itemNodeOf(node: DefaultMutableTreeNode): ItemNode? = when (val u = node.userObject) {
        is ItemNode -> u
        is ReplyNode -> (node.parent as? DefaultMutableTreeNode)?.userObject as? ItemNode
        else -> null
    }

    /** Open [n]'s record as the row's click does. */
    private fun open(n: ItemNode) {
        when (val p = n.item.payload) {
            is Payload.StoreOverview -> service.open(p.overview, n.repo)
            is Payload.StoreReview -> service.openReview(p.review, n.repo)
            is Payload.StoreThread -> service.openNotes(listOf(p.thread), p.thread.root.address.path, p.commit, p.worktree, n.repo)
            is Payload.SessionOverview -> n.session?.let { steer.showOverview(it, p.entry, keepAnchor = false) }
            is Payload.SessionNote -> n.session?.let { steer.showNote(it, p.entry) }
        }
    }

    /** Whether the open document [open] is [n]'s record (a session note opens an editor, never a document). */
    private fun shows(n: ItemNode, open: Overview): Boolean = when (val p = n.item.payload) {
        is Payload.StoreOverview -> p.overview.identity == open.identity
        is Payload.StoreReview -> open.viewKind == "review:${p.review.id}:"
        is Payload.StoreThread -> open.viewKind == "notes:${p.thread.root.id}:"
        is Payload.SessionOverview -> n.session?.let { steer.sessionIdentity(it, p.entry.doc) == open.identity } == true
        is Payload.SessionNote -> false
    }

    /** Select the row of the open document without opening it again. */
    private fun selectShowing(open: OpenOverview) {
        val match = TreeUtil.findNode(root) { n -> (n.userObject as? ItemNode)?.let { it.repo == open.repo && shows(it, open.overview) } == true } ?: return
        syncing = true
        try {
            TreeUtil.selectNode(tree, match)
        } finally {
            syncing = false
        }
    }

    private val selectedNode: DefaultMutableTreeNode? get() = tree.lastSelectedPathComponent as? DefaultMutableTreeNode

    /** The selected row's link (a session overview or note item only), or null. */
    private fun linkOfSelected(): String? {
        val n = selectedNode?.userObject as? ItemNode ?: return null
        val s = n.session ?: return null
        return when (val p = n.item.payload) {
            is Payload.SessionOverview -> steer.overviewLink(s, p.entry.doc)
            is Payload.SessionNote -> steer.noteLink(s, p.entry.doc)
            else -> null
        }
    }

    /** Copy the selected row's link to the clipboard and return it; null (and the clipboard untouched) for a row without one. */
    internal fun copyLinkOfSelected(): String? {
        val link = linkOfSelected() ?: return null
        com.intellij.openapi.ide.CopyPasteManager.getInstance().setContents(java.awt.datatransfer.StringSelection(link))
        return link
    }

    /** "Copy Link" on a session row: one line naming the document, its worktree and its file, for an agent to read. */
    private inner class CopyLinkAction : AnAction(
        GgNotesBundle.message("action.GgNotes.CopyLink.text"),
        GgNotesBundle.message("action.GgNotes.CopyLink.description"),
        AllIcons.Actions.Copy,
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            val n = selectedNode?.userObject as? ItemNode
            e.presentation.isEnabledAndVisible = n?.session != null &&
                (n.item.payload is Payload.SessionOverview || n.item.payload is Payload.SessionNote)
        }

        override fun actionPerformed(e: AnActionEvent) {
            copyLinkOfSelected()
        }
    }

    private fun confirm(question: String, button: String): Boolean = Messages.showYesNoDialog(
        project, question, GgNotesBundle.message("remove.confirm.title"),
        button, Messages.getCancelButton(), Messages.getWarningIcon(),
    ) == Messages.YES

    /** A store plan's question: the kind's sentence, then that gg does the deleting. */
    private fun storeQuestion(plan: RemovalPlan): String =
        GgNotesBundle.message("remove.confirm.${plan.kind.name}", plan.label, plan.records) + "\n\n" + GgNotesBundle.message("remove.confirm.note")

    /** The store removal a row stands for, with the repository whose store holds it; null for a session item. */
    private fun planFor(node: DefaultMutableTreeNode): Pair<RemovalPlan, GitRepository>? = when (val u = node.userObject) {
        is ItemNode -> RemovalPlan.forItem(u.item)?.let { it to u.repo }
        is ReplyNode -> itemNodeOf(node)?.let { RemovalPlan.forReply(u.reply) to it.repo }
        else -> null
    }

    /** The tree's context menu: delete the selected item (store records through gg, session items by unlinking their files). */
    private inner class RemoveFromStoreAction : AnAction(
        GgNotesBundle.message("action.GgNotes.RemoveFromStore.text"),
        GgNotesBundle.message("action.GgNotes.RemoveFromStore.description"),
        AllIcons.Actions.GC,
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            val node = selectedNode
            val n = node?.userObject as? ItemNode
            val session = n != null && SessionRemoval.of(n.item) != null
            e.presentation.isEnabledAndVisible = node != null &&
                (if (session) n?.session != null else planFor(node)?.first?.lines?.isNotEmpty() == true)
            // a session item's Delete unlinks files, not store records, and the label says so
            e.presentation.text = GgNotesBundle.message(if (session) "action.GgNotes.RemoveFromSession.text" else "action.GgNotes.RemoveFromStore.text")
        }

        override fun actionPerformed(e: AnActionEvent) {
            val node = selectedNode ?: return
            val n = node.userObject as? ItemNode
            val removal = n?.let { SessionRemoval.of(it.item) }
            if (n != null && removal != null) {
                // session docs (session-docs SPEC §4.4): unlink the files; every gg reader of the worktree loses them
                val s = n.session ?: return
                val question = when (val p = n.item.payload) {
                    is Payload.SessionNote -> GgNotesBundle.message("remove.confirm.SESSION_NOTE", p.entry.doc.summary, p.entry.doc.id)
                    is Payload.SessionOverview ->
                        if (removal.notes.isEmpty()) GgNotesBundle.message("remove.confirm.SESSION", p.entry.doc.title, p.entry.doc.id)
                        else GgNotesBundle.message("remove.confirm.SESSION_WITH_NOTES", p.entry.doc.title, p.entry.doc.id, removal.notes.size)
                    else -> return
                }
                if (!confirm(question, GgNotesBundle.message("action.GgNotes.RemoveFromSession.text"))) return
                for (id in removal.notes) steer.deleteNote(s, id)
                for (id in removal.overviews) steer.deleteOverview(s, id)
                return
            }
            val (plan, repo) = planFor(node) ?: return
            if (!confirm(storeQuestion(plan), GgNotesBundle.message("action.GgNotes.RemoveFromStore.text"))) return
            StoreRemoval.perform(project, repo, plan)
        }
    }

    /** "Remove Everything on This Commit": every store record of the selected store item's commit, through gg. */
    private inner class RemoveCommitAction : AnAction(
        GgNotesBundle.message("action.GgNotes.RemoveCommit.text"),
        GgNotesBundle.message("action.GgNotes.RemoveCommit.description"),
        AllIcons.Actions.GC,
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        /** The selected store item's commit entry in its repository's model, or null. */
        private fun target(): Pair<dev.ggnotes.store.CommitEntry, GitRepository>? {
            val n = selectedNode?.userObject as? ItemNode ?: return null
            if (SessionRemoval.of(n.item) != null) return null
            val commit = n.item.owner.commit ?: return null
            val entry = service.snapshot.repos.firstOrNull { it.repo == n.repo }?.model?.commits?.firstOrNull { it.commit == commit } ?: return null
            return if (entry.isEmpty) null else entry to n.repo
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabledAndVisible = target() != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            val (entry, repo) = target() ?: return
            val plan = RemovalPlan.forCommit(entry)
            if (!confirm(storeQuestion(plan), GgNotesBundle.message("action.GgNotes.RemoveFromStore.text"))) return
            StoreRemoval.perform(project, repo, plan)
        }
    }

    override fun showOverview(open: OpenOverview?) {
        document.show(open)
        if (open != null) {
            selectShowing(open)
        } else {
            // Close leaves no row selected: a selected row stays "shown" to the tree, so a click on
            // it would fire no selection change and the document could not be reopened from it
            syncing = true
            try {
                tree.clearSelection()
            } finally {
                syncing = false
            }
            // "document only" with no document would show only the placeholder: bring the list back
            if (service.listHidden) service.setListHidden(false)
        }
        showDocumentPane(open != null)
        tree.repaint() // the session rows' "on screen" badge follows the open document
    }

    /** With nothing open the document half leaves the splitter and the list takes the whole window. */
    private fun showDocumentPane(visible: Boolean) {
        val shown = splitter.secondComponent != null
        if (shown == visible) return
        splitter.secondComponent = if (visible) document else null
        splitter.revalidate()
        splitter.repaint()
        if (visible) fitTree()
    }

    /** The selected row's user object, for tests. */
    internal val selectedRow: Any? get() = selectedNode?.userObject

    /** The selected item's [NoteItem.id] (null for a day, kind, repository or reply row), for tests. */
    internal val selectedItemId: String? get() = (selectedNode?.userObject as? ItemNode)?.item?.id

    /** The tree's context menu, in its order. */
    internal val popupActions: List<AnAction> get() = listOf(copyLink, remove, removeCommit)

    /** The rendered first text fragment of every row at [depth] (0 = the top rows), in tree order, for tests. */
    internal fun rowTexts(depth: Int): List<String> {
        val out = ArrayList<String>()
        fun walk(node: DefaultMutableTreeNode, level: Int) {
            for (i in 0 until node.childCount) {
                val c = node.getChildAt(i) as DefaultMutableTreeNode
                if (level == depth) {
                    val comp = renderer.getTreeCellRendererComponent(tree, c, false, true, c.isLeaf, 0, false) as SimpleColoredComponent
                    val it = comp.iterator()
                    out += if (it.hasNext()) it.next() else ""
                } else {
                    walk(c, level + 1)
                }
            }
        }
        walk(root, 0)
        return out
    }

    /** The tree, for tests (the Delete key binding lives on it). */
    internal val treeComponent: JTree get() = tree

    /**
     * What a double-click on a row does: with a document row selected (selecting it opened it,
     * or started opening it in the background), hide the list so the document takes the whole
     * window — the Document Only state, which the toolbar toggle or Close brings back. A session
     * note opens an editor, not a document, so it leaves the list alone.
     */
    internal fun openSelectedFullArea() {
        val node = selectedNode ?: return
        val opensADocument = when (val u = node.userObject) {
            is ItemNode -> u.item.payload !is Payload.SessionNote
            is ReplyNode -> true
            else -> false
        }
        if (!opensADocument) return
        service.setListHidden(true)
    }

    /** Whether the document half is in the splitter, for tests. */
    internal val documentShown: Boolean get() = splitter.secondComponent != null

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
        private val time = DateTimeFormatter.ofPattern("HH:mm")
        private val dateTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            val node = value as? DefaultMutableTreeNode
            when (val u = node?.userObject) {
                is RepoNode -> append(u.repo.root.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                is TypeNode -> append(GgNotesBundle.message("toolwindow.kind.${u.kind.name}"), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                is DayNode -> append(u.day?.let { date.format(it) } ?: GgNotesBundle.message("toolwindow.noDate"), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                is ItemNode -> item(u, node)
                is ReplyNode -> {
                    val r = u.reply
                    append("↳ ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    append(r.author.ifBlank { r.source }, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append(": " + r.summary, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    toolTipText = r.rationale.ifBlank { null }
                }
            }
        }

        private fun item(u: ItemNode, node: DefaultMutableTreeNode) {
            val item = u.item
            icon = kindIcon(item.kind)
            val resolved = (item.payload as? Payload.StoreThread)?.thread?.resolved == true
            append(item.title.ifBlank { "(untitled)" }, if (resolved) SimpleTextAttributes.GRAYED_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
            val tail = listOf(ItemViews.ownerLabel(item), item.author, whenText(item, node)).filter { it.isNotBlank() }
            if (tail.isNotEmpty()) append("  " + tail.joinToString(" · "), SimpleTextAttributes.GRAYED_ATTRIBUTES)
            val tip: String? = when (val p = item.payload) {
                is Payload.StoreOverview -> {
                    val o = p.overview
                    badge(RowBadge.of(o, u.tips[o.branch]))
                    if (p.replies.isNotEmpty()) append("  " + GgNotesBundle.message("toolwindow.replies", p.replies.size), SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                    if (o.document is DocumentResult.CannotRender) append("  " + GgNotesBundle.message("toolwindow.cannotRender"), SimpleTextAttributes.ERROR_ATTRIBUTES)
                    OverviewGrouping.rowText(o)
                }
                is Payload.StoreReview -> {
                    val r = p.review.record
                    badge(RowBadge.ofReview(p.review, u.tips[r.address.branch]))
                    val counts = p.review.counts()
                    if (counts.isNotEmpty()) append("  " + Reviews.countsText(counts), SimpleTextAttributes.REGULAR_ITALIC_ATTRIBUTES)
                    when (p.review.content) {
                        is ReviewContent.Prose -> append("  prose", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                        is ReviewContent.CannotRender -> append("  " + GgNotesBundle.message("toolwindow.cannotRender"), SimpleTextAttributes.ERROR_ATTRIBUTES)
                        else -> Unit
                    }
                    if (u.treeChanged.isNotEmpty()) append("  ⚠ " + GgNotesBundle.message("toolwindow.treeChanged"), SimpleTextAttributes.ERROR_ATTRIBUTES)
                    r.scope
                }
                is Payload.StoreThread -> {
                    val t = p.thread
                    val n = t.root
                    if (n.side == "old") append("  " + GgNotesBundle.message("toolwindow.old"), SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                    if (t.resolved) append("  ✓ " + GgNotesBundle.message("toolwindow.resolved"), SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                    if (t.forgeDraft) append("  " + GgNotesBundle.message("toolwindow.draft"), SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                    for (m in n.meta) append("  ${m.key}: ${m.value}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    n.rationale.ifBlank { null }
                }
                is Payload.SessionOverview -> {
                    // judged now, not at build time: Close or another row's click changes it without a rebuild
                    if (service.open?.overview?.let { shows(u, it) } == true) append("  " + GgNotesBundle.message("toolwindow.shown"), SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                    if (u.unresolved.isNotEmpty()) append("  ⚠ " + GgNotesBundle.message("toolwindow.unresolved", u.unresolved.size), SimpleTextAttributes.ERROR_ATTRIBUTES)
                    u.session?.docs?.fileOf(dev.ggnotes.session.DocName(p.entry.doc.id, dev.ggnotes.session.DocKind.OVERVIEW))?.toString()
                }
                is Payload.SessionNote ->
                    u.session?.docs?.fileOf(dev.ggnotes.session.DocName(p.entry.doc.id, dev.ggnotes.session.DocKind.NOTE))?.toString()
            }
            val commit = item.owner.commit
            if (commit != null && u.subject == null) append("  " + GgNotesBundle.message("toolwindow.unknownCommit"), SimpleTextAttributes.ERROR_ATTRIBUTES)
            val commitTip = commit?.let { u.subject ?: it }
            toolTipText = when {
                tip == null -> commitTip
                commitTip == null -> tip
                else -> "<html>" + escape(tip).replace("\n", "<br>") + "<br>" + escape(commitTip) + "</html>"
            }
        }

        /**
         * The item's time: only `HH:mm` when its day is the day row above it (the day is the parent),
         * the date too for a contained item of another day, nothing for an undated item.
         */
        private fun whenText(item: NoteItem, node: DefaultMutableTreeNode): String {
            val at = item.time?.atZone(ZoneId.systemDefault()) ?: return ""
            val day = generateSequence(node.parent as? DefaultMutableTreeNode) { it.parent as? DefaultMutableTreeNode }
                .mapNotNull { it.userObject as? DayNode }.firstOrNull()?.day
            return if (day == at.toLocalDate()) time.format(at) else dateTime.format(at)
        }

        private fun kindIcon(kind: ItemKind): Icon = when (kind) {
            ItemKind.BRANCH -> AllIcons.Vcs.Branch
            ItemKind.COMMIT -> AllIcons.Vcs.CommitNode
            ItemKind.PULL_REQUEST -> AllIcons.Vcs.Merge
            ItemKind.FILE -> AllIcons.FileTypes.Text
            ItemKind.OTHER -> AllIcons.Actions.Preview
        }

        /** The short grey badge after a row's grey tail; nothing for a plain commit row. */
        private fun badge(b: RowBadge?) {
            val text = when (b) {
                null -> return
                is RowBadge.Pr -> GgNotesBundle.message("toolwindow.badge.pr", b.number)
                is RowBadge.Branch -> GgNotesBundle.message("toolwindow.badge.branch", b.name)
                is RowBadge.WasTip -> GgNotesBundle.message("toolwindow.badge.wasTip", b.name)
                RowBadge.Range -> GgNotesBundle.message("toolwindow.badge.range")
                RowBadge.Preview -> GgNotesBundle.message("toolwindow.badge.preview")
                RowBadge.Pair -> GgNotesBundle.message("toolwindow.badge.pair")
                RowBadge.Working -> GgNotesBundle.message("toolwindow.badge.working")
            }
            append("  $text", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
        }
    }
}
