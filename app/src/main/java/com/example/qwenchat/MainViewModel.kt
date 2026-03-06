package com.example.qwenchat

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

private val Application.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
        private val KEY_TEMPERATURE = floatPreferencesKey("temperature")
        private val KEY_CONTEXT_SIZE = intPreferencesKey("context_size")
        private val KEY_SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
        private val KEY_REASONING = booleanPreferencesKey("reasoning")
        private val KEY_MODEL_PATH = stringPreferencesKey("model_path")

        const val DEFAULT_TEMPERATURE = 0.3f
        const val DEFAULT_CONTEXT_SIZE = 8192
        const val DEFAULT_SYSTEM_PROMPT = "You are a helpful assistant."
    }

    data class ChatMessage(
        val id: Long,
        val content: String,
        val isUser: Boolean,
    )

    sealed class AppState {
        data object NoModel : AppState()
        data object LoadingModel : AppState()
        data object Ready : AppState()
        data object Generating : AppState()
        data class Error(val message: String) : AppState()
    }

    private val app = application
    private lateinit var engine: InferenceEngine
    private var generationJob: Job? = null
    private var nextId = 0L

    private val _appState = MutableStateFlow<AppState>(AppState.NoModel)
    val appState: StateFlow<AppState> = _appState

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _statusText = MutableStateFlow("")
    val statusText: StateFlow<String> = _statusText

    // Settings
    private val _temperature = MutableStateFlow(DEFAULT_TEMPERATURE)
    val temperature: StateFlow<Float> = _temperature

    private val _contextSize = MutableStateFlow(DEFAULT_CONTEXT_SIZE)
    val contextSize: StateFlow<Int> = _contextSize

    private val _systemPrompt = MutableStateFlow(DEFAULT_SYSTEM_PROMPT)
    val systemPrompt: StateFlow<String> = _systemPrompt

    private val _reasoningEnabled = MutableStateFlow(false)
    val reasoningEnabled: StateFlow<Boolean> = _reasoningEnabled

    private val _modelPath = MutableStateFlow<String?>(null)
    val modelPath: StateFlow<String?> = _modelPath

    init {
        viewModelScope.launch(Dispatchers.Default) {
            engine = AiChat.getInferenceEngine(app.applicationContext)
            loadSettings()
            // Auto-load model if path saved
            _modelPath.value?.let { path ->
                if (File(path).exists()) {
                    loadModelFromPath(path)
                }
            }
        }
    }

    private suspend fun loadSettings() {
        val prefs = app.settingsStore.data.first()
        _temperature.value = prefs[KEY_TEMPERATURE] ?: DEFAULT_TEMPERATURE
        _contextSize.value = prefs[KEY_CONTEXT_SIZE] ?: DEFAULT_CONTEXT_SIZE
        _systemPrompt.value = prefs[KEY_SYSTEM_PROMPT] ?: DEFAULT_SYSTEM_PROMPT
        _reasoningEnabled.value = prefs[KEY_REASONING] ?: false
        _modelPath.value = prefs[KEY_MODEL_PATH]
    }

    private suspend fun saveSettings() {
        app.settingsStore.edit { prefs ->
            prefs[KEY_TEMPERATURE] = _temperature.value
            prefs[KEY_CONTEXT_SIZE] = _contextSize.value
            prefs[KEY_SYSTEM_PROMPT] = _systemPrompt.value
            prefs[KEY_REASONING] = _reasoningEnabled.value
            _modelPath.value?.let { prefs[KEY_MODEL_PATH] = it }
        }
    }

    fun updateTemperature(temp: Float) {
        _temperature.value = temp
        if (::engine.isInitialized) {
            engine.setTemperature(temp)
        }
        viewModelScope.launch { saveSettings() }
    }

    fun updateContextSize(size: Int) {
        _contextSize.value = size
        if (::engine.isInitialized) {
            engine.setContextSize(size)
        }
        viewModelScope.launch { saveSettings() }
    }

    fun updateSystemPrompt(prompt: String) {
        _systemPrompt.value = prompt
        viewModelScope.launch { saveSettings() }
    }

    fun updateReasoning(enabled: Boolean) {
        _reasoningEnabled.value = enabled
        if (::engine.isInitialized) {
            engine.setEnableThinking(enabled)
        }
        viewModelScope.launch { saveSettings() }
    }

    fun loadModelFromUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _appState.value = AppState.LoadingModel
                _statusText.value = "Copying model file..."

                val modelsDir = File(app.filesDir, "models").also {
                    if (!it.exists()) it.mkdirs()
                }
                val destFile = File(modelsDir, "model.gguf")

                app.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                } ?: throw Exception("Could not open file")

                _modelPath.value = destFile.absolutePath
                saveSettings()
                loadModelFromPath(destFile.absolutePath)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load model from URI", e)
                _appState.value = AppState.Error("Failed to load model: ${e.message}")
            }
        }
    }

    fun loadModelFromPath(path: String) {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                _appState.value = AppState.LoadingModel
                _statusText.value = "Loading model..."

                // Apply settings before loading
                engine.setContextSize(_contextSize.value)
                engine.setTemperature(_temperature.value)
                engine.setEnableThinking(_reasoningEnabled.value)

                engine.loadModel(path)

                _statusText.value = "Setting system prompt..."
                engine.setSystemPrompt(_systemPrompt.value)

                _modelPath.value = path
                saveSettings()

                _statusText.value = ""
                _appState.value = AppState.Ready
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load model", e)
                _appState.value = AppState.Error("Failed to load model: ${e.message}")
            }
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank() || _appState.value != AppState.Ready) return

        val userMsg = ChatMessage(nextId++, text, isUser = true)
        _messages.value = _messages.value + userMsg

        val assistantId = nextId++
        _messages.value = _messages.value + ChatMessage(assistantId, "", isUser = false)

        _appState.value = AppState.Generating
        val responseBuilder = StringBuilder()

        generationJob = viewModelScope.launch(Dispatchers.Default) {
            engine.sendUserPrompt(text)
                .onCompletion {
                    withContext(Dispatchers.Main) {
                        _appState.value = AppState.Ready
                    }
                }
                .collect { token ->
                    responseBuilder.append(token)
                    val updated = _messages.value.toMutableList()
                    val idx = updated.indexOfLast { it.id == assistantId }
                    if (idx >= 0) {
                        updated[idx] = updated[idx].copy(content = responseBuilder.toString())
                        _messages.value = updated
                    }
                }
        }
    }

    fun stopGeneration() {
        generationJob?.cancel()
        generationJob = null
    }

    fun clearChat() {
        _messages.value = emptyList()
        // Re-send system prompt to reset context
        if (_appState.value == AppState.Ready) {
            viewModelScope.launch(Dispatchers.Default) {
                try {
                    engine.setSystemPrompt(_systemPrompt.value)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to reset system prompt", e)
                }
            }
        }
    }

    fun dismissError() {
        _appState.value = if (_modelPath.value != null) AppState.Ready else AppState.NoModel
    }

    override fun onCleared() {
        super.onCleared()
        if (::engine.isInitialized) {
            engine.destroy()
        }
    }
}
