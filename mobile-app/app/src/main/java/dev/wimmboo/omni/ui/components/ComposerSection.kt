package dev.wimmboo.omni.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wimmboo.omni.data.model.ModelPreset
import dev.wimmboo.omni.data.model.OmniCatalog
import dev.wimmboo.omni.data.model.OmniSettings
import dev.wimmboo.omni.data.model.PendingFile
import dev.wimmboo.omni.data.model.PromptTemplate
import dev.wimmboo.omni.ui.icons.OmniIcons
import dev.wimmboo.omni.ui.theme.InterFontFamily
import dev.wimmboo.omni.ui.theme.JetBrainsMonoFontFamily
import dev.wimmboo.omni.ui.theme.ModeBadgeColors
import dev.wimmboo.omni.ui.theme.OmniTheme

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ComposerCard(
    text: String,
    settings: OmniSettings,
    incognito: Boolean,
    isStreaming: Boolean,
    isEditing: Boolean,
    agentModeOn: Boolean,
    codeMode: Boolean,
    webSearch: Boolean,
    activeTemplate: PromptTemplate?,
    pendingFiles: List<PendingFile>,
    isRecordingVoice: Boolean,
    showFooterNote: Boolean,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onCancelEdit: () -> Unit,
    onApplyEdit: () -> Unit,
    onAttachClick: () -> Unit,
    onRemoveFile: (String) -> Unit,
    onToggleAgent: () -> Unit,
    onApplySlashTemplate: (PromptTemplate) -> Unit,
    onDismissCodeMode: () -> Unit,
    onDismissWebSearch: () -> Unit,
    onDismissTemplate: () -> Unit,
    onSelectModel: (String) -> Unit,
    onSaveCustomModel: (editingId: String?, id: String, name: String, ctx: Int?) -> Unit,
    onDeleteCustomModel: (String) -> Unit,
    onSelectEffort: (String) -> Unit,
    onToggleThinking: (Boolean) -> Unit,
    onMicClick: () -> Unit,
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = OmniTheme.colors
    var modelSheetOpen by remember { mutableStateOf(false) }
    var effortSheetOpen by remember { mutableStateOf(false) }
    var addModelVisible by remember { mutableStateOf(false) }
    var editingCustomModelId by remember { mutableStateOf<String?>(null) }
    var addModelId by remember { mutableStateOf("") }
    var addModelName by remember { mutableStateOf("") }
    var addModelCtx by remember { mutableStateOf("131072") }

    val templates = settings.promptTemplates.ifEmpty { OmniCatalog.DEFAULT_TEMPLATES }
    val slashMatches = remember(text, templates) {
        if (text.startsWith("/")) {
            val q = text.drop(1).lowercase()
            templates.filter { it.key.lowercase().contains(q) || it.label.lowercase().contains(q) }
        } else {
            emptyList()
        }
    }

    val hasPayload = text.trim().isNotEmpty() || pendingFiles.isNotEmpty()
    val effortTiers = remember(settings) { settings.effortTiers() }
    val activeModelName = remember(settings) {
        val id = settings.actualModelId()
        settings.allModelsForComposer().firstOrNull { it.id == id }?.name?.ifBlank { id }
            ?: id.ifBlank { "Model" }
    }
    val agentAvailable = remember(settings) { settings.agentAvailable() }

    Column(modifier = modifier.fillMaxWidth()) {
        // Slash command popup above composer
        AnimatedVisibility(visible = slashMatches.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.bgElevated)
                    .border(1.dp, colors.borderStrong, RoundedCornerShape(14.dp))
                    .padding(4.dp),
            ) {
                slashMatches.forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onApplySlashTemplate(item) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(colors.accentSoft)
                                    .padding(horizontal = 7.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    text = "/${item.key}",
                                    color = colors.accent,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            Text(
                                text = item.label,
                                color = colors.text,
                                fontFamily = InterFontFamily,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = item.hint ?: item.prompt.ifEmpty { "Activate mode" },
                            color = colors.textFaint,
                            fontFamily = InterFontFamily,
                            fontSize = 11.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 130.dp),
                        )
                    }
                }
            }
        }

        // Main .composer rounded card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(colors.bgComposer)
                .border(1.dp, colors.border, RoundedCornerShape(22.dp))
                .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 10.dp),
        ) {
            // Editing message banner
            if (isEditing) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.accentSoft)
                        .border(1.dp, colors.accentSoft, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Editing message",
                        color = colors.accent,
                        fontFamily = InterFontFamily,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CompactPillButton(
                            label = "Cancel",
                            primary = false,
                            onClick = onCancelEdit,
                        )
                        CompactPillButton(
                            label = "Update",
                            primary = true,
                            onClick = onApplyEdit,
                        )
                    }
                }
            }

            // Text input
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 26.dp, max = 160.dp)
                    .padding(horizontal = 2.dp),
            ) {
                if (text.isEmpty()) {
                    Text(
                        text = if (incognito) "Message privately\u2026" else "How can I help you today? (Type / for commands)",
                        color = colors.textFaint,
                        fontFamily = InterFontFamily,
                        fontSize = 15.sp,
                        lineHeight = 22.5.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = onTextChange,
                    textStyle = TextStyle(
                        color = colors.text,
                        fontFamily = InterFontFamily,
                        fontSize = 15.sp,
                        lineHeight = 22.5.sp,
                    ),
                    cursorBrush = SolidColor(colors.accent),
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Pending files list
            if (pendingFiles.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    pendingFiles.forEach { file ->
                        PendingFilePill(
                            file = file,
                            onRemove = { onRemoveFile(file.id) },
                        )
                    }
                }
            }

            // Active mode badges
            val showTemplateBadge = activeTemplate != null && !activeTemplate.enableCodeMode && !activeTemplate.enableWebSearch
            if (codeMode || webSearch || showTemplateBadge) {
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (codeMode) {
                        ActiveModeBadge(
                            icon = OmniIcons.Code,
                            label = "Code mode",
                            style = ModeBadgeColors.purple,
                            onDismiss = onDismissCodeMode,
                        )
                    }
                    if (webSearch) {
                        ActiveModeBadge(
                            icon = OmniIcons.Search,
                            label = "Web search",
                            style = ModeBadgeColors.blue,
                            onDismiss = onDismissWebSearch,
                        )
                    }
                    if (showTemplateBadge) {
                        val tpl = activeTemplate!!
                        ActiveModeBadge(
                            icon = OmniIcons.Spark,
                            label = tpl.label,
                            style = ModeBadgeColors.forName(tpl.color),
                            onDismiss = onDismissTemplate,
                        )
                    }
                }
            }

            // Footer row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    // Attach (+)
                    ComposerSmallIconBtn(
                        icon = OmniIcons.Plus,
                        contentDescription = "Attach file",
                        tint = colors.textFaint,
                        onClick = onAttachClick,
                    )

                    // Agent mode (>_)
                    ComposerSmallIconBtn(
                        icon = OmniIcons.Terminal,
                        contentDescription = "Toggle agent mode",
                        tint = if (agentModeOn && agentAvailable) colors.agentActive else colors.textFaint,
                        background = if (agentModeOn && agentAvailable) colors.agentActiveSoft else Color.Transparent,
                        alpha = if (agentAvailable) 1f else 0.4f,
                        onClick = onToggleAgent,
                    )

                    // Model picker trigger pill
                    Row(
                        modifier = Modifier
                            .height(28.dp)
                            .widthIn(max = 148.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0x0DFFFFFF))
                            .border(1.dp, colors.borderSoft, RoundedCornerShape(16.dp))
                            .clickable { modelSheetOpen = true }
                            .padding(start = 9.dp, end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = activeModelName,
                            color = colors.textSecondary,
                            fontFamily = InterFontFamily,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Icon(
                            imageVector = OmniIcons.ChevronDown,
                            contentDescription = "Switch model",
                            tint = colors.textFaint,
                            modifier = Modifier.size(11.dp),
                        )
                    }

                    // Reasoning effort trigger pill (only when active model supports effort tiers)
                    if (!effortTiers.isNullOrEmpty()) {
                        Row(
                            modifier = Modifier
                                .height(26.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(colors.bgChip)
                                .border(1.dp, colors.borderSoft, RoundedCornerShape(8.dp))
                                .clickable { effortSheetOpen = true }
                                .padding(horizontal = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text(
                                text = OmniCatalog.effortLabel(settings.effort),
                                color = colors.textSecondary,
                                fontFamily = InterFontFamily,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                            )
                            Icon(
                                imageVector = OmniIcons.ChevronDown,
                                contentDescription = "Reasoning effort",
                                tint = colors.textMuted,
                                modifier = Modifier.size(11.dp),
                            )
                        }
                    }
                }

                Spacer(Modifier.width(6.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // Voice dictation mic button
                    val micScale = if (isRecordingVoice) {
                        val inf = rememberInfiniteTransition(label = "micPulse")
                        val sc by inf.animateFloat(
                            initialValue = 1f,
                            targetValue = 1.12f,
                            animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
                            label = "micScale",
                        )
                        sc
                    } else 1f

                    ComposerSmallIconBtn(
                        icon = OmniIcons.Mic,
                        contentDescription = "Voice dictation",
                        tint = if (isRecordingVoice) colors.danger else colors.textFaint,
                        background = if (isRecordingVoice) colors.dangerSoft else Color.Transparent,
                        onClick = onMicClick,
                        modifier = Modifier.scale(micScale),
                    )

                    // Send / Stop circular button
                    val sendEnabled = isStreaming || hasPayload
                    val btnBg = when {
                        isStreaming -> colors.bgElevated
                        hasPayload -> colors.sendReadyBg
                        else -> colors.sendIdleBg
                    }
                    val btnFg = when {
                        isStreaming -> colors.textSecondary
                        hasPayload -> colors.sendReadyFg
                        else -> colors.textMuted
                    }
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(btnBg)
                            .then(
                                if (isStreaming) Modifier.border(1.dp, colors.borderSoft, CircleShape)
                                else Modifier
                            )
                            .alpha(if (sendEnabled) 1f else 0.4f)
                            .clickable(enabled = sendEnabled) {
                                when {
                                    isEditing -> onApplyEdit()
                                    isStreaming -> onStop()
                                    else -> onSend()
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (isStreaming) OmniIcons.Stop else OmniIcons.Send,
                            contentDescription = if (isStreaming) "Stop generating" else "Send message",
                            tint = btnFg,
                            modifier = Modifier.size(if (isStreaming) 13.dp else 15.dp),
                        )
                    }
                }
            }

            // Inline Add / Edit Custom Model drawer
            AnimatedVisibility(visible = addModelVisible) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0x40000000))
                        .border(1.dp, colors.borderSoft, RoundedCornerShape(14.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = if (editingCustomModelId != null) "Edit custom model" else "Add custom model",
                        color = colors.textSecondary,
                        fontFamily = InterFontFamily,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    CompactInputField(
                        value = addModelId,
                        onValueChange = { addModelId = it },
                        placeholder = "Model ID (e.g. gpt-6.1-sol)",
                        mono = true,
                    )
                    CompactInputField(
                        value = addModelName,
                        onValueChange = { addModelName = it },
                        placeholder = "Display name (optional)",
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CompactInputField(
                            value = addModelCtx,
                            onValueChange = { v -> addModelCtx = v.filter { it.isDigit() } },
                            placeholder = "Context window",
                            mono = true,
                            numeric = true,
                            modifier = Modifier.weight(1f),
                        )
                        CompactPillButton(
                            label = "Save",
                            primary = true,
                            onClick = {
                                onSaveCustomModel(editingCustomModelId, addModelId, addModelName, addModelCtx.toIntOrNull())
                                addModelVisible = false
                                editingCustomModelId = null
                                addModelId = ""
                                addModelName = ""
                                addModelCtx = "131072"
                            },
                        )
                        CompactPillButton(
                            label = "Cancel",
                            primary = false,
                            onClick = {
                                addModelVisible = false
                                editingCustomModelId = null
                                addModelId = ""
                                addModelName = ""
                                addModelCtx = "131072"
                            },
                        )
                    }
                }
            }
        }

        // Composer footer note
        if (showFooterNote) {
            val missingConfig = !incognito && (settings.effectiveApiKey().isBlank() || !settings.hasValidEndpoint())
            val noteText = when {
                incognito -> "Incognito chats aren\u2019t stored on this device."
                missingConfig -> "Open Settings to add your API key and endpoint."
                else -> "Omni can make mistakes. Check important info."
            }
            Text(
                text = noteText,
                color = if (missingConfig) colors.accent else colors.textFaint,
                fontFamily = InterFontFamily,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (missingConfig) Modifier.clickable(onClick = onOpenSettings) else Modifier)
                    .padding(top = 8.dp),
            )
        }
    }

    // Model picker Bottom Sheet
    if (modelSheetOpen) {
        ModelPickerBottomSheet(
            settings = settings,
            onDismiss = { modelSheetOpen = false },
            onSelectModel = { id ->
                onSelectModel(id)
                modelSheetOpen = false
            },
            onAddModel = {
                modelSheetOpen = false
                editingCustomModelId = null
                addModelId = ""
                addModelName = ""
                addModelCtx = "131072"
                addModelVisible = true
            },
            onEditCustomModel = { m ->
                modelSheetOpen = false
                editingCustomModelId = m.id
                addModelId = m.id
                addModelName = m.name
                addModelCtx = m.ctx.toString()
                addModelVisible = true
            },
            onDeleteCustomModel = { id -> onDeleteCustomModel(id) },
        )
    }

    // Effort picker Bottom Sheet
    if (effortSheetOpen && !effortTiers.isNullOrEmpty()) {
        EffortBottomSheet(
            tiers = effortTiers,
            selectedTier = settings.effort,
            defaultTier = settings.effortDefault(),
            thinkingEnabled = settings.thinking,
            onSelectTier = { tier ->
                onSelectEffort(tier)
                effortSheetOpen = false
            },
            onToggleThinking = onToggleThinking,
            onDismiss = { effortSheetOpen = false },
        )
    }
}

