package dev.ggnotes.startup

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.util.concurrency.AppExecutorUtil
import dev.ggnotes.GgNotesBundle
import dev.ggnotes.overview.OverviewService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private val LOG = logger<GgNotesStartupActivity>()

/** Warms the overview service so the stores are read (and polled) from project open, not first tool-window use. */
class GgNotesStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        LOG.info(GgNotesBundle.message("startup.ready", project.name))
        val service = OverviewService.getInstance(project)
        if (System.getProperty("ggnotes.demo") != null) demo(project, service)
        service.refresh()
    }

    /**
     * Dev-only (`-Dggnotes.demo`): once the stores are read, open the tool
     * window, the first overview and its first anchor without a user, so a
     * smoke run or a screenshot shows the whole viewer.
     */
    private fun demo(project: Project, service: OverviewService) {
        val done = AtomicBoolean(false)
        service.addSnapshotListener(service) {
            val repo = service.snapshot.repos.firstOrNull { it.groups.isNotEmpty() } ?: return@addSnapshotListener
            if (!done.compareAndSet(false, true)) return@addSnapshotListener
            val overview = repo.groups.first().overviews.first()
            LOG.info("GgNotes demo: opening '${overview.title}'")
            val tw = ToolWindowManager.getInstance(project).getToolWindow(OverviewService.TOOL_WINDOW_ID) ?: return@addSnapshotListener
            tw.activate({
                service.open(overview, repo.repo)
                AppExecutorUtil.getAppScheduledExecutorService().schedule({
                    ApplicationManager.getApplication().invokeLater({ service.goToAnchor(1) }, project.disposed)
                }, 3, TimeUnit.SECONDS)
                System.getProperty("ggnotes.demo.shot")?.let { path ->
                    AppExecutorUtil.getAppScheduledExecutorService().schedule({
                        ApplicationManager.getApplication().invokeLater({ paintFrame(project, path) }, project.disposed)
                    }, 9, TimeUnit.SECONDS)
                }
            }, true)
        }
    }

    /** Paints the project frame into a PNG (works where a screen grab cannot, e.g. WSLg). */
    private fun paintFrame(project: Project, path: String) {
        val frame = WindowManager.getInstance().getFrame(project) ?: return
        val image = java.awt.image.BufferedImage(frame.width, frame.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            frame.paint(g)
        } finally {
            g.dispose()
        }
        javax.imageio.ImageIO.write(image, "png", java.io.File(path))
        LOG.info("GgNotes demo: shot $path (${frame.width}x${frame.height})")
    }
}
