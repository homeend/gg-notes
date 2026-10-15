package dev.ggnotes.store

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

/** SPEC-part2 §4.3 (the fingerprint) and §5.2 (relocation), pinned by gg's vectors and cases. */
class RelocatorTest {

    @Test
    fun `context_hash vectors`() {
        val vectors = JsonParser.parseString(Files.readString(Paths.get("docs/gg-notes-store/vectors.json"))).asJsonObject
        for (v in vectors.getAsJsonArray("context_hash")) {
            val o = v.asJsonObject
            val lines = o.getAsJsonArray("lines").map { it.asString }
            assertEquals(lines.toString(), o.get("hash").asString, Relocator.contextHash(lines))
        }
    }

    @Test
    fun `worktree key vector`() {
        val vectors = JsonParser.parseString(Files.readString(Paths.get("docs/gg-notes-store/vectors.json"))).asJsonObject
        for (v in vectors.getAsJsonArray("worktree_key")) {
            val o = v.asJsonObject
            assertEquals(o.get("key").asString, StoreLocator.key(o.get("top").asString, windows = false))
        }
    }

    @Test
    fun `every relocation case of gg`() {
        val cases = JsonParser.parseString(Files.readString(Paths.get("docs/gg-notes-store/relocation-cases.json"))).asJsonArray
        assertEquals(10, cases.size())
        for (c in cases) {
            val o = c.asJsonObject
            val name = o.get("name").asString
            val lines = o.get("lines").takeIf { !it.isJsonNull }?.asJsonArray?.map { it.asString }
            val range = o.getAsJsonArray("range")
            val anchored = o.getAsJsonArray("anchored").map { it.asString }
            val hash = if (anchored.isEmpty()) "" else Relocator.contextHash(anchored)
            val result = Relocator.relocate(lines, range[0].asInt, range[1].asInt, hash)
            val at = o.getAsJsonArray("at")
            assertEquals(name, o.get("status").asString, result.status.name.lowercase())
            assertEquals("$name: first", at[0].asInt, result.first)
            assertEquals("$name: last", at[1].asInt, result.last)
        }
    }

    @Test
    fun `a committed note is only checked - a match is active, a mismatch stale, no hash unverified`() {
        val lines = listOf("func gate(closed bool) bool {", "\topen := !closed", "\tif closed {", "\t\treturn false", "\t}", "\treturn open", "}")
        val hash = "b1bc9c91a8cc51f3b0cd715540473b6191cdc8efcc69f33bf890d9de4affed84"
        assertEquals(Relocation(RelocationStatus.ACTIVE, 3, 4), Relocator.verifyCommitted(lines, 3, 4, hash))
        assertEquals(Relocation(RelocationStatus.STALE, 2, 3), Relocator.verifyCommitted(lines, 2, 3, hash))
        assertEquals(Relocation(RelocationStatus.UNVERIFIED, 2, 2), Relocator.verifyCommitted(lines, 2, 2, ""))
        assertEquals(Relocation(RelocationStatus.ORPHANED, 3, 4), Relocator.verifyCommitted(null, 3, 4, hash))
        assertEquals("past the end is stale and clamped", Relocation(RelocationStatus.STALE, 7, 7), Relocator.verifyCommitted(lines, 9, 12, hash))
    }

    @Test
    fun `git blob ids of working bytes`() {
        assertEquals("e69de29bb2d1d6434b8b29ae775ad8c2e48c5391", Relocator.blobId(ByteArray(0)))
        assertEquals("ce013625030ba8dba906f756967f9e9ca394464a", Relocator.blobId("hello\n".toByteArray()))
    }

    @Test
    fun `splitting file bytes into lines matches git's line numbering`() {
        assertEquals(listOf("a", "b"), Relocator.lines("a\nb\n".toByteArray()))
        assertEquals(listOf("a", "b"), Relocator.lines("a\nb".toByteArray()))
        assertEquals(listOf("a", "b"), Relocator.lines("a\r\nb\r\n".toByteArray()))
        assertEquals(emptyList<String>(), Relocator.lines(ByteArray(0)))
    }
}
