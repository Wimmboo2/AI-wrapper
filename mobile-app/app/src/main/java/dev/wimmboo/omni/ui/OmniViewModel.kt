package dev.wimmboo.omni.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.wimmboo.omni.data.backup.BackupManager
import dev.wimmboo.omni.data.backup.ChatImportResult
import dev.wimmboo.omni.data.backup.SettingsImportResult
import dev.wimmboo.omni.data.local.ChatRepository
import dev.wimmboo.omni.data.local.OmniDatabase
import dev.wimmboo.omni.data.local.SettingsStore
import dev.wimmboo.omni.data.model.Chat
import dev.wimmboo.omni.data.model.ChatComparator
import dev.wimmboo.omni.data.model.ChatMessage
import dev.wimmboo.omni.data.model.CustomModel
import dev.wimmboo.omni.data.model.MessageMetrics
import dev.wimmboo.omni.data.model.OmniCatalog
import dev.wimmboo.omni.data.model.OmniSettings
import dev.wimmboo.omni.data.model.PendingFile
import dev.wimmboo.omni.data.model.PromptTemplate
import dev.wimmboo.omni.data.model.QuickCustomModel
import dev.wimmboo.omni.data.model.ToolCallRecord
import dev.wimmboo.omni.data.model.ToolResultRecord
import dev.wimmboo.omni.data.model.ToolStep
import dev.wimmboo.omni.data.model.buildUserMessagePayload
import dev.wimmboo.omni.data.model.deriveTitle
import dev.wimmboo.omni.data.model.generateChatId
import dev.wimmboo.omni.data.model.messageToApiMessages
import dev.wimmboo.omni.data.model.restorePendingFilesFromMessage
import dev.wimmboo.omni.data.model.stepToApiMessages
import dev.wimmboo.omni.data.model.stripAttachmentMarker
import dev.wimmboo.omni.data.network.SandboxSession
import dev.wimmboo.omni.data.network.WorkerClient
import dev.wimmboo.omni.data.network.WorkerStreamParser
import dev.wimmboo.omni.domain.MetricsCalculator
import dev.wimmboo.omni.domain.ServerTimingData
import dev.wimmboo.omni.domain.ThinkSplitter
import dev.wimmboo.omni.domain.TurnRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class ToastMessage(
    val text: String,
    val isInfo: Boolean = false,
    val id: Long = nextToastId.incrementAndGet(),
) {
    companion object {
        private val nextToastId = AtomicLong(0L)
    }
}

data class EditingState(
    val chatId: String,
    val msgIndex: Int,
)

data class SendOptions(
    val skipUserPush: Boolean = false,
    val searchMode: String? = null,
    val lengthHint: String? = null,
    val effort: String? = null,
)

data class CodePreviewState(
    val code: String,
    val language: String,
)

data class OmniUiState(
    val initialized: Boolean = false,
    val settings: OmniSettings = OmniSettings(),
    val chats: List<Chat> = emptyList(),
    val activeChatId: String? = null,
    val incognito: Boolean = false,
    val incognitoChat: Chat? = null,
    val streamingChatIds: Set<String> = emptySet(),
    val liveStatusLabels: Map<String, String> = emptyMap(),
    val composerText: String = "",
    val pendingFiles: List<PendingFile> = emptyList(),
    val codeMode: Boolean = false,
    val webSearch: Boolean = false,
    val activeTemplate: PromptTemplate? = null,
    val editingState: EditingState? = null,
    val sidebarSearchQuery: String = "",
    val settingsOpen: Boolean = false,
    val metricsSheetOpen: Boolean = false,
    val codePreview: CodePreviewState? = null,
    val pendingSettingsImport: OmniSettings? = null,
    val toast: ToastMessage? = null,
) {
    val activeChat: Chat?
        get() = if (incognito) incognitoChat else chats.firstOrNull { it.id == activeChatId }

    val isActiveChatStreaming: Boolean
        get() = activeChat?.id in streamingChatIds

    val displayMetrics: MessageMetrics?
        get() = MetricsCalculator.chatDisplayMetrics(activeChat)
}

class OmniViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsStore = SettingsStore(application)
    private val chatRepo = ChatRepository(OmniDatabase.getInstance(application).chatDao())
    private val workerClient = WorkerClient()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val _uiState = MutableStateFlow(OmniUiState())
    val uiState: StateFlow<OmniUiState> = _uiState.asStateFlow()

    private val streamJobs = ConcurrentHashMap<String, Job>()
    private val sandboxes = ConcurrentHashMap<String, SandboxSession>()
    private val keepaliveJobs = ConcurrentHashMap<String, Job>()
    private var toastDismissJob: Job? = null

    init {
        viewModelScope.launch {
            val loadedSettings = settingsStore.loadSettings()
            val loadedChats = chatRepo.loadAllChats()
            val savedActive = settingsStore.loadActiveChatId()
            val resolvedActive = if (savedActive != null && loadedChats.any { it.id == savedActive }) {
                savedActive
            } else {
                loadedChats.firstOrNull()?.id
            }
            _uiState.update {
                it.copy(
                    initialized = true,
                    settings = loadedSettings,
                    chats = loadedChats,
                    activeChatId = resolvedActive,
                )
            }
        }
    }

    // ---- Toast ----

    fun showToast(text: String, isInfo: Boolean = false) {
        val msg = ToastMessage(text = text, isInfo = isInfo)
        _uiState.update { it.copy(toast = msg) }
        toastDismissJob?.cancel()
        toastDismissJob = viewModelScope.launch {
            delay(4000)
            _uiState.update { state ->
                if (state.toast?.id == msg.id) state.copy(toast = null) else state
            }
        }
    }

    fun dismissToast() {
        toastDismissJob?.cancel()
        _uiState.update { it.copy(toast = null) }
    }

    // ---- Navigation / Chats ----

    fun setSidebarSearchQuery(query: String) {
        _uiState.update { it.copy(sidebarSearchQuery = query) }
    }

    fun startNewChat() {
        cancelMessageEdit()
        val state = _uiState.value
        val chat = createChat(asIncognito = state.incognito)
        if (!chat.incognito) persistActiveChatId(chat.id)
    }

    private fun createChat(asIncognito: Boolean): Chat {
        val now = System.currentTimeMillis()
        val chat = Chat(
            id = generateChatId(),
            title = if (asIncognito) "Incognito chat" else "New chat",
            messages = emptyList(),
            createdAt = now,
            updatedAt = now,
            incognito = asIncognito,
        )
        _uiState.update { s ->
            if (asIncognito) {
                s.copy(incognitoChat = chat, metricsSheetOpen = false)
            } else {
                val updated = (listOf(chat) + s.chats).sortedWith(ChatComparator)
                s.copy(chats = updated, activeChatId = chat.id, metricsSheetOpen = false)
            }
        }
        if (!asIncognito) {
            viewModelScope.launch {
                chatRepo.saveChat(chat)
                settingsStore.saveActiveChatId(chat.id)
            }
        }
        return chat
    }

    fun selectChat(chatId: String) {
        if (_uiState.value.incognito) return
        cancelMessageEdit()
        _uiState.update { it.copy(activeChatId = chatId, metricsSheetOpen = false) }
        persistActiveChatId(chatId)
    }

    fun deleteChat(chatId: String) {
        streamJobs.remove(chatId)?.cancel()
        killSandboxAsync(chatId)
        _uiState.update { s ->
            val remaining = s.chats.filter { it.id != chatId }
            val nextActive = if (!s.incognito && s.activeChatId == chatId) {
                remaining.firstOrNull()?.id
            } else {
                s.activeChatId
            }
            val nextEdit = if (s.editingState?.chatId == chatId) null else s.editingState
            s.copy(
                chats = remaining,
                activeChatId = nextActive,
                streamingChatIds = s.streamingChatIds - chatId,
                liveStatusLabels = s.liveStatusLabels - chatId,
                editingState = nextEdit,
            )
        }
        viewModelScope.launch {
            chatRepo.deleteChat(chatId)
            if (!_uiState.value.incognito) {
                settingsStore.saveActiveChatId(_uiState.value.activeChatId)
            }
        }
    }

    fun togglePinChat(chatId: String) {
        val target = _uiState.value.chats.firstOrNull { it.id == chatId } ?: return
        val updated = target.copy(pinnedAt = if (target.pinnedAt != null) null else System.currentTimeMillis())
        updatePersistedChat(updated)
    }

    fun renameChat(chatId: String, newTitle: String) {
        val trimmed = newTitle.trim().take(100)
        if (trimmed.isEmpty()) return
        val s = _uiState.value
        if (s.incognito && s.incognitoChat?.id == chatId) {
            _uiState.update { it.copy(incognitoChat = it.incognitoChat?.copy(title = trimmed)) }
            return
        }
        val target = s.chats.firstOrNull { it.id == chatId } ?: return
        updatePersistedChat(target.copy(title = trimmed))
    }

    fun toggleIncognito() {
        if (_uiState.value.incognito) exitIncognito() else enterIncognito()
    }

    fun enterIncognito() {
        cancelMessageEdit()
        _uiState.update { s ->
            val inc = s.incognitoChat ?: Chat(
                id = generateChatId(),
                title = "Incognito chat",
                messages = emptyList(),
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                incognito = true,
            )
            s.copy(incognito = true, incognitoChat = inc, metricsSheetOpen = false)
        }
        showToast("Incognito on \u2014 this chat won\u2019t be saved", isInfo = true)
    }

    fun exitIncognito() {
        cancelMessageEdit()
        val incId = _uiState.value.incognitoChat?.id
        if (incId != null) {
            streamJobs.remove(incId)?.cancel()
            killSandboxAsync(incId)
        }
        _uiState.update { s ->
            val nextActive = if (s.activeChatId != null && s.chats.any { it.id == s.activeChatId }) {
                s.activeChatId
            } else {
                s.chats.firstOrNull()?.id
            }
            s.copy(
                incognito = false,
                incognitoChat = null,
                activeChatId = nextActive,
                streamingChatIds = if (incId != null) s.streamingChatIds - incId else s.streamingChatIds,
                liveStatusLabels = if (incId != null) s.liveStatusLabels - incId else s.liveStatusLabels,
                metricsSheetOpen = false,
            )
        }
    }

    // ---- Composer / Templates / Files / Modes ----

    fun onComposerTextChange(text: String) {
        _uiState.update { it.copy(composerText = text) }
    }

    fun appendDictatedText(transcript: String) {
        val clean = transcript.trim()
        if (clean.isEmpty()) return
        _uiState.update { s ->
            val cur = s.composerText.trim()
            val next = if (cur.isEmpty()) clean else "$cur $clean"
            s.copy(composerText = next)
        }
    }

    fun applySuggestionChip(key: String) {
        val prompt = OmniCatalog.SUGGESTION_PROMPTS[key] ?: return
        _uiState.update { it.copy(composerText = prompt) }
        if (key == "choice") {
            sendMessage()
        }
    }

    fun searchCommandQuery(text: String): String? {
        val m = Regex("^/search\\b\\s*([\\s\\S]*)$", RegexOption.IGNORE_CASE).find(text) ?: return null
        return m.groupValues[1].replace(Regex("\\s+"), " ").trim()
    }

    fun applySlashTemplate(item: PromptTemplate) {
        if (item.key == "search") {
            val q = searchCommandQuery(_uiState.value.composerText)
            if (!q.isNullOrEmpty()) {
                _uiState.update {
                    it.copy(
                        composerText = q,
                        webSearch = true,
                        activeTemplate = null,
                    )
                }
                sendMessage(SendOptions(searchMode = "on"))
                return
            }
        }
        _uiState.update { s ->
            s.copy(
                composerText = item.prompt,
                codeMode = if (item.enableCodeMode) true else s.codeMode,
                webSearch = if (item.enableWebSearch) true else s.webSearch,
                activeTemplate = item,
            )
        }
        if (item.enableCodeMode) showToast("Code mode activated", isInfo = true)
        if (item.enableWebSearch) showToast("Web search activated", isInfo = true)
    }

    fun dismissCodeMode() {
        _uiState.update { it.copy(codeMode = false) }
        showToast("Code mode disabled", isInfo = true)
    }

    fun toggleWebSearch() {
        val next = !_uiState.value.webSearch
        _uiState.update { it.copy(webSearch = next) }
        showToast(if (next) "Web search activated" else "Web search disabled", isInfo = true)
    }

    fun dismissWebSearch() {
        _uiState.update { it.copy(webSearch = false) }
        showToast("Web search disabled", isInfo = true)
    }

    fun dismissActiveTemplate() {
        _uiState.update { it.copy(activeTemplate = null) }
        showToast("Template disabled", isInfo = true)
    }

    fun addPendingFiles(incoming: List<PendingFile>) {
        if (incoming.isEmpty()) return
        val visionOk = _uiState.value.settings.supportsVision()
        val accepted = mutableListOf<PendingFile>()
        var rejectedVision = false
        for (f in incoming) {
            if (f.isImage && !visionOk) {
                rejectedVision = true
                continue
            }
            accepted += f
        }
        if (rejectedVision) {
            showToast("This model doesn\u2019t support images", isInfo = true)
        }
        if (accepted.isNotEmpty()) {
            _uiState.update { it.copy(pendingFiles = it.pendingFiles + accepted) }
        }
    }

    fun removePendingFile(fileId: String) {
        _uiState.update { s -> s.copy(pendingFiles = s.pendingFiles.filter { it.id != fileId }) }
    }

    fun toggleAgentMode() {
        val s = _uiState.value
        val chat = s.activeChat ?: createChat(asIncognito = s.incognito)
        if (!s.settings.agentAvailable()) {
            val lbl = OmniCatalog.SANDBOX_PROVIDERS[s.settings.activeSandboxProviderId()]?.label ?: "E2B"
            val msg = if (s.settings.activeSandboxKey().isNotBlank()) {
                "This model does not support tool calling"
            } else {
                "Add your $lbl API key in settings to use agent mode"
            }
            showToast(msg, isInfo = true)
            return
        }
        val nextOn = !chat.agentMode
        val updated = chat.copy(agentMode = nextOn)
        replaceChatInState(updated, persist = !updated.incognito)
        if (!nextOn) killSandboxAsync(updated.id)
        showToast(
            if (nextOn) "Agent mode: on \u2014 the model can run code" else "Agent mode: off",
            isInfo = true,
        )
    }

    // ---- Model & Effort Picker ----

    fun selectModel(modelId: String) {
        val s = _uiState.value.settings
        val quickMatch = s.quickCustomModels.firstOrNull { it.provider == s.provider && it.id == modelId }
        val next = s.copy(
            model = modelId,
            customModelId = "",
            customModelCtx = quickMatch?.ctx ?: s.customModelCtx,
        ).normalized()
        persistSettings(next)
    }

    fun saveQuickCustomModel(editingModelId: String?, rawId: String, rawName: String, rawCtx: Int?) {
        val id = rawId.trim()
        if (id.isEmpty()) {
            showToast("Model ID is required")
            return
        }
        val name = rawName.trim().ifEmpty { id }
        val ctx = rawCtx?.takeIf { it > 0 } ?: 131072
        val s = _uiState.value.settings

        val nextSettings = if (s.provider == "custom") {
            val list = s.customModels.toMutableList()
            val targetKey = editingModelId ?: id
            val idx = list.indexOfFirst { it.id == targetKey }
            val entry = CustomModel(id = id, name = name, ctx = ctx)
            if (idx >= 0) list[idx] = entry else list.add(entry)
            s.copy(
                customModels = list,
                model = if (s.model == editingModelId || s.model.isBlank()) id else s.model,
                customModelId = id,
                customModelCtx = ctx,
            )
        } else {
            val list = s.quickCustomModels.toMutableList()
            val targetKey = editingModelId ?: id
            val idx = list.indexOfFirst { it.provider == s.provider && it.id == targetKey }
            val entry = QuickCustomModel(provider = s.provider, id = id, name = name, ctx = ctx)
            if (idx >= 0) list[idx] = entry else list.add(entry)
            s.copy(
                quickCustomModels = list,
                model = if (s.model == editingModelId || s.model.isBlank()) id else s.model,
                customModelId = id,
                customModelCtx = ctx,
            )
        }.normalized()

        persistSettings(nextSettings)
        showToast(if (editingModelId != null) "Model updated" else "Model added: $name", isInfo = true)
    }

    fun deleteCustomModel(modelId: String) {
        val s = _uiState.value.settings
        val next = if (s.provider == "custom") {
            s.copy(customModels = s.customModels.filter { it.id != modelId })
        } else {
            s.copy(quickCustomModels = s.quickCustomModels.filter { !(it.provider == s.provider && it.id == modelId) })
        }
        val fixedModel = if (next.model == modelId) {
            next.allModelsForComposer().firstOrNull()?.id.orEmpty()
        } else {
            next.model
        }
        persistSettings(next.copy(model = fixedModel).normalized())
        showToast("Model deleted", isInfo = true)
    }

    fun selectEffort(tier: String) {
        val next = _uiState.value.settings.copy(effort = tier).normalized()
        persistSettings(next)
    }

    fun toggleThinking(enabled: Boolean) {
        val next = _uiState.value.settings.copy(thinking = enabled)
        persistSettings(next)
    }

    // ---- Settings & Modals ----

    fun openSettings() {
        _uiState.update { it.copy(settingsOpen = true) }
    }

    fun closeSettingsAndSave(updated: OmniSettings) {
        persistSettings(updated.normalized())
        _uiState.update { it.copy(settingsOpen = false) }
        showToast("Settings saved", isInfo = true)
    }

    fun dismissSettingsWithoutSaving() {
        _uiState.update { it.copy(settingsOpen = false) }
    }

    fun resetSettings(): OmniSettings {
        val fresh = OmniSettings()
        persistSettings(fresh)
        return fresh
    }

    fun setMetricsSheetOpen(open: Boolean) {
        _uiState.update { it.copy(metricsSheetOpen = open) }
    }

    fun openCodePreview(code: String, language: String) {
        _uiState.update { it.copy(codePreview = CodePreviewState(code = code, language = language)) }
    }

    fun closeCodePreview() {
        _uiState.update { it.copy(codePreview = null) }
    }

    // ---- Message Editing ----

    fun beginComposerEdit(msgIndex: Int) {
        val s = _uiState.value
        val chat = s.activeChat ?: return
        if (chat.id in s.streamingChatIds) return
        val msg = chat.messages.getOrNull(msgIndex) ?: return
        if (msg.role != "user") return

        val restored = restorePendingFilesFromMessage(msg)
        val visionOk = s.settings.supportsVision()
        val kept = mutableListOf<PendingFile>()
        var skippedVision = false
        for (f in restored) {
            if (f.isImage && !visionOk) {
                skippedVision = true
                continue
            }
            kept += f
        }
        if (skippedVision) {
            showToast("This model doesn\u2019t support images \u2014 attachments omitted", isInfo = true)
        }
        _uiState.update {
            it.copy(
                editingState = EditingState(chatId = chat.id, msgIndex = msgIndex),
                composerText = stripAttachmentMarker(msg.content),
                pendingFiles = kept,
            )
        }
    }

    fun cancelMessageEdit() {
        if (_uiState.value.editingState == null) return
        _uiState.update {
            it.copy(
                editingState = null,
                composerText = "",
                pendingFiles = emptyList(),
            )
        }
    }

    fun applyMessageEdit() {
        val s = _uiState.value
        val edit = s.editingState ?: return
        val chat = s.activeChat ?: run { cancelMessageEdit(); return }
        if (chat.id != edit.chatId || chat.id in s.streamingChatIds) return
        val text = s.composerText.trim()
        if (text.isEmpty() && s.pendingFiles.isEmpty()) return
        val msg = chat.messages.getOrNull(edit.msgIndex)
        if (msg == null || msg.role != "user") {
            cancelMessageEdit()
            return
        }
        val payload = buildUserMessagePayload(text, s.pendingFiles, skipPrefix = true)
        val updatedMsg = msg.copy(content = payload.displayContent, apiContent = payload.apiContent)
        val truncated = chat.messages.take(edit.msgIndex) + updatedMsg
        val updatedChat = chat.copy(
            messages = truncated,
            metrics = null,
            updatedAt = System.currentTimeMillis(),
        )
        replaceChatInState(updatedChat, persist = !updatedChat.incognito)
        _uiState.update {
            it.copy(
                editingState = null,
                composerText = "",
                pendingFiles = emptyList(),
            )
        }
        sendMessage(SendOptions(skipUserPush = true))
    }

    // ---- Regenerate & Branches ----

    fun regenerateMessage(msgIndex: Int, opts: SendOptions = SendOptions()) {
        val s = _uiState.value
        val chat = s.activeChat ?: return
        if (chat.id in s.streamingChatIds) return
        val oldMsg = chat.messages.getOrNull(msgIndex) ?: return
        val parentIdx = msgIndex - 1
        val parentUser = chat.messages.getOrNull(parentIdx) ?: return
        if (parentUser.role != "user") return

        val key = parentIdx.toString()
        val nextBranches = chat.branches.toMutableMap()
        val nextLiveReplies = chat.liveReplies.toMutableMap()
        val arr = (nextBranches[key] ?: emptyList()).toMutableList()
        nextLiveReplies.remove(key)?.let { arr.add(it) }

        val isDup = arr.any { it.content == oldMsg.content && it.steps == null && oldMsg.steps == null }
        if (!isDup) {
            arr.add(oldMsg.toBranchSnapshot())
        }
        nextBranches[key] = arr
        val nextBranchActive = chat.branchActive.toMutableMap()
        nextBranchActive[key] = arr.size

        // Preserve original user prompt + attachments when regenerating
        val updatedChat = chat.copy(
            messages = chat.messages.take(parentIdx + 1),
            metrics = null,
            branches = nextBranches,
            branchActive = nextBranchActive,
            liveReplies = nextLiveReplies,
            updatedAt = System.currentTimeMillis(),
        )
        replaceChatInState(updatedChat, persist = !updatedChat.incognito)
        sendMessage(opts.copy(skipUserPush = true))
    }

    fun cycleBranch(parentUserIdx: Int, direction: Int) {
        val s = _uiState.value
        val chat = s.activeChat ?: return
        if (chat.id in s.streamingChatIds) return
        val key = parentUserIdx.toString()
        val branches = chat.branches[key]?.toMutableList() ?: return
        if (branches.isEmpty()) return
        val current = chat.messages.getOrNull(parentUserIdx + 1) ?: return
        if (current.role != "assistant") return

        val liveReplies = chat.liveReplies.toMutableMap()
        var active = (chat.branchActive[key] ?: branches.size).coerceIn(0, branches.size)
        val total = branches.size + 1

        if (active < branches.size) {
            branches[active] = current.toBranchSnapshot()
        } else {
            liveReplies[key] = current.toBranchSnapshot()
        }

        active = ((active + direction) % total + total) % total
        val nextAssistant = if (active < branches.size) {
            current.withBranchRestored(branches[active])
        } else {
            val stashed = liveReplies.remove(key)
            if (stashed != null) current.withBranchRestored(stashed) else current
        }

        val nextMessages = chat.messages.toMutableList()
        nextMessages[parentUserIdx + 1] = nextAssistant

        val updated = chat.copy(
            messages = nextMessages,
            branches = chat.branches + (key to branches.toList()),
            branchActive = chat.branchActive + (key to active),
            liveReplies = liveReplies,
        )
        replaceChatInState(updated, persist = !updated.incognito)
    }

    fun toggleSourcesOpen(msgIndex: Int) {
        val chat = _uiState.value.activeChat ?: return
        val msg = chat.messages.getOrNull(msgIndex) ?: return
        val nextMsgs = chat.messages.toMutableList()
        nextMsgs[msgIndex] = msg.copy(sourcesOpen = !msg.sourcesOpen)
        replaceChatInState(chat.copy(messages = nextMsgs), persist = !chat.incognito)
    }

    fun expandSources(msgIndex: Int) {
        val chat = _uiState.value.activeChat ?: return
        val msg = chat.messages.getOrNull(msgIndex) ?: return
        if (msg.sourcesOpen) return
        val nextMsgs = chat.messages.toMutableList()
        nextMsgs[msgIndex] = msg.copy(sourcesOpen = true)
        replaceChatInState(chat.copy(messages = nextMsgs), persist = !chat.incognito)
    }

    // ---- Send & Streaming Engine ----

    fun sendMessage(rawOpts: SendOptions = SendOptions()) {
        val s = _uiState.value
        val activeBefore = s.activeChat
        if (activeBefore != null && activeBefore.id in s.streamingChatIds) return

        var opts = rawOpts
        var text = s.composerText.trim()
        val pending = s.pendingFiles

        val slashSearch = searchCommandQuery(text)
        if (slashSearch != null) {
            if (slashSearch.isEmpty() && pending.isEmpty() && !opts.skipUserPush) {
                showToast("Type a query after /search")
                return
            }
            if (slashSearch.isNotEmpty()) {
                text = slashSearch
                opts = opts.copy(searchMode = "on")
            }
        }
        if (!opts.skipUserPush && text.isEmpty() && pending.isEmpty()) return

        val settings = s.settings
        if (settings.apiEndpoint.isBlank()) {
            showToast("Set your API endpoint in Settings first")
            return
        }
        val effectiveKey = settings.effectiveApiKey()
        if (effectiveKey.isBlank()) {
            showToast("Set your API key in Settings first")
            return
        }
        if (settings.provider == "custom" && settings.customBaseURL.isBlank()) {
            showToast("Set the custom base URL in Settings first")
            return
        }

        var chat = activeBefore ?: createChat(asIncognito = s.incognito)
        val currentWebSearch = s.webSearch
        val currentCodeMode = s.codeMode
        val currentTemplate = s.activeTemplate

        val now = System.currentTimeMillis()
        var userApiContent: kotlinx.serialization.json.JsonElement? = null
        if (!opts.skipUserPush) {
            val payload = buildUserMessagePayload(text, pending, activeTemplate = currentTemplate)
            userApiContent = payload.apiContent
            val userMsg = ChatMessage(
                role = "user",
                content = payload.displayContent,
                apiContent = payload.apiContent,
            )
            val titleSource = text.ifEmpty {
                if (pending.size == 1) pending[0].name else "${pending.size} attachments"
            }
            val nextTitle = if (chat.title == "New chat" || chat.title == "Incognito chat") {
                deriveTitle(titleSource)
            } else {
                chat.title
            }
            chat = chat.copy(
                title = nextTitle,
                messages = chat.messages + userMsg,
                updatedAt = now,
            )
        } else {
            chat = chat.copy(updatedAt = now)
        }

        val searchActive = currentWebSearch || opts.searchMode == "on"
        val initialLabel = when {
            searchActive && pending.isNotEmpty() -> "Searching web \u00b7 Looking at images."
            searchActive -> "Searching web."
            currentCodeMode -> "Writing code."
            opts.effort != null -> "Thinking harder."
            opts.lengthHint == "long" -> "Writing a longer response."
            opts.lengthHint == "short" -> "Writing a concise response."
            pending.isNotEmpty() -> if (pending.any { it.isImage }) "Looking at images." else "Reading files."
            else -> "Thinking."
        }

        val assistantPlaceholder = ChatMessage(
            role = "assistant",
            content = "",
            thinking = "",
            thinkingComplete = false,
            initialLabel = initialLabel,
        )
        val assistantIndex = chat.messages.size
        chat = chat.copy(messages = chat.messages + assistantPlaceholder)
        replaceChatInState(chat, persist = !chat.incognito)

        _uiState.update {
            it.copy(
                composerText = if (opts.skipUserPush) it.composerText else "",
                pendingFiles = if (opts.skipUserPush) it.pendingFiles else emptyList(),
                webSearch = false,
                codeMode = false,
                activeTemplate = null,
                streamingChatIds = it.streamingChatIds + chat.id,
                liveStatusLabels = it.liveStatusLabels + (chat.id to initialLabel),
            )
        }

        // Build wire history
        val historyMessages = mutableListOf<JsonObject>()
        val prior = chat.messages.dropLast(1).filter { m ->
            m.role == "user" || (m.role == "assistant" && (m.content.isNotEmpty() || !m.steps.isNullOrEmpty()))
        }
        prior.forEach { historyMessages += messageToApiMessages(it) }
        if (!opts.skipUserPush && userApiContent != null) {
            val lastUser = buildJsonObject {
                put("role", "user")
                put("content", userApiContent)
            }
            if (historyMessages.isEmpty() || historyMessages.last()["role"]?.toString() != "\"user\"") {
                historyMessages += lastUser
            } else {
                historyMessages[historyMessages.lastIndex] = lastUser
            }
        }

        val wireMessages = mutableListOf<JsonObject>()
        val agentActive = chat.agentMode && settings.agentAvailable()
        if (agentActive) {
            wireMessages += buildJsonObject {
                put("role", "system")
                put("content", OmniCatalog.AGENT_SYSTEM_PROMPT)
            }
        }
        opts.lengthHint?.let { hintKey ->
            OmniCatalog.LENGTH_HINTS[hintKey]?.let { hint ->
                wireMessages += buildJsonObject {
                    put("role", "system")
                    put("content", hint)
                }
            }
        }
        if (settings.systemPrompt.isNotBlank()) {
            wireMessages += buildJsonObject {
                put("role", "system")
                put("content", settings.systemPrompt)
            }
        }
        if (currentCodeMode) {
            wireMessages += buildJsonObject {
                put("role", "system")
                put("content", OmniCatalog.CODE_MODE_SYSTEM_PROMPT)
            }
        }
        wireMessages += historyMessages

        val requestMaxTokens = if (opts.lengthHint == "long") {
            (settings.maxTokens * 2).coerceIn(settings.maxTokens, 32768)
        } else {
            settings.maxTokens
        }

        var resolvedSearchMode = when {
            opts.searchMode == "off" -> "off"
            searchActive -> "on"
            else -> settings.searchMode.ifBlank { "auto" }
        }
        val nativeOnlyProvider = OmniCatalog.isNativeSearchProvider(settings.provider)
        val hasTavilyKey = settings.tavilyKey.trim().isNotEmpty()
        var effectiveBackend = if (settings.searchBackend == "tavily") "tavily" else "auto"
        if (effectiveBackend == "tavily" && !hasTavilyKey && nativeOnlyProvider) {
            effectiveBackend = "auto"
        }
        val usesTavily = effectiveBackend == "tavily" || !nativeOnlyProvider
        if (resolvedSearchMode != "off" && usesTavily && !hasTavilyKey) {
            if (resolvedSearchMode == "on") {
                showToast("Add your Tavily API key in Settings to use web search", isInfo = true)
            }
            resolvedSearchMode = "off"
        }

        val actualModel = settings.actualModelId()
        val effectiveEffort = opts.effort ?: settings.effort
        val chatId = chat.id
        val isIncognitoChat = chat.incognito

        val job = viewModelScope.launch {
            runStreamingConversation(
                chatId = chatId,
                isIncognitoChat = isIncognitoChat,
                assistantIndex = assistantIndex,
                initialAssistant = assistantPlaceholder,
                settings = settings,
                effectiveKey = effectiveKey,
                actualModel = actualModel,
                initialMessages = wireMessages,
                requestMaxTokens = requestMaxTokens,
                resolvedSearchMode = resolvedSearchMode,
                effectiveBackend = effectiveBackend,
                usesTavily = usesTavily,
                effectiveEffort = effectiveEffort,
                agentActive = agentActive,
            )
        }
        streamJobs[chatId] = job
    }

    private suspend fun runStreamingConversation(
        chatId: String,
        isIncognitoChat: Boolean,
        assistantIndex: Int,
        initialAssistant: ChatMessage,
        settings: OmniSettings,
        effectiveKey: String,
        actualModel: String,
        initialMessages: List<JsonObject>,
        requestMaxTokens: Int,
        resolvedSearchMode: String,
        effectiveBackend: String,
        usesTavily: Boolean,
        effectiveEffort: String,
        agentActive: Boolean,
    ) {
        var assistantMsg = initialAssistant
        val currentMessages = initialMessages.toMutableList()
        var includeTools = agentActive
        val runStartMs = nowMonoMs()
        val turnRecords = mutableListOf<TurnRecord>()
        var totalToolMs = 0.0
        var totalToolCalls = 0
        var lastStopReason: String? = null
        val thinkKey = settings.thinkModelKey()

        fun pushAssistantUpdate(persist: Boolean = false) {
            updateAssistantMessage(chatId, isIncognitoChat, assistantIndex, assistantMsg, persist = persist)
        }

        fun setStatusLabel(label: String) {
            _uiState.update { it.copy(liveStatusLabels = it.liveStatusLabels + (chatId to label)) }
        }

        fun buildRequestObject(): JsonObject = buildJsonObject {
            put("provider", settings.provider)
            put("model", actualModel)
            put("apiKey", effectiveKey)
            put("messages", buildJsonArray { currentMessages.forEach { add(it) } })
            put("temperature", settings.temperature)
            put("max_tokens", requestMaxTokens)
            put("stream", true)
            put("thinking", settings.thinking)
            put("searchMode", resolvedSearchMode)
            put("effort", effectiveEffort)
            if (resolvedSearchMode != "off") {
                put("searchBackend", effectiveBackend)
                put("searchDepth", settings.searchDepth)
                if (usesTavily) put("tavilyKey", settings.tavilyKey.trim())
            }
            if (includeTools) {
                put("tools", OmniCatalog.AGENT_TOOLS_JSON)
            }
            if (settings.provider == "custom") {
                put("baseURL", settings.customBaseURL)
            }
            if (settings.proxyKey.isNotBlank()) {
                put("proxyKey", settings.proxyKey.trim())
            }
        }

        suspend fun runOneTurn(reqObj: JsonObject): List<ToolCallRecord> {
            val turn = TurnRecord(startMs = nowMonoMs())
            turnRecords += turn

            data class PendingTool(var id: String = "", var name: String = "", val args: StringBuilder = StringBuilder())
            val pendingTools = linkedMapOf<Int, PendingTool>()
            val toolOrder = mutableListOf<Int>()

            val latestSettings = _uiState.value.settings
            val presumeOpen = latestSettings.implicitThinkModels[thinkKey] == true
            val thinkSplit = ThinkSplitter(presumeOpen = presumeOpen)
            var thinkSettled = false

            fun markToken(visible: Boolean) {
                val now = nowMonoMs()
                if (turn.firstTokenMs == null) turn.firstTokenMs = now
                if (visible) {
                    if (turn.firstVisibleMs == null) turn.firstVisibleMs = now
                    turn.deltaCount++
                }
            }

            fun applyVisibleDelta(raw: String) {
                val split = thinkSplit.feed(raw)
                val showThinking = _uiState.value.settings.thinking
                if (split.reclaim) {
                    val curSettings = _uiState.value.settings
                    if (curSettings.implicitThinkModels[thinkKey] != true) {
                        val updatedMap = curSettings.implicitThinkModels + (thinkKey to true)
                        persistSettings(curSettings.copy(implicitThinkModels = updatedMap))
                    }
                    if (assistantMsg.content.isNotEmpty()) {
                        assistantMsg = assistantMsg.copy(
                            thinking = if (showThinking) assistantMsg.thinking + assistantMsg.content else assistantMsg.thinking,
                            content = "",
                        )
                    }
                }
                if (split.think.isNotEmpty() && showThinking) {
                    assistantMsg = assistantMsg.copy(thinking = assistantMsg.thinking + split.think)
                }
                if (split.closed) {
                    assistantMsg = assistantMsg.copy(thinkingComplete = true)
                }
                if (split.text.isNotEmpty()) {
                    val nextComplete = if (assistantMsg.thinking.isNotEmpty() && !assistantMsg.thinkingComplete) true else assistantMsg.thinkingComplete
                    val visible = if (assistantMsg.content.isNotEmpty()) split.text else split.text.trimStart()
                    assistantMsg = assistantMsg.copy(
                        content = assistantMsg.content + visible,
                        thinkingComplete = nextComplete,
                    )
                }
                if (split.text.isNotEmpty() || split.think.isNotEmpty() || split.reclaim) {
                    pushAssistantUpdate()
                }
            }

            fun settleThinkSplitter(interrupted: Boolean) {
                if (thinkSettled) return
                thinkSettled = true
                val showThinking = _uiState.value.settings.thinking
                if (interrupted && thinkSplit.isInThought()) {
                    val rest = thinkSplit.buf
                    thinkSplit.buf = ""
                    if (rest.isNotEmpty() && showThinking) {
                        assistantMsg = assistantMsg.copy(thinking = assistantMsg.thinking + rest)
                        pushAssistantUpdate()
                    }
                    return
                }
                val tail = thinkSplit.flush()
                if (tail.unpresume) {
                    val curSettings = _uiState.value.settings
                    if (curSettings.implicitThinkModels.containsKey(thinkKey)) {
                        persistSettings(curSettings.copy(implicitThinkModels = curSettings.implicitThinkModels - thinkKey))
                    }
                    val restored = assistantMsg.thinking + tail.text
                    val nextContent = if (assistantMsg.content.isNotEmpty()) {
                        assistantMsg.content + restored
                    } else {
                        restored.trimStart()
                    }
                    assistantMsg = assistantMsg.copy(thinking = "", content = nextContent)
                } else {
                    val nextThinking = if (tail.think.isNotEmpty() && showThinking) {
                        assistantMsg.thinking + tail.think
                    } else assistantMsg.thinking
                    val nextContent = if (tail.text.isNotEmpty()) assistantMsg.content + tail.text else assistantMsg.content
                    assistantMsg = assistantMsg.copy(thinking = nextThinking, content = nextContent)
                }
                pushAssistantUpdate()
            }

            try {
                workerClient.streamChat(settings, reqObj).collect { event ->
                    val showThinking = _uiState.value.settings.thinking
                    if (!event.thinkingDelta.isNullOrEmpty()) {
                        markToken(visible = false)
                        if (showThinking) {
                            assistantMsg = assistantMsg.copy(thinking = assistantMsg.thinking + event.thinkingDelta)
                            pushAssistantUpdate()
                        }
                    }
                    if (event.thinkingDone) {
                        assistantMsg = assistantMsg.copy(thinkingComplete = true)
                    }
                    if (!event.delta.isNullOrEmpty()) {
                        markToken(visible = true)
                        applyVisibleDelta(event.delta)
                    }

                    event.toolStart?.let { ts ->
                        val slot = pendingTools.getOrPut(ts.slot) {
                            toolOrder += ts.slot
                            PendingTool()
                        }
                        if (ts.id.isNotEmpty()) slot.id = ts.id
                        if (ts.name.isNotEmpty()) slot.name = ts.name
                        setStatusLabel("Preparing ${slot.name.ifEmpty { "tool" }}\u2026")
                    }
                    event.toolArgs?.let { ta ->
                        pendingTools[ta.slot]?.args?.append(ta.delta)
                    }

                    event.usage?.let { u ->
                        turn.promptTokens = MetricsCalculator.keepCount(turn.promptTokens, u.promptTokens)
                        turn.completionTokens = MetricsCalculator.keepCount(turn.completionTokens, u.completionTokens)
                        turn.cachedTokens = MetricsCalculator.keepCount(turn.cachedTokens, u.cachedTokens)
                        turn.cacheWriteTokens = MetricsCalculator.keepCount(turn.cacheWriteTokens, u.cacheWriteTokens)
                        turn.reasoningTokens = MetricsCalculator.keepCount(turn.reasoningTokens, u.reasoningTokens)
                    }
                    event.serverTiming?.let { st ->
                        val existing = turn.server
                        if (existing == null) {
                            turn.server = ServerTimingData(
                                source = st.source,
                                promptTokens = st.promptTokens,
                                completionTokens = st.completionTokens,
                                promptMs = st.promptMs,
                                genMs = st.genMs,
                                queueMs = st.queueMs,
                            )
                        } else {
                            if (!st.source.isNullOrEmpty()) existing.source = st.source
                            existing.promptTokens = MetricsCalculator.keepCount(existing.promptTokens, st.promptTokens)
                            existing.completionTokens = MetricsCalculator.keepCount(existing.completionTokens, st.completionTokens)
                            existing.promptMs = MetricsCalculator.keepDouble(existing.promptMs, st.promptMs)
                            existing.genMs = MetricsCalculator.keepDouble(existing.genMs, st.genMs)
                            existing.queueMs = MetricsCalculator.keepDouble(existing.queueMs, st.queueMs)
                        }
                    }
                    event.stopReason?.let { lastStopReason = it }

                    event.search?.let { srch ->
                        assistantMsg = assistantMsg.copy(search = srch)
                        if (assistantMsg.content.isEmpty()) {
                            if (srch.sources.isNotEmpty()) {
                                val qPart = if (srch.query.isNotEmpty()) "\u201c${srch.query.take(60)}\u201d \u00b7 " else ""
                                val cachedNote = if (srch.cached) " \u00b7 cached" else ""
                                val sWord = if (srch.sources.size != 1) "s" else ""
                                setStatusLabel("$qPart${srch.sources.size} source$sWord$cachedNote")
                            } else if (!srch.error.isNullOrEmpty()) {
                                setStatusLabel(srch.error)
                            }
                        }
                        pushAssistantUpdate()
                    }

                    if (!event.error.isNullOrEmpty()) {
                        throw RuntimeException(event.error)
                    }
                }
            } catch (ce: CancellationException) {
                settleThinkSplitter(interrupted = true)
                throw ce
            } catch (e: Exception) {
                settleThinkSplitter(interrupted = false)
                throw e
            }

            settleThinkSplitter(interrupted = false)
            turn.endMs = nowMonoMs()

            return toolOrder.mapNotNull { slotIdx ->
                val t = pendingTools[slotIdx] ?: return@mapNotNull null
                if (t.name.isEmpty()) return@mapNotNull null
                val rawArgs = t.args.toString().ifBlank { "{}" }
                val parsedArgs = try {
                    (json.parseToJsonElement(rawArgs) as? JsonObject) ?: JsonObject(emptyMap())
                } catch (_: Exception) {
                    JsonObject(emptyMap())
                }
                ToolCallRecord(
                    id = t.id.ifBlank { "call_$slotIdx" },
                    name = t.name,
                    argsText = rawArgs,
                    args = parsedArgs,
                )
            }
        }

        try {
            val maxSteps = settings.agentMaxSteps.coerceIn(1, 25)
            var step = 0

            while (true) {
                val turnTextStart = assistantMsg.content.length
                val calls = runOneTurn(buildRequestObject())
                if (calls.isEmpty()) break

                val turnText = assistantMsg.content.substring(turnTextStart)
                val capped = step >= maxSteps
                val results = mutableListOf<ToolResultRecord>()

                if (capped) {
                    for (c in calls) {
                        results += ToolResultRecord(
                            id = c.id,
                            name = c.name,
                            content = "Error: maximum agent steps ($maxSteps) reached. Answer with what you have.",
                        )
                    }
                } else {
                    for (c in calls) {
                        setStatusLabel("Running ${c.name}\u2026")
                        val toolStart = nowMonoMs()
                        val sb = ensureSandbox(chatId, settings)
                        val out = workerClient.executeAgentTool(settings, sb, c)
                        totalToolMs += nowMonoMs() - toolStart
                        totalToolCalls++
                        results += ToolResultRecord(id = c.id, name = c.name, content = out)
                    }
                }

                val toolStep = ToolStep(text = turnText, calls = calls, results = results)
                val nextSteps = (assistantMsg.steps ?: emptyList()) + toolStep
                assistantMsg = assistantMsg.copy(
                    steps = nextSteps,
                    content = assistantMsg.content.substring(0, turnTextStart),
                )
                pushAssistantUpdate(persist = !isIncognitoChat)

                currentMessages += stepToApiMessages(toolStep)
                if (capped) includeTools = false
                step++
            }

            val finalMetrics = MetricsCalculator.finalizeMetrics(
                runStartMs = runStartMs,
                nowMs = nowMonoMs(),
                turnRecords = turnRecords,
                requestMessages = currentMessages,
                assistantMsg = assistantMsg,
                contextWindow = settings.contextWindow(),
                toolMs = totalToolMs,
                toolCalls = totalToolCalls,
                stopReason = lastStopReason,
                partial = false,
            )
            assistantMsg = assistantMsg.copy(
                thinkingComplete = true,
                content = if (assistantMsg.content.isEmpty() && assistantMsg.steps.isNullOrEmpty()) {
                    "(No response received)"
                } else assistantMsg.content,
                metrics = finalMetrics,
                inTokens = finalMetrics.inputTokens,
                outTokens = finalMetrics.outputTokens,
            )
            finalizeChatMetricsAndPersist(chatId, isIncognitoChat, assistantIndex, assistantMsg, finalMetrics)
        } catch (ce: CancellationException) {
            withContext(NonCancellable) {
                val keepOpen = assistantMsg.thinking.isNotEmpty() && !assistantMsg.thinkingComplete
                val partialContent = assistantMsg.content.ifEmpty { "*(Generation stopped)*" }
                val partialMetrics = MetricsCalculator.finalizeMetrics(
                    runStartMs = runStartMs,
                    nowMs = nowMonoMs(),
                    turnRecords = turnRecords,
                    requestMessages = currentMessages,
                    assistantMsg = assistantMsg.copy(content = partialContent),
                    contextWindow = settings.contextWindow(),
                    toolMs = totalToolMs,
                    toolCalls = totalToolCalls,
                    stopReason = lastStopReason,
                    partial = true,
                )
                assistantMsg = assistantMsg.copy(
                    content = partialContent,
                    thinkingComplete = !keepOpen,
                    metrics = partialMetrics,
                    inTokens = partialMetrics.inputTokens,
                    outTokens = partialMetrics.outputTokens,
                )
                finalizeChatMetricsAndPersist(chatId, isIncognitoChat, assistantIndex, assistantMsg, partialMetrics)
            }
        } catch (e: Exception) {
            val shown = WorkerStreamParser.friendlyErrorText(e.message)
            val errContent = assistantMsg.content.ifEmpty { "Error: $shown" }
            val partialMetrics = MetricsCalculator.finalizeMetrics(
                runStartMs = runStartMs,
                nowMs = nowMonoMs(),
                turnRecords = turnRecords,
                requestMessages = currentMessages,
                assistantMsg = assistantMsg.copy(content = errContent),
                contextWindow = settings.contextWindow(),
                toolMs = totalToolMs,
                toolCalls = totalToolCalls,
                stopReason = lastStopReason,
                partial = true,
            )
            assistantMsg = assistantMsg.copy(
                content = errContent,
                thinkingComplete = true,
                metrics = partialMetrics,
                inTokens = partialMetrics.inputTokens,
                outTokens = partialMetrics.outputTokens,
            )
            finalizeChatMetricsAndPersist(chatId, isIncognitoChat, assistantIndex, assistantMsg, partialMetrics)
            if (_uiState.value.activeChat?.id == chatId) {
                showToast(shown.ifBlank { "Failed to reach endpoint" })
            }
        } finally {
            withContext(NonCancellable) {
                streamJobs.remove(chatId)
                killSandboxSuspend(chatId)
                _uiState.update {
                    it.copy(
                        streamingChatIds = it.streamingChatIds - chatId,
                        liveStatusLabels = it.liveStatusLabels - chatId,
                    )
                }
            }
        }
    }

    fun stopGeneration() {
        val id = _uiState.value.activeChat?.id ?: return
        streamJobs[id]?.cancel()
    }

    // ---- Sandbox Lifecycle ----

    private suspend fun ensureSandbox(chatId: String, settings: OmniSettings): SandboxSession {
        sandboxes[chatId]?.let { return it }
        val created = workerClient.sandboxCreate(settings)
        sandboxes[chatId] = created
        startKeepalive(chatId, settings)
        return created
    }

    private fun startKeepalive(chatId: String, settings: OmniSettings) {
        keepaliveJobs.remove(chatId)?.cancel()
        val intervalMs = maxOf(30_000L, (settings.agentSandboxTimeout * 1000L) / 3L)
        keepaliveJobs[chatId] = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(intervalMs)
                val sb = sandboxes[chatId] ?: break
                workerClient.sandboxKeepalive(_uiState.value.settings, sb)
            }
        }
    }

    private suspend fun killSandboxSuspend(chatId: String) {
        keepaliveJobs.remove(chatId)?.cancel()
        val sb = sandboxes.remove(chatId) ?: return
        workerClient.sandboxKill(_uiState.value.settings, sb)
    }

    private fun killSandboxAsync(chatId: String) {
        keepaliveJobs.remove(chatId)?.cancel()
        val sb = sandboxes.remove(chatId) ?: return
        val curSettings = _uiState.value.settings
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            workerClient.sandboxKill(curSettings, sb)
        }
    }

    // ---- Backup Import / Export ----

    fun buildChatsExportJson(): Pair<String, Int> {
        val persisted = _uiState.value.chats.filter { !it.incognito }
        val jsonStr = BackupManager.exportChatsJson(persisted, _uiState.value.activeChatId)
        return jsonStr to persisted.size
    }

    fun importChatsFromJson(raw: String) {
        val existingIds = _uiState.value.chats.map { it.id }.toSet()
        when (val result = BackupManager.importChatsJson(raw, existingIds)) {
            is ChatImportResult.Error -> showToast(result.message)
            is ChatImportResult.Success -> {
                if (result.newChats.isEmpty()) {
                    showToast("No new chats to import (all already exist)")
                    return
                }
                val merged = (result.newChats + _uiState.value.chats).sortedWith(ChatComparator)
                val nextActive = _uiState.value.activeChatId ?: merged.firstOrNull()?.id
                _uiState.update { it.copy(chats = merged, activeChatId = nextActive) }
                viewModelScope.launch {
                    result.newChats.forEach { chatRepo.saveChat(it) }
                    if (nextActive != null && !_uiState.value.incognito) {
                        settingsStore.saveActiveChatId(nextActive)
                    }
                }
                val n = result.newChats.size
                showToast("Imported $n chat${if (n != 1) "s" else ""}", isInfo = true)
            }
        }
    }

    fun buildSettingsExportJson(settingsToExport: OmniSettings = _uiState.value.settings): String {
        val norm = settingsToExport.normalized()
        persistSettings(norm)
        return BackupManager.exportSettingsJson(norm)
    }

    fun prepareSettingsImport(raw: String) {
        when (val result = BackupManager.importSettingsJson(raw)) {
            is SettingsImportResult.Error -> showToast(result.message)
            is SettingsImportResult.Success -> {
                _uiState.update { it.copy(pendingSettingsImport = result.settings) }
            }
        }
    }

    fun confirmSettingsImport() {
        val incoming = _uiState.value.pendingSettingsImport ?: return
        persistSettings(incoming)
        _uiState.update { it.copy(pendingSettingsImport = null) }
        showToast("Settings imported", isInfo = true)
    }

    fun cancelSettingsImport() {
        _uiState.update { it.copy(pendingSettingsImport = null) }
    }

    // ---- Internal State Helpers ----

    private fun persistSettings(next: OmniSettings) {
        _uiState.update { it.copy(settings = next) }
        viewModelScope.launch { settingsStore.saveSettings(next) }
    }

    private fun persistActiveChatId(id: String?) {
        viewModelScope.launch { settingsStore.saveActiveChatId(id) }
    }

    private fun updatePersistedChat(chat: Chat) {
        replaceChatInState(chat, persist = !chat.incognito)
    }

    private fun replaceChatInState(chat: Chat, persist: Boolean) {
        _uiState.update { s ->
            if (chat.incognito) {
                s.copy(incognitoChat = chat)
            } else {
                val exists = s.chats.any { it.id == chat.id }
                val updatedList = if (exists) {
                    s.chats.map { if (it.id == chat.id) chat else it }
                } else {
                    listOf(chat) + s.chats
                }.sortedWith(ChatComparator)
                s.copy(chats = updatedList)
            }
        }
        if (persist && !chat.incognito) {
            viewModelScope.launch { chatRepo.saveChat(chat) }
        }
    }

    private fun updateAssistantMessage(
        chatId: String,
        isIncognitoChat: Boolean,
        msgIdx: Int,
        msg: ChatMessage,
        persist: Boolean,
    ) {
        var latestChat: Chat? = null
        _uiState.update { s ->
            if (isIncognitoChat) {
                val c = s.incognitoChat
                if (c == null || c.id != chatId || msgIdx !in c.messages.indices) return@update s
                val nextMsgs = c.messages.toMutableList()
                nextMsgs[msgIdx] = msg
                val updated = c.copy(messages = nextMsgs)
                latestChat = updated
                s.copy(incognitoChat = updated)
            } else {
                val c = s.chats.firstOrNull { it.id == chatId }
                if (c == null || msgIdx !in c.messages.indices) return@update s
                val nextMsgs = c.messages.toMutableList()
                nextMsgs[msgIdx] = msg
                val updated = c.copy(messages = nextMsgs)
                latestChat = updated
                s.copy(chats = s.chats.map { if (it.id == chatId) updated else it })
            }
        }
        if (persist && !isIncognitoChat) {
            latestChat?.let { c -> viewModelScope.launch { chatRepo.saveChat(c) } }
        }
    }

    private fun finalizeChatMetricsAndPersist(
        chatId: String,
        isIncognitoChat: Boolean,
        msgIdx: Int,
        msg: ChatMessage,
        metrics: MessageMetrics,
    ) {
        var latestChat: Chat? = null
        _uiState.update { s ->
            if (isIncognitoChat) {
                val c = s.incognitoChat
                if (c == null || c.id != chatId || msgIdx !in c.messages.indices) return@update s
                val nextMsgs = c.messages.toMutableList()
                nextMsgs[msgIdx] = msg
                val updated = c.copy(messages = nextMsgs, metrics = metrics)
                latestChat = updated
                s.copy(incognitoChat = updated)
            } else {
                val c = s.chats.firstOrNull { it.id == chatId }
                if (c == null || msgIdx !in c.messages.indices) return@update s
                val nextMsgs = c.messages.toMutableList()
                nextMsgs[msgIdx] = msg
                val updated = c.copy(messages = nextMsgs, metrics = metrics)
                latestChat = updated
                s.copy(chats = s.chats.map { if (it.id == chatId) updated else it })
            }
        }
        if (!isIncognitoChat) {
            latestChat?.let { c -> viewModelScope.launch { chatRepo.saveChat(c) } }
        }
    }

    private fun nowMonoMs(): Double = System.nanoTime() / 1_000_000.0

    override fun onCleared() {
        super.onCleared()
        streamJobs.values.forEach { it.cancel() }
        streamJobs.clear()
        keepaliveJobs.values.forEach { it.cancel() }
        keepaliveJobs.clear()
        sandboxes.clear()
        workerClient.close()
    }
}
