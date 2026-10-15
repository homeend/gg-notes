package dev.ggnotes.store

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Paths

/** SPEC §3: base directory rules and the repository key. */
class StoreLocatorTest {

    @Test
    fun `key of the gigagit common dir matches the confirmed vector`() {
        assertEquals("d10200a3a219735f", StoreLocator.key("/work/gigagit/.git", windows = false))
    }

    @Test
    fun `key of the git-focus common dir matches the confirmed vector`() {
        assertEquals("4acf7db7161a42e0", StoreLocator.key("/home/homeend/git-focus/.git", windows = false))
    }

    @Test
    fun `windows key of T others test-1 matches the oracle run on Windows`() {
        // `gg overview store --json` (spec branch 026da99e, windows/amd64) in T:\others\test-1
        // printed key ca3f14ca89ceb043 on 2026-10-15; Windows git prints the common dir with
        // forward slashes, and clean() turns them into backslashes before hashing (SPEC §3.2).
        assertEquals("ca3f14ca89ceb043", StoreLocator.key("T:/others/test-1/.git", windows = true))
        assertEquals("ca3f14ca89ceb043", StoreLocator.key("T:\\others\\test-1\\.git", windows = true))
    }

    @Test
    fun `key trims whitespace around git output`() {
        assertEquals("d10200a3a219735f", StoreLocator.key("  /work/gigagit/.git\n", windows = false))
    }

    @Test
    fun `clean removes dot segments and repeated separators without touching symlinks`() {
        assertEquals("/a/b/c", StoreLocator.clean("/a//b/./c/", windows = false))
        assertEquals("/a/c", StoreLocator.clean("/a/b/../c", windows = false))
        assertEquals("/", StoreLocator.clean("/", windows = false))
    }

    @Test
    fun `clean turns slashes into backslashes on windows only`() {
        assertEquals("T:\\others\\gigagit\\.git", StoreLocator.clean("T:/others/gigagit/.git", windows = true))
        assertEquals("T:/others/gigagit/.git", StoreLocator.clean("T:/others/gigagit/.git", windows = false))
    }

    @Test
    fun `base dir prefers XDG_STATE_HOME when set and non-empty`() {
        val env = mapOf("XDG_STATE_HOME" to "/tmp/state", "LOCALAPPDATA" to "C:\\Users\\x\\AppData\\Local")
        assertEquals(Paths.get("/tmp/state/gg/notes"), StoreLocator.baseDir(env::get, windows = true, home = Paths.get("/home/u")))
    }

    @Test
    fun `base dir uses LocalAppData on windows when XDG_STATE_HOME is empty`() {
        val env = mapOf("XDG_STATE_HOME" to "", "LOCALAPPDATA" to "/mnt/c/Users/x/AppData/Local")
        assertEquals(Paths.get("/mnt/c/Users/x/AppData/Local/gg/notes"),
            StoreLocator.baseDir(env::get, windows = true, home = Paths.get("/home/u")))
    }

    @Test
    fun `base dir falls back to dot-local state under home`() {
        val env = mapOf("LOCALAPPDATA" to "/ignored/off/windows")
        assertEquals(Paths.get("/home/u/.local/state/gg/notes"),
            StoreLocator.baseDir(env::get, windows = false, home = Paths.get("/home/u")))
    }

    @Test
    fun `store dir is base slash key`() {
        assertEquals(Paths.get("/home/u/.local/state/gg/notes/d10200a3a219735f"),
            StoreLocator.storeDir(Paths.get("/home/u/.local/state/gg/notes"), "d10200a3a219735f"))
    }
}
