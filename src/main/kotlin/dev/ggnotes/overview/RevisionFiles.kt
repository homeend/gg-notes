package dev.ggnotes.overview

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
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
        val refresher = project.getService(GitIndexFileSystemRefresher::class.java) ?: return null
        return indexFile(refresher, root, filePath)
    }

    /**
     * The refresher's find-or-create lookup, by name: `getFile` on 242–252, `createFile` from 253
     * on (where `findFile` only finds a file already made). Linking either one directly is a
     * NoSuchMethodError on the other side of the rename, so the plugin never names it in bytecode.
     */
    internal fun indexFile(refresher: Any, root: VirtualFile, filePath: FilePath): VirtualFile? {
        for (name in listOf("getFile", "createFile")) {
            val method = runCatching { refresher.javaClass.getMethod(name, VirtualFile::class.java, FilePath::class.java) }.getOrNull() ?: continue
            return method.invoke(refresher, root, filePath) as VirtualFile?
        }
        return null
    }
}
