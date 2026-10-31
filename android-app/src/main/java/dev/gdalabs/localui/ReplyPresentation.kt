// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 GDA Labs
package dev.gdalabs.localui

data class ReplyPresentation(val answer: String, val reasoning: String = "", val awaitingAnswer: Boolean = false)

/** Separates model protocol tags without interpreting tag-like text inside code. */
fun presentReply(raw: String, reasoningMode: Boolean): ReplyPresentation {
    var fenceChar: Char? = null
    var fenceLength = 0
    var inlineLength = 0
    var offset = 0
    var close = -1
    for (line in raw.split('\n')) {
        val trimmed = line.trimStart()
        val fence = trimmed.firstOrNull()?.takeIf { it == '`' || it == '~' }
        val run = if (fence != null) trimmed.takeWhile { it == fence }.length else 0
        val boundary = run >= 3
        if (boundary && fenceChar == null) { fenceChar = fence; fenceLength = run }
        else if (boundary && fence == fenceChar && run >= fenceLength && trimmed.drop(run).isBlank()) fenceChar = null
        else if (fenceChar == null) {
            var i = 0
            while (i < line.length) {
                if (line[i] == '`' && (i == 0 || line[i - 1] != '\\')) {
                    val count = line.substring(i).takeWhile { it == '`' }.length
                    if (inlineLength == 0) inlineLength = count else if (inlineLength == count) inlineLength = 0
                    i += count; continue
                }
                if (inlineLength == 0 && line.startsWith("</think>", i) && (i == 0 || line[i - 1] != '\\')) { close = offset + i; break }
                i++
            }
        }
        if (close >= 0) break
        offset += line.length + 1
    }
    if (close >= 0) return ReplyPresentation(raw.substring(close + 8).trimStart(), raw.substring(0, close).trimStart().removePrefix("<think>").trim())
    if (reasoningMode || raw.trimStart().startsWith("<think>"))
        return ReplyPresentation("", raw.trimStart().removePrefix("<think>").trim(), true)
    return ReplyPresentation(raw)
}
