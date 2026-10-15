package dev.ggnotes.overview

import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.vfs.ContentRevisionVirtualFile
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.vcsUtil.VcsUtil
import git4idea.GitContentRevision
import git4idea.GitRevisionNumber

/** Read-only virtual files for a path at a commit or in the index. Background only (they run git). */
object RevisionFiles {

    /** The file at [sha] as the IDE's VCS layer shows a revision (a read-only tab titled `name (sha)`). */
    fun atCommit(project: Project, root: VirtualFile, sha: String, path: String): VirtualFile {
        val filePath = VcsUtil.getFilePath(root.path + "/" + path, false)
        val revision = GitContentRevision.createRevision(filePath, GitRevisionNumber(sha), project)
        // warm the revision's content cache off the EDT so the editor opens without a git call
        runCatching { revision.content }
        return ContentRevisionVirtualFile.create(revision)
    }

    /** The index's copy of [path] as a read-only light file; null when the path is not staged. */
    fun staged(bytes: ByteArray, path: String): VirtualFile {
        val name = path.substringAfterLast('/')
        val detected = FileTypeManager.getInstance().getFileTypeByFileName(name)
        // an unknown or binary type gets no document (and no editor): show the text as plain text
        val type = if (detected.isBinary || detected == com.intellij.openapi.fileTypes.UnknownFileType.INSTANCE) com.intellij.openapi.fileTypes.PlainTextFileType.INSTANCE else detected
        val f = LightVirtualFile("$name (staged)", type, String(bytes, Charsets.UTF_8))
        f.isWritable = false
        return f
    }
}
