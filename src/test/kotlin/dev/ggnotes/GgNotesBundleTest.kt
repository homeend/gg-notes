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
        assertEquals("AgentNotes loaded for project demo", GgNotesBundle.message("startup.ready", "demo"))
    }

    @Test
    fun `the product is AgentNotes in every text the user sees`() {
        assertEquals("AgentNotes", GgNotesBundle.message("plugin.name"))
        assertEquals("AgentNotes", GgNotesBundle.message("group.GgNotes.Toolbar.text"))
        assertEquals("AgentNotes", GgNotesBundle.message("notification.group.name"))
        // keys keep their GgNotes spelling (action and tool window ids are frozen); no VALUE names the old product
        val old = loadBundle().filterValues { "GgNotes" in it }
        assertEquals("values still naming GgNotes: $old", emptyMap<String, String>(), old)
    }

    @Test
    fun `the descriptor names AgentNotes and keeps the id`() {
        val xml = javaClass.getResourceAsStream("/META-INF/plugin.xml")!!.readBytes().toString(Charsets.UTF_8)
        assertTrue(xml.contains("<name>AgentNotes</name>"))
        assertTrue("the id stays, so the new zip replaces the installed plugin", xml.contains("<id>dev.ggnotes</id>"))
        assertTrue(Regex("""<notificationGroup id="GgNotes"[^>]*key="notification.group.name"""").containsMatchIn(xml))
    }

    @Test
    fun `the tool window is shown as Overviews while its id stays GgNotes`() {
        // the platform reads the stripe title of tool window <id> from the plugin bundle key toolwindow.stripe.<id>
        assertEquals("Overviews", GgNotesBundle.message("toolwindow.stripe.GgNotes"))
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
