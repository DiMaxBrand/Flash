package com.example.flash.ui.update

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit

/**
 * A small, deliberately non-exhaustive Markdown renderer for exactly one job: showing this app's
 * own GitHub release descriptions (see CLAUDE.md's release-notes convention — `## Что нового` /
 * `## What's new` / `## Developer notes` headers, `` `code` `` spans, occasional bold and italic
 * text) inside ReleaseNotesSection (UpdatesScreen.kt) as something more than raw
 * Markdown source with literal `#`/`*` characters showing. Not a general CommonMark
 * implementation — headers, inline code, bold, and italic cover everything this app's own release
 * notes actually use; nothing else (tables, links, images, nested/ordered lists, block quotes) is
 * attempted.
 *
 * Body text stays at whatever size the caller's own text style already sets — [baseFontSize] is
 * only used to scale headers relative to it, by design (only headers get bigger/bolder).
 */
fun markdownToAnnotatedString(
    markdown: String,
    baseFontSize: TextUnit,
    codeColor: Color,
    codeBackground: Color,
): AnnotatedString = buildAnnotatedString {
    val lines = markdown.split("\n")
    lines.forEachIndexed { index, rawLine ->
        val headerMatch = HEADER_PATTERN.matchEntire(rawLine)
        val bulletMatch = if (headerMatch == null) BULLET_PATTERN.matchEntire(rawLine) else null
        when {
            headerMatch != null -> {
                // #### and deeper collapse to the same (smallest) heading size as ### — this
                // app's own release notes never go past ##, but headers arriving from unrelated
                // Markdown text (a pasted changelog, say) shouldn't blow up or look identical to
                // body text either.
                val level = headerMatch.groupValues[1].length.coerceAtMost(3)
                val size = when (level) {
                    1 -> baseFontSize * 1.45f
                    2 -> baseFontSize * 1.25f
                    else -> baseFontSize * 1.1f
                }
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = size)) {
                    appendInline(headerMatch.groupValues[2], codeColor, codeBackground)
                }
            }
            bulletMatch != null -> {
                append("•  ")
                appendInline(bulletMatch.groupValues[1], codeColor, codeBackground)
            }
            else -> appendInline(rawLine, codeColor, codeBackground)
        }
        if (index != lines.lastIndex) append("\n")
    }
}

private val HEADER_PATTERN = Regex("^(#{1,6})\\s+(.*)$")
private val BULLET_PATTERN = Regex("^[-*]\\s+(.*)$")

// Single combined pattern (rather than three separate ones raced against each other) so matches
// are found left-to-right in one pass with no ambiguity about which style "wins" at a given
// position: group 1 = inline code, group 2 = bold, group 3 = italic. The italic alternative
// requires a non-word/non-marker character on both sides so `**bold**` doesn't also get read as
// two adjacent italic markers.
private val INLINE_PATTERN = Regex(
    "`([^`]+)`" +
        "|\\*\\*([^*]+)\\*\\*" +
        "|(?<![*\\w])[*_]([^*_]+)[*_](?![*\\w])",
)

private fun AnnotatedString.Builder.appendInline(text: String, codeColor: Color, codeBackground: Color) {
    var last = 0
    for (match in INLINE_PATTERN.findAll(text)) {
        if (match.range.first > last) append(text.substring(last, match.range.first))
        val code = match.groups[1]?.value
        val bold = match.groups[2]?.value
        val italic = match.groups[3]?.value
        when {
            code != null -> withStyle(
                SpanStyle(fontFamily = FontFamily.Monospace, color = codeColor, background = codeBackground),
            ) { append(code) }
            bold != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold) }
            italic != null -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(italic) }
        }
        last = match.range.last + 1
    }
    if (last < text.length) append(text.substring(last))
}
