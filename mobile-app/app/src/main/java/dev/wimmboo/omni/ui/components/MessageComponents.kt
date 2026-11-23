package dev.wimmboo.omni.ui.components

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wimmboo.omni.data.model.Chat
import dev.wimmboo.omni.data.model.ChatMessage
import dev.wimmboo.omni.data.model.SearchData
import dev.wimmboo.omni.data.model.ToolStep
import dev.wimmboo.omni.ui.SendOptions
import dev.wimmboo.omni.ui.icons.OmniIcons
import dev.wimmboo.omni.ui.markdown.OmniMarkdown
import dev.wimmboo.omni.ui.theme.InterFontFamily
import dev.wimmboo.omni.ui.theme.JetBrainsMonoFontFamily
import dev.wimmboo.omni.ui.theme.OmniTheme
import kotlinx.coroutines.delay
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

@Composable
fun MessageRow(
    msg: ChatMessage,
    msgIndex: Int,
    chat: Chat,
    isStreaming: Boolean,
    liveStatusLabel: String?,
    effortTiers: List<String>?,
    onEditUserMessage: () -> Unit,
    onRegenerate: (SendOptions) -> Unit,
    onCycleBranch: (Int) -> Unit,
    onToggleSources: () -> Unit,
    onExpandSources: () -> Unit,
    onOpenSettings: () -> Unit,
    onPreviewCode: (String, String) -> Unit,
    onDownloadCode: (String, String) -> Unit,
    onShowToast: (String) -> Unit,
) {
    val colors = OmniTheme.colors
    val isUser = msg.role == "user"

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // .message-avatar
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .size(28.dp)
                .clip(CircleShape)
                .background(if (isUser) colors.bgElevated else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isUser) OmniIcons.User else OmniIcons.Spark,
                contentDescription = null,
                tint = if (isUser) colors.textMuted else colors.accent,
                modifier = Modifier.size(18.dp),
            )
        }

        // .message-body
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (isUser) "You" else "Omni",
                color = colors.text,
                fontFamily = InterFontFamily,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.15).sp,
                modifier = Modifier.padding(bottom = 5.dp),
            )

            if (isUser) {
                UserMessageContent(msg = msg)
                UserMessageActions(
                    content = msg.content,
                    onEdit = onEditUserMessage,
                    onCopied = { onShowToast("Copied") },
                )
            } else {
                // Live thinking row when streaming and no visible answer yet
                if (isStreaming && msg.content.isEmpty()) {
                    ThinkingLiveRow(baseLabel = liveStatusLabel ?: msg.initialLabel ?: "Thinking.")
                }

                // Collapsible Thought process block
                if (msg.thinking.isNotEmpty()) {
                    ThinkingDetailsBlock(
                        thinkingText = msg.thinking,
                        initiallyOpen = !msg.thinkingComplete,
                        thinkingComplete = msg.thinkingComplete,
                    )
                }

                // Agent tool-call steps
                if (!msg.steps.isNullOrEmpty()) {
                    ToolStepsBlock(
                        steps = msg.steps,
                        onPreviewCode = onPreviewCode,
                        onDownloadCode = onDownloadCode,
                        onCopyToast = onShowToast,
                    )
                }

                // Markdown body
                if (msg.content.isNotEmpty()) {
                    OmniMarkdown(
                        text = msg.content,
                        streaming = isStreaming,
                        sources = msg.search?.sources ?: emptyList(),
                        onCitationClick = { onExpandSources() },
                        onPreviewCode = onPreviewCode,
                        onDownloadCode = onDownloadCode,
                        onCopyToast = onShowToast,
                    )
                }

                // Search sources footer or error note
                val search = msg.search
                if (search != null && search.sources.isNotEmpty()) {
                    SourcesFooter(
                        search = search,
                        open = msg.sourcesOpen,
                        onToggle = onToggleSources,
                    )
                } else if (search != null && !search.error.isNullOrEmpty() && msg.content.isNotEmpty()) {
                    SearchErrorNote(
                        error = search.error,
                        showSettingsLink = search.code == "no_key" || search.code == "auth",
                        onOpenSettings = onOpenSettings,
                    )
                }

                // Assistant actions bar (Copy, Branch switcher, Regenerate menu — NO TTS!)
                if (!isStreaming && (msg.content.isNotEmpty() || !msg.steps.isNullOrEmpty())) {
                    val parentIdx = msgIndex - 1
                    val branchList = if (parentIdx >= 0) chat.branches[parentIdx.toString()] else null
                    val branchCount = branchList?.size ?: 0
                    val activeBranchIdx = if (branchCount > 0) {
                        (chat.branchActive[parentIdx.toString()] ?: branchCount).coerceIn(0, branchCount)
                    } else 0

                    AssistantMessageActions(
                        content = msg.content,
                        usedWebSearch = !msg.search?.sources.isNullOrEmpty(),
                        branchCount = branchCount,
                        activeBranchIdx = activeBranchIdx,
                        effortTiers = effortTiers,
                        onCycleBranch = onCycleBranch,
                        onRegenerate = onRegenerate,
                        onCopied = { onShowToast("Copied") },
                    )
                }
            }
        }
    }
}