@Composable
private fun PendingFilePill(
    file: PendingFile,
    onRemove: () -> Unit,
) {
    val colors = OmniTheme.colors
    val thumb = if (file.isImage) rememberDecodedDataUrlBitmap(file.data) else null
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x0FFFFFFF))
            .padding(start = 6.dp, end = 6.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (thumb != null) {
            Image(
                bitmap = thumb,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(18.dp)
                    .clip(RoundedCornerShape(4.dp)),
            )
        }
        Text(
            text = file.name,
            color = colors.textSecondary,
            fontFamily = InterFontFamily,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 140.dp),
        )
        Icon(
            imageVector = OmniIcons.Close,
            contentDescription = "Remove attachment",
            tint = colors.textMuted,
            modifier = Modifier
                .size(14.dp)
                .clickable(onClick = onRemove)
                .padding(1.dp),
        )
    }
}

@Composable
private fun ActiveModeBadge(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    style: ModeBadgeColors.Style,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(style.bg)
            .border(1.dp, style.border, RoundedCornerShape(999.dp))
            .padding(start = 8.dp, end = 6.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = style.fg,
            modifier = Modifier.size(13.dp),
        )
        Text(
            text = label,
            color = style.fg,
            fontFamily = InterFontFamily,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
        Icon(
            imageVector = OmniIcons.Close,
            contentDescription = "Remove $label",
            tint = style.fg.copy(alpha = 0.8f),
            modifier = Modifier
                .size(14.dp)
                .clip(CircleShape)
                .clickable(onClick = onDismiss)
                .padding(1.dp),
        )
    }
}

