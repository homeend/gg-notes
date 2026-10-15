package dev.ggnotes.store

import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.LeafASTNode
import org.intellij.markdown.ast.accept
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.ast.impl.ListCompositeNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.html.GeneratingProvider
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.html.InlineHolderGeneratingProvider
import org.intellij.markdown.html.TransparentInlineHolderProvider
import org.intellij.markdown.parser.LinkMap
import org.intellij.markdown.parser.MarkdownParser

/** One anchor of a rendered document: its 1-based number in document order, its plain label, its target. */
data class RenderedAnchor(val index: Int, val label: String, val target: LinkTarget.Anchor)

/** The document as HTML for the tool window plus the anchors it contains (SPEC §6: the first 100). */
data class RenderedDocument(val html: String, val anchors: List<RenderedAnchor>)

/**
 * SPEC §6–§7: GFM markdown → HTML with gg's link rules. Anchors become
 * `<a href="anchor:N">label<sup>N</sup></a>` — numbered in document order
 * as gg draws them — preceded by `<a name="aN">` for scrolling; a
 * missing anchor is a `<span class="missing">`; the current one carries
 * `class="current"`. Every newline inside a paragraph is a hard break and
 * raw HTML is literal text.
 */
object MarkdownRenderer {

    const val MAX_ANCHORS = 100

    // character classes only: an alternation inside a repeated group recurses per
    // character in java.util.regex and overflows the stack on a long prose review
    private val INLINE_LINK = Regex("""!?\[([^\[\]]{0,2000})\]\(([^)\s]{1,2000})(\s+"[^"]*")?\)""")

    /**
     * SPEC-part2 §3.2: a review's summary (and a prose review) has NO anchors.
     * Every inline link whose destination would be an anchor becomes
     * `label (dest)` text before rendering; http(s) links stay links.
     */
    fun neutralizeAnchors(markdown: String): String = INLINE_LINK.replace(markdown) { m ->
        when (LinkClassifier.classify(m.groupValues[2])) {
            is LinkTarget.Anchor -> m.groupValues[1] + " (" + m.groupValues[2] + ")"
            else -> m.value
        }
    }

    fun render(markdown: String, currentAnchor: Int? = null, missingAnchors: Set<Int> = emptySet()): RenderedDocument {
        val flavour = GFMFlavourDescriptor()
        val tree = MarkdownParser(flavour).buildMarkdownTreeFromString(markdown)
        val session = Session(markdown, currentAnchor, missingAnchors)
        val providers = flavour.createHtmlGeneratingProviders(LinkMap.buildLinkMap(tree, markdown), null) + session.overrides()
        val html = HtmlGenerator(markdown, tree, providers, false).generateHtml()
        return RenderedDocument(html, session.anchors)
    }

