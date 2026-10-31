// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 GDA Labs
package dev.gdalabs.localui

import org.commonmark.Extension
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.*
import org.commonmark.node.*
import org.commonmark.parser.Parser
import java.net.URI

enum class TextMark { BOLD, ITALIC, CODE, STRIKE, LINK }
data class MarkRange(val start: Int, val end: Int, val mark: TextMark, val target: String? = null)
data class RichContent(val text: String, val marks: List<MarkRange> = emptyList())
sealed interface ContentBlock {
    data class Paragraph(val content: RichContent) : ContentBlock
    data class Heading(val level: Int, val content: RichContent) : ContentBlock
    data class Code(val text: String, val language: String = "") : ContentBlock
    data class Quote(val blocks: List<ContentBlock>) : ContentBlock
    data class Item(val marker: String, val blocks: List<ContentBlock>) : ContentBlock
    data class Listing(val items: List<Item>) : ContentBlock
    data class Table(val headers: List<RichContent>, val rows: List<List<RichContent>>) : ContentBlock
    data object Rule : ContentBlock
}
data class MarkdownContent(val blocks: List<ContentBlock>) {
    val plainText: String get() = blocks.joinToString("\n\n") { plain(it) }
    private fun plain(block: ContentBlock): String = when (block) {
        is ContentBlock.Paragraph -> block.content.text
        is ContentBlock.Heading -> block.content.text
        is ContentBlock.Code -> block.text
        is ContentBlock.Quote -> block.blocks.joinToString("\n", transform = ::plain)
        is ContentBlock.Item -> block.marker + " " + block.blocks.joinToString("\n", transform = ::plain)
        is ContentBlock.Listing -> block.items.joinToString("\n", transform = ::plain)
        is ContentBlock.Table -> (listOf(block.headers) + block.rows).joinToString("\n") { row -> row.joinToString("\t") { it.text } }
        ContentBlock.Rule -> "────────"
    }
}

/** Parses locally to display data; HTML is never executed and images are never fetched. */
object MarkdownReader {
    private val extensions: List<Extension> = listOf(TablesExtension.create(), StrikethroughExtension.create())
    private val parser = Parser.builder().extensions(extensions).build()
    fun parse(text: String): MarkdownContent = MarkdownContent(blocks(parser.parse(text), 0))
    private fun children(node: Node): List<Node> = buildList {
        var child = node.firstChild
        while (child != null) { add(child); child = child.next }
    }
    private fun blocks(node: Node, depth: Int): List<ContentBlock> {
        if (depth > 24) return listOf(ContentBlock.Paragraph(RichContent(flatText(node))))
        return when (node) {
            is Document -> children(node).flatMap { blocks(it, depth + 1) }
            is Paragraph -> listOf(ContentBlock.Paragraph(inline(node)))
            is Heading -> listOf(ContentBlock.Heading(node.level, inline(node)))
            is FencedCodeBlock -> listOf(ContentBlock.Code(node.literal.removeSuffix("\n"), node.info.orEmpty().substringBefore(' ').take(40)))
            is IndentedCodeBlock -> listOf(ContentBlock.Code(node.literal.removeSuffix("\n")))
            is BlockQuote -> listOf(ContentBlock.Quote(children(node).flatMap { blocks(it, depth + 1) }))
            is BulletList -> listOf(ContentBlock.Listing(children(node).map { ContentBlock.Item("•", children(it).flatMap { c -> blocks(c, depth + 1) }) }))
            is OrderedList -> {
                val start = node.markerStartNumber ?: 1
                listOf(ContentBlock.Listing(children(node).mapIndexed { index, item ->
                    ContentBlock.Item("${start + index}.", children(item).flatMap { blocks(it, depth + 1) })
                }))
            }
            is TableBlock -> {
                val head = children(node).filterIsInstance<TableHead>().flatMap(::children)
                val body = children(node).filterIsInstance<TableBody>().flatMap(::children)
                listOf(ContentBlock.Table(head.firstOrNull()?.let { children(it).map(::inline) }.orEmpty(), body.map { row -> children(row).map(::inline) }))
            }
            is ThematicBreak -> listOf(ContentBlock.Rule)
            is HtmlBlock -> listOf(ContentBlock.Paragraph(RichContent(node.literal.trimEnd())))
            else -> children(node).flatMap { blocks(it, depth + 1) }
        }
    }
    private fun inline(parent: Node): RichContent {
        val text = StringBuilder()
        val marks = mutableListOf<MarkRange>()
        fun visit(node: Node, depth: Int) {
            if (depth > 32) { text.append(flatText(node)); return }
            val start = text.length
            when (node) {
                is Text -> text.append(node.literal)
                is Code -> { text.append(node.literal); marks += MarkRange(start, text.length, TextMark.CODE) }
                is SoftLineBreak, is HardLineBreak -> text.append('\n')
                is HtmlInline -> text.append(node.literal)
                is Image -> { text.append("[Image: "); text.append(flatText(node).ifBlank { "image" }); text.append(']') }
                else -> {
                    children(node).forEach { visit(it, depth + 1) }
                    val kind = when (node) { is StrongEmphasis -> TextMark.BOLD; is Emphasis -> TextMark.ITALIC; is Strikethrough -> TextMark.STRIKE; is Link -> TextMark.LINK; else -> null }
                    if (kind != null && text.length > start) marks += MarkRange(start, text.length, kind,
                        if (node is Link && safeLink(node.destination)) node.destination else null)
                }
            }
        }
        children(parent).forEach { visit(it, 0) }
        return RichContent(text.toString(), marks)
    }
    private fun flatText(node: Node): String {
        val stack = java.util.ArrayDeque<Node>(); stack.push(node)
        return buildString {
            while (stack.isNotEmpty()) {
                val item = stack.pop()
                when (item) {
                    is Text -> append(item.literal)
                    is Code -> append(item.literal)
                    is HtmlInline -> append(item.literal)
                    is HtmlBlock -> append(item.literal)
                    is FencedCodeBlock -> append(item.literal)
                    is IndentedCodeBlock -> append(item.literal)
                    is SoftLineBreak, is HardLineBreak -> append('\n')
                    else -> children(item).asReversed().forEach(stack::push)
                }
            }
        }
    }
    private fun safeLink(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null
    }.getOrDefault(false)
}
