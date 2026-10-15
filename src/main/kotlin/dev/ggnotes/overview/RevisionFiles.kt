package dev.ggnotes.overview

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.vfs.ContentRevisionVirtualFile
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcsUtil.VcsUtil
import git4idea.GitContentRevision
import git4idea.GitRevisionNumber
import git4idea.index.vfs.GitIndexFileSystemRefresher

/** Virtual files for a path at a commit or in the index. Background only (they run git). */
object RevisionFiles {

    /** The file at [sha] as the IDE's VCS layer shows a revision (a read-only tab titled `name (sha)`). */
    fun atCommit(project: Project, root: VirtualFile, sha: String, path: String): VirtualFile {
        val filePath = VcsUtil.getFilePath(root.path + "/" + path, false)
        val revision = GitContentRevision.createRevision(filePath, GitRevisionNumber(sha), project)
        // warm the revision's content cache off the EDT so the editor opens without a git call
        runCatching { revision.content }
        return ContentRevisionVirtualFile.create(revision)
    }

    /**
     * The index's copy of [path]: git4idea's own index file system (what the
     * Commit tool window's staging area opens). The caller puts the editor in
     * viewer mode; the file itself stays the IDE's staging file.
     */
    fun staged(project: Project, root: VirtualFile, path: String): VirtualFile? {
        val filePath = VcsUtil.getFilePath(root.path + "/" + path, false)
        return project.getService(GitIndexFileSystemRefresher::class.java)?.getFile(root, filePath)
    }
}