@Composable
private fun UserMessageContent(msg: ChatMessage) {
    val colors = OmniTheme.colors
    val imageUrls = remember(msg.apiContent) { msg.extractImageDataUrls() }
    val shape = RoundedCornerShape(topStart = 6.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 18.dp)

    Column(
        modifier = Modifier
            .clip(shape)
            .background(colors.bgUserBubble)
            .padding(horizontal = 15.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        imageUrls.forEach { dataUrl ->
            val bitmap = remember(dataUrl) { decodeDataUrlBitmap(dataUrl) }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "Attached image",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .widthIn(max = 260.dp)
                        .heightIn(max = 260.dp)
                        .clip(RoundedCornerShape(10.dp)),
                )
            }
        }
        if (msg.content.isNotEmpty()) {
            SelectionContainer {
                Text(
                    text = msg.content,
                    color = colors.text,
                    fontFamily = InterFontFamily,
                    fontSize = 15.sp,
                    lineHeight = 24.5.sp,
                )
            }
        }
    }
}

@Composable
private fun UserMessageActions(
    content: String,
    onEdit: () -> Unit,
    onCopied: () -> Unit,
) {
    val colors = OmniTheme.colors
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1400)
            copied = false
        }
    }

    Row(
        modifier = Modifier.padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MsgActionButton(
            icon = if (copied) OmniIcons.Check else OmniIcons.Copy,
            contentDescription = "Copy message",
            tint = if (copied) colors.success else colors.textSecondary,
            onClick = {
                clipboard.setText(AnnotatedString(content))
                copied = true
                onCopied()
            },
        )
        MsgActionButton(
            icon = OmniIcons.Edit,
            contentDescription = "Edit message",
            tint = colors.textSecondary,
            onClick = onEdit,
        )
    }
}

