package com.example.qwenchat.ui

import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

import com.example.qwenchat.MainViewModel

/** Detect LaTeX delimiters in text. */
private val LATEX_PATTERN = Regex("""\$\$[\s\S]+?\$\$|\$[^\s$].*?[^\s$]\$|\\\[[\s\S]+?\\]|\\\(.*?\\\)""")

// Semi-transparent blue for user bubbles
private val UserBubbleColor = Color(0x331976D2)
private val UserBubbleColorDark = Color(0x3342A5F5)
// Semi-transparent forest green for assistant bubbles
private val AssistantBubbleColor = Color(0x2E2E7D32)
private val AssistantBubbleColorDark = Color(0x2E66BB6A)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    messages: List<MainViewModel.ChatMessage>,
    appState: MainViewModel.AppState,
    visionAvailable: Boolean = false,
    pendingImagePath: String? = null,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onNewChat: () -> Unit,
    onSessionsClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onAttachImage: () -> Unit = {},
    onClearAttachment: () -> Unit = {},
) {
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val isGenerating = appState is MainViewModel.AppState.Generating

    // Auto-scroll to bottom of the last message during generation
    LaunchedEffect(messages.size, messages.lastOrNull()?.content?.length) {
        if (messages.isNotEmpty()) {
            val lastIndex = messages.size - 1
            listState.scrollToItem(lastIndex)
            // Scroll to the very bottom of the last item
            val layoutInfo = listState.layoutInfo
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()
            if (lastVisible != null && lastVisible.index == lastIndex) {
                val bottomOffset = lastVisible.size - layoutInfo.viewportSize.height +
                        layoutInfo.afterContentPadding
                if (bottomOffset > 0) {
                    listState.scrollToItem(lastIndex, bottomOffset)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Qwen Chat") },
                actions = {
                    IconButton(onClick = onNewChat) {
                        Icon(Icons.Default.NoteAdd, contentDescription = "New chat")
                    }
                    IconButton(onClick = onSessionsClick) {
                        Icon(Icons.Default.History, contentDescription = "Sessions")
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            // Messages list
            LazyColumn(
                modifier = Modifier.weight(1f),
                state = listState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    MessageBubble(message)
                }
            }

            // Pending image preview
            if (pendingImagePath != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LocalImage(
                        path = pendingImagePath,
                        modifier = Modifier
                            .heightIn(max = 80.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                    IconButton(onClick = onClearAttachment) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Remove image",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Input bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (visionAvailable) {
                    IconButton(
                        onClick = onAttachImage,
                        enabled = appState is MainViewModel.AppState.Ready
                    ) {
                        Icon(Icons.Default.AttachFile, contentDescription = "Attach image")
                    }
                }

                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Type a message...") },
                    enabled = appState is MainViewModel.AppState.Ready || isGenerating,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (inputText.isNotBlank() && !isGenerating) {
                                onSend(inputText)
                                inputText = ""
                            }
                        }
                    ),
                    maxLines = 4,
                    shape = RoundedCornerShape(24.dp)
                )

                if (isGenerating) {
                    IconButton(onClick = onStop) {
                        Icon(
                            Icons.Default.Stop,
                            contentDescription = "Stop",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                } else {
                    IconButton(
                        onClick = {
                            if (inputText.isNotBlank()) {
                                onSend(inputText)
                                inputText = ""
                            }
                        },
                        enabled = inputText.isNotBlank() && appState is MainViewModel.AppState.Ready
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: MainViewModel.ChatMessage) {
    val isUser = message.isUser

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(if (isUser) 0.85f else 0.95f)
        ) {
            if (isUser) {
                val isDark = !MaterialTheme.colorScheme.surface.luminance().let { it > 0.5f }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp))
                        .background(if (isDark) UserBubbleColorDark else UserBubbleColor)
                        .padding(12.dp)
                ) {
                    Column {
                        message.imageUri?.let { path ->
                            LocalImage(
                                path = path,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 200.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        Text(
                            text = message.content,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            } else {
                if (message.reasoningContent != null) {
                    ThinkingBlock(message.reasoningContent)
                    if (message.content.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        AssistantText(message.content)
                    }
                } else {
                    AssistantText(message.content)
                }
            }
        }
    }
}

@Composable
private fun AssistantText(text: String) {
    val isDark = !MaterialTheme.colorScheme.surface.luminance().let { it > 0.5f }
    val hasLatex = remember(text) { LATEX_PATTERN.containsMatchIn(text) }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp))
            .background(if (isDark) AssistantBubbleColorDark else AssistantBubbleColor)
            .padding(12.dp)
    ) {
        if (hasLatex) {
            LatexText(text = text)
        } else {
            Text(
                text = text,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun LatexText(text: String) {
    val context = LocalContext.current
    val textColor = MaterialTheme.colorScheme.onSurface
    val colorHex = remember(textColor) {
        val argb = textColor.toArgb()
        String.format("#%06X", argb and 0xFFFFFF)
    }

    // Height in dp, reported by JS callback
    var webViewHeightDp by remember { mutableIntStateOf(200) }

    val htmlTemplate = remember {
        context.assets.open("katex.html").bufferedReader().readText()
    }
    val html = remember(colorHex) {
        htmlTemplate.replace("BODY_COLOR", colorHex)
    }

    // Escape text for JS string
    val escapedText = remember(text) {
        text.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "")
    }

    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .height(webViewHeightDp.dp),
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onHeightReady(height: Int) {
                        // JS reports CSS pixels which map 1:1 to dp
                        webViewHeightDp = height + 8 // small padding
                    }
                }, "AndroidBridge")
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        view?.evaluateJavascript("renderContent(\"$escapedText\")", null)
                    }
                }
                loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            }
        },
        update = { webView ->
            webView.evaluateJavascript("renderContent(\"$escapedText\")", null)
        }
    )
}

@Composable
private fun ThinkingBlock(thinking: String) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = if (expanded) "Thinking (tap to collapse)" else "Thinking (tap to expand)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            AnimatedVisibility(visible = expanded) {
                Text(
                    text = thinking,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun LocalImage(path: String, modifier: Modifier = Modifier) {
    val bitmap = remember(path) {
        BitmapFactory.decodeFile(path)?.let { bmp ->
            val rotation = try {
                when (ExifInterface(path).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } catch (_: Exception) { 0f }
            if (rotation != 0f) {
                val matrix = Matrix().apply { postRotate(rotation) }
                android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
            } else bmp
        }?.asImageBitmap()
    }
    bitmap?.let {
        Image(
            bitmap = it,
            contentDescription = "Attached image",
            modifier = modifier,
            contentScale = ContentScale.Fit
        )
    }
}

