package dev.wimmboo.omni.ui.markdown

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wimmboo.omni.data.model.SearchSource
import dev.wimmboo.omni.ui.icons.OmniIcons
import dev.wimmboo.omni.ui.theme.InterFontFamily
import dev.wimmboo.omni.ui.theme.JetBrainsMonoFontFamily
import dev.wimmboo.omni.ui.theme.OmniColorPalette
import dev.wimmboo.omni.ui.theme.OmniTheme
import kotlinx.coroutines.delay
import java.net.URI

private val HR_RE = Regex("^([-*_])\\s*\\1\\s*\\1[\\s\\1]*$")
private val HEADING_RE = Regex("^(#{1,3})\\s+(.+)$")
private val BULLET_MATCH_RE = Regex("^[-*+]\\s+.+$")
private val BULLET_EXTRACT_RE = Regex("^\\s*[-*+]\\s+(.+)$")
private val ORDERED_MATCH_RE = Regex("^\\d+\\.\\s+.+$")
private val ORDERED_EXTRACT_RE = Regex("^\\s*(\\d+)\\.\\s+(.+)$")
private val TABLE_SEP_CELL_RE = Regex("^\\s*:?-{2,}:?\\s*$")
private val CONTROL_CHARS_RE = Regex("[\\u0000-\\u001F\\u007F]")

private sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class CodeBlock(val language: String, val code: String, val isClosed: Boolean) : MdBlock()
    data class Blockquote(val lines: List<String>) : MdBlock()
    data class BulletList(val items: List<String>) : MdBlock()
    data class OrderedList(val items: List<Pair<String, String>>) : MdBlock()
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MdBlock()
    data object HorizontalRule : MdBlock()
    data class Paragraph(val text: String) : MdBlock()
}

private fun parseMarkdownBlocks(raw: String): List<MdBlock> {
    if (raw.isEmpty()) return emptyList()
    val lines = raw.replace("\r\n", "\n").split('\n')
    val blocks = mutableListOf<MdBlock>()
    var i = 0

    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        if (trimmed.isEmpty()) {
            i++
            continue
        }

        // Fenced code block (including unclosed during streaming)
        if (trimmed.startsWith("```")) {
            val lang = trimmed.removePrefix("```").trim().takeWhile { it.isLetterOrDigit() || it in "_-+#." }
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trim().startsWith("```")) {
                codeLines += lines[i]
                i++
            }
            val isClosed = i < lines.size && lines[i].trim().startsWith("```")
            if (isClosed) i++
            blocks += MdBlock.CodeBlock(
                language = lang.ifEmpty { "code" },
                code = codeLines.joinToString("\n"),
                isClosed = isClosed,
            )
            continue
        }

        // Horizontal rule
        if (HR_RE.matches(trimmed)) {
            blocks += MdBlock.HorizontalRule
            i++
            continue
        }

        // Headings
        val hMatch = HEADING_RE.find(trimmed)
        if (hMatch != null) {
            blocks += MdBlock.Heading(hMatch.groupValues[1].length, hMatch.groupValues[2].trim())
            i++
            continue
        }

        // Table (header + separator row)
        if (trimmed.contains('|') && i + 1 < lines.size && isTableSeparator(lines[i + 1])) {
            val headers = splitTableRow(line)
            i += 2
            val rows = mutableListOf<List<String>>()
            while (i < lines.size && lines[i].trim().isNotEmpty() && lines[i].contains('|')) {
                rows += splitTableRow(lines[i])
                i++
            }
            blocks += MdBlock.Table(headers, rows)
            continue
        }

        // Blockquote
        if (trimmed.startsWith(">")) {
            val qLines = mutableListOf<String>()
            while (i < lines.size && lines[i].trim().startsWith(">")) {
                qLines += lines[i].trim().removePrefix(">").trimStart()
                i++
            }
            blocks += MdBlock.Blockquote(qLines)
            continue
        }

        // Unordered list
        if (BULLET_MATCH_RE.matches(trimmed)) {
            val items = mutableListOf<String>()
            while (i < lines.size) {
                val m = BULLET_EXTRACT_RE.find(lines[i]) ?: break
                items += m.groupValues[1]
                i++
            }
            blocks += MdBlock.BulletList(items)
            continue
        }

        // Ordered list
        if (ORDERED_MATCH_RE.matches(trimmed)) {
            val items = mutableListOf<Pair<String, String>>()
            while (i < lines.size) {
                val m = ORDERED_EXTRACT_RE.find(lines[i]) ?: break
                items += m.groupValues[1] to m.groupValues[2]
                i++
            }
            blocks += MdBlock.OrderedList(items)
            continue
        }

        // Paragraph (accumulates consecutive plain lines)
        val pLines = mutableListOf<String>()
        while (i < lines.size) {
            val cur = lines[i]
            val ct = cur.trim()
            if (ct.isEmpty() ||
                ct.startsWith("```") ||
                ct.startsWith(">") ||
                HEADING_RE.matches(ct) ||
                BULLET_MATCH_RE.matches(ct) ||
                ORDERED_MATCH_RE.matches(ct) ||
                HR_RE.matches(ct) ||
                (ct.contains('|') && i + 1 < lines.size && isTableSeparator(lines[i + 1]))
            ) {
                break
            }
            pLines += cur
            i++
        }
        if (pLines.isNotEmpty()) {
            blocks += MdBlock.Paragraph(pLines.joinToString("\n"))
        } else {
            i++
        }
    }

    return blocks
}

