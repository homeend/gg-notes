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
        val file = ContentRevisionVirtualFile.create(revision)
        // Load the bytes INTO THE FILE here, off the EDT: opening it at a line makes the platform
        // ask the file for its length on the EDT (OpenFileDescriptor's lazy range marker), and
        // git4idea refuses to read a revision there. The file keeps the bytes; warming only the
        // revision's cache is not enough, git4idea's check runs before its cache lookup.
        runCatching { file.contentsToByteArray() }
        return file
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
