package com.example.qwenchat

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.qwenchat.download.ModelDownloader
import com.example.qwenchat.ui.QwenChatTheme
import com.example.qwenchat.ui.ChatScreen
import com.example.qwenchat.ui.ModelScreen
import com.example.qwenchat.ui.SessionsScreen
import com.example.qwenchat.ui.SettingsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    private var pendingFilePick: ((android.net.Uri?) -> Unit)? = null
    private var pendingImagePick: ((android.net.Uri?) -> Unit)? = null

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        pendingFilePick?.invoke(uri)
        pendingFilePick = null
    }

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        pendingImagePick?.invoke(uri)
        pendingImagePick = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            QwenChatTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavigation(
                        onPickFile = { callback ->
                            pendingFilePick = callback
                            filePickerLauncher.launch(arrayOf("*/*"))
                        },
                        onPickImage = { callback ->
                            pendingImagePick = callback
                            imagePickerLauncher.launch("image/*")
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AppNavigation(
    viewModel: MainViewModel = viewModel(),
    onPickFile: (callback: (android.net.Uri?) -> Unit) -> Unit,
    onPickImage: (callback: (android.net.Uri?) -> Unit) -> Unit,
) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val appState by viewModel.appState.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val statusText by viewModel.statusText.collectAsState()
    val temperature by viewModel.temperature.collectAsState()
    val contextSize by viewModel.contextSize.collectAsState()
    val systemPrompt by viewModel.systemPrompt.collectAsState()
    val reasoningEnabled by viewModel.reasoningEnabled.collectAsState()
    val recursionDepth by viewModel.recursionDepth.collectAsState()
    val sessions by viewModel.sessions.collectAsState()
    val currentSessionId by viewModel.currentSessionId.collectAsState()
    val visionAvailable by viewModel.visionAvailable.collectAsState()
    val pendingImagePath by viewModel.pendingImagePath.collectAsState()

    var downloadProgress by remember { mutableStateOf<Float?>(null) }

    val showChat = appState is MainViewModel.AppState.Ready ||
            appState is MainViewModel.AppState.Generating

    // React to state changes for navigation
    LaunchedEffect(showChat) {
        val currentRoute = navController.currentDestination?.route
        if (showChat && currentRoute != "chat" && currentRoute != "settings" && currentRoute != "sessions") {
            navController.navigate("chat") {
                popUpTo("model") { inclusive = true }
            }
        } else if (!showChat && currentRoute == "chat") {
            navController.navigate("model") {
                popUpTo("chat") { inclusive = true }
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = if (showChat) "chat" else "model"
    ) {
        composable("model") {
            ModelScreen(
                appState = appState,
                statusText = statusText,
                downloadProgress = downloadProgress,
                onPickFile = {
                    onPickFile { uri ->
                        uri?.let { viewModel.loadModelFromUri(it) }
                    }
                },
                onDownload = {
                    downloadProgress = 0f
                    scope.launch(Dispatchers.IO) {
                        try {
                            val app = viewModel.getApplication<android.app.Application>()
                            val modelsDir = File(app.filesDir, "models")
                            val file = ModelDownloader.download(modelsDir) { progress ->
                                downloadProgress = progress.fraction
                            }
                            downloadProgress = null
                            viewModel.loadModelFromPath(file.absolutePath)
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Download failed", e)
                            downloadProgress = null
                        }
                    }
                },
                onDismissError = { viewModel.dismissError() }
            )
        }

        composable("chat") {
            ChatScreen(
                messages = messages,
                appState = appState,
                visionAvailable = visionAvailable,
                pendingImagePath = pendingImagePath,
                onSend = { viewModel.sendMessage(it) },
                onStop = { viewModel.stopGeneration() },
                onNewChat = { viewModel.newChat() },
                onSessionsClick = { navController.navigate("sessions") },
                onSettingsClick = { navController.navigate("settings") },
                onAttachImage = {
                    onPickImage { uri ->
                        uri?.let { viewModel.attachImage(it) }
                    }
                },
                onClearAttachment = { viewModel.clearAttachment() }
            )
        }

        composable("sessions") {
            SessionsScreen(
                sessions = sessions,
                currentSessionId = currentSessionId,
                onSessionClick = { viewModel.loadSession(it) },
                onNewChat = { viewModel.newChat() },
                onDelete = { viewModel.deleteSession(it) },
                onBack = { navController.popBackStack() }
            )
        }

        composable("settings") {
            SettingsScreen(
                temperature = temperature,
                contextSize = contextSize,
                systemPrompt = systemPrompt,
                reasoningEnabled = reasoningEnabled,
                recursionDepth = recursionDepth,
                mmprojLoaded = visionAvailable,
                onTemperatureChange = { viewModel.updateTemperature(it) },
                onContextSizeChange = { viewModel.updateContextSize(it) },
                onSystemPromptChange = { viewModel.updateSystemPrompt(it) },
                onReasoningChange = { viewModel.updateReasoning(it) },
                onRecursionDepthChange = { viewModel.updateRecursionDepth(it) },
                onPickMmproj = {
                    onPickFile { uri ->
                        uri?.let { viewModel.loadMmprojFromUri(it) }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }
    }
}
