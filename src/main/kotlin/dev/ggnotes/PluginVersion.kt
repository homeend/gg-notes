package dev.ggnotes

import java.util.Properties

/**
 * This plugin's version (`0.4.4+<sha>`), as the build stamped it into
 * `dev/ggnotes/version.properties`. The IDE's own lookups by plugin id or
 * class are internal API from 2026.2, so the plugin carries the answer.
 */
object PluginVersion {

    val current: String by lazy {
        runCatching {
            PluginVersion::class.java.getResourceAsStream("version.properties")?.use { input ->
                Properties().apply { load(input) }.getProperty("version")
            }
        }.getOrNull()?.takeIf { it.isNotBlank() && !it.startsWith("\${") } ?: "dev"
    }
}