private fun isTableSeparator(line: String): Boolean {
    val t = line.trim().removePrefix("|").removeSuffix("|")
    if (t.isBlank()) return false
    return t.split('|').all { cell -> TABLE_SEP_CELL_RE.matches(cell) }
}

private fun splitTableRow(line: String): List<String> =
    line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

private fun sanitizeHref(raw: String): String? {
    val clean = raw.replace(CONTROL_CHARS_RE, "").trim()
    if (clean.isEmpty()) return null
    return try {
        val uri = URI(clean)
        val scheme = uri.scheme?.lowercase()
        if (scheme == "http" || scheme == "https" || scheme == "mailto") clean else null
    } catch (_: Exception) {
        null
    }
}

private val INLINE_TOKEN_RE = Regex(
    "`([^`]+)`|" +
        "\\*\\*\\*(.+?)\\*\\*\\*|" +
        "\\*\\*(.+?)\\*\\*|" +
        "(?<!\\*)\\*([^*\\n]+?)\\*(?!\\*)|" +
        "~~(.+?)~~|" +
        "\\[([^\\]]+)\\]\\(([^)]+)\\)|" +
        "\\[(\\d+)\\]",
)

@Composable
fun OmniMarkdown(
    text: String,
    streaming: Boolean,
    sources: List<SearchSource>,
    onCitationClick: (Int) -> Unit,
    onPreviewCode: (String, String) -> Unit,
    onDownloadCode: (String, String) -> Unit,
    onCopyToast: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = OmniTheme.colors
    val blocks = remember(text) { parseMarkdownBlocks(text) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        blocks.forEachIndexed { idx, block ->
            val isLast = idx == blocks.lastIndex
            when (block) {
                is MdBlock.Heading -> {
                    val size = when (block.level) {
                        1 -> 19.5.sp
                        2 -> 17.sp
                        else -> 15.8.sp
                    }
                    InlineRichText(
                        raw = block.text,
                        style = TextStyle(
                            fontFamily = InterFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = size,
                            lineHeight = size * 1.35f,
                            letterSpacing = (-0.3).sp,
                            color = colors.text,
                        ),
                        sources = if (streaming) emptyList() else sources,
                        showCursor = streaming && isLast,
                        onCitationClick = onCitationClick,
                    )
                }

                is MdBlock.CodeBlock -> {
                    CodeBlockView(
                        language = block.language,
                        code = block.code,
                        activeStreamingBlock = streaming && isLast && !block.isClosed,
                        onPreview = { onPreviewCode(block.code, block.language) },
                        onDownload = { onDownloadCode(block.code, block.language) },
                        onCopyToast = onCopyToast,
                    )
                }

                is MdBlock.Blockquote -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min)
                            .padding(vertical = 2.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .width(2.dp)
                                .fillMaxHeight()
                                .background(colors.accent, RoundedCornerShape(1.dp)),
                        )
                        Spacer(Modifier.width(12.dp))
                        InlineRichText(
                            raw = block.lines.joinToString("\n"),
                            style = TextStyle(
                                fontFamily = InterFontFamily,
                                fontSize = 15.sp,
                                lineHeight = 25.sp,
                                color = colors.textSecondary,
                            ),
                            sources = if (streaming) emptyList() else sources,
                            showCursor = streaming && isLast,
                            onCitationClick = onCitationClick,
                        )
                    }
                }

                is MdBlock.BulletList -> {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        block.items.forEachIndexed { itemIdx, item ->
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "\u2022",
                                    color = colors.textSecondary,
                                    fontSize = 15.sp,
                                    lineHeight = 25.sp,
                                    modifier = Modifier.padding(start = 6.dp, end = 8.dp),
                                )
                                InlineRichText(
                                    raw = item,
                                    style = TextStyle(
                                        fontFamily = InterFontFamily,
                                        fontSize = 15.sp,
                                        lineHeight = 25.sp,
                                        color = colors.text,
                                    ),
                                    sources = if (streaming) emptyList() else sources,
                                    showCursor = streaming && isLast && itemIdx == block.items.lastIndex,
                                    onCitationClick = onCitationClick,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }

                is MdBlock.OrderedList -> {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        block.items.forEachIndexed { itemIdx, (num, item) ->
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "$num.",
                                    color = colors.textSecondary,
                                    fontSize = 15.sp,
                                    lineHeight = 25.sp,
                                    modifier = Modifier.padding(start = 2.dp, end = 8.dp),
                                )
                                InlineRichText(
                                    raw = item,
                                    style = TextStyle(
                                        fontFamily = InterFontFamily,
                                        fontSize = 15.sp,
                                        lineHeight = 25.sp,
                                        color = colors.text,
                                    ),
                                    sources = if (streaming) emptyList() else sources,
                                    showCursor = streaming && isLast && itemIdx == block.items.lastIndex,
                                    onCitationClick = onCitationClick,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }

                is MdBlock.Table -> {
                    MarkdownTableView(block = block, colors = colors)
                }

                MdBlock.HorizontalRule -> {
                    HorizontalDivider(
                        color = colors.borderSoft,
                        thickness = 1.dp,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }

                is MdBlock.Paragraph -> {
                    InlineRichText(
                        raw = block.text,
                        style = TextStyle(
                            fontFamily = InterFontFamily,
                            fontSize = 15.sp,
                            lineHeight = 25.5.sp,
                            color = colors.text,
                        ),
                        sources = if (streaming) emptyList() else sources,
                        showCursor = streaming && isLast,
                        onCitationClick = onCitationClick,
                    )
                }
            }
        }
    }
}

