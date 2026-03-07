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
import com.arm.aichat.InferenceEngine.State as EngineState
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
        private val KEY_MMPROJ_PATH = stringPreferencesKey("mmproj_path")

        const val DEFAULT_TEMPERATURE = 0.3f
        const val DEFAULT_CONTEXT_SIZE = 8192
        const val DEFAULT_SYSTEM_PROMPT = "You are a helpful assistant."
    }

    data class ChatMessage(
        val id: Long,
        val content: String,
        val isUser: Boolean,
        val imageUri: String? = null,
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

    val sessionStore = SessionStore(application)
    private var currentSession: Session? = null

    private val _appState = MutableStateFlow<AppState>(AppState.NoModel)
    val appState: StateFlow<AppState> = _appState

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _statusText = MutableStateFlow("")
    val statusText: StateFlow<String> = _statusText

    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    val sessions: StateFlow<List<Session>> = _sessions

    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId

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

    private val _mmprojPath = MutableStateFlow<String?>(null)
    val mmprojPath: StateFlow<String?> = _mmprojPath

    private val _visionAvailable = MutableStateFlow(false)
    val visionAvailable: StateFlow<Boolean> = _visionAvailable

    private val _pendingImagePath = MutableStateFlow<String?>(null)
    val pendingImagePath: StateFlow<String?> = _pendingImagePath

    init {
        viewModelScope.launch(Dispatchers.Default) {
            engine = AiChat.getInferenceEngine(app.applicationContext)
            loadSettings()
            refreshSessions()
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
        _mmprojPath.value = prefs[KEY_MMPROJ_PATH]
    }

    private suspend fun saveSettings() {
        app.settingsStore.edit { prefs ->
            prefs[KEY_TEMPERATURE] = _temperature.value
            prefs[KEY_CONTEXT_SIZE] = _contextSize.value
            prefs[KEY_SYSTEM_PROMPT] = _systemPrompt.value
            prefs[KEY_REASONING] = _reasoningEnabled.value
            _modelPath.value?.let { prefs[KEY_MODEL_PATH] = it }
            _mmprojPath.value?.let { prefs[KEY_MMPROJ_PATH] = it }
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
                _statusText.value = "Initializing engine..."

                engine.state.first { it is EngineState.Initialized || it is EngineState.Error }
                if (engine.state.value is EngineState.Error) {
                    throw Exception("Engine failed to initialize")
                }

                engine.setContextSize(_contextSize.value)

                _statusText.value = "Loading model..."
                engine.loadModel(path)

                engine.setTemperature(_temperature.value)
                engine.setEnableThinking(_reasoningEnabled.value)

                // Load vision projector if available
                _mmprojPath.value?.let { mmprojPath ->
                    if (File(mmprojPath).exists()) {
                        _statusText.value = "Loading vision model..."
                        try {
                            engine.loadMmproj(mmprojPath)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to load mmproj (continuing without vision)", e)
                        }
                    }
                }
                _visionAvailable.value = engine.isVisionLoaded()

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

    fun loadMmprojFromUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _statusText.value = "Copying vision model..."
                val modelsDir = File(app.filesDir, "models").also {
                    if (!it.exists()) it.mkdirs()
                }
                val destFile = File(modelsDir, "mmproj.gguf")
                app.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                } ?: throw Exception("Could not open file")

                _mmprojPath.value = destFile.absolutePath
                saveSettings()

                if (_appState.value == AppState.Ready) {
                    _statusText.value = "Loading vision model..."
                    engine.loadMmproj(destFile.absolutePath)
                    _visionAvailable.value = engine.isVisionLoaded()
                }
                _statusText.value = ""
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load mmproj", e)
                _statusText.value = ""
            }
        }
    }

    fun attachImage(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val imagesDir = File(app.filesDir, "images").also {
                    if (!it.exists()) it.mkdirs()
                }
                val destFile = File(imagesDir, "${System.currentTimeMillis()}.jpg")
                app.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                } ?: return@launch
                _pendingImagePath.value = destFile.absolutePath
            } catch (e: Exception) {
                Log.e(TAG, "Failed to attach image", e)
            }
        }
    }

    fun clearAttachment() {
        val path = _pendingImagePath.value
        _pendingImagePath.value = null
        // Delete the temp file if it was never sent
        path?.let { File(it).delete() }
    }

    fun sendMessage(text: String) {
        if (text.isBlank() || _appState.value != AppState.Ready) return

        // Create a new session if none active
        if (currentSession == null) {
            currentSession = Session()
            _currentSessionId.value = currentSession!!.id
        }

        val imagePath = _pendingImagePath.value
        _pendingImagePath.value = null

        val userMsg = ChatMessage(nextId++, text, isUser = true, imageUri = imagePath)
        _messages.value = _messages.value + userMsg

        val assistantId = nextId++
        _messages.value = _messages.value + ChatMessage(assistantId, "", isUser = false)

        _appState.value = AppState.Generating
        val responseBuilder = StringBuilder()

        generationJob = viewModelScope.launch(Dispatchers.Default) {
            val tokenFlow = if (imagePath != null) {
                val imageData = File(imagePath).readBytes()
                engine.sendUserPromptWithImage(text, imageData)
            } else {
                engine.sendUserPrompt(text)
            }

            tokenFlow
                .onCompletion {
                    saveCurrentSession()
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

    private suspend fun saveCurrentSession() {
        val session = currentSession ?: return
        val msgs = _messages.value
        if (msgs.isEmpty()) return

        // Auto-name from first user message
        val name = session.name.ifEmpty {
            msgs.firstOrNull { it.isUser }?.content?.take(50) ?: "New chat"
        }

        val updated = session.copy(name = name, messages = msgs)
        currentSession = updated
        sessionStore.saveSession(updated)
        refreshSessions()
    }

    fun stopGeneration() {
        generationJob?.cancel()
        generationJob = null
    }

    fun newChat() {
        currentSession = null
        _currentSessionId.value = null
        _messages.value = emptyList()
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

    fun loadSession(session: Session) {
        currentSession = session
        _currentSessionId.value = session.id
        _messages.value = session.messages
        // Update nextId to avoid collisions
        val maxId = session.messages.maxOfOrNull { it.id } ?: 0
        nextId = maxId + 1
    }

    fun deleteSession(id: String) {
        viewModelScope.launch {
            sessionStore.deleteSession(id)
            if (currentSession?.id == id) {
                newChat()
            }
            refreshSessions()
        }
    }

    fun renameSession(id: String, name: String) {
        viewModelScope.launch {
            val session = sessionStore.loadSession(id) ?: return@launch
            val updated = session.copy(name = name)
            sessionStore.saveSession(updated)
            if (currentSession?.id == id) {
                currentSession = updated
            }
            refreshSessions()
        }
    }

    private suspend fun refreshSessions() {
        _sessions.value = sessionStore.listSessions()
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
