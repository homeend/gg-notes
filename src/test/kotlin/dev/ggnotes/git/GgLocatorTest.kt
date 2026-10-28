package dev.ggnotes.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** gg is found without a property: the IDE's PATH, the login shell's PATH, then Go's bin directories. */
class GgLocatorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun env(vararg pairs: Pair<String, String>): (String) -> String? = mapOf(*pairs)::get

    @Test
    fun `candidates follow PATH, then the shell PATH, then GOBIN, GOPATH and the default go bin`() {
        val c = GgLocator.candidates(
            path = "/usr/bin:/opt/x",
            shellPath = "/usr/bin:/home/u/.local/bin",
            env = env("GOBIN" to "/gobin", "GOPATH" to "/gp1:/gp2"),
            home = "/home/u",
            windows = false,
        )
        assertEquals(
            listOf(
                "/usr/bin/gg", "/opt/x/gg", "/home/u/.local/bin/gg",
                "/gobin/gg", "/gp1/bin/gg", "/gp2/bin/gg", "/home/u/go/bin/gg",
                "/usr/local/bin/gg", "/opt/homebrew/bin/gg", "/home/linuxbrew/.linuxbrew/bin/gg",
            ),
            c,
        )
    }

    @Test
    fun `relative and blank PATH entries are skipped so a gg in the checkout is never picked`() {
        val c = GgLocator.candidates(path = ".::bin:/a", shellPath = null, env = env(), home = "/h", windows = false)
        assertEquals("/a/gg", c.first())
        assertEquals(true, c.all { it.startsWith("/") })
    }

    @Test
    fun `windows looks for gg exe, splits on semicolons, strips quotes and uses the user profile go bin`() {
        val c = GgLocator.candidates(
            path = "\"C:\\p q\";.;D:\\r\\;\\\\srv\\tools",
            shellPath = null,
            env = env("GOPATH" to "E:\\gp"),
            home = "C:\\Users\\u",
            windows = true,
        )
        assertEquals(
            listOf("C:\\p q\\gg.exe", "D:\\r\\gg.exe", "\\\\srv\\tools\\gg.exe", "E:\\gp\\bin\\gg.exe", "C:\\Users\\u\\go\\bin\\gg.exe"),
            c,
        )
    }

    @Test
    fun `find picks the first candidate that exists and is executable`() {
        val goBin = tmp.newFolder("home", "go", "bin")
        val gg = File(goBin, "gg").apply { writeText("#!/bin/sh\n"); setExecutable(true) }
        val emptyDir = tmp.newFolder("empty")
        val found = GgLocator.find(
            path = emptyDir.path, shellPath = null, env = env(), home = goBin.parentFile.parent, windows = false,
        )
        assertEquals(gg.canonicalPath, found?.canonicalPath)
    }

    @Test
    fun `find skips a directory named gg and returns null when nothing is there`() {
        val dir = tmp.newFolder("d")
        File(dir, "gg").mkdir()
        assertNull(GgLocator.find(path = dir.path, shellPath = null, env = env(), home = tmp.newFolder("h").path, windows = false))
    }
}