@Composable
private fun StreamingCursorBox(color: Color) {
    val inf = rememberInfiniteTransition(label = "streamCursor")
    val cursorAlpha by inf.animateFloat(
        initialValue = 0.7f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(500),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "cursorBlink",
    )
    Box(
        modifier = Modifier
            .padding(start = 2.dp)
            .size(width = 6.dp, height = 14.dp)
            .graphicsLayer { alpha = cursorAlpha }
            .clip(RoundedCornerShape(1.dp))
            .background(color),
    )
}

@Composable
private fun InlineRichText(
    raw: String,
    style: TextStyle,
    sources: List<SearchSource>,
    showCursor: Boolean,
    onCitationClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = OmniTheme.colors
    val uriHandler = LocalUriHandler.current

    val (annotated, inlineMap) = remember(raw, style, sources, showCursor, colors) {
        val map = mutableMapOf<String, InlineTextContent>()
        val text = buildAnnotatedString {
            var cursor = 0
            for (m in INLINE_TOKEN_RE.findAll(raw)) {
                if (m.range.first > cursor) {
                    append(raw.substring(cursor, m.range.first))
                }
                when {
                    m.groups[1] != null -> {
                        // `inline code`
                        pushStyle(
                            SpanStyle(
                                fontFamily = JetBrainsMonoFontFamily,
                                fontSize = style.fontSize * 0.88f,
                                background = colors.bgInlineCode,
                                color = colors.text,
                            ),
                        )
                        append(" ${m.groups[1]!!.value} ")
                        pop()
                    }
                    m.groups[2] != null -> {
                        pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold, fontStyle = FontStyle.Italic))
                        append(m.groups[2]!!.value)
                        pop()
                    }
                    m.groups[3] != null -> {
                        pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold))
                        append(m.groups[3]!!.value)
                        pop()
                    }
                    m.groups[4] != null -> {
                        pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                        append(m.groups[4]!!.value)
                        pop()
                    }
                    m.groups[5] != null -> {
                        pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                        append(m.groups[5]!!.value)
                        pop()
                    }
                    m.groups[6] != null && m.groups[7] != null -> {
                        val label = m.groups[6]!!.value
                        val href = m.groups[7]!!.value
                        val numericLink = label.trim().toIntOrNull()
                        if (numericLink != null && numericLink in 1..sources.size) {
                            val chipId = "cite_${numericLink}_${m.range.first}"
                            appendInlineContent(chipId, "[$numericLink]")
                            map[chipId] = buildCitationInlineContent(
                                index = numericLink,
                                source = sources[numericLink - 1],
                                colors = colors,
                                onClick = { onCitationClick(numericLink) },
                            )
                        } else {
                            val safe = sanitizeHref(href)
                            if (safe != null) {
                                pushLink(
                                    LinkAnnotation.Url(
                                        url = safe,
                                        styles = TextLinkStyles(
                                            style = SpanStyle(
                                                color = colors.accent,
                                                textDecoration = TextDecoration.None,
                                            ),
                                        ),
                                        linkInteractionListener = {
                                            try { uriHandler.openUri(safe) } catch (_: Exception) {}
                                        },
                                    ),
                                )
                                append(label)
                                pop()
                            } else {
                                append(m.value)
                            }
                        }
                    }
                    m.groups[8] != null -> {
                        val n = m.groups[8]!!.value.toIntOrNull()
                        if (n != null && n in 1..sources.size) {
                            val chipId = "cite_${n}_${m.range.first}"
                            appendInlineContent(chipId, "[$n]")
                            map[chipId] = buildCitationInlineContent(
                                index = n,
                                source = sources[n - 1],
                                colors = colors,
                                onClick = { onCitationClick(n) },
                            )
                        } else {
                            append(m.value)
                        }
                    }
                }
                cursor = m.range.last + 1
            }
            if (cursor < raw.length) {
                append(raw.substring(cursor))
            }
            if (showCursor) {
                appendInlineContent("stream_cursor", " ")
            }
        }
        if (showCursor) {
            map["stream_cursor"] = InlineTextContent(
                placeholder = Placeholder(
                    width = 8.sp,
                    height = 15.sp,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                ),
            ) {
                StreamingCursorBox(color = colors.accent)
            }
        }
        text to map
    }

    SelectionContainer(modifier = modifier) {
        Text(
            text = annotated,
            style = style,
            inlineContent = inlineMap,
        )
    }
}

