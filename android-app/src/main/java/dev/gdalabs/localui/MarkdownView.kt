// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 GDA Labs
package dev.gdalabs.localui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

@Composable
fun MarkdownView(source: String, modifier: Modifier = Modifier) {
    val document = remember(source) { MarkdownReader.parse(source) }
    MarkdownBlocks(document.blocks, modifier)
}

@Composable
private fun RichText(content: RichContent, modifier: Modifier = Modifier, heading: Int = 0) {
    val colors = MaterialTheme.colorScheme
    val value = remember(content, colors) {
        buildAnnotatedString {
            append(content.text)
            for (range in content.marks) {
                val style = when (range.mark) {
                    TextMark.BOLD -> SpanStyle(fontWeight = FontWeight.SemiBold)
                    TextMark.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                    TextMark.CODE -> SpanStyle(fontFamily = FontFamily.Monospace, background = colors.surfaceVariant.copy(alpha = .6f))
                    TextMark.STRIKE -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                    TextMark.LINK -> SpanStyle(color = colors.primary, textDecoration = TextDecoration.Underline)
                }
                addStyle(style, range.start, range.end)
                if (range.mark == TextMark.LINK && range.target != null)
                    addLink(LinkAnnotation.Url(range.target), range.start, range.end)
            }
        }
    }
    Text(value, modifier, style = when (heading) {
        1 -> MaterialTheme.typography.headlineSmall
        2 -> MaterialTheme.typography.titleLarge
        in 3..6 -> MaterialTheme.typography.titleMedium
        else -> MaterialTheme.typography.bodyLarge
    }, fontWeight = if (heading > 0) FontWeight.SemiBold else null)
}

@Composable
private fun MarkdownBlocks(blocks: List<ContentBlock>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (block in blocks) when (block) {
            is ContentBlock.Paragraph -> RichText(block.content)
            is ContentBlock.Heading -> RichText(block.content, Modifier.padding(top = 5.dp), block.level)
            is ContentBlock.Code -> Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f)) {
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(block.language.ifBlank { "Code" }, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Code", block.text)) }) { Text("Copy") }
                    }
                    Text(block.text, Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(14.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                }
            }
            is ContentBlock.Quote -> Row(Modifier.fillMaxWidth()) {
                Box(Modifier.width(3.dp).heightIn(min = 26.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = .45f)))
                MarkdownBlocks(block.blocks, Modifier.padding(start = 12.dp).weight(1f))
            }
            is ContentBlock.Listing -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (item in block.items) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(item.marker, Modifier.widthIn(min = 14.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyLarge)
                    MarkdownBlocks(item.blocks, Modifier.weight(1f))
                }
            }
            is ContentBlock.Table -> Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .35f)) {
                Column(Modifier.horizontalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (block.headers.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { block.headers.forEach { RichText(it.copy(marks = it.marks + MarkRange(0, it.text.length, TextMark.BOLD)), Modifier.width(150.dp)) } }
                        HorizontalDivider()
                    }
                    block.rows.forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { row.forEach { RichText(it, Modifier.width(150.dp)) } } }
                }
            }
            ContentBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 6.dp))
            is ContentBlock.Item -> MarkdownBlocks(block.blocks)
        }
    }
}
