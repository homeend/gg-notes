package dev.ggnotes

import com.intellij.openapi.util.IconLoader

object GgNotesIcons {
    /** Referenced by FQN from plugin.xml (`icon=` attribute) — needs @JvmField. */
    @JvmField
    val GgNotes = IconLoader.getIcon("/icons/ggNotes.svg", GgNotesIcons::class.java)

    /** 13 px stripe icon for the tool window; referenced from plugin.xml. */
    @JvmField
    val ToolWindow = IconLoader.getIcon("/icons/ggNotesToolWindow.svg", GgNotesIcons::class.java)
}
