package dev.ggnotes.overview

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import dev.ggnotes.GgNotesBundle
import dev.ggnotes.git.GgCli
import dev.ggnotes.store.RemovalPlan
import git4idea.repo.GitRepository
import java.io.File
import java.util.concurrent.CompletableFuture

/**
 * "Remove from gg store": run the plan through `gg batch` in the repository's
 * root (gg owns the store files), re-read the store, close the open document
 * if it showed something that went, and report a refusal as a notification.
 */
object StoreRemoval {

    fun perform(project: Project, repo: GitRepository, plan: RemovalPlan): CompletableFuture<GgCli.Result> {
        val service = OverviewService.getInstance(project)
        return CompletableFuture.supplyAsync({ GgCli.batch(File(repo.root.path), plan.lines) }, AppExecutorUtil.getAppExecutorService())
            .thenApply { result ->
                service.refreshSync()
                val open = service.open
                if (open != null && open.repo == repo && shows(open, plan)) {
                    ApplicationManager.getApplication().invokeAndWait { if (!project.isDisposed) service.close() }
                }
                if (!result.ok) {
                    val text = (result.stderr.ifBlank { result.stdout }).trim().lines().filter { it.isNotBlank() }.take(6).joinToString("\n")
                    ApplicationManager.getApplication().invokeLater({
                        NotificationGroupManager.getInstance().getNotificationGroup("GgNotes")
                            .createNotification(GgNotesBundle.message("remove.failed", text), NotificationType.WARNING).notify(project)
                    }, project.disposed)
                }
                result
            }
    }

    /** The open document shows a removed record: the record itself, or a note thread of a `notes:<ids>:` view. */
    private fun shows(open: OpenOverview, plan: RemovalPlan): Boolean {
        val o = open.overview
        if (o.id in plan.ids) return true
        if (o.viewKind.startsWith("notes:")) return plan.ids.any { it in o.viewKind }
        return false
    }
}