@Composable
private fun AssistantMessageActions(
    content: String,
    usedWebSearch: Boolean,
    branchCount: Int,
    activeBranchIdx: Int,
    effortTiers: List<String>?,
    onCycleBranch: (Int) -> Unit,
    onRegenerate: (SendOptions) -> Unit,
    onCopied: () -> Unit,
) {
    val colors = OmniTheme.colors
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    var regenMenuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1400)
            copied = false
        }
    }

    Row(
        modifier = Modifier.padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MsgActionButton(
            icon = if (copied) OmniIcons.Check else OmniIcons.Copy,
            contentDescription = "Copy message",
            tint = if (copied) colors.success else colors.textSecondary,
            onClick = {
                clipboard.setText(AnnotatedString(content))
                copied = true
                onCopied()
            },
        )

        if (branchCount > 0) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 2.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onCycleBranch(-1) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = OmniIcons.ChevronLeft,
                        contentDescription = "Previous response",
                        tint = colors.textSecondary,
                        modifier = Modifier.size(13.dp),
                    )
                }
                Text(
                    text = "${activeBranchIdx + 1}/${branchCount + 1}",
                    color = colors.textMuted,
                    fontFamily = InterFontFamily,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onCycleBranch(1) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = OmniIcons.ChevronRight,
                        contentDescription = "Next response",
                        tint = colors.textSecondary,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
        }

        // Regenerate dropdown (exact parity with web; Read Aloud intentionally excluded)
        Box {
            MsgActionButton(
                icon = OmniIcons.Retry,
                contentDescription = "Regenerate response",
                tint = colors.textSecondary,
                onClick = { regenMenuOpen = true },
            )
            DropdownMenu(
                expanded = regenMenuOpen,
                onDismissRequest = { regenMenuOpen = false },
                containerColor = colors.bgElevated,
                shape = RoundedCornerShape(12.dp),
            ) {
                RegenDropdownRow(
                    icon = OmniIcons.Retry,
                    label = "Regenerate",
                    onClick = {
                        regenMenuOpen = false
                        onRegenerate(SendOptions())
                    },
                )
                RegenDropdownRow(
                    icon = OmniIcons.Search,
                    label = if (usedWebSearch) "Don't use web search" else "With Web search",
                    onClick = {
                        regenMenuOpen = false
                        onRegenerate(SendOptions(searchMode = if (usedWebSearch) "off" else "on"))
                    },
                )
                RegenDropdownRow(
                    icon = OmniIcons.Longer,
                    label = "Longer",
                    onClick = {
                        regenMenuOpen = false
                        onRegenerate(SendOptions(lengthHint = "long"))
                    },
                )
                RegenDropdownRow(
                    icon = OmniIcons.Shorter,
                    label = "Shorter",
                    onClick = {
                        regenMenuOpen = false
                        onRegenerate(SendOptions(lengthHint = "short"))
                    },
                )
                if (!effortTiers.isNullOrEmpty()) {
                    RegenDropdownRow(
                        icon = OmniIcons.Spark,
                        label = "Think harder",
                        onClick = {
                            regenMenuOpen = false
                            onRegenerate(SendOptions(effort = effortTiers.last()))
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RegenDropdownRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val colors = OmniTheme.colors
    DropdownMenuItem(
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = colors.textMuted,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = label,
                    color = colors.text,
                    fontFamily = InterFontFamily,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        },
        onClick = onClick,
    )
}

@Composable
private fun MsgActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(15.dp),
        )
    }
}