    fun escape(s: CharSequence): String = buildString(s.length) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            else -> append(c)
        }
    }

    private val ESCAPED_PUNCT = Regex("""\\([!-/:-@\[-`{-~])""")
    private val LABEL_MARKUP = Regex("""[`*_~]""")

    private class Session(val text: String, val current: Int?, val missing: Set<Int>) {
        val anchors = ArrayList<RenderedAnchor>()
        private val labelProvider = TransparentInlineHolderProvider(1, -1)

        fun overrides(): Map<IElementType, GeneratingProvider> = mapOf(
            MarkdownTokenTypes.EOL to EolProvider,
            MarkdownElementTypes.HTML_BLOCK to HtmlBlockAsText,
            MarkdownTokenTypes.HTML_TAG to HtmlTagAsText,
            MarkdownElementTypes.INLINE_LINK to LinkProvider(image = false),
            MarkdownElementTypes.IMAGE to LinkProvider(image = true),
            MarkdownElementTypes.AUTOLINK to AutolinkProvider,
            MarkdownElementTypes.LIST_ITEM to ListItemProvider,
        )

        /** A newline inside a paragraph is a hard break (GitHub-comment style); elsewhere it is nothing. */
        object EolProvider : GeneratingProvider {
            override fun processNode(visitor: HtmlGenerator.HtmlGeneratingVisitor, text: String, node: ASTNode) {
                if (node.parent?.type == MarkdownElementTypes.PARAGRAPH) visitor.consumeHtml("<br />")
                else visitor.consumeHtml("\n")
            }
        }

        object HtmlBlockAsText : GeneratingProvider {
            override fun processNode(visitor: HtmlGenerator.HtmlGeneratingVisitor, text: String, node: ASTNode) {
                val raw = node.getTextInNode(text).toString().trimEnd('\n')
                visitor.consumeHtml("<p>" + escape(raw).replace("\n", "<br />") + "</p>\n")
            }
        }

        object HtmlTagAsText : GeneratingProvider {
            override fun processNode(visitor: HtmlGenerator.HtmlGeneratingVisitor, text: String, node: ASTNode) {
                visitor.consumeHtml(escape(node.getTextInNode(text)))
            }
        }

        /** `<http://…>` stays a link only when it is a clean http(s) URL; anything else is its text. */
        object AutolinkProvider : GeneratingProvider {
            override fun processNode(visitor: HtmlGenerator.HtmlGeneratingVisitor, text: String, node: ASTNode) {
                val raw = node.getTextInNode(text).toString()
                val inner = raw.removePrefix("<").removeSuffix(">")
                when (val t = LinkClassifier.classify(inner)) {
                    is LinkTarget.Url -> visitor.consumeHtml("<a href=\"${escape(t.url)}\">${escape(inner)}</a>")
                    else -> visitor.consumeHtml(escape(inner))
                }
            }
        }

        /** Like the GFM list item, with ☐/☑ text instead of `<input>` and tight paragraphs unwrapped. */
        object ListItemProvider : GeneratingProvider {
            override fun processNode(visitor: HtmlGenerator.HtmlGeneratingVisitor, text: String, node: ASTNode) {
                val checkBox = node.findChildOfType(GFMTokenTypes.CHECK_BOX)
                val prefix = checkBox?.getTextInNode(text)?.let { if (it.length > 1 && it[1] != ' ') "☑ " else "☐ " } ?: ""
                visitor.consumeTagOpen(node, "li")
                val loose = (node.parent as? ListCompositeNode)?.loose ?: false
                var prefixed = false
                for (child in node.children) {
                    if (child is LeafASTNode) continue
                    if (!prefixed) {
                        if (child.type == MarkdownElementTypes.PARAGRAPH) {
                            ItemParagraph(loose, prefix).processNode(visitor, text, child)
                        } else {
                            visitor.consumeHtml(prefix)
                            child.accept(visitor)
                        }
                        prefixed = true
                    } else {
                        child.accept(visitor)
                    }
                }
                visitor.consumeTagClose("li")
            }

            class ItemParagraph(val wrap: Boolean, val prefix: String) : InlineHolderGeneratingProvider() {
                override fun openTag(visitor: HtmlGenerator.HtmlGeneratingVisitor, text: String, node: ASTNode) {
                    if (wrap) visitor.consumeTagOpen(node, "p")
                    visitor.consumeHtml(prefix)
                }

                override fun closeTag(visitor: HtmlGenerator.HtmlGeneratingVisitor, text: String, node: ASTNode) {
                    if (wrap) visitor.consumeTagClose("p")
                }
            }
        }

        inner class LinkProvider(val image: Boolean) : GeneratingProvider {
            override fun processNode(visitor: HtmlGenerator.HtmlGeneratingVisitor, text: String, node: ASTNode) {
                val link = if (image) node.findChildOfType(MarkdownElementTypes.INLINE_LINK) else node
                val labelNode = link?.findChildOfType(MarkdownElementTypes.LINK_TEXT)
                if (link == null || labelNode == null) {
                    // a reference-style link or image: no destination gg would store; show its text
                    visitor.consumeHtml(escape(node.getTextInNode(text)))
                    return
                }
                val destination = rawDestination(link)
                val plainLabel = plainLabel(labelNode)
                fun label() {
                    if (image) visitor.consumeHtml(escape(plainLabel)) else labelProvider.processNode(visitor, text, labelNode)
                }
                when (val target = LinkClassifier.classify(destination)) {
                    is LinkTarget.LabelOnly -> label()
                    is LinkTarget.Url -> {
                        visitor.consumeHtml("<a href=\"${escape(target.url)}\">")
                        label()
                        visitor.consumeHtml("</a>")
                    }
                    is LinkTarget.Text -> {
                        label()
                        visitor.consumeHtml(" (" + escape(target.dest) + ")")
                    }
                    is LinkTarget.Anchor -> {
                        if (anchors.size >= MAX_ANCHORS) {
                            label() // the 101st and later: the label alone (SPEC §6)
                            return
                        }
                        val n = anchors.size + 1
                        anchors += RenderedAnchor(n, plainLabel, target)
                        visitor.consumeHtml("<a name=\"a$n\"></a>")
                        if (n in missing) {
                            visitor.consumeHtml("<span class=\"missing\">")
                            label()
                            visitor.consumeHtml("<sup>$n</sup></span>")
                        } else {
                            val cls = if (n == current) " class=\"current\"" else ""
                            visitor.consumeHtml("<a href=\"anchor:$n\"$cls>")
                            label()
                            visitor.consumeHtml("<sup>$n</sup></a>")
                        }
                    }
                }
            }

            private fun rawDestination(link: ASTNode): String {
                val raw = link.findChildOfType(MarkdownElementTypes.LINK_DESTINATION)?.getTextInNode(text)?.toString() ?: ""
                val unwrapped = if (raw.length >= 2 && raw.startsWith("<") && raw.endsWith(">")) raw.substring(1, raw.length - 1) else raw
                return ESCAPED_PUNCT.replace(unwrapped, "$1")
            }

            private fun plainLabel(labelNode: ASTNode): String {
                val inner = labelNode.getTextInNode(text).toString().removePrefix("[").removeSuffix("]")
                return LABEL_MARKUP.replace(ESCAPED_PUNCT.replace(inner, "$1"), "").replace(Regex("\\s+"), " ").trim()
            }
        }
    }
}