private fun buildCitationInlineContent(
    index: Int,
    source: SearchSource,
    colors: OmniColorPalette,
    onClick: () -> Unit,
): InlineTextContent {
    val labelText = (source.title.ifBlank { source.domain.ifBlank { source.url } }).take(24)
    val approxWidthSp = (36 + labelText.length * 6.2f).coerceIn(54f, 165f)
    return InlineTextContent(
        placeholder = Placeholder(
            width = approxWidthSp.sp,
            height = 20.sp,
            placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
        ),
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 2.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(colors.accentSoft)
                .clickable(onClick = onClick)
                .padding(start = 5.dp, end = 6.dp, top = 1.dp, bottom = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = index.toString(),
                color = colors.accent,
                fontFamily = InterFontFamily,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Text(
                text = labelText,
                color = colors.accent.copy(alpha = 0.92f),
                fontFamily = InterFontFamily,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(
                imageVector = OmniIcons.ExternalLink,
                contentDescription = null,
                tint = colors.accent.copy(alpha = 0.75f),
                modifier = Modifier.size(9.dp),
            )
        }
    }
}

private val PLAIN_STREAM_CODE_STYLE = SpanStyle(color = Color(0xFFDCDCDC))

@Composable
private fun CodeBlockView(
    language: String,
    code: String,
    activeStreamingBlock: Boolean,
    onPreview: () -> Unit,
    onDownload: () -> Unit,
    onCopyToast: (String) -> Unit,
) {
    val colors = OmniTheme.colors
    val clipboard = LocalClipboardManager.current
    var wrapEnabled by remember { mutableStateOf(true) }
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1600)
            copied = false
        }
    }

    val highlighted = remember(code, language, activeStreamingBlock) {
        if (activeStreamingBlock && code.length > 600) {
            AnnotatedString(code, PLAIN_STREAM_CODE_STYLE)
        } else {
            CodeHighlighter.highlight(code, language)
        }
    }
    val previewable = remember(language) { CodeHighlighter.isPreviewable(language) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.bgCodeBlock)
            .border(1.dp, colors.border, RoundedCornerShape(12.dp)),
    ) {
        // Header matching .code-block-header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.bgCodeHeader)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = language.lowercase(),
                color = colors.accent.copy(alpha = 0.9f),
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (previewable) {
                    CodeHeaderActionButton(
                        icon = OmniIcons.Play,
                        label = "Preview",
                        tint = colors.textSecondary,
                        onClick = onPreview,
                    )
                }
                CodeHeaderActionButton(
                    icon = OmniIcons.Wrap,
                    label = "Wrap",
                    tint = if (wrapEnabled) colors.accent else colors.textSecondary,
                    onClick = { wrapEnabled = !wrapEnabled },
                )
                CodeHeaderActionButton(
                    icon = OmniIcons.Download,
                    label = "Download",
                    tint = colors.textSecondary,
                    onClick = onDownload,
                )
                CodeHeaderActionButton(
                    icon = if (copied) OmniIcons.Check else OmniIcons.Copy,
                    label = if (copied) "Copied!" else "Copy",
                    tint = if (copied) colors.success else colors.textSecondary,
                    onClick = {
                        clipboard.setText(AnnotatedString(code))
                        copied = true
                        onCopyToast("Copied code")
                    },
                )
            }
        }

        HorizontalDivider(color = colors.border, thickness = 1.dp)

        // Code body
        val hScroll = rememberScrollState()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (!wrapEnabled) Modifier.horizontalScroll(hScroll) else Modifier)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            SelectionContainer {
                Text(
                    text = highlighted,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 12.5.sp,
                    lineHeight = 19.5.sp,
                    softWrap = wrapEnabled,
                )
            }
        }
    }
}

@Composable
private fun CodeHeaderActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(12.dp),
        )
        Text(
            text = label,
            color = tint,
            fontFamily = InterFontFamily,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun MarkdownTableView(block: MdBlock.Table, colors: OmniColorPalette) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, colors.borderSoft, RoundedCornerShape(10.dp))
            .horizontalScroll(scroll),
    ) {
        // Header row
        Row(
            modifier = Modifier.background(colors.bgCodeHeader),
        ) {
            block.headers.forEach { h ->
                Text(
                    text = h,
                    color = colors.text,
                    fontFamily = InterFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .widthIn(min = 96.dp, max = 220.dp)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }
        HorizontalDivider(color = colors.borderSoft, thickness = 1.dp)
        block.rows.forEachIndexed { rIdx, row ->
            Row {
                block.headers.indices.forEach { cIdx ->
                    Text(
                        text = row.getOrNull(cIdx).orEmpty(),
                        color = colors.textSecondary,
                        fontFamily = InterFontFamily,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .widthIn(min = 96.dp, max = 220.dp)
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                    )
                }
            }
            if (rIdx < block.rows.lastIndex) {
                HorizontalDivider(color = colors.border, thickness = 1.dp)
            }
        }
    }
}