@Composable
private fun ThinkingLiveRow(baseLabel: String) {
    val colors = OmniTheme.colors
    val inf = rememberInfiniteTransition(label = "thinkPulse")
    val dotAlpha by inf.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "thinkDotAlpha",
    )
    val shouldAnimateDots = baseLabel.endsWith(".") && !baseLabel.endsWith("\u2026")
    val cleanBase = baseLabel.trimEnd('.')
    var dotCount by remember(baseLabel) { mutableIntStateOf(1) }

    LaunchedEffect(baseLabel) {
        if (!shouldAnimateDots) return@LaunchedEffect
        while (true) {
            delay(450)
            dotCount = (dotCount % 3) + 1
        }
    }

    val shown = if (shouldAnimateDots) cleanBase + ".".repeat(dotCount) else baseLabel

    Row(
        modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .alpha(dotAlpha)
                .clip(CircleShape)
                .background(colors.accent),
        )
        Text(
            text = shown,
            color = colors.textMuted,
            fontFamily = InterFontFamily,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun ThinkingDetailsBlock(
    thinkingText: String,
    initiallyOpen: Boolean,
    thinkingComplete: Boolean,
) {
    val colors = OmniTheme.colors
    var expanded by remember { mutableStateOf(initiallyOpen) }

    LaunchedEffect(thinkingComplete) {
        if (thinkingComplete) expanded = false
    }

    val chevronAngle by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(200),
        label = "thinkChevron",
    )

    Column(modifier = Modifier.padding(bottom = 12.dp)) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 2.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = OmniIcons.ChevronRight,
                contentDescription = null,
                tint = colors.textFaint,
                modifier = Modifier
                    .size(12.dp)
                    .rotate(chevronAngle),
            )
            Text(
                text = "Thought process",
                color = colors.textFaint,
                fontFamily = InterFontFamily,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                HorizontalDivider(color = colors.border, thickness = 1.dp)
                val scroll = rememberScrollState()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .verticalScroll(scroll)
                        .padding(top = 10.dp, bottom = 2.dp),
                ) {
                    SelectionContainer {
                        Text(
                            text = thinkingText,
                            color = colors.textMuted,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 12.5.sp,
                            lineHeight = 20.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolStepsBlock(
    steps: List<ToolStep>,
    onPreviewCode: (String, String) -> Unit,
    onDownloadCode: (String, String) -> Unit,
    onCopyToast: (String) -> Unit,
) {
    val colors = OmniTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        steps.forEach { step ->
            if (step.text.isNotEmpty()) {
                OmniMarkdown(
                    text = step.text,
                    streaming = false,
                    sources = emptyList(),
                    onCitationClick = {},
                    onPreviewCode = onPreviewCode,
                    onDownloadCode = onDownloadCode,
                    onCopyToast = onCopyToast,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            step.calls.forEachIndexed { idx, call ->
                val result = step.results.getOrNull(idx)
                val failed = result != null && result.content.startsWith("Error:")
                var open by remember { mutableStateOf(false) }

                val label = when (call.name) {
                    "run_python" -> "Ran Python"
                    "run_bash" -> "Ran shell"
                    "write_file" -> "Wrote file"
                    "read_file" -> "Read file"
                    else -> call.name
                }
                val argText = call.args["code"]?.jsonPrimitive?.contentOrNull
                    ?: call.args["script"]?.jsonPrimitive?.contentOrNull
                    ?: call.args["content"]?.jsonPrimitive?.contentOrNull
                    ?: call.args["path"]?.jsonPrimitive?.contentOrNull

                val borderColor = if (failed) Color(0x59EF4444) else colors.borderSoft
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.bgElevated)
                        .border(1.dp, borderColor, RoundedCornerShape(12.dp)),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { open = !open }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = OmniIcons.Code,
                            contentDescription = null,
                            tint = (if (failed) colors.textFaint else colors.textMuted).copy(alpha = 0.75f),
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = label,
                            color = if (failed) colors.textFaint else colors.textMuted,
                            fontFamily = InterFontFamily,
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = OmniIcons.ChevronDown,
                            contentDescription = null,
                            tint = colors.textFaint,
                            modifier = Modifier
                                .size(13.dp)
                                .rotate(if (open) 180f else 0f),
                        )
                    }

                    AnimatedVisibility(visible = open) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            HorizontalDivider(color = colors.borderSoft, thickness = 1.dp)
                            if (!argText.isNullOrEmpty()) {
                                SelectionContainer {
                                    Text(
                                        text = argText,
                                        color = colors.textMuted,
                                        fontFamily = JetBrainsMonoFontFamily,
                                        fontSize = 12.sp,
                                        lineHeight = 18.5.sp,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 260.dp)
                                            .verticalScroll(rememberScrollState())
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                    )
                                }
                            }
                            if (result != null && result.content.isNotEmpty()) {
                                HorizontalDivider(color = colors.borderSoft, thickness = 1.dp)
                                Text(
                                    text = "OUTPUT",
                                    color = colors.textFaint,
                                    fontFamily = InterFontFamily,
                                    fontSize = 11.sp,
                                    letterSpacing = 0.4.sp,
                                    modifier = Modifier.padding(start = 12.dp, top = 8.dp),
                                )
                                SelectionContainer {
                                    Text(
                                        text = result.content,
                                        color = colors.textFaint,
                                        fontFamily = JetBrainsMonoFontFamily,
                                        fontSize = 12.sp,
                                        lineHeight = 18.5.sp,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 260.dp)
                                            .verticalScroll(rememberScrollState())
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourcesFooter(
    search: SearchData,
    open: Boolean,
    onToggle: () -> Unit,
) {
    val colors = OmniTheme.colors
    val uriHandler = LocalUriHandler.current
    val chevronRot by animateFloatAsState(
        targetValue = if (open) 180f else 0f,
        animationSpec = tween(220),
        label = "sourcesChevron",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
    ) {
        HorizontalDivider(color = colors.borderSoft, thickness = 1.dp)
        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onToggle)
                .padding(horizontal = 2.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Icon(
                imageVector = OmniIcons.Link,
                contentDescription = null,
                tint = colors.textMuted.copy(alpha = 0.8f),
                modifier = Modifier.size(13.dp),
            )
            Text(
                text = "SOURCES",
                color = colors.textMuted,
                fontFamily = InterFontFamily,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.7.sp,
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color(0x12FFFFFF))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = search.sources.size.toString(),
                    color = colors.textSecondary,
                    fontFamily = InterFontFamily,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            val meta = when {
                search.cached -> "cached"
                search.credits > 0 -> "${search.credits} ${if (search.credits == 1) "credit" else "credits"}"
                else -> ""
            }
            if (meta.isNotEmpty()) {
                Text(
                    text = meta,
                    color = colors.textFaint,
                    fontFamily = InterFontFamily,
                    fontSize = 10.5.sp,
                )
            }
            Icon(
                imageVector = OmniIcons.ChevronDown,
                contentDescription = null,
                tint = colors.textMuted.copy(alpha = 0.75f),
                modifier = Modifier
                    .size(12.dp)
                    .rotate(chevronRot),
            )
        }

        AnimatedVisibility(
            visible = open,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                search.sources.forEachIndexed { idx, src ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                if (src.url.startsWith("http://") || src.url.startsWith("https://")) {
                                    try { uriHandler.openUri(src.url) } catch (_: Exception) {}
                                }
                            }
                            .padding(horizontal = 6.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = (idx + 1).toString(),
                            color = colors.accent,
                            fontFamily = InterFontFamily,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.widthIn(min = 14.dp),
                        )
                        Text(
                            text = src.title.ifBlank { src.url.ifBlank { "Source" } },
                            color = colors.textSecondary,
                            fontFamily = InterFontFamily,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (src.domain.isNotEmpty()) {
                            Text(
                                text = src.domain,
                                color = colors.textFaint,
                                fontFamily = InterFontFamily,
                                fontSize = 11.sp,
                                maxLines = 1,
                            )
                        }
                        if (src.date.isNotEmpty()) {
                            Text(
                                text = src.date,
                                color = colors.textFaint,
                                fontFamily = InterFontFamily,
                                fontSize = 11.sp,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchErrorNote(
    error: String,
    showSettingsLink: Boolean,
    onOpenSettings: () -> Unit,
) {
    val colors = OmniTheme.colors
    Row(
        modifier = Modifier.padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = error,
            color = colors.textFaint,
            fontFamily = InterFontFamily,
            fontSize = 12.sp,
        )
        if (showSettingsLink) {
            Text(
                text = "Open settings",
                color = colors.accent,
                fontFamily = InterFontFamily,
                fontSize = 12.sp,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable(onClick = onOpenSettings),
            )
        }
    }
}

fun decodeDataUrlBitmap(dataUrl: String): ImageBitmap? {
    return try {
        val comma = dataUrl.indexOf(',')
        val b64 = if (comma >= 0) dataUrl.substring(comma + 1) else dataUrl
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    } catch (_: Exception) {
        null
    }
}
