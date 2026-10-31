// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 GDA Labs
package dev.gdalabs.localui

import org.junit.Assert.*
import org.junit.Test

class MarkdownContentTest {
    @Test fun boldAndItalicAreStylesNotVisibleMarkers() {
        val block = MarkdownReader.parse("**太字** and *italic*").blocks.single() as ContentBlock.Paragraph
        assertEquals("太字 and italic", block.content.text)
        assertTrue(block.content.marks.any { it.mark == TextMark.BOLD && block.content.text.substring(it.start, it.end) == "太字" })
        assertTrue(block.content.marks.any { it.mark == TextMark.ITALIC })
    }
    @Test fun fencedCodePreservesLiteralStarsAndIndentation() {
        val block = MarkdownReader.parse("```kotlin\n  val stars = \"**literal**\"\n```").blocks.single() as ContentBlock.Code
        assertEquals("  val stars = \"**literal**\"", block.text)
        assertEquals("kotlin", block.language)
    }
    @Test fun inlineCodeIsNotParsedAgain() {
        val block = MarkdownReader.parse("Use `2 ** 3` here.").blocks.single() as ContentBlock.Paragraph
        assertEquals("Use 2 ** 3 here.", block.content.text)
        assertEquals(listOf(TextMark.CODE), block.content.marks.map { it.mark })
    }
    @Test fun multiplicationAndExponentTextStayIntact() {
        assertEquals("2 * 3 and x ** 2", MarkdownReader.parse("2 * 3 and x ** 2").plainText)
    }
    @Test fun headingsAndBulletListsHaveStructure() {
        val doc = MarkdownReader.parse("## Heading\n\n- one\n- two")
        assertEquals(2, (doc.blocks.first() as ContentBlock.Heading).level)
        assertEquals("Heading\n\n• one\n• two", doc.plainText)
    }
    @Test fun orderedListsKeepTheirStartingNumber() {
        assertEquals("7. one\n8. two", MarkdownReader.parse("7. one\n8. two").plainText)
    }
    @Test fun tablesDoNotDisplaySeparatorSyntax() {
        val table = MarkdownReader.parse("| A | B |\n| --- | --- |\n| 1 | 2 |").blocks.single() as ContentBlock.Table
        assertEquals(listOf("A", "B"), table.headers.map { it.text })
        assertEquals(listOf("1", "2"), table.rows.single().map { it.text })
    }
    @Test fun escapedMarkersStayLiteral() { assertEquals("*literal*", MarkdownReader.parse("\\*literal\\*").plainText) }
    @Test fun incompleteStreamingMarkerDoesNotLoseText() { assertEquals("**hello", MarkdownReader.parse("**hello").plainText) }
    @Test fun nestedEmphasisKeepsBothStyles() {
        val p = MarkdownReader.parse("**bold and *italic***").blocks.single() as ContentBlock.Paragraph
        assertEquals("bold and italic", p.content.text)
        assertTrue(p.content.marks.any { it.mark == TextMark.BOLD }); assertTrue(p.content.marks.any { it.mark == TextMark.ITALIC })
    }
    @Test fun onlyWebLinksAreActionable() {
        val good = MarkdownReader.parse("[Site](https://example.com)").blocks.single() as ContentBlock.Paragraph
        val bad = MarkdownReader.parse("[App](javascript:alert(1))").blocks.single() as ContentBlock.Paragraph
        assertEquals("https://example.com", good.content.marks.single().target)
        assertNull(bad.content.marks.single().target)
    }
    @Test fun imagesAreOnlyLocalTextDescriptions() { assertEquals("[Image: picture]", MarkdownReader.parse("![picture](https://example.com/image.png)").plainText) }
    @Test fun htmlRemainsTextInsteadOfExecuting() { assertEquals("<b>hello</b>", MarkdownReader.parse("<b>hello</b>").plainText) }
    @Test fun deeplyNestedQuotesKeepTheirContent() { assertTrue(MarkdownReader.parse("> ".repeat(80) + "body").plainText.contains("body")) }
    @Test fun emptyMessageIsValid() { assertTrue(MarkdownReader.parse("").blocks.isEmpty()) }
    @Test fun strikeThroughHasAStyle() {
        val p = MarkdownReader.parse("~~old~~").blocks.single() as ContentBlock.Paragraph
        assertEquals("old", p.content.text); assertEquals(TextMark.STRIKE, p.content.marks.single().mark)
    }
    @Test fun reasoningAndAnswerCanBeDisplayedSeparately() {
        val result = presentReply("<think>work</think>\n**Answer**", true)
        assertEquals("work", result.reasoning); assertEquals("**Answer**", result.answer); assertFalse(result.awaitingAnswer)
    }
    @Test fun reasoningCutoffIsNotPresentedAsAFinalAnswer() {
        val result = presentReply("Still working", true)
        assertTrue(result.awaitingAnswer); assertEquals("", result.answer); assertEquals("Still working", result.reasoning)
    }
    @Test fun codeContainingProtocolTagsIsPreserved() {
        val raw = "```xml\n</think>\n```"
        assertEquals(raw, presentReply(raw, false).answer)
        val inline = "Use `</think>` in this example."
        assertEquals(inline, presentReply(inline, false).answer)
        val double = "Use ``</think>`` in this example."
        assertEquals(double, presentReply(double, false).answer)
    }
    @Test fun leakedReasoningDelimiterIsSeparated() {
        val result = presentReply("work</think>Final", false)
        assertEquals("work", result.reasoning); assertEquals("Final", result.answer)
    }
    @Test fun largerBundledVariantIsPreferredAfterAnUpgrade() {
        val small = UiModel("small", "Q4", "AD-Q4_K_M", 1132208000)
        val large = UiModel("large", "Q6", "AD-Q6_K", 1587969920)
        assertEquals(large, preferredModel(listOf(small, large), "AD-Q6_K"))
    }
    @Test fun modelChoiceHandlesMissingOrEmptyVariants() {
        val model = UiModel("one", "Model", "Q2_0", 100)
        assertEquals(model, preferredModel(listOf(model), "missing"))
        assertNull(preferredModel(emptyList(), "AD-Q6_K"))
    }
}
