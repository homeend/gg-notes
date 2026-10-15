package dev.ggnotes

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.GgNotesBundle"

/** All user-visible strings. The IDE's display language (JetBrains language
 * packs) picks the locale; [DynamicBundle] resolves it at lookup time. */
object GgNotesBundle {
    private val INSTANCE = DynamicBundle(GgNotesBundle::class.java, BUNDLE)

    @JvmStatic
    @Nls
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String =
        INSTANCE.getMessage(key, *params)
}
