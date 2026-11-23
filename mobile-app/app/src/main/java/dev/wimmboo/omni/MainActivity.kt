package dev.wimmboo.omni

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dev.wimmboo.omni.data.backup.BackupManager
import dev.wimmboo.omni.data.model.OmniSettings
import dev.wimmboo.omni.ui.AttachmentHelper
import dev.wimmboo.omni.ui.OmniApp
import dev.wimmboo.omni.ui.OmniViewModel
import dev.wimmboo.omni.ui.markdown.CodeHighlighter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val viewModel: OmniViewModel by viewModels()

    private val isRecordingVoice = mutableStateOf(false)
    private var pendingExportJson: String? = null
    private var pendingExportToast: String? = null
    private var pendingCodeContent: String? = null
    private var pendingCodeName: String? = null

    private val attachLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) {
                AttachmentHelper.readAttachments(contentResolver, uris)
            }
            viewModel.addPendingFiles(files)
        }
    }

    private val exportJsonLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val payload = pendingExportJson
        val toastText = pendingExportToast
        pendingExportJson = null
        pendingExportToast = null
        if (uri == null || payload == null) return@registerForActivityResult
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                AttachmentHelper.writeUtf8Text(contentResolver, uri, payload)
            }
            if (ok) {
                viewModel.showToast(toastText ?: "Exported", isInfo = true)
            } else {
                viewModel.showToast("Could not save file")
            }
        }
    }

    private val importChatsLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val raw = withContext(Dispatchers.IO) {
                AttachmentHelper.readUtf8Text(contentResolver, uri)
            }
            if (raw != null) viewModel.importChatsFromJson(raw)
            else viewModel.showToast("Could not read file")
        }
    }

    private val importSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val raw = withContext(Dispatchers.IO) {
                AttachmentHelper.readUtf8Text(contentResolver, uri)
            }
            if (raw != null) viewModel.prepareSettingsImport(raw)
            else viewModel.showToast("Could not read file")
        }
    }

    private val saveCodeLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        val code = pendingCodeContent
        val name = pendingCodeName ?: "code.txt"
        pendingCodeContent = null
        pendingCodeName = null
        if (uri == null || code == null) return@registerForActivityResult
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                AttachmentHelper.writeUtf8Text(contentResolver, uri, code)
            }
            if (ok) viewModel.showToast("Downloaded $name", isInfo = true)
            else viewModel.showToast("Could not save $name")
        }
    }

    private val voiceLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        isRecordingVoice.value = false
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        val spoken = matches?.firstOrNull()
        if (!spoken.isNullOrBlank()) {
            viewModel.appendDictatedText(spoken)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySystemBars()

        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()

            LaunchedEffect(state.incognito) {
                applySystemBars()
            }

            OmniApp(
                state = state,
                viewModel = viewModel,
                isRecordingVoice = isRecordingVoice.value,
                onRequestAttachFiles = ::launchFileAttach,
                onRequestExportChats = ::launchExportChats,
                onRequestImportChats = ::launchImportChats,
                onRequestExportSettings = ::launchExportSettings,
                onRequestImportSettings = ::launchImportSettings,
                onRequestDownloadCode = ::launchDownloadCode,
                onRequestVoiceDictation = ::launchVoiceDictation,
            )
        }
    }

    private fun applySystemBars() {
        val darkTransparent = SystemBarStyle.dark(Color.TRANSPARENT)
        enableEdgeToEdge(
            statusBarStyle = darkTransparent,
            navigationBarStyle = darkTransparent,
        )
    }

    private fun launchFileAttach() {
        attachLauncher.launch(
            arrayOf(
                "image/*",
                "text/*",
                "application/json",
                "application/xml",
                "application/javascript",
                "application/pdf",
            ),
        )
    }

    private fun launchExportChats() {
        lifecycleScope.launch {
            val (json, count) = viewModel.buildChatsExportJsonAsync()
            pendingExportJson = json
            pendingExportToast = "Chats exported ($count saved)"
            exportJsonLauncher.launch(BackupManager.defaultChatsFilename())
        }
    }

    private fun launchImportChats() {
        importChatsLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
    }

    private fun launchExportSettings(draft: OmniSettings) {
        lifecycleScope.launch {
            pendingExportJson = viewModel.buildSettingsExportJsonAsync(draft)
            pendingExportToast = "Settings exported. The file includes your API keys."
            exportJsonLauncher.launch(BackupManager.defaultSettingsFilename())
        }
    }

    private fun launchImportSettings() {
        importSettingsLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
    }

    private fun launchDownloadCode(code: String, language: String) {
        val ext = CodeHighlighter.fileExtensionFor(language)
        val filename = "code.$ext"
        pendingCodeContent = code
        pendingCodeName = filename
        saveCodeLauncher.launch(filename)
    }

    private fun launchVoiceDictation() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your message")
        }
        try {
            isRecordingVoice.value = true
            voiceLauncher.launch(intent)
        } catch (_: Exception) {
            isRecordingVoice.value = false
            viewModel.showToast("Voice dictation is not available on this device", isInfo = true)
        }
    }
}
