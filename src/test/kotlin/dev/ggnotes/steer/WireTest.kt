package dev.ggnotes.steer

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SPEC §3.1 / §3.2: the command and reply JSON, every field optional but id and cmd. */
class WireTest {

    @Test
    fun `the spec's navigate command parses with target line worktree and wait`() {
        val c = Wire.parseCommand("""{"id":"1-1","cmd":"navigate","file":"src/a.go","target":{"state":"commit","commit":"d5efa035df1f3c4c949040bebb924a35d0ea61a0"},
            "line":{"side":"new","no":40,"end":60},"worktree":"/home/dev/src/gate","wait":true,"unknown_key":1}""")!!
        assertEquals("1-1", c.id)
        assertEquals("navigate", c.cmd)
        assertEquals("src/a.go", c.file)
        assertEquals("commit", c.target?.state)
        assertEquals("d5efa035df1f3c4c949040bebb924a35d0ea61a0", c.target?.commit)
        assertEquals(40, c.line?.no)
        assertEquals(60, c.line?.end)
        assertEquals("new", c.line?.side)
        assertEquals("/home/dev/src/gate", c.worktree)
        assertTrue(c.wait)
        assertNull(c.step)
        assertFalse(c.background)
    }

    @Test
    fun `a minimal command has defaults and the retired verbs' fields still parse`() {
        val n = Wire.parseCommand("""{"id":"2-2","cmd":"note_add","file":"a.go","start":3,"end":4,"summary":"s","rationale":"r","author":"me","meta":{"k":"v"},"display":{"k":{"show":["badge"]}}}""")!!
        assertEquals(3, n.start)
        assertEquals(4, n.end)
        assertEquals("s", n.summary)
        assertEquals(mapOf("k" to "v"), n.meta)
        assertTrue(n.display!!.isJsonObject)
        assertFalse(n.wait)
        val o = Wire.parseCommand("""{"id":"3-3","cmd":"overview_add","title":"Tour","text":"# T","background":true}""")!!
        assertEquals("Tour", o.title)
        assertTrue(o.background)
        assertNull(Wire.parseCommand("""{"cmd":"navigate"}"""))
        assertNull(Wire.parseCommand("not json"))
        assertNull(Wire.parseCommand("""{"id":"4-4"}"""))
    }

    @Test
    fun `replies serialize with only the fields that are set and no html escaping`() {
        val ok = Wire.toJson(Reply.ok("1-1", "opened src/a.go:3-4 at line 3"))
        assertEquals("""{"id":"1-1","ok":true,"detail":"opened src/a.go:3-4 at line 3"}""", ok)
        val fail = Wire.toJson(Reply.fail("1-2", "the IDE shows <root>, not /x"))
        assertEquals("""{"id":"1-2","ok":false,"error":"the IDE shows <root>, not /x"}""", fail)
        val files = Wire.toJson(Reply.ok("1-3", "").copy(files = listOf(OpenFileWire("f1", "a.go", "worktree", null, null, 3, "shown", 1))))
        val parsed = JsonParser.parseString(files).asJsonObject
        assertEquals("f1", parsed.getAsJsonArray("files")[0].asJsonObject.get("id").asString)
        assertFalse(parsed.has("detail"))
        assertFalse(parsed.getAsJsonArray("files")[0].asJsonObject.has("title"))
        val quiet = Wire.toJson(Reply.ok("1-4", "").copy(files = listOf(OpenFileWire("f1", "a.go", "worktree", null, null, 0, "background", 0))))
        val fp = JsonParser.parseString(quiet).asJsonObject.getAsJsonArray("files")[0].asJsonObject
        assertFalse("zero counts stay out like gg's omitempty", fp.has("notes"))
        assertFalse(fp.has("line"))
        assertFalse(fp.has("author"))
        // an overview row (session docs) carries its front matter: author, created as RFC 3339 UTC, anchors
        val ov = Wire.toJson(Reply.ok("1-5", "").copy(files = listOf(OpenFileWire("20261018T143201-9f3a", "20261018T143201-9f3a.md", "overview", "Tour", null, 0, "shown", 0,
            author = "claude", created = "2026-10-18T14:32:01Z", anchors = 3))))
        val op = JsonParser.parseString(ov).asJsonObject.getAsJsonArray("files")[0].asJsonObject
        assertEquals("claude", op.get("author").asString)
        assertEquals("2026-10-18T14:32:01Z", op.get("created").asString)
        assertEquals(3, op.get("anchors").asInt)
    }

    @Test
    fun `an item row carries kind and owner with only the set keys`() {
        val row = OpenFileWire("store:0n1", "a.go", "store-thread", "t", null, 3, "background", 0, kind = "file", owner = OwnerWire(commit = "abc", path = "a.go"))
        val o = JsonParser.parseString(Wire.toJson(Reply.ok("1-6", "").copy(files = listOf(row)))).asJsonObject.getAsJsonArray("files")[0].asJsonObject
        assertEquals("file", o.get("kind").asString)
        assertEquals(setOf("commit", "path"), o.getAsJsonObject("owner").keySet())
        val old = JsonParser.parseString(Wire.toJson(Reply.ok("1-7", "").copy(files = listOf(OpenFileWire("f1", "a.go", "worktree", null, null, 0, "background", 0))))).asJsonObject.getAsJsonArray("files")[0].asJsonObject
        assertFalse(old.has("kind"))
        assertFalse(old.has("owner"))
    }
}
