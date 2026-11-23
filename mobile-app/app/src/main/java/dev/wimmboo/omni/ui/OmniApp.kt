package dev.wimmboo.omni.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wimmboo.omni.data.model.OmniSettings
import dev.wimmboo.omni.ui.components.CodePreviewModal
import dev.wimmboo.omni.ui.components.ComposerCard
import dev.wimmboo.omni.ui.components.EmptyChatView
import dev.wimmboo.omni.ui.components.MessageRow
import dev.wimmboo.omni.ui.components.MetricsBottomSheet
import dev.wimmboo.omni.ui.components.MetricsSummaryBar
import dev.wimmboo.omni.ui.components.OmniIconButton
import dev.wimmboo.omni.ui.components.SettingsModal
import dev.wimmboo.omni.ui.components.SidebarDrawerContent
import dev.wimmboo.omni.ui.icons.OmniIcons
import dev.wimmboo.omni.ui.theme.InterFontFamily
import dev.wimmboo.omni.ui.theme.OmniTheme
import kotlinx.coroutines.launch

@Composable
fun OmniApp(
    state: OmniUiState,
    viewModel: OmniViewModel,
    isRecordingVoice: Boolean,
    onRequestAttachFiles: () -> Unit,
    onRequestExportChats: () -> Unit,
    onRequestImportChats: () -> Unit,
    onRequestExportSettings: (OmniSettings) -> Unit,
    onRequestImportSettings: () -> Unit,
    onRequestDownloadCode: (code: String, language: String) -> Unit,
    onRequestVoiceDictation: () -> Unit,
) {
    OmniTheme(incognito = state.incognito) {
        val colors = OmniTheme.colors
        val drawerState = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()

        val activeChat = state.activeChat
        val hasMessages = activeChat != null && activeChat.messages.isNotEmpty()
        val isStreaming = state.isActiveChatStreaming

        BackHandler(
            enabled = state.codePreview != null ||
                state.settingsOpen ||
                state.metricsSheetOpen ||
                drawerState.isOpen ||
                state.editingState != null,
        ) {
            when {
                state.codePreview != null -> viewModel.closeCodePreview()
                state.settingsOpen -> viewModel.dismissSettingsWithoutSaving()
                state.metricsSheetOpen -> viewModel.setMetricsSheetOpen(false)
                drawerState.isOpen -> scope.launch { drawerState.close() }
                state.editingState != null -> viewModel.cancelMessageEdit()
            }
        }

        ModalNavigationDrawer(
            drawerState = drawerState,
            scrimColor = Color(0x73000000),
            drawerContent = {
                ModalDrawerSheet(
                    drawerContainerColor = colors.bgSidebar,
                    drawerShape = RoundedCornerShape(0.dp),
                    modifier = Modifier.width(284.dp),
                ) {
                    SidebarDrawerContent(
                        chats = state.chats,
                        activeChatId = state.activeChatId,
                        incognito = state.incognito,
                        incognitoChat = state.incognitoChat,
                        streamingChatIds = state.streamingChatIds,
                        searchQuery = state.sidebarSearchQuery,
                        onSearchQueryChange = viewModel::setSidebarSearchQuery,
                        onCollapse = { scope.launch { drawerState.close() } },
                        onNewChat = viewModel::startNewChat,
                        onOpenSettings = viewModel::openSettings,
                        onExportChats = onRequestExportChats,
                        onImportChats = onRequestImportChats,
                        onSelectChat = viewModel::selectChat,
                        onPinChat = viewModel::togglePinChat,
                        onRenameChat = viewModel::renameChat,
                        onDeleteChat = viewModel::deleteChat,
                    )
                }
            },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.bg)
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding(),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    OmniTopBar(
                        incognito = state.incognito,
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        onToggleIncognito = viewModel::toggleIncognito,
                    )

                    if (!hasMessages) {
                        EmptyChatView(
                            state = state,
                            isRecordingVoice = isRecordingVoice,
                            viewModel = viewModel,
                            onAttachClick = onRequestAttachFiles,
                            onMicClick = onRequestVoiceDictation,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        )
                    } else {
                        val currentChat = activeChat!!
                        val listState = rememberLazyListState()
                        var userScrolledUp by remember(currentChat.id) { mutableStateOf(false) }

                        val isNearBottom by remember {
                            derivedStateOf {
                                val layout = listState.layoutInfo
                                val total = layout.totalItemsCount
                                if (total == 0) true
                                else {
                                    val lastVis = layout.visibleItemsInfo.lastOrNull()
                                    lastVis != null &&
                                        lastVis.index >= total - 1 &&
                                        (lastVis.offset + lastVis.size) <= (layout.viewportEndOffset + 48)
                                }
                            }
                        }

                        LaunchedEffect(listState.isScrollInProgress, isNearBottom) {
                            if (listState.isScrollInProgress && !isNearBottom) {
                                userScrolledUp = true
                            } else if (isNearBottom) {
                                userScrolledUp = false
                            }
                        }

                        val msgs = currentChat.messages
                        val lastMsg = msgs.lastOrNull()
                        val scrollTick = Triple(
                            msgs.size,
                            (lastMsg?.content?.length ?: 0) / 16,
                            ((lastMsg?.thinking?.length ?: 0) / 16) + (lastMsg?.steps?.size ?: 0),
                        )

                        LaunchedEffect(scrollTick, currentChat.id, isStreaming) {
                            if (!userScrolledUp && !listState.isScrollInProgress && msgs.isNotEmpty()) {
                                listState.scrollToItem(msgs.lastIndex, scrollOffset = 100_000)
                            }
                        }

                        val effortTiers = remember(state.settings) { state.settings.effortTiers() }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        ) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(
                                    start = 14.dp,
                                    end = 14.dp,
                                    top = 10.dp,
                                    bottom = 20.dp,
                                ),
                                verticalArrangement = Arrangement.spacedBy(20.dp),
                            ) {
                                itemsIndexed(
                                    items = msgs,
                                    key = { index, m -> "${currentChat.id}_${index}_${m.role}" },
                                    contentType = { _, m -> m.role },
                                ) { idx, msg ->
                                    val streamingThis = isStreaming && idx == msgs.lastIndex && msg.role == "assistant"
                                    MessageRow(
                                        msg = msg,
                                        msgIndex = idx,
                                        chat = currentChat,
                                        isStreaming = streamingThis,
                                        liveStatusLabel = state.liveStatusLabels[currentChat.id],
                                        effortTiers = effortTiers,
                                        onEditUserMessage = { viewModel.beginComposerEdit(idx) },
                                        onRegenerate = { opts -> viewModel.regenerateMessage(idx, opts) },
                                        onCycleBranch = { dir -> viewModel.cycleBranch(idx - 1, dir) },
                                        onToggleSources = { viewModel.toggleSourcesOpen(idx) },
                                        onExpandSources = { viewModel.expandSources(idx) },
                                        onOpenSettings = viewModel::openSettings,
                                        onPreviewCode = viewModel::openCodePreview,
                                        onDownloadCode = onRequestDownloadCode,
                                        onShowToast = { t -> viewModel.showToast(t, isInfo = true) },
                                    )
                                }
                            }

                            JumpLatestPill(
                                visible = userScrolledUp && isStreaming,
                                onClick = {
                                    userScrolledUp = false
                                    scope.launch {
                                        if (msgs.isNotEmpty()) {
                                            listState.animateScrollToItem(msgs.lastIndex)
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 12.dp),
                            )
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                        ) {
                            MetricsSummaryBar(
                                metrics = state.displayMetrics,
                                isOpen = state.metricsSheetOpen,
                                onToggle = { viewModel.setMetricsSheetOpen(!state.metricsSheetOpen) },
                                modifier = Modifier.padding(bottom = 4.dp),
                            )

                            ComposerCard(
                                text = state.composerText,
                                settings = state.settings,
                                incognito = state.incognito,
                                isStreaming = isStreaming,
                                isEditing = state.editingState != null,
                                agentModeOn = currentChat.agentMode,
                                codeMode = state.codeMode,
                                webSearch = state.webSearch,
                                activeTemplate = state.activeTemplate,
                                pendingFiles = state.pendingFiles,
                                isRecordingVoice = isRecordingVoice,
                                showFooterNote = true,
                                onTextChange = viewModel::onComposerTextChange,
                                onSend = { viewModel.sendMessage() },
                                onStop = viewModel::stopGeneration,
                                onCancelEdit = viewModel::cancelMessageEdit,
                                onApplyEdit = viewModel::applyMessageEdit,
                                onAttachClick = onRequestAttachFiles,
                                onRemoveFile = viewModel::removePendingFile,
                                onToggleAgent = viewModel::toggleAgentMode,
                                onApplySlashTemplate = viewModel::applySlashTemplate,
                                onDismissCodeMode = viewModel::dismissCodeMode,
                                onDismissWebSearch = viewModel::dismissWebSearch,
                                onDismissTemplate = viewModel::dismissActiveTemplate,
                                onSelectModel = viewModel::selectModel,
                                onSaveCustomModel = viewModel::saveQuickCustomModel,
                                onDeleteCustomModel = viewModel::deleteCustomModel,
                                onSelectEffort = viewModel::selectEffort,
                                onToggleThinking = viewModel::toggleThinking,
                                onMicClick = onRequestVoiceDictation,
                                onOpenSettings = viewModel::openSettings,
                            )
                        }
                    }
                }

                AnimatedVisibility(
                    visible = state.toast != null,
                    enter = fadeIn() + slideInVertically { it / 2 },
                    exit = fadeOut() + slideOutVertically { it / 2 },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 16.dp, vertical = 22.dp),
                ) {
                    state.toast?.let { toast ->
                        val bg = if (toast.isInfo) colors.toastInfoBg else colors.toastErrorBg
                        val border = if (toast.isInfo) colors.toastInfoBorder else colors.toastErrorBorder
                        val fg = if (toast.isInfo) colors.toastInfoText else colors.toastErrorText
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(bg)
                                .border(1.dp, border, RoundedCornerShape(12.dp))
                                .clickable { viewModel.dismissToast() }
                                .padding(start = 16.dp, end = 10.dp, top = 11.dp, bottom = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = toast.text,
                                color = fg,
                                fontFamily = InterFontFamily,
                                fontSize = 13.sp,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Icon(
                                imageVector = OmniIcons.Close,
                                contentDescription = "Dismiss",
                                tint = fg.copy(alpha = 0.7f),
                                modifier = Modifier
                                    .size(16.dp)
                                    .clickable { viewModel.dismissToast() },
                            )
                        }
                    }
                }
            }
        }

        if (state.metricsSheetOpen) {
            MetricsBottomSheet(
                metrics = state.displayMetrics,
                activeChat = activeChat,
                contextWindowFallback = state.settings.contextWindow(),
                onDismiss = { viewModel.setMetricsSheetOpen(false) },
            )
        }

        if (state.settingsOpen) {
            SettingsModal(
                settings = state.settings,
                pendingImport = state.pendingSettingsImport,
                onCloseAndSave = viewModel::closeSettingsAndSave,
                onReset = viewModel::resetSettings,
                onExportSettings = onRequestExportSettings,
                onImportSettingsClick = onRequestImportSettings,
                onConfirmImport = viewModel::confirmSettingsImport,
                onCancelImport = viewModel::cancelSettingsImport,
            )
        }

        state.codePreview?.let { preview ->
            CodePreviewModal(
                preview = preview,
                onDismiss = viewModel::closeCodePreview,
            )
        }
    }
}

