package dev.ggnotes.startup

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import dev.ggnotes.GgNotesBundle
import dev.ggnotes.overview.OverviewService

private val LOG = logger<GgNotesStartupActivity>()

/** Warms the overview service so the stores are read (and polled) from project open, not first tool-window use. */
class GgNotesStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        LOG.info(GgNotesBundle.message("startup.ready", project.name))
        OverviewService.getInstance(project).refresh()
    }
}
