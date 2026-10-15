package dev.ggnotes.steer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** PLUGIN.md §2–§3: presence, the sub-inbox drain rules, reply writing and the open-time sweep. */
class InboxTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun inbox(): Path = tmp.newFolder("inbox").toPath().also { Files.createDirectories(it.resolve("ide")) }

    private fun post(ide: Path, name: String, json: String) {
        Files.writeString(ide.resolve(name), json)
    }

    @Test
    fun `drain reads commands in name order and deletes them before parsing`() {
        val ide = inbox().resolve("ide")
        post(ide, "cmd-200-1.json", """{"id":"200-1","cmd":"files"}""")
        post(ide, "cmd-100-1.json", """{"id":"100-1","cmd":"navigate","file":"a.go"}""")
        post(ide, "reply-050-1.json", """{"id":"050-1","ok":true}""")
        post(ide, "notes.txt", "not a command")
        val got = Inbox.drain(ide)
        assertEquals(listOf("100-1", "200-1"), got.map { it.id })
        assertFalse(Files.exists(ide.resolve("cmd-100-1.json")))
        assertFalse(Files.exists(ide.resolve("cmd-200-1.json")))
        assertTrue("other files are left alone", Files.exists(ide.resolve("reply-050-1.json")))
        assertTrue(Files.exists(ide.resolve("notes.txt")))
    }

    @Test
    fun `oversized, malformed and bad-id commands are deleted and dropped`() {
        val ide = inbox().resolve("ide")
        Files.write(ide.resolve("cmd-1-1.json"), ByteArray(Inbox.MAX_COMMAND_BYTES.toInt() + 1))
        post(ide, "cmd-2-1.json", "{not json")
        post(ide, "cmd-3-1.json", """{"id":"../escape","cmd":"files"}""")
        post(ide, "cmd-4-1.json", """{"id":"a/b","cmd":"files"}""")
        post(ide, "cmd-5-1.json", """{"id":"a\\b","cmd":"files"}""")
        post(ide, "cmd-6-1.json", """{"id":"","cmd":"files"}""")
        post(ide, "cmd-7-1.json", """{"id":".","cmd":"files"}""")
        post(ide, "cmd-8-1.json", """{"id":"8-1","cmd":"files"}""")
        val got = Inbox.drain(ide)
        assertEquals(listOf("8-1"), got.map { it.id })
        assertEquals("every cmd file is gone", emptyList<String>(), Files.list(ide).use { s -> s.map { it.fileName.toString() }.filter { it.startsWith("cmd-") }.toList() })
    }

    @Test
    fun `a reply is written by temp and rename with no temp file left`() {
        val ide = inbox().resolve("ide")
        Inbox.writeReply(ide, Reply.ok("1-1", "opened a.go:3-4 at line 3"))
        assertEquals("""{"id":"1-1","ok":true,"detail":"opened a.go:3-4 at line 3"}""", Files.readString(ide.resolve("reply-1-1.json")))
        assertEquals(listOf("reply-1-1.json"), Files.list(ide).use { s -> s.map { it.fileName.toString() }.toList() })
    }

    @Test
    fun `the sweep removes the sub-inbox's leftovers and never the top-level files`() {
        val inbox = inbox()
        val ide = inbox.resolve("ide")
        post(ide, "cmd-1-1.json", "{}")
        post(ide, "reply-2-2.json", "{}")
        post(ide, "keep.json", "{}")
        Files.writeString(inbox.resolve("cmd-9-9.json"), "{}")
        Files.writeString(inbox.resolve("reply-9-9.json"), "{}")
        Files.writeString(inbox.resolve("tui.json"), "{}")
        Inbox.sweep(ide)
        assertEquals(listOf("keep.json"), Files.list(ide).use { s -> s.map { it.fileName.toString() }.toList() })
        assertTrue(Files.exists(inbox.resolve("cmd-9-9.json")))
        assertTrue(Files.exists(inbox.resolve("reply-9-9.json")))
        assertTrue(Files.exists(inbox.resolve("tui.json")))
    }

    @Test
    fun `presence json has gg's keys and touch moves the mtime`() {
        val inbox = inbox()
        val json = Presence.json(pid = 12345L, worktree = "/home/dev/src/gate", started = Instant.parse("2026-10-15T12:00:00Z"), ide = "IntelliJ IDEA 2026.2", plugin = "0.2.0")
        assertEquals("""{"pid":12345,"worktree":"/home/dev/src/gate","started":"2026-10-15T12:00:00Z","ide":"IntelliJ IDEA 2026.2","plugin":"0.2.0"}""", json)
        Presence.write(inbox, json)
        val file = inbox.resolve("ide.json")
        assertEquals(json, Files.readString(file))
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(1_000_000L))
        Presence.touch(inbox, json)
        assertTrue(Files.getLastModifiedTime(file).toMillis() > System.currentTimeMillis() - 10_000)
        assertEquals("no temp file left", listOf("ide", "ide.json"), Files.list(inbox).use { s -> s.map { it.fileName.toString() }.sorted().toList() })
        Presence.remove(inbox)
        assertFalse(Files.exists(file))
    }
}
