package dev.ggnotes.overview

import com.intellij.mock.MockVirtualFile
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vcs.LocalFilePath
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The index copy of a path comes from git4idea's GitIndexFileSystemRefresher, whose lookup was
 * renamed: `getFile` (find or create) on 242–252, `createFile` from 253 on. Linking either one
 * fails on the other (NoSuchMethodError), so [RevisionFiles.indexFile] finds it by name. These
 * stand-ins have the two shapes.
 */
class IndexFileLookupTest {

    private val root = MockVirtualFile(true, "repo")
    private val path: FilePath = LocalFilePath("/repo/a.txt", false)
    private val staged = MockVirtualFile("a.txt")

    class Before242(private val out: VirtualFile) {
        @Suppress("unused") fun getFile(root: VirtualFile, path: FilePath): VirtualFile = out
    }

    class Since253(private val out: VirtualFile) {
        @Suppress("unused") fun findFile(root: VirtualFile, path: FilePath): VirtualFile? = null
        @Suppress("unused") fun createFile(root: VirtualFile, path: FilePath): VirtualFile = out
    }

    @Test
    fun `242 to 252 - getFile`() {
        assertSame(staged, RevisionFiles.indexFile(Before242(staged), root, path))
    }

    @Test
    fun `253 on - createFile, not the find-only findFile`() {
        assertSame(staged, RevisionFiles.indexFile(Since253(staged), root, path))
    }

    @Test
    fun `neither - no file`() {
        assertNull(RevisionFiles.indexFile(Any(), root, path))
    }
}
