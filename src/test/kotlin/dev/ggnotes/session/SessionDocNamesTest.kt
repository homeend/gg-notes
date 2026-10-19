package dev.ggnotes.session

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

/** Session-docs SPEC §3.1 through gg's `id-vectors.json`: ids, file names, and what a reader ignores. */
class SessionDocNamesTest {

    private val vectors = JsonParser.parseString(Files.readString(Paths.get("docs/gg-session-docs/id-vectors.json"))).asJsonObject

    @Test
    fun `valid and invalid ids`() {
        for (id in vectors.getAsJsonArray("valid")) assertTrue(id.asString, SessionDocNames.isValidId(id.asString))
        for (id in vectors.getAsJsonArray("invalid")) assertFalse(id.asString, SessionDocNames.isValidId(id.asString))
    }

    @Test
    fun `a file name is its id and kind`() {
        for (n in vectors.getAsJsonArray("names")) {
            val o = n.asJsonObject
            val kind = if (o.get("kind").asString == "overview") DocKind.OVERVIEW else DocKind.NOTE
            assertEquals(DocName(o.get("id").asString, kind), SessionDocNames.parse(o.get("name").asString))
            assertEquals(o.get("name").asString, SessionDocNames.fileName(o.get("id").asString, kind))
        }
    }

    @Test
    fun `entries a reader ignores`() {
        for (n in vectors.getAsJsonArray("ignored")) assertNull(n.asString, SessionDocNames.parse(n.asString))
    }
}
