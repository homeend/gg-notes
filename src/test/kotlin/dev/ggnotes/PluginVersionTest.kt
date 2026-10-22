package dev.ggnotes

import org.junit.Assert.assertTrue
import org.junit.Test

/** The plugin's own version, as the build stamped it (presence `plugin`), without the IDE's plugin registry. */
class PluginVersionTest {

    @Test
    fun `the build stamps the project version`() {
        val v = PluginVersion.current
        assertTrue("got '$v'", Regex("""\d+\.\d+\.\d+\+[0-9a-z]+""").matches(v))
    }
}