@Composable
private fun OmniTopBar(
    incognito: Boolean,
    onOpenDrawer: () -> Unit,
    onToggleIncognito: () -> Unit,
) {
    val colors = OmniTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        OmniIconButton(
            icon = OmniIcons.Sidebar,
            contentDescription = "Open sidebar",
            tint = colors.textMuted,
            onClick = onOpenDrawer,
        )

        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            if (incognito) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(colors.incognitoSoft)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = OmniIcons.Incognito,
                        contentDescription = null,
                        tint = colors.incognito,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = "Incognito",
                        color = colors.incognito,
                        fontFamily = InterFontFamily,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "\u00b7 not saved",
                        color = colors.incognito.copy(alpha = 0.7f),
                        fontFamily = InterFontFamily,
                        fontSize = 12.5.sp,
                    )
                }
            }
        }

        OmniIconButton(
            icon = OmniIcons.Incognito,
            contentDescription = "Toggle incognito",
            tint = if (incognito) colors.incognito else colors.textMuted,
            background = if (incognito) colors.incognitoSoft else Color.Transparent,
            onClick = onToggleIncognito,
        )
    }
}

@Composable
private fun JumpLatestPill(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = OmniTheme.colors
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(colors.bgElevated)
                .border(1.dp, colors.borderStrong, RoundedCornerShape(20.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Jump to latest",
                color = colors.textSecondary,
                fontFamily = InterFontFamily,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            Icon(
                imageVector = OmniIcons.ChevronDown,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

