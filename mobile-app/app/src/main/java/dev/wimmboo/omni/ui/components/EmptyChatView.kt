package dev.wimmboo.omni.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wimmboo.omni.ui.OmniUiState
import dev.wimmboo.omni.ui.OmniViewModel
import dev.wimmboo.omni.ui.icons.OmniIcons
import dev.wimmboo.omni.ui.theme.InstrumentSerifFontFamily
import dev.wimmboo.omni.ui.theme.InterFontFamily
import dev.wimmboo.omni.ui.theme.OmniTheme

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EmptyChatView(
    state: OmniUiState,
    isRecordingVoice: Boolean,
    viewModel: OmniViewModel,
    onAttachClick: () -> Unit,
    onMicClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = OmniTheme.colors
    val greeting = remember(state.incognito) {
        if (state.incognito) {
            "This chat stays between us"
        } else {
            val headings = listOf(
                "What shall we think through?",
                "What\u2019s on your mind?",
                "Where should we begin?",
                "Ready when you are",
            )
            headings[((System.currentTimeMillis() / 86_400_000L) % headings.size).toInt()]
        }
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(bottom = 24.dp),
        ) {
            Icon(
                imageVector = OmniIcons.Spark,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = greeting,
                color = colors.emptyTitle,
                fontFamily = InstrumentSerifFontFamily,
                fontSize = 28.sp,
                lineHeight = 34.sp,
                letterSpacing = (-0.4).sp,
                textAlign = TextAlign.Center,
            )
        }

        val showMissingNote = !state.incognito &&
            (state.settings.effectiveApiKey().isBlank() || state.settings.apiEndpoint.isBlank())

        ComposerCard(
            text = state.composerText,
            settings = state.settings,
            incognito = state.incognito,
            isStreaming = false,
            isEditing = state.editingState != null,
            agentModeOn = state.activeChat?.agentMode == true,
            codeMode = state.codeMode,
            webSearch = state.webSearch,
            activeTemplate = state.activeTemplate,
            pendingFiles = state.pendingFiles,
            isRecordingVoice = isRecordingVoice,
            showFooterNote = showMissingNote,
            onTextChange = viewModel::onComposerTextChange,
            onSend = { viewModel.sendMessage() },
            onStop = viewModel::stopGeneration,
            onCancelEdit = viewModel::cancelMessageEdit,
            onApplyEdit = viewModel::applyMessageEdit,
            onAttachClick = onAttachClick,
            onRemoveFile = viewModel::removePendingFile,
            onToggleAgent = viewModel::toggleAgentMode,
            onToggleWebSearch = viewModel::toggleWebSearch,
            onApplySlashTemplate = viewModel::applySlashTemplate,
            onDismissCodeMode = viewModel::dismissCodeMode,
            onDismissWebSearch = viewModel::dismissWebSearch,
            onDismissTemplate = viewModel::dismissActiveTemplate,
            onSelectModel = viewModel::selectModel,
            onSaveCustomModel = viewModel::saveQuickCustomModel,
            onDeleteCustomModel = viewModel::deleteCustomModel,
            onSelectEffort = viewModel::selectEffort,
            onToggleThinking = viewModel::toggleThinking,
            onMicClick = onMicClick,
        )

        Spacer(Modifier.height(16.dp))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            PromptChip(OmniIcons.Edit, "Write") { viewModel.applySuggestionChip("write") }
            PromptChip(OmniIcons.Learn, "Learn") { viewModel.applySuggestionChip("learn") }
            PromptChip(OmniIcons.Code, "Code") { viewModel.applySuggestionChip("code") }
            PromptChip(OmniIcons.Coffee, "Life stuff") { viewModel.applySuggestionChip("life") }
            PromptChip(OmniIcons.HelpCircle, "Omni's choice") { viewModel.applySuggestionChip("choice") }
        }

        if (state.incognito) {
            Text(
                text = "Chats in incognito aren\u2019t written to local storage. Closing the app clears them.",
                color = colors.textFaint,
                fontFamily = InterFontFamily,
                fontSize = 12.5.sp,
                lineHeight = 19.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 18.dp, start = 16.dp, end = 16.dp),
            )
        }
    }
}

@Composable
private fun PromptChip(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val colors = OmniTheme.colors
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(colors.bgSuggestionChip)
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.textSecondary.copy(alpha = 0.75f),
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = label,
            color = colors.textSecondary,
            fontFamily = InterFontFamily,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