@Composable
private fun ComposerSmallIconBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    tint: Color,
    background: Color = Color.Transparent,
    alpha: Float = 1f,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .alpha(alpha)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun CompactPillButton(
    label: String,
    primary: Boolean,
    onClick: () -> Unit,
) {
    val colors = OmniTheme.colors
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (primary) colors.sendReadyBg else Color(0x0FFFFFFF))
            .then(
                if (!primary) Modifier.border(1.dp, colors.borderSoft, RoundedCornerShape(9.dp))
                else Modifier
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            color = if (primary) colors.sendReadyFg else colors.textSecondary,
            fontFamily = InterFontFamily,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun CompactInputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    mono: Boolean = false,
    numeric: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = OmniTheme.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.bgInputField)
            .border(1.dp, colors.borderSoft, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                color = colors.textFaint,
                fontFamily = if (mono) JetBrainsMonoFontFamily else InterFontFamily,
                fontSize = 12.sp,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
            textStyle = TextStyle(
                color = colors.text,
                fontFamily = if (mono) JetBrainsMonoFontFamily else InterFontFamily,
                fontSize = 12.sp,
            ),
            cursorBrush = SolidColor(colors.accent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPickerBottomSheet(
    settings: OmniSettings,
    onDismiss: () -> Unit,
    onSelectModel: (String) -> Unit,
    onAddModel: () -> Unit,
    onEditCustomModel: (ModelPreset) -> Unit,
    onDeleteCustomModel: (String) -> Unit,
) {
    val colors = OmniTheme.colors
    var query by remember { mutableStateOf("") }
    val currentModelId = settings.actualModelId()
    val providerCfg = OmniCatalog.getProviderConfig(settings.provider)
    val providerName = providerCfg?.name ?: settings.provider
    val customModels = remember(settings) { settings.customModelsForCurrentProvider() }
    val presetModels = remember(settings) {
        if (settings.provider == "custom") emptyList() else (providerCfg?.models ?: emptyList())
    }
    val q = query.trim().lowercase()
    val filteredCustom = remember(customModels, q) {
        if (q.isEmpty()) customModels
        else customModels.filter { it.name.lowercase().contains(q) || it.id.lowercase().contains(q) }
    }
    val filteredPresets = remember(presetModels, q) {
        if (q.isEmpty()) presetModels
        else presetModels.filter { it.name.lowercase().contains(q) || it.id.lowercase().contains(q) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.bgElevated,
        scrimColor = Color(0x80000000),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .navigationBarsPadding(),
        ) {
            // Search input
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 14.dp),
            ) {
                if (query.isEmpty()) {
                    Text(
                        text = "Search models...",
                        color = colors.textFaint,
                        fontFamily = InterFontFamily,
                        fontSize = 14.sp,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = colors.text,
                        fontFamily = InterFontFamily,
                        fontSize = 14.sp,
                    ),
                    cursorBrush = SolidColor(colors.accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            HorizontalDivider(color = colors.border, thickness = 1.dp)

            LazyColumn(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            ) {
                if (filteredCustom.isNotEmpty()) {
                    item {
                        Text(
                            text = "CUSTOM",
                            color = colors.textFaint,
                            fontFamily = InterFontFamily,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.4.sp,
                            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 4.dp),
                        )
                    }
                    items(
                        items = filteredCustom,
                        key = { "custom_${it.id}" },
                        contentType = { "custom_model" },
                    ) { m ->
                        ModelPickerRow(
                            model = m,
                            selected = m.id == currentModelId,
                            isCustom = true,
                            onSelect = { onSelectModel(m.id) },
                            onEdit = { onEditCustomModel(m) },
                            onDelete = { onDeleteCustomModel(m.id) },
                        )
                    }
                    if (filteredPresets.isNotEmpty()) {
                        item {
                            HorizontalDivider(
                                color = colors.border,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }
                }

                if (filteredPresets.isNotEmpty()) {
                    item {
                        Text(
                            text = "${providerName.uppercase()} PRESETS",
                            color = colors.textFaint,
                            fontFamily = InterFontFamily,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.4.sp,
                            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 4.dp),
                        )
                    }
                    items(
                        items = filteredPresets,
                        key = { "preset_${it.id}" },
                        contentType = { "preset_model" },
                    ) { m ->
                        ModelPickerRow(
                            model = m,
                            selected = m.id == currentModelId,
                            isCustom = false,
                            onSelect = { onSelectModel(m.id) },
                            onEdit = {},
                            onDelete = {},
                        )
                    }
                }
            }

            HorizontalDivider(color = colors.border, thickness = 1.dp)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onAddModel)
                    .padding(horizontal = 14.dp, vertical = 14.dp),
            ) {
                Text(
                    text = "+ Add model...",
                    color = colors.textSecondary,
                    fontFamily = InterFontFamily,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun ModelPickerRow(
    model: ModelPreset,
    selected: Boolean,
    isCustom: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = OmniTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) colors.accentSoft else Color.Transparent)
            .clickable(onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (selected) "${model.name} \u2713" else model.name,
                color = if (selected) colors.accent else colors.text,
                fontFamily = InterFontFamily,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = model.id,
                color = if (selected) colors.accent.copy(alpha = 0.7f) else colors.textFaint,
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 11.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (isCustom) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onEdit),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = OmniIcons.Edit,
                        contentDescription = "Edit custom model",
                        tint = colors.textFaint,
                        modifier = Modifier.size(14.dp),
                    )
                }
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onDelete),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = OmniIcons.Trash,
                        contentDescription = "Delete custom model",
                        tint = colors.danger.copy(alpha = 0.8f),
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EffortBottomSheet(
    tiers: List<String>,
    selectedTier: String,
    defaultTier: String?,
    thinkingEnabled: Boolean,
    onSelectTier: (String) -> Unit,
    onToggleThinking: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = OmniTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.bgElevated,
        scrimColor = Color(0x80000000),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(16.dp),
        ) {
            Text(
                text = "Higher effort gives more thorough answers but takes longer and uses more tokens.",
                color = colors.textMuted,
                fontFamily = InterFontFamily,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(bottom = 10.dp),
            )
            tiers.forEach { tier ->
                val isSelected = tier == selectedTier
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) Color(0x0DFFFFFF) else Color.Transparent)
                        .clickable { onSelectTier(tier) }
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = OmniCatalog.effortLabel(tier),
                        color = colors.text,
                        fontFamily = InterFontFamily,
                        fontSize = 13.5.sp,
                        modifier = Modifier.weight(1f),
                    )
                    if (tier == defaultTier) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(colors.accentSoft)
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = "DEFAULT",
                                color = colors.accent,
                                fontFamily = InterFontFamily,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.5.sp,
                            )
                        }
                    }
                    if (isSelected) {
                        Icon(
                            imageVector = OmniIcons.Check,
                            contentDescription = "Selected",
                            tint = colors.accent,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
            }
            HorizontalDivider(
                color = colors.borderSoft,
                modifier = Modifier.padding(vertical = 10.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Thinking",
                        color = colors.text,
                        fontFamily = InterFontFamily,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "Show intermediate reasoning when available.",
                        color = colors.textFaint,
                        fontFamily = InterFontFamily,
                        fontSize = 12.sp,
                    )
                }
                Spacer(Modifier.width(12.dp))
                OmniToggleSwitch(
                    checked = thinkingEnabled,
                    onCheckedChange = onToggleThinking,
                )
            }
        }
    }
}

@Composable
fun OmniToggleSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = OmniTheme.colors
    Box(
        modifier = modifier
            .size(width = 42.dp, height = 24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (checked) colors.accent else Color(0x1AFFFFFF))
            .clickable { onCheckedChange(!checked) }
            .padding(3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(Color(0xFFDDDDDD)),
        )
    }
}
