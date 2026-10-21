package dev.ggnotes.session

import org.junit.Assert.assertEquals
import org.junit.Test

/** The line "Copy Link" puts on the clipboard: enough for an agent with only a shell to find and read the document. */
class SessionDocRefsTest {

    @Test
    fun `an overview's link names it, its worktree and its file`() {
        assertEquals(
            "Agent Notes overview 20261019T204513-a1b2 \"Risk module introduced (947b9dc)\" " +
                "(worktree T:\\workspace\\dd-java; file C:\\Users\\u\\AppData\\Local\\gg\\sessions\\k\\steer\\w\\docs\\20261019T204513-a1b2.md)",
            SessionDocRefs.overviewLink("20261019T204513-a1b2", "Risk module introduced (947b9dc)", "T:\\workspace\\dd-java",
                "C:\\Users\\u\\AppData\\Local\\gg\\sessions\\k\\steer\\w\\docs\\20261019T204513-a1b2.md"))
    }

    @Test
    fun `a note's link carries where it sits now and says when it is outdated`() {
        assertEquals(
            "Agent Notes note n1 at src/a/Planning.java:85 \"Trigger 2: planning publishes the event.\" (worktree /w; file /s/docs/n1.toml)",
            SessionDocRefs.noteLink("n1", "src/a/Planning.java", Placement(85, 85, false), "Trigger 2: planning publishes the event.", "/w", "/s/docs/n1.toml"))
        assertEquals(
            "Agent Notes note n2 at a.go:3-4 (outdated) \"s\" (worktree /w; file /s/docs/n2.toml)",
            SessionDocRefs.noteLink("n2", "a.go", Placement(3, 4, true), "s", "/w", "/s/docs/n2.toml"))
    }

    @Test
    fun `titles are quoted as gg quotes them`() {
        assertEquals("overview x \"say \\\"hi\\\"\\tnow\\\\\"", SessionDocRefs.overviewName("x", "say \"hi\"\tnow\\"))
    }
}
