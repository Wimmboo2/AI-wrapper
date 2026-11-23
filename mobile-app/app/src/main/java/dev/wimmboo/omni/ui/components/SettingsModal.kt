package dev.wimmboo.omni.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.wimmboo.omni.data.model.CustomModel
import dev.wimmboo.omni.data.model.OmniCatalog
import dev.wimmboo.omni.data.model.OmniSettings
import dev.wimmboo.omni.domain.MetricsCalculator
import dev.wimmboo.omni.ui.icons.OmniIcons
import dev.wimmboo.omni.ui.theme.InterFontFamily
import dev.wimmboo.omni.ui.theme.JetBrainsMonoFontFamily
import dev.wimmboo.omni.ui.theme.OmniTheme
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun SettingsModal(
    settings: OmniSettings,
    pendingImport: OmniSettings?,
    onCloseAndSave: (OmniSettings) -> Unit,
    onReset: () -> OmniSettings,
    onExportSettings: (OmniSettings) -> Unit,
    onImportSettingsClick: () -> Unit,
    onConfirmImport: () -> Unit,
    onCancelImport: () -> Unit,
) {
    val colors = OmniTheme.colors
    var draft by remember(settings) { mutableStateOf(settings) }
    var showProviderPicker by remember { mutableStateOf(false) }
    var showEffortSheet by remember { mutableStateOf(false) }

    var proxyKeyVisible by remember { mutableStateOf(false) }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var sandboxKeyVisible by remember { mutableStateOf(false) }
    var tavilyKeyVisible by remember { mutableStateOf(false) }

    val providerCfg = OmniCatalog.getProviderConfig(draft.provider)
    val sandboxProvId = draft.activeSandboxProviderId()
    val sandboxMeta = OmniCatalog.SANDBOX_PROVIDERS[sandboxProvId] ?: OmniCatalog.SANDBOX_PROVIDERS["e2b"]!!
    val effortTiers = remember(draft) { draft.effortTiers() }

    Dialog(
        onDismissRequest = { onCloseAndSave(draft) },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x8C000000))
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.95f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.bgModal)
                    .border(1.dp, colors.border, RoundedCornerShape(20.dp)),
            ) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 14.dp, top = 16.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Settings",
                        color = colors.text,
                        fontFamily = InterFontFamily,
                        fontSize = 15.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.15).sp,
                    )
                    OmniIconButton(
                        icon = OmniIcons.Close,
                        contentDescription = "Close settings",
                        tint = colors.textMuted,
                        onClick = { onCloseAndSave(draft) },
                    )
                }

                // Scrollable body
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    // 1. API Endpoint
                    SettingGroup(
                        label = "API Endpoint",
                        description = "URL of your Cloudflare Worker or compatible proxy.",
                    ) {
                        SettingTextField(
                            value = draft.apiEndpoint,
                            onValueChange = { draft = draft.copy(apiEndpoint = it) },
                            placeholder = OmniCatalog.DEFAULT_WORKER_ENDPOINT,
                            mono = true,
                        )
                        if (providerCfg != null && providerCfg.defaultEndpoint.isNotEmpty()) {
                            Text(
                                text = "${providerCfg.name} API: ${providerCfg.defaultEndpoint}",
                                color = colors.textFaint,
                                fontFamily = InterFontFamily,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(top = 5.dp, start = 2.dp),
                            )
                        }
                    }

                    // 2. App Origin (native ALLOWED_ORIGINS support without touching worker.js)
                    SettingGroup(
                        label = "App Origin",
                        description = "Origin header sent to a Worker locked with ALLOWED_ORIGINS. Defaults to the hosted web frontend; leave empty if your Worker uses OMNI_PROXY_KEY only.",
                    ) {
                        SettingTextField(
                            value = draft.appOrigin,
                            onValueChange = { draft = draft.copy(appOrigin = it) },
                            placeholder = OmniCatalog.DEFAULT_APP_ORIGIN,
                            mono = true,
                        )
                    }

                    // 3. Worker access key
                    SettingGroup(
                        label = "Worker access key",
                        description = "Only if the Worker has OMNI_PROXY_KEY set. Leave empty when the Worker is locked with ALLOWED_ORIGINS instead. Sent to your Worker, not to the model provider.",
                    ) {
                        SettingSecretField(
                            value = draft.proxyKey,
                            onValueChange = { draft = draft.copy(proxyKey = it) },
                            placeholder = "Same value as OMNI_PROXY_KEY",
                            visible = proxyKeyVisible,
                            onToggleVisible = { proxyKeyVisible = !proxyKeyVisible },
                        )
                    }

                    // 4. Provider
                    SettingGroup(
                        label = "Provider",
                        description = "Select your AI provider to populate available models.",
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(colors.bgInputField)
                                .border(1.dp, colors.borderSoft, RoundedCornerShape(10.dp))
                                .clickable { showProviderPicker = true }
                                .padding(horizontal = 12.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = OmniCatalog.getProviderDisplayName(draft.provider),
                                color = colors.text,
                                fontFamily = InterFontFamily,
                                fontSize = 13.sp,
                            )
                            Icon(
                                imageVector = OmniIcons.ChevronDown,
                                contentDescription = null,
                                tint = colors.textFaint,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    }

                    // 5. API Key
                    val currentProviderKey = draft.apiKeys[draft.provider] ?: draft.apiKey
                    SettingGroup(
                        label = "API Key",
                        description = "Encrypted with Android Keystore on this device. Never sent anywhere except your endpoint.",
                    ) {
                        SettingSecretField(
                            value = currentProviderKey,
                            onValueChange = { newVal ->
                                val nextMap = draft.apiKeys.toMutableMap()
                                if (newVal.trim().isNotEmpty()) nextMap[draft.provider] = newVal.trim()
                                else nextMap.remove(draft.provider)
                                draft = draft.copy(apiKey = newVal.trim(), apiKeys = nextMap)
                            },
                            placeholder = providerCfg?.apiKeyPlaceholder ?: "Enter API key...",
                            visible = apiKeyVisible,
                            onToggleVisible = { apiKeyVisible = !apiKeyVisible },
                        )
                    }

                    // 6. Custom provider section
                    if (draft.provider == "custom") {
                        SettingGroup(
                            label = "Base URL",
                            description = "OpenAI-compatible endpoint URL.",
                        ) {
                            SettingTextField(
                                value = draft.customBaseURL,
                                onValueChange = { draft = draft.copy(customBaseURL = it) },
                                placeholder = "https://api.openai.com/v1/chat/completions",
                                mono = true,
                            )
                        }

                        SettingGroup(
                            label = "Custom Models",
                            description = "",
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                draft.customModels.forEachIndexed { idx, cm ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(Color(0x08FFFFFF))
                                            .border(1.dp, colors.borderSoft, RoundedCornerShape(10.dp))
                                            .padding(horizontal = 8.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        InlineMiniInput(
                                            value = cm.name,
                                            onValueChange = { v ->
                                                val list = draft.customModels.toMutableList()
                                                list[idx] = cm.copy(name = v)
                                                draft = draft.copy(customModels = list)
                                            },
                                            placeholder = "Name",
                                            modifier = Modifier.weight(1.6f),
                                        )
                                        InlineMiniInput(
                                            value = cm.id,
                                            onValueChange = { v ->
                                                val list = draft.customModels.toMutableList()
                                                list[idx] = cm.copy(id = v)
                                                draft = draft.copy(customModels = list)
                                            },
                                            placeholder = "Model ID",
                                            mono = true,
                                            modifier = Modifier.weight(2f),
                                        )
                                        InlineMiniInput(
                                            value = if (cm.ctx > 0) cm.ctx.toString() else "",
                                            onValueChange = { v ->
                                                val list = draft.customModels.toMutableList()
                                                list[idx] = cm.copy(ctx = v.filter { it.isDigit() }.toIntOrNull() ?: 0)
                                                draft = draft.copy(customModels = list)
                                            },
                                            placeholder = "Ctx",
                                            mono = true,
                                            numeric = true,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Box(
                                            modifier = Modifier
                                                .size(26.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .clickable {
                                                    val list = draft.customModels.toMutableList()
                                                    list.removeAt(idx)
                                                    draft = draft.copy(customModels = list)
                                                },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                imageVector = OmniIcons.Close,
                                                contentDescription = "Remove model",
                                                tint = colors.textFaint,
                                                modifier = Modifier.size(14.dp),
                                            )
                                        }
                                    }
                                }

                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .border(1.dp, colors.borderSoft, RoundedCornerShape(8.dp))
                                        .clickable {
                                            draft = draft.copy(
                                                customModels = draft.customModels + CustomModel(id = "", name = "", ctx = 8192),
                                            )
                                        }
                                        .padding(horizontal = 12.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Icon(
                                        imageVector = OmniIcons.Plus,
                                        contentDescription = null,
                                        tint = colors.textFaint,
                                        modifier = Modifier.size(13.dp),
                                    )
                                    Text(
                                        text = "Add model",
                                        color = colors.textFaint,
                                        fontFamily = InterFontFamily,
                                        fontSize = 12.sp,
                                    )
                                }
                            }
                        }
                    }

                    // 7. Custom Instructions
                    SettingGroup(
                        label = "Custom Instructions",
                        description = "System prompt prepended to every message. Leave empty for none.",
                    ) {
                        SettingTextField(
                            value = draft.systemPrompt,
                            onValueChange = { draft = draft.copy(systemPrompt = it) },
                            placeholder = "You are a helpful assistant...",
                            mono = true,
                            minLines = 3,
                            maxLines = 6,
                        )
                    }

                    // 8. Agent sandbox provider
                    SettingGroup(
                        label = "Agent sandbox provider",
                        description = "Which service runs the code in agent mode. Both bill per second of sandbox lifetime, so sandboxes are killed as soon as a run ends. Keys are stored per provider, so you can switch without re-pasting.",
                    ) {
                        OmniSegmentedControl(
                            options = listOf("e2b" to "E2B", "novita" to "Novita"),
                            selected = sandboxProvId,
                            onSelect = { nextProv ->
                                sandboxKeyVisible = false
                                draft = draft.copy(sandboxProvider = nextProv)
                            },
                        )
                    }

                    // 9. Sandbox API Key
                    val currentSandboxKey = draft.activeSandboxKey(sandboxProvId)
                    SettingGroup(
                        label = "${sandboxMeta.label} API Key",
                        description = "Enables agent mode, which lets the model run code in a sandboxed Linux VM. Your key, your account \u2014 stored encrypted on this device and sent only to your own Worker, which keeps none of it.",
                    ) {
                        SettingSecretField(
                            value = currentSandboxKey,
                            onValueChange = { newKey ->
                                val clean = newKey.trim()
                                val nextMap = draft.sandboxKeys.toMutableMap()
                                if (clean.isNotEmpty()) nextMap[sandboxProvId] = clean else nextMap.remove(sandboxProvId)
                                draft = draft.copy(
                                    sandboxKeys = nextMap,
                                    e2bKey = if (sandboxProvId == "e2b") clean else draft.e2bKey,
                                )
                            },
                            placeholder = sandboxMeta.placeholder,
                            visible = sandboxKeyVisible,
                            onToggleVisible = { sandboxKeyVisible = !sandboxKeyVisible },
                        )
                    }

                    // 10. Agent step limit
                    SettingSliderGroup(
                        label = "Agent step limit",
                        valueBadge = draft.agentMaxSteps.toString(),
                        description = "How many tool calls one reply may chain before the model is forced to answer. Each step re-sends the whole conversation, so higher costs more.",
                        value = draft.agentMaxSteps.toFloat(),
                        range = 1f..25f,
                        onValueChange = { draft = draft.copy(agentMaxSteps = it.roundToInt().coerceIn(1, 25)) },
                    )

                    // 11. Temperature
                    SettingSliderGroup(
                        label = "Temperature",
                        valueBadge = String.format(Locale.US, "%.2f", draft.temperature),
                        description = "Higher is more creative; lower is more focused.",
                        value = draft.temperature.toFloat(),
                        range = 0f..2f,
                        onValueChange = { v ->
                            val snapped = ((v * 20f).roundToInt() / 20.0).coerceIn(0.0, 2.0)
                            draft = draft.copy(temperature = snapped)
                        },
                    )

                    // 12. Max tokens
                    SettingSliderGroup(
                        label = "Max tokens",
                        valueBadge = MetricsCalculator.fmtInt(draft.maxTokens.toLong()),
                        description = "Cap on tokens generated per response.",
                        value = draft.maxTokens.toFloat(),
                        range = 64f..32768f,
                        onValueChange = { v ->
                            val snapped = ((v / 64f).roundToInt() * 64).coerceIn(64, 32768)
                            draft = draft.copy(maxTokens = snapped)
                        },
                    )

                    // 13. Thinking toggle
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
                            checked = draft.thinking,
                            onCheckedChange = { draft = draft.copy(thinking = it) },
                        )
                    }

                    // 14. Reasoning effort (hidden unless model supports effort tiers)
                    if (!effortTiers.isNullOrEmpty()) {
                        SettingGroup(label = "Reasoning effort", description = "") {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0x08FFFFFF))
                                    .border(1.dp, colors.borderSoft, RoundedCornerShape(10.dp))
                                    .clickable { showEffortSheet = true }
                                    .padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = OmniCatalog.effortLabel(draft.effort),
                                    color = colors.text,
                                    fontFamily = InterFontFamily,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                Icon(
                                    imageVector = OmniIcons.ChevronDown,
                                    contentDescription = null,
                                    tint = colors.textMuted,
                                    modifier = Modifier.size(13.dp),
                                )
                            }
                        }
                    }

                    // 15. Web search mode
                    SettingGroup(
                        label = "Web search",
                        description = "Auto lets the model or a built-in heuristic decide when to search.",
                    ) {
                        OmniSegmentedControl(
                            options = listOf("auto" to "Auto", "on" to "On", "off" to "Off"),
                            selected = draft.searchMode,
                            onSelect = { draft = draft.copy(searchMode = it) },
                        )
                    }

                    // 16. Tavily API Key
                    SettingGroup(
                        label = "Tavily API Key",
                        description = "Powers web search. Your key, your Tavily account \u2014 stored encrypted on this device and sent only to your own Worker, which keeps none of it. Get one at tavily.com.",
                    ) {
                        SettingSecretField(
                            value = draft.tavilyKey,
                            onValueChange = { draft = draft.copy(tavilyKey = it.trim()) },
                            placeholder = "tvly-...",
                            visible = tavilyKeyVisible,
                            onToggleVisible = { tavilyKeyVisible = !tavilyKeyVisible },
                        )
                        Text(
                            text = if (draft.tavilyKey.trim().isNotEmpty()) {
                                "Key saved on this device. Searches bill your own Tavily account."
                            } else {
                                "No key saved. Web search stays off until you add one."
                            },
                            color = colors.textFaint,
                            fontFamily = InterFontFamily,
                            fontSize = 11.5.sp,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }

                    // 17. Search backend
                    SettingGroup(
                        label = "Search backend",
                        description = "Auto uses each provider's built-in search where available and Tavily everywhere else. Tavily forces your key for every provider.",
                    ) {
                        OmniSegmentedControl(
                            options = listOf("auto" to "Auto", "tavily" to "Tavily"),
                            selected = draft.searchBackend,
                            onSelect = { draft = draft.copy(searchBackend = it) },
                        )
                    }

                    // 18. Search depth
                    SettingGroup(
                        label = "Search depth",
                        description = "Auto uses 1 Tavily credit normally and 2 for complex queries. Advanced always uses 2; Basic always uses 1.",
                    ) {
                        OmniSegmentedControl(
                            options = listOf("auto" to "Auto", "basic" to "Basic", "advanced" to "Advanced"),
                            selected = draft.searchDepth,
                            onSelect = { draft = draft.copy(searchDepth = it) },
                        )
                    }

                    // 19. Backup
                    SettingGroup(
                        label = "Backup",
                        description = "Export every setting stored on this device, including provider keys, the worker access key, the Tavily key, and sandbox keys. Import files exported from the web app or another device. The file is plain JSON, so keep it private.",
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ModalButton(
                                label = "Export",
                                primary = false,
                                onClick = { onExportSettings(draft) },
                            )
                            ModalButton(
                                label = "Import",
                                primary = false,
                                onClick = onImportSettingsClick,
                            )
                        }
                    }

                    Spacer(Modifier.height(6.dp))
                }

                // Footer
                HorizontalDivider(color = colors.border, thickness = 1.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.bgModal)
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ModalButton(
                        label = "Reset",
                        primary = false,
                        onClick = { draft = onReset() },
                    )
                    Spacer(Modifier.width(8.dp))
                    ModalButton(
                        label = "Done",
                        primary = true,
                        onClick = { onCloseAndSave(draft) },
                    )
                }
            }
        }
    }

    // Provider Picker Sheet
    if (showProviderPicker) {
        ProviderPickerBottomSheet(
            selectedProvider = draft.provider,
            onSelect = { provId ->
                val models = draft.availablePresetModels(provId)
                apiKeyVisible = false
                draft = draft.copy(
                    provider = provId,
                    model = models.firstOrNull()?.id.orEmpty(),
                    apiKey = draft.apiKeys[provId].orEmpty(),
                ).normalized()
                showProviderPicker = false
            },
            onDismiss = { showProviderPicker = false },
        )
    }

    // Effort Sheet from Settings
    if (showEffortSheet && !effortTiers.isNullOrEmpty()) {
        EffortBottomSheet(
            tiers = effortTiers,
            selectedTier = draft.effort,
            defaultTier = draft.effortDefault(),
            thinkingEnabled = draft.thinking,
            onSelectTier = { tier ->
                draft = draft.copy(effort = tier)
                showEffortSheet = false
            },
            onToggleThinking = { draft = draft.copy(thinking = it) },
            onDismiss = { showEffortSheet = false },
        )
    }

    // Settings import confirmation dialog
    if (pendingImport != null) {
        AlertDialog(
            onDismissRequest = onCancelImport,
            containerColor = colors.bgModal,
            titleContentColor = colors.text,
            textContentColor = colors.textSecondary,
            title = {
                Text(
                    text = "Replace settings?",
                    fontFamily = InterFontFamily,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
            },
            text = {
                Text(
                    text = "Replace settings on this device with this file? API keys in the file will overwrite the ones stored here.",
                    fontFamily = InterFontFamily,
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onConfirmImport()
                        draft = pendingImport
                    },
                ) {
                    Text("Replace", color = colors.accent, fontFamily = InterFontFamily)
                }
            },
            dismissButton = {
                TextButton(onClick = onCancelImport) {
                    Text("Cancel", color = colors.textSecondary, fontFamily = InterFontFamily)
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderPickerBottomSheet(
    selectedProvider: String,
    onSelect: (String) -> Unit,
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
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 460.dp)
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 10.dp),
        ) {
            items(OmniCatalog.SETTINGS_PROVIDER_ORDER) { key ->
                val selected = key == selectedProvider
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) colors.accentSoft else Color.Transparent)
                        .clickable { onSelect(key) }
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = OmniCatalog.getProviderDisplayName(key),
                        color = if (selected) colors.accent else colors.textSecondary,
                        fontFamily = InterFontFamily,
                        fontSize = 13.5.sp,
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    )
                    if (selected) {
                        Icon(
                            imageVector = OmniIcons.Check,
                            contentDescription = "Selected",
                            tint = colors.accent,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingGroup(
    label: String,
    description: String,
    content: @Composable () -> Unit,
) {
    val colors = OmniTheme.colors
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            color = colors.text,
            fontFamily = InterFontFamily,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
        if (description.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = description,
                color = colors.textFaint,
                fontFamily = InterFontFamily,
                fontSize = 12.sp,
                lineHeight = 17.5.sp,
            )
        }
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun SettingSliderGroup(
    label: String,
    valueBadge: String,
    description: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    val colors = OmniTheme.colors
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                color = colors.text,
                fontFamily = InterFontFamily,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.accentSoft)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    text = valueBadge,
                    color = colors.accent,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 12.sp,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = description,
            color = colors.textFaint,
            fontFamily = InterFontFamily,
            fontSize = 12.sp,
            lineHeight = 17.5.sp,
        )
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = colors.accent,
                activeTrackColor = colors.accent,
                inactiveTrackColor = Color(0x14FFFFFF),
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SettingTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    mono: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = 1,
) {
    val colors = OmniTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.bgInputField)
            .border(1.dp, colors.borderSoft, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                color = colors.textFaint,
                fontFamily = if (mono) JetBrainsMonoFontFamily else InterFontFamily,
                fontSize = 13.sp,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = maxLines == 1,
            minLines = minLines,
            maxLines = maxLines,
            textStyle = TextStyle(
                color = colors.text,
                fontFamily = if (mono) JetBrainsMonoFontFamily else InterFontFamily,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            ),
            cursorBrush = SolidColor(colors.accent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SettingSecretField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    visible: Boolean,
    onToggleVisible: () -> Unit,
) {
    val colors = OmniTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.bgInputField)
            .border(1.dp, colors.borderSoft, RoundedCornerShape(10.dp))
            .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 4.dp),
        ) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    color = colors.textFaint,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 13.sp,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                textStyle = TextStyle(
                    color = colors.text,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 13.sp,
                ),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OmniIconButton(
            icon = if (visible) OmniIcons.EyeOff else OmniIcons.Eye,
            contentDescription = if (visible) "Hide key" else "Show key",
            tint = colors.textFaint,
            onClick = onToggleVisible,
        )
    }
}

@Composable
private fun InlineMiniInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    mono: Boolean = false,
    numeric: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = OmniTheme.colors
    Box(modifier = modifier) {
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                color = colors.textFaint,
                fontFamily = if (mono) JetBrainsMonoFontFamily else InterFontFamily,
                fontSize = 12.5.sp,
                maxLines = 1,
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
                fontSize = 12.5.sp,
            ),
            cursorBrush = SolidColor(colors.accent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun OmniSegmentedControl(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    val colors = OmniTheme.colors
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(colors.bgChip)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { (id, label) ->
            val active = id == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (active) colors.bgActive else Color.Transparent)
                    .clickable { onSelect(id) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(
                    text = label,
                    color = if (active) colors.text else colors.textMuted,
                    fontFamily = InterFontFamily,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun ModalButton(
    label: String,
    primary: Boolean,
    onClick: () -> Unit,
) {
    val colors = OmniTheme.colors
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (primary) colors.sendReadyBg else Color(0x0FFFFFFF))
            .then(
                if (!primary) Modifier.border(1.dp, colors.borderSoft, RoundedCornerShape(10.dp))
                else Modifier
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            color = if (primary) colors.sendReadyFg else colors.textSecondary,
            fontFamily = InterFontFamily,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
