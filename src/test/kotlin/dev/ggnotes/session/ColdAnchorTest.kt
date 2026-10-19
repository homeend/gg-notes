package dev.ggnotes.session

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

/** SPEC §4.3 through gg's `cold-anchor-cases.json`: the first placement of a note from its lines alone. */
class ColdAnchorTest {

    @Test
    fun `every case of the corpus`() {
        val cases = JsonParser.parseString(Files.readString(Paths.get("docs/gg-session-docs/cold-anchor-cases.json"))).asJsonArray
        assertEquals(10, cases.size())
        for (c in cases) {
            val case = c.asJsonObject
            val file = case.getAsJsonArray("file").map { it.asString }
            val note = case.getAsJsonObject("note")
            val expect = case.getAsJsonObject("expect")
            val got = ColdAnchor.place(file, note.get("start").asInt, note.get("end").asInt, note.getAsJsonArray("lines").map { it.asString })
            assertEquals(case.get("name").asString,
                Placement(expect.get("start").asInt, expect.get("end").asInt, expect.get("outdated").asBoolean), got)
        }
    }

    @Test
    fun `a file with no lines gives an outdated note at 1-1`() {
        assertEquals(Placement(1, 1, true), ColdAnchor.place(null, 5, 6, listOf("e", "f")))
        assertEquals(Placement(1, 1, true), ColdAnchor.place(emptyList(), 5, 6, listOf("e", "f")))
    }
}
