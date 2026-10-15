package dev.ggnotes.steer

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Paths

/** PLUGIN.md §2: `Enc(p)` and the inbox directory, against the real example on disk. */
class SteerPathsTest {

    @Test
    fun `enc of the real linux example`() {
        assertEquals("-home-homeend-.claude-jobs-2620a6cb-tmp-cmprepo-.git",
            SteerPaths.enc("/home/homeend/.claude/jobs/2620a6cb/tmp/cmprepo/.git", windows = false))
        assertEquals("-home-homeend-.claude-jobs-2620a6cb-tmp-cmprepo",
            SteerPaths.enc("/home/homeend/.claude/jobs/2620a6cb/tmp/cmprepo", windows = false))
    }

    @Test
    fun `enc cleans first - repeated separators, dot segments, trailing slash`() {
        assertEquals("-a-b", SteerPaths.enc("/a//b/./", windows = false))
        assertEquals("-a-c", SteerPaths.enc("/a/b/../c", windows = false))
    }

    @Test
    fun `enc on windows turns both slash kinds and the drive colon into dashes`() {
        assertEquals("C--src-x-.git", SteerPaths.enc("C:\\src\\x\\.git", windows = true))
        assertEquals("C--src-x-.git", SteerPaths.enc("C:/src/x/.git", windows = true))
    }

    @Test
    fun `gg's repokey-vectors json, windows rows with the windows rule`() {
        val rows = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(java.nio.file.Paths.get("docs/gg-steer/repokey-vectors.json"))).asJsonArray
        assertEquals(7, rows.size())
        for (r in rows) {
            val o = r.asJsonObject
            val windows = o.get("os")?.asString == "windows"
            assertEquals(o.get("in").asString, o.get("out").asString, SteerPaths.enc(o.get("in").asString, windows = windows))
        }
    }

    @Test
    fun `inbox dir is state slash gg sessions enc(common) steer enc(worktree)`() {
        val inbox = SteerPaths.inboxDir(Paths.get("/home/homeend/.local/state"),
            "/home/homeend/.claude/jobs/2620a6cb/tmp/cmprepo/.git", "/home/homeend/.claude/jobs/2620a6cb/tmp/cmprepo", windows = false)
        assertEquals(Paths.get("/home/homeend/.local/state/gg/sessions/-home-homeend-.claude-jobs-2620a6cb-tmp-cmprepo-.git/steer/-home-homeend-.claude-jobs-2620a6cb-tmp-cmprepo"), inbox)
        assertEquals(inbox.resolve("ide"), SteerPaths.ideDir(inbox))
    }
}
