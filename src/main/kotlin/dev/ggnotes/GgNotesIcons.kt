package dev.ggnotes

import com.intellij.openapi.util.IconLoader

object GgNotesIcons {
    /** Referenced by FQN from plugin.xml (`icon=` attribute) — needs @JvmField. */
    @JvmField
    val GgNotes = IconLoader.getIcon("/icons/ggNotes.svg", GgNotesIcons::class.java)
}
