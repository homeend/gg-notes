package dev.ggnotes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.text.MessageFormat
import java.util.PropertyResourceBundle

/** Guards the bundle: the keys the code uses exist and every value survives MessageFormat. */
class GgNotesBundleTest {

    private fun loadBundle(): Map<String, String> {
        val stream = javaClass.getResourceAsStream("/messages/GgNotesBundle.properties")
            ?: error("bundle not on classpath")
        return InputStreamReader(stream, StandardCharsets.UTF_8).use { reader ->
            val bundle = PropertyResourceBundle(reader)
            bundle.keys.toList().associateWith { bundle.getString(it) }
        }
    }

    @Test
    fun `startup message formats the project name`() {
        assertEquals("GgNotes loaded for project demo", GgNotesBundle.message("startup.ready", "demo"))
    }

    @Test
    fun `the tree's context menu says Delete on every row`() {
        assertEquals("Delete", GgNotesBundle.message("action.GgNotes.RemoveFromStore.text"))
        assertEquals("Delete", GgNotesBundle.message("action.GgNotes.RemoveFromSession.text"))
        assertEquals("Delete", GgNotesBundle.message("remove.confirm.title"))
    }

    @Test
    fun `every value is non-blank and well-formed`() {
        val bundle = loadBundle()
        assertTrue("default bundle is empty", bundle.isNotEmpty())
        for ((key, value) in bundle) {
            assertTrue("blank value for $key", value.isNotBlank())
            if ('{' in value) MessageFormat(value) // throws if malformed
        }
    }
}
