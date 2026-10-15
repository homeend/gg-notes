package dev.ggnotes.startup

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import dev.ggnotes.GgNotesBundle

private val LOG = logger<GgNotesStartupActivity>()

/** Scaffold placeholder: proves the plugin loads and the bundle resolves. */
class GgNotesStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        LOG.info(GgNotesBundle.message("startup.ready", project.name))
    }
}
