package dev.ggnotes.git

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.util.GitFileUtils

/**
 * The three git questions the plugin asks, through git4idea. Every call runs
 * the git executable: never call from the EDT. Only the
 * `GitLineHandler(Project, VirtualFile, GitCommand)` constructor is used
 * (stable 242 → 262, see CLAUDE.md).
 */
object GitAccess {

    private val LOG = logger<GitAccess>()

    private fun assertBackground() {
        if (ApplicationManager.getApplication().isDispatchThread) {
            LOG.error("GgNotes: git must not run on the EDT")
        }
    }

    /** SPEC §3.2: `git rev-parse --path-format=absolute --git-common-dir`, trimmed; null when git fails. */
    fun commonDir(project: Project, root: VirtualFile): String? {
        assertBackground()
        val h = GitLineHandler(project, root, GitCommand.REV_PARSE)
        h.setSilent(true)
        h.addParameters("--path-format=absolute", "--git-common-dir")
        val result = Git.getInstance().runCommand(h)
        if (!result.success()) {
            LOG.info("GgNotes: rev-parse failed in ${root.path}: ${result.errorOutputAsJoinedString}")
            return null
        }
        return result.output.firstOrNull { it.isNotBlank() }?.trim()
    }

    /** The commit's subject line, or null when the commit is not in the repository (SPEC §8.2). */
    fun commitSubject(project: Project, root: VirtualFile, sha: String): String? {
        assertBackground()
        val h = GitLineHandler(project, root, GitCommand.LOG)
        h.setSilent(true)
        h.addParameters("-1", "--format=%s", sha)
        h.endOptions()
        val result = Git.getInstance().runCommand(h)
        if (!result.success()) return null
        return result.output.firstOrNull()?.trim() ?: ""
    }

    /** The index's copy of a path (`git show :<path>`); null when the path is not staged. */
    fun indexContent(project: Project, root: VirtualFile, path: String): ByteArray? {
        assertBackground()
        return try {
            GitFileUtils.getFileContent(project, root, "", path)
        } catch (e: Exception) {
            LOG.debug("GgNotes: no $path in the index: ${e.message}")
            null
        }
    }

    /** `git show <sha>:<path>` as bytes; null when the path is not at the commit. */
    fun fileContent(project: Project, root: VirtualFile, sha: String, path: String): ByteArray? {
        assertBackground()
        return try {
            GitFileUtils.getFileContent(project, root, sha, path)
        } catch (e: Exception) {
            LOG.debug("GgNotes: no $path at $sha: ${e.message}")
            null
        }
    }
}
